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
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service that owns everything drawn over other apps:
 *  - the draggable floating icon (user's photo) that opens the Control Panel in place,
 *  - the Control Panel (HTML, ui/panel.html) with functions and the ALL APPS grid,
 *  - the bottom-centre assistant orb that opens the Alexa-style sheet (ui/assistant.html),
 *  - the always-on "Hey AR" voice assistant.
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

        /** Actions that don't change what's on screen, so the panel can stay open. */
        private val KEEP_PANEL = setOf(
            "volume_up", "volume_down", "mute", "flashlight_on", "flashlight_off", "media_play_pause", "media_next",
            "media_previous", "brightness_up", "brightness_down", "brightness_max", "rotate_toggle", "ringer_cycle",
            "battery", "time", "keep_awake", "dnd_toggle", "torch_toggle", "read_screen",
        )
        private val KEEP_SHEET = KEEP_PANEL + setOf("chat", "read_screen", "agent_task")
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private lateinit var voice: VoiceAssistant
    private lateinit var executor: CommandExecutor
    private val themed by lazy { ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault) }

    // Floating icon
    private var bubble: FrameLayout? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var bubbleRing: GradientDrawable
    private var pulse: ValueAnimator? = null
    private var keepAwake = false

    // Control panel
    private var panel: OverlayWeb? = null

    // Assistant orb + sheet
    private var orb: FrameLayout? = null
    private lateinit var orbRing: GradientDrawable
    private var sheet: OverlayWeb? = null
    private var sheetOpenedByVoice = false
    private val autoClose = Runnable { if (sheetOpenedByVoice) closeSheet() }

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        executor = CommandExecutor(this, this)
        voice = VoiceAssistant(this, this)
        // The service shares the app's process, so a startup failure must not crash the app.
        try {
            startAsForeground()
            createBubble()
            if (Prefs.bottomOrb(this)) createOrb()
        } catch (t: Throwable) {
            CrashReporter.save(this, "OverlayService start failed", t)
            stopSelf()
            return
        }
        runCatching { startVoiceIfAllowed() }
        worker.execute { runCatching { AppRepository.load(this) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopSelf()
                ACTION_LISTEN -> openSheet(listen = true)
                ACTION_RELOAD_SETTINGS -> {
                    voice.stop()
                    startVoiceIfAllowed()
                    if (Prefs.bottomOrb(this) && orb == null) createOrb()
                    if (!Prefs.bottomOrb(this)) removeOrb()
                }
            }
        } catch (t: Throwable) {
            CrashReporter.save(this, "OverlayService command failed", t)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { pulse?.cancel() }
        runCatching { ScreenAgent.cancel() }
        runCatching { FloatingWebWindow.closeAll() }
        runCatching { voice.stop() }
        worker.shutdownNow()
        closePanel(animate = false)
        closeSheet(animate = false)
        removeOrb()
        bubble?.let { safeRemove(it) }
        bubble = null
        super.onDestroy()
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startVoiceIfAllowed() {
        if (micGranted()) voice.start(Prefs.wakeWordEnabled(this))
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AR Controller", NotificationManager.IMPORTANCE_LOW))
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
            .setContentText("Say \"Hey AR\" or tap the assistant")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Talk", listen)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ------------------------------------------------------------------ floating icon

    private fun avatarView(sizeDp: Int, ring: GradientDrawable): FrameLayout = FrameLayout(this).apply {
        background = ring
        val pad = dp(3)
        setPadding(pad, pad, pad, pad)
        elevation = dp(10).toFloat()
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ar_bubble)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(dp(sizeDp) - 2 * pad, dp(sizeDp) - 2 * pad))
    }

    private fun ring(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#0B1020"))
        setStroke(dp(2), Color.parseColor(color))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val size = dp(56)
        bubbleRing = ring("#8B5CF6")
        val view = avatarView(56, bubbleRing)
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
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; dragging = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(90).start()
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
                    v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(120).start()
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
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                if (v.isAttachedToWindow) wm.updateViewLayout(v, bubbleParams)
            }
            start()
        }
    }

    private fun stateColor(state: VoiceAssistant.State) = when (state) {
        VoiceAssistant.State.LISTENING_COMMAND -> "#22C55E"
        VoiceAssistant.State.THINKING -> "#F59E0B"
        VoiceAssistant.State.SPEAKING -> "#38BDF8"
        else -> "#8B5CF6"
    }

    private fun showVoiceState(state: VoiceAssistant.State) {
        val c = Color.parseColor(stateColor(state))
        bubbleRing.setStroke(dp(2), c)
        if (orb != null) orbRing.setStroke(dp(3), c)
        val target = orb ?: bubble ?: return
        if (state == VoiceAssistant.State.LISTENING_COMMAND) {
            if (pulse == null) {
                pulse = ValueAnimator.ofFloat(1f, 1.1f).apply {
                    duration = 650
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { target.scaleX = it.animatedValue as Float; target.scaleY = it.animatedValue as Float }
                    start()
                }
            }
        } else {
            pulse?.cancel()
            pulse = null
            target.scaleX = 1f; target.scaleY = 1f
        }
    }

    private fun setKeepAwake(on: Boolean) {
        keepAwake = on
        val b = bubble ?: return
        bubbleParams.flags = if (on) bubbleParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else bubbleParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        wm.updateViewLayout(b, bubbleParams)
    }

    // ------------------------------------------------------------------ HTML overlay windows

    /** An overlay window hosting one of the HTML screens in assets/ui. */
    private inner class OverlayWeb(page: String, val params: WindowManager.LayoutParams, val onBack: () -> Unit) : Bridge.Host {
        val root = Root()
        val web: WebView = WebView(themed)
        val bridge = Bridge(this@OverlayService, web, this)

        init {
            web.setBackgroundColor(Color.TRANSPARENT)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.isVerticalScrollBarEnabled = false
            bridge.attach()
            root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web.loadUrl("file:///android_asset/ui/$page")
            wm.addView(root, params)
        }

        fun rect() = Rect(params.x, params.y, params.x + params.width, params.y + params.height)

        fun setFocusable(focusable: Boolean) {
            if (!root.isAttachedToWindow) return
            params.flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(root, params)
        }

        fun destroy(animate: Boolean) {
            bridge.alive = false
            val finish = {
                safeRemove(root)
                web.destroy()
            }
            if (animate && root.isAttachedToWindow) {
                root.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(140).withEndAction(finish).start()
            } else finish()
        }

        override fun executor() = this@OverlayService.executor
        override fun listen() = openSheet(listen = true)
        override fun hostCommand(cmd: String, arg: String): String = handleHostCommand(this, cmd, arg)

        inner class Root : FrameLayout(this@OverlayService) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onBack()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    onBack()
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
        }
    }

    private fun webParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

    /** Commands sent from the HTML screens via ARNative.host(...) / action / launch. */
    private fun handleHostCommand(win: OverlayWeb, cmd: String, arg: String): String {
        when {
            cmd == "close" -> main.post { if (win === panel) closePanel() else closeSheet() }
            cmd == "focus" -> win.setFocusable(arg == "1")
            cmd == "openMain" -> main.post {
                closePanel()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            cmd == "assistant" -> main.post {
                closePanel()
                openSheet(listen = arg == "listen")
            }
            cmd == "say" -> main.post { onCommand(arg) }
            cmd == "stopAgent" -> ScreenAgent.cancel()
            cmd == "stopService" -> stopSelf()
            cmd == "state" -> return JSONObject().put("keepAwake", keepAwake).put("status", lastStatus).toString()
            cmd == "action:keep_awake" -> {
                setKeepAwake(!keepAwake)
                return if (keepAwake) "Screen on rahegi" else "Screen normal"
            }
            cmd == "action:close_floating" -> {
                FloatingWebWindow.closeAll()
                return "Floating windows band"
            }
            cmd.startsWith("action:") -> {
                val name = cmd.removePrefix("action:")
                if (win === panel && name !in KEEP_PANEL) {
                    // Hide the panel first so it isn't captured in screenshots or left over the app.
                    FloatingAppLauncher.anchor = win.rect()
                    closePanel()
                    main.postDelayed({
                        val reply = executor.execute(AICommand(name, target = arg.ifBlank { null }, text = arg.ifBlank { null }))
                        if (reply.contains("allow", true) || reply.contains("nahi", true)) toast(reply)
                    }, 260)
                    return "ok"
                }
            }
            cmd.startsWith("launch:") -> {
                FloatingAppLauncher.anchor = win.rect()
                if (win === panel) main.postDelayed({ closePanel() }, 120)
            }
        }
        return ""
    }

    // ------------------------------------------------------------------ control panel

    private fun openPanel() {
        if (panel != null) return
        val w = minOf(dp(360), screenW() - dp(16))
        val h = minOf(dp(580), (screenH() * 0.78f).toInt())
        val params = webParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            val fromRight = bubbleParams.x > screenW() / 2
            x = if (fromRight) (bubbleParams.x + bubbleParams.width - w).coerceAtLeast(dp(8)) else bubbleParams.x.coerceAtMost(screenW() - w - dp(8))
            y = (bubbleParams.y - dp(40)).coerceIn(dp(24), screenH() - h - dp(24))
        }
        bubble?.visibility = View.GONE
        panel = OverlayWeb("panel.html", params) { closePanel() }.also { p ->
            val fromRight = bubbleParams.x > screenW() / 2
            p.root.pivotX = if (fromRight) w.toFloat() else 0f
            p.root.pivotY = (bubbleParams.y - params.y).toFloat().coerceIn(0f, h.toFloat())
            p.root.scaleX = 0.5f; p.root.scaleY = 0.5f; p.root.alpha = 0f
            p.root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(OvershootInterpolator(0.7f)).start()
        }
    }

    private fun closePanel(animate: Boolean = true) {
        val p = panel ?: return
        panel = null
        p.destroy(animate)
        bubble?.let { b ->
            b.visibility = View.VISIBLE
            b.alpha = 0f
            b.animate().alpha(1f).setStartDelay(100).setDuration(180).start()
        }
    }

    // ------------------------------------------------------------------ assistant orb + sheet

    @SuppressLint("ClickableViewAccessibility")
    private fun createOrb() {
        if (orb != null) return
        val size = dp(62)
        orbRing = ring("#8B5CF6").apply { setStroke(dp(3), Color.parseColor("#8B5CF6")) }
        val view = avatarView(62, orbRing)
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    openSheet(listen = true)
                }
                MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            true
        }
        wm.addView(view, params)
        orb = view
        view.alpha = 0f
        view.translationY = dp(30).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator()).start()
    }

    private fun removeOrb() {
        orb?.let { safeRemove(it) }
        orb = null
    }

    private fun openSheet(listen: Boolean, byVoice: Boolean = false) {
        main.removeCallbacks(autoClose)
        if (sheet == null) {
            val w = minOf(screenW() - dp(16), dp(520))
            val h = minOf(dp(380), (screenH() * 0.6f).toInt())
            val params = webParams(w, h).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(12)
            }
            closePanel(animate = false)
            orb?.visibility = View.GONE
            sheetOpenedByVoice = byVoice
            sheet = OverlayWeb("assistant.html", params) { closeSheet() }.also { s ->
                s.root.translationY = h.toFloat() * 0.4f
                s.root.alpha = 0f
                s.root.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            }
            sheet?.bridge?.emit("status", JSONObject().put("text", lastStatus))
        } else if (!byVoice) {
            sheetOpenedByVoice = false
        }
        if (listen) {
            if (!micGranted()) {
                sheet?.bridge?.emit("reply", JSONObject().put("text", "Microphone permission dein (AR Controller app kholen)"))
            } else if (voice.state != VoiceAssistant.State.LISTENING_COMMAND) {
                voice.listenForCommandNow()
            }
        }
    }

    private fun closeSheet(animate: Boolean = true) {
        main.removeCallbacks(autoClose)
        val s = sheet ?: return
        sheet = null
        s.destroy(animate)
        orb?.let { o ->
            o.visibility = View.VISIBLE
            o.alpha = 0f
            o.animate().alpha(1f).setStartDelay(80).setDuration(200).start()
        }
    }

    // ------------------------------------------------------------------ voice + AI

    private var lastStatus = "Say \"Hey AR\" or tap the mic"

    private fun emitAll(type: String, data: JSONObject) {
        sheet?.bridge?.emit(type, data)
        panel?.bridge?.emit(type, data)
    }

    override fun onStateChanged(state: VoiceAssistant.State, detail: String?) {
        showVoiceState(state)
        // "Hey AR" heard → show the assistant like Alexa does.
        if (state == VoiceAssistant.State.LISTENING_COMMAND && sheet == null) openSheet(listen = false, byVoice = true)
        detail?.let { lastStatus = it }
        emitAll("voice", JSONObject().put("state", state.name).put("detail", detail ?: ""))
        if (state == VoiceAssistant.State.WAITING_WAKE_WORD || state == VoiceAssistant.State.OFF) {
            if (sheetOpenedByVoice) main.postDelayed(autoClose, 5000)
        } else {
            main.removeCallbacks(autoClose)
        }
    }

    override fun onCommand(text: String) {
        if (ScreenAgent.isRunning && Regex("^(stop|ruk|ruko|ruk jao|bas|cancel|band karo)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())) {
            ScreenAgent.cancel()
            voice.speak("Ruk gaya")
            return
        }
        emitAll("heard", JSONObject().put("text", text))
        voice.setThinking(text)
        worker.execute {
            val cmd = runCatching { AIBrain.interpret(this, text) }.getOrElse { AICommand("chat", reply = "Error: ${it.message}") }
            main.post { runCommand(cmd) }
        }
    }

    override fun onAgentProgress(text: String) {
        showVoiceState(VoiceAssistant.State.THINKING)
        lastStatus = text
        emitAll("agent", JSONObject().put("text", text))
    }

    override fun onAgentDone(reply: String) {
        lastStatus = reply
        emitAll("reply", JSONObject().put("text", reply).put("done", true))
        voice.speak(reply)
    }

    private fun runCommand(cmd: AICommand) {
        val screenChanging = cmd.action !in KEEP_SHEET
        if (screenChanging) {
            panel?.let { FloatingAppLauncher.anchor = it.rect() }
            closePanel()
        }
        main.postDelayed({
            val reply = runCatching { executor.execute(cmd) }.getOrElse { "Error: ${it.message}" }
            lastStatus = reply
            emitAll("reply", JSONObject().put("text", reply).put("action", cmd.action))
            voice.speak(reply)
            // Opening an app etc.: get the assistant out of the way after showing the answer.
            if (screenChanging && sheet != null) {
                main.removeCallbacks(autoClose)
                main.postDelayed({ closeSheet() }, 1600)
            }
        }, if (screenChanging) 200L else 0L)
    }

    // ------------------------------------------------------------------ helpers

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    private fun safeRemove(v: View) {
        try { wm.removeView(v) } catch (_: Throwable) { }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels
}
