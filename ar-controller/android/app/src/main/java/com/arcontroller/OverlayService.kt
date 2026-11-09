package com.arcontroller

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service that owns everything drawn on top of other apps:
 *  - the draggable "AR" floating icon,
 *  - the Main Control Panel that expands in place when the icon is tapped,
 *  - the ALL APPS grid (tap = floating window, long-press = full screen),
 *  - the Ask-AI page, and the always-on "Hey AR" voice assistant.
 */
class OverlayService : Service(), VoiceAssistant.Listener, ScreenAgent.Listener {

    companion object {
        const val ACTION_STOP = "com.arcontroller.action.STOP"
        const val ACTION_LISTEN = "com.arcontroller.action.LISTEN"
        const val ACTION_RELOAD_SETTINGS = "com.arcontroller.action.RELOAD"
        private const val CHANNEL_ID = "ar_controller"
        private const val NOTIFICATION_ID = 7

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, OverlayService::class.java))

        fun send(ctx: Context, action: String) {
            if (isRunning) ctx.startService(Intent(ctx, OverlayService::class.java).setAction(action))
        }
    }

    private enum class Page { FUNCTIONS, APPS, ASK }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private lateinit var voice: VoiceAssistant
    private lateinit var executor: CommandExecutor

    private var bubble: FrameLayout? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var bubbleBg: GradientDrawable

    private var panel: PanelRoot? = null
    private var panelCard: View? = null
    private var pulse: ValueAnimator? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var pageContainer: FrameLayout? = null
    private var statusText: TextView? = null
    private var askReplyText: TextView? = null
    private var lastStatus = "Bolein \"Hey AR\" ya mic dabayein"

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        executor = CommandExecutor(this, this)
        FloatingAppLauncher.ensureHiddenApi()
        startAsForeground()
        createBubble()
        voice = VoiceAssistant(this, this)
        startVoiceIfAllowed()
        worker.execute { AppRepository.load(this) } // warm the app list + icons
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_LISTEN -> listenNow()
            ACTION_RELOAD_SETTINGS -> {
                voice.stop()
                AIBrain.clearHistory()
                startVoiceIfAllowed()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        pulse?.cancel()
        ScreenAgent.cancel()
        FloatingWebWindow.closeAll()
        voice.stop()
        worker.shutdownNow()
        panel?.let { safeRemove(it) }
        bubble?.let { safeRemove(it) }
        panel = null
        bubble = null
        super.onDestroy()
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startVoiceIfAllowed() {
        if (micGranted()) voice.start(Prefs.wakeWordEnabled(this))
        else onStateChanged(VoiceAssistant.State.OFF, "Microphone permission nahi hai")
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AR Controller", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification()
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && micGranted() ->
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                Build.VERSION.SDK_INT >= 34 ->
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else -> startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // Android 14+ refuses a microphone FGS started from the background (e.g. START_STICKY restart).
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else throw e
        }
    }

    private fun buildNotification(): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), piFlags)
        val listen = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_LISTEN), piFlags)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), piFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AR Controller is active")
            .setContentText("Say \"Hey AR\" or tap the floating icon")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Listen", listen)
            .addAction(0, "Stop", stop)
            .build()
    }

    // ------------------------------------------------------------------ floating icon

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val size = dp(58)
        bubbleBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#7C3AED"), Color.parseColor("#2563EB")),
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Color.WHITE)
        }
        val view = FrameLayout(this).apply {
            background = bubbleBg
            elevation = dp(8).toFloat()
            addView(
                TextView(context).apply {
                    text = "AR"
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        bubbleParams = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW() - size - dp(6)
            y = screenH() / 3
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        bubbleParams.x = (startX + dx).toInt()
                        bubbleParams.y = (startY + dy).toInt().coerceIn(0, screenH() - size)
                        wm.updateViewLayout(v, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(80).start()
                    if (dragging) snapToEdge(v) else if (e.actionMasked == MotionEvent.ACTION_UP) openPanel()
                }
            }
            true
        }
        wm.addView(view, bubbleParams)
        bubble = view
    }

    private fun snapToEdge(v: View) {
        val size = bubbleParams.width
        val target = if (bubbleParams.x + size / 2 < screenW() / 2) dp(6) else screenW() - size - dp(6)
        ValueAnimator.ofInt(bubbleParams.x, target).apply {
            duration = 220
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                if (v.isAttachedToWindow) wm.updateViewLayout(v, bubbleParams)
            }
            start()
        }
    }

    private fun setBubbleColor(state: VoiceAssistant.State) {
        val colors = when (state) {
            VoiceAssistant.State.LISTENING_COMMAND -> intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#059669"))
            VoiceAssistant.State.THINKING -> intArrayOf(Color.parseColor("#F59E0B"), Color.parseColor("#EA580C"))
            VoiceAssistant.State.SPEAKING -> intArrayOf(Color.parseColor("#EC4899"), Color.parseColor("#8B5CF6"))
            else -> intArrayOf(Color.parseColor("#7C3AED"), Color.parseColor("#2563EB"))
        }
        bubbleBg.colors = colors
        // Gentle "breathing" pulse while AR is listening for a command.
        val b = bubble ?: return
        if (state == VoiceAssistant.State.LISTENING_COMMAND) {
            if (pulse == null) {
                pulse = ValueAnimator.ofFloat(1f, 1.12f).apply {
                    duration = 600
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { b.scaleX = it.animatedValue as Float; b.scaleY = it.animatedValue as Float }
                    start()
                }
            }
        } else {
            pulse?.cancel()
            pulse = null
            b.scaleX = 1f; b.scaleY = 1f
        }
    }

    // ------------------------------------------------------------------ control panel

    private fun openPanel(page: Page = Page.FUNCTIONS) {
        if (panel != null) {
            showPage(page)
            return
        }
        val panelW = minOf(dp(340), screenW() - dp(16))
        val root = PanelRoot(this) { closePanel() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F5161E3A"), Color.parseColor("#F20B1022")),
            ).apply {
                cornerRadius = 26f * resources.displayMetrics.density
                setStroke(dp(1), Color.parseColor("#3B4A75"))
            }
            setPadding(dp(14), dp(12), dp(14), dp(14))
            elevation = dp(12).toFloat()
        }

        // Header: title + live voice status + close.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("AR Controller", 17f, "#FFFFFF", bold = true))
        statusText = label(lastStatus, 12f, "#A5B4FC").apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        titles.addView(statusText)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            label("✕", 18f, "#CBD5E1").apply {
                gravity = Gravity.CENTER
                background = rounded("#1E293B", 16f)
                setOnClickListener { closePanel() }
            },
            LinearLayout.LayoutParams(dp(34), dp(34)),
        )
        card.addView(header)

        val container = FrameLayout(this)
        card.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        pageContainer = container

        root.addView(card, FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Open the panel right where the icon is, clamped to the screen.
        val estimatedH = dp(470)
        val params = WindowManager.LayoutParams(
            panelW, ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x.coerceIn(dp(8), maxOf(dp(8), screenW() - panelW - dp(8)))
            y = bubbleParams.y.coerceIn(dp(24), maxOf(dp(24), screenH() - estimatedH))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        bubble?.visibility = View.GONE
        wm.addView(root, params)
        panel = root
        panelParams = params
        showPage(page)

        // Expand animation from the icon's corner.
        val fromRight = bubbleParams.x > screenW() / 2
        card.pivotX = if (fromRight) panelW.toFloat() else 0f
        card.pivotY = 0f
        card.scaleX = 0.4f; card.scaleY = 0.4f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(0.9f)).start()
        panelCard = card
    }

    private fun closePanel() {
        val root = panel
        val card = panelCard
        if (root != null && card != null) {
            // Shrink back toward the icon, then remove the window.
            card.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(140)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { safeRemove(root) }.start()
        } else {
            root?.let { safeRemove(it) }
        }
        panel = null
        panelCard = null
        panelParams = null
        pageContainer = null
        statusText = null
        askReplyText = null
        bubble?.let { b ->
            b.visibility = View.VISIBLE
            b.scaleX = 0.5f; b.scaleY = 0.5f; b.alpha = 0f
            b.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(80).setDuration(200)
                .setInterpolator(OvershootInterpolator()).start()
        }
    }

    private fun showPage(page: Page) {
        val container = pageContainer ?: return
        container.removeAllViews()
        // Only pages with text input take keyboard focus; otherwise keys go to the app underneath.
        setPanelFocusable(page != Page.FUNCTIONS)
        val view = when (page) {
            Page.FUNCTIONS -> buildFunctionsPage()
            Page.APPS -> buildAppsPage()
            Page.ASK -> buildAskPage()
        }
        container.addView(view)
        view.alpha = 0f
        view.translationX = dp(if (page == Page.FUNCTIONS) -24 else 24).toFloat()
        view.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun setPanelFocusable(focusable: Boolean) {
        val p = panelParams ?: return
        val root = panel ?: return
        p.flags = if (focusable) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(root, p)
    }

    private data class Tile(val icon: String, val title: String, val accent: String = "#1E293B", val onTap: () -> Unit)

    private fun buildFunctionsPage(): View {
        val tiles = listOf(
            Tile("🎙", "Hey AR", "#4C1D95") { listenNow() },
            Tile("💬", "Ask AI", "#4C1D95") { showPage(Page.ASK) },
            Tile("📱", "ALL APPS", "#1D4ED8") { showPage(Page.APPS) },
            Tile("🔦", "Torch") { flash(executor.toggleTorch()) },
            Tile("🔊", "Vol +") { runCommand(AICommand("volume_up")) },
            Tile("🔉", "Vol −") { runCommand(AICommand("volume_down")) },
            Tile("🔇", "Mute") { runCommand(AICommand("mute")) },
            Tile("🏠", "Home") { runCommand(AICommand("home")) },
            Tile("↩", "Back") { runCommand(AICommand("back")) },
            Tile("🗂", "Recents") { runCommand(AICommand("recents")) },
            Tile("🔔", "Notifs") { runCommand(AICommand("notifications")) },
            Tile("⚙", "Quick Set") { runCommand(AICommand("quick_settings")) },
            Tile("📸", "Screenshot") { runCommand(AICommand("screenshot")) },
            Tile("🔒", "Lock") { runCommand(AICommand("lock_screen")) },
            Tile("🌐", "Web box") { runCommand(AICommand("float_website", text = "https://www.google.com")) },
            Tile("🗗", "Close box") { runCommand(AICommand("close_floating")) },
            Tile("📶", "Wi-Fi") { runCommand(AICommand("open_settings", "wifi")) },
            Tile("🅱", "Bluetooth") { runCommand(AICommand("open_settings", "bluetooth")) },
            Tile("📍", "Location") { runCommand(AICommand("open_settings", "location")) },
            Tile("📞", "Dialer") {
                closePanel()
                startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            Tile("⏻", "Stop AR", "#7F1D1D") { stopSelf() },
        )
        val grid = GridLayout(this).apply { columnCount = 4 }
        tiles.forEach { t ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(t.accent, 16f)
                foreground = ripple(16f)
                setPadding(dp(2), dp(10), dp(2), dp(8))
                addView(label(t.icon, 22f, "#FFFFFF").apply { gravity = Gravity.CENTER })
                addView(label(t.title, 10.5f, "#E2E8F0").apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                setOnClickListener { t.onTap() }
            }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        return grid
    }

    private fun buildAppsPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label("←", 20f, "#FFFFFF").apply {
            gravity = Gravity.CENTER
            background = rounded("#1E293B", 14f)
            setOnClickListener { showPage(Page.FUNCTIONS) }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val search = EditText(this).apply {
            hint = "Search apps…"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            background = rounded("#1E293B", 14f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        top.addView(search, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) })
        page.addView(top)

        val info = label("Loading apps…", 11f, "#94A3B8").apply { setPadding(0, dp(6), 0, dp(4)) }
        page.addView(info)

        val adapter = AppsAdapter()
        val grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(6)
            horizontalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            this.adapter = adapter
            setOnItemClickListener { _, _, pos, _ -> launchFloating(adapter.getItem(pos)) }
            setOnItemLongClickListener { _, _, pos, _ ->
                val app = adapter.getItem(pos)
                closePanel()
                FloatingAppLauncher.launchFullscreen(this@OverlayService, app.packageName)
                true
            }
        }
        page.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(360), screenH() / 2)))
        page.addView(label("Tap = floating window  •  Long-press = full screen", 10.5f, "#64748B").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
        })

        worker.execute {
            val apps = AppRepository.load(this)
            main.post {
                adapter.setApps(apps)
                info.text = "${apps.size} apps installed"
            }
        }
        return page
    }

    private fun launchFloating(app: InstalledApp) {
        closePanel()
        val mode = FloatingAppLauncher.launchFloating(this, app.packageName, app.label)
        flash(
            when (mode) {
                FloatingAppLauncher.Mode.FREEFORM -> "${app.label} floating window mein khul gayi"
                FloatingAppLauncher.Mode.WEB -> "${app.label} floating box mein — drag/resize kar sakte hain"
                FloatingAppLauncher.Mode.ATTEMPTED ->
                    "${app.label} ka web version nahi. Har app box mein kholne ke liye app mein Floating setup karein"
                FloatingAppLauncher.Mode.FULLSCREEN, FloatingAppLauncher.Mode.FAILED -> "${app.label} open nahi ho saki"
            },
        )
    }

    private fun buildAskPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(label("← Functions", 13f, "#A5B4FC").apply {
            setPadding(0, 0, 0, dp(8))
            setOnClickListener { showPage(Page.FUNCTIONS) }
        })
        val input = EditText(this).apply {
            hint = "e.g. YouTube kholo • Ali ko call karo • torch on • float WhatsApp"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 2
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded("#1E293B", 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        page.addView(input)
        val reply = label("", 13f, "#E2E8F0").apply { setPadding(dp(2), dp(10), dp(2), dp(4)) }
        askReplyText = reply

        val send = {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                reply.text = "…"
                onCommand(text)
            }
        }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        row.addView(label("🎙  Speak", 14f, "#FFFFFF").apply {
            gravity = Gravity.CENTER
            background = rounded("#4C1D95", 14f)
            setOnClickListener { listenNow() }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        row.addView(label("Send ➤", 14f, "#FFFFFF", bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded("#2563EB", 14f)
            setOnClickListener { send() }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        page.addView(row)
        page.addView(reply)
        input.requestFocus()
        return page
    }

    private inner class AppsAdapter : BaseAdapter() {
        private var all: List<InstalledApp> = emptyList()
        private var shown: List<InstalledApp> = emptyList()

        fun setApps(apps: List<InstalledApp>) { all = apps; shown = apps; notifyDataSetChanged() }

        fun filter(q: String) {
            shown = if (q.isBlank()) all else all.filter { it.label.contains(q, ignoreCase = true) }
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(6), dp(2), dp(4))
                foreground = ripple(14f)
                addView(ImageView(context), LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(label("", 10.5f, "#E2E8F0").apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            val app = shown[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }

    // ------------------------------------------------------------------ voice + AI

    private fun listenNow() {
        if (!micGranted()) {
            flash("Microphone permission dein (AR Controller app kholen)")
            return
        }
        voice.listenForCommandNow()
    }

    override fun onStateChanged(state: VoiceAssistant.State, detail: String?) {
        setBubbleColor(state)
        detail?.let {
            lastStatus = it
            statusText?.text = it
        }
    }

    override fun onCommand(text: String) {
        if (ScreenAgent.isRunning && Regex("^(stop|ruk|ruko|ruk jao|bas|cancel|band karo)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())) {
            ScreenAgent.cancel()
            voice.speak("Ruk gaya")
            return
        }
        voice.setThinking("“$text” — soch raha hoon…")
        worker.execute {
            val cmd = AIBrain.interpret(this, text)
            main.post { runCommand(cmd) }
        }
    }

    override fun onAgentProgress(text: String) {
        setBubbleColor(VoiceAssistant.State.THINKING)
        lastStatus = "⚙ $text"
        statusText?.text = lastStatus
    }

    override fun onAgentDone(reply: String) {
        lastStatus = reply
        statusText?.text = reply
        voice.speak(reply)
    }

    /** Executes a command; hides the panel first when the action affects what's on screen. */
    private fun runCommand(cmd: AICommand) {
        val keepPanel = cmd.action in setOf(
            "chat", "volume_up", "volume_down", "mute", "flashlight_on", "flashlight_off", "read_screen",
        )
        val wasOpen = panel != null
        if (!keepPanel) closePanel()
        val delay = if (!keepPanel && wasOpen) 350L else 0L // let the panel disappear (matters for screenshots)
        main.postDelayed({
            val reply = executor.execute(cmd)
            askReplyText?.text = reply
            statusText?.text = reply
            lastStatus = reply
            voice.speak(reply)
        }, delay)
    }

    // ------------------------------------------------------------------ helpers

    private fun flash(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        lastStatus = msg
        statusText?.text = msg
    }

    private fun safeRemove(v: View) {
        try { wm.removeView(v) } catch (_: Throwable) { }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    private fun ripple(radiusDp: Float) = RippleDrawable(
        ColorStateList.valueOf(Color.parseColor("#40FFFFFF")),
        null,
        rounded("#FFFFFFFF", radiusDp),
    )

    private fun rounded(color: String, radiusDp: Float, stroke: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = radiusDp * resources.displayMetrics.density
        stroke?.let { setStroke(dp(1), Color.parseColor(it)) }
    }

    private fun label(text: String, sp: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Root of the panel window: closes on Back key or a tap outside the panel. */
    private class PanelRoot(ctx: Context, private val onDismiss: () -> Unit) : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onDismiss()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onDismiss()
                return true
            }
            return super.onTouchEvent(event)
        }
    }
}
