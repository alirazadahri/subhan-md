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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service that owns everything drawn over other apps:
 *  - the draggable photo icon that opens the Control Panel (assets/ui/panel.html) in place,
 *  - the bottom-centre dot orb (AI on): tap → listen → caption shows what was heard and the answer,
 *  - the incoming-call card (Answer / Decline / AI answer / Busy SMS),
 *  - WhatsApp message alerts and voice replies.
 */
class OverlayService : Service(), VoiceAssistant.Listener, ScreenAgent.Listener, CallAssistant.Ui {

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
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private lateinit var voice: VoiceAssistant
    private lateinit var executor: CommandExecutor
    private lateinit var calls: CallAssistant
    private val themed by lazy { ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault) }
    private var voiceStarted = false

    // Floating icon
    private var bubble: FrameLayout? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var bubbleRing: GradientDrawable
    private var keepAwake = false

    // Control panel
    private var panel: OverlayWeb? = null

    // Orb + caption
    private var orb: OrbView? = null
    private var caption: LinearLayout? = null
    private var captionTitle: TextView? = null
    private var captionText: TextView? = null
    private val hideCaption = Runnable { setCaptionVisible(false) }

    // Call card
    private var callCard: LinearLayout? = null
    private var callStatus: TextView? = null
    private var callButtons: LinearLayout? = null
    private var ringingNumber = ""

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        executor = CommandExecutor(this, this)
        voice = VoiceAssistant(this, this)
        calls = CallAssistant(this, this)
        // The service shares the app's process, so a startup failure must not crash the app.
        try {
            startAsForeground()
            createBubble()
        } catch (t: Throwable) {
            CrashReporter.save(this, "OverlayService start failed", t)
            stopSelf()
            return
        }
        runCatching { applyAi() }
        runCatching { calls.start() }
        NotificationWatcher.onMessage = { msg -> main.post { onWhatsAppMessage(msg) } }
        worker.execute { runCatching { AppRepository.load(this) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopSelf()
                ACTION_LISTEN -> tapOrb()
                ACTION_RELOAD_SETTINGS -> {
                    applyAi()
                    runCatching { calls.start() }
                }
            }
        } catch (t: Throwable) {
            CrashReporter.save(this, "OverlayService command failed", t)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        NotificationWatcher.onMessage = null
        runCatching { ScreenAgent.cancel() }
        runCatching { FloatingWebWindow.closeAll() }
        runCatching { voice.stop() }
        runCatching { calls.stop() }
        worker.shutdownNow()
        closePanel(animate = false)
        removeOrb()
        hideIncoming()
        bubble?.let { safeRemove(it) }
        bubble = null
        super.onDestroy()
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** AI on → orb + voice; AI off → controller only (no microphone). */
    private fun applyAi() {
        if (Prefs.aiEnabled(this)) {
            if (orb == null) createOrb()
            if (!voiceStarted && micGranted()) {
                voice.start(false) // tap-to-talk only: no always-on listening loop
                voiceStarted = true
            }
        } else {
            removeOrb()
            if (voiceStarted) {
                voice.stop()
                voiceStarted = false
            }
        }
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
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), piFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AR Controller is active")
            .setContentText("Tap the orb to talk")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ------------------------------------------------------------------ floating icon

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val size = dp(56)
        bubbleRing = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#0B1020"))
            setStroke(dp(2), Color.parseColor("#8B5CF6"))
        }
        val view = FrameLayout(this).apply {
            background = bubbleRing
            val pad = dp(3)
            setPadding(pad, pad, pad, pad)
            elevation = dp(10).toFloat()
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ar_bubble)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(size - 2 * pad, size - 2 * pad))
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

    private fun setKeepAwake(on: Boolean) {
        keepAwake = on
        val b = bubble ?: return
        bubbleParams.flags = if (on) bubbleParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else bubbleParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        wm.updateViewLayout(b, bubbleParams)
    }

    // ------------------------------------------------------------------ HTML control panel

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
                root.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(140).withEndAction(finish).start()
            } else finish()
        }

        override fun executor() = this@OverlayService.executor
        override fun listen() {
            main.post { closePanel(); tapOrb() }
        }
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

    /** Commands sent from the panel via ARNative.host(...) / action / launch. */
    private fun handleHostCommand(win: OverlayWeb, cmd: String, arg: String): String {
        when {
            cmd == "close" -> main.post { closePanel() }
            cmd == "focus" -> win.setFocusable(arg == "1")
            cmd == "openMain" -> main.post {
                closePanel()
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("tab", arg),
                )
            }
            cmd == "ai" -> main.post {
                Prefs.setAiEnabled(this, arg == "1")
                applyAi()
            }
            cmd == "stopAgent" -> ScreenAgent.cancel()
            cmd == "stopService" -> main.post { stopSelf() }
            cmd == "state" -> return JSONObject().put("keepAwake", keepAwake).put("ai", Prefs.aiEnabled(this)).toString()
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
                if (name !in KEEP_PANEL) {
                    // Hide the panel first so it isn't captured in screenshots or left over the app.
                    FloatingAppLauncher.anchor = win.rect()
                    main.post { closePanel() }
                    main.postDelayed({
                        val reply = executor.execute(AICommand(name, target = arg.ifBlank { null }, text = arg.ifBlank { null }))
                        if (reply.contains("allow", true) || reply.contains("nahi", true)) toast(reply)
                    }, 260)
                    return "ok"
                }
            }
            cmd.startsWith("launch:") -> {
                FloatingAppLauncher.anchor = win.rect()
                main.postDelayed({ closePanel() }, 120)
            }
        }
        return ""
    }

    private fun openPanel() {
        if (panel != null) return
        val w = minOf(dp(360), screenW() - dp(16))
        val h = minOf(dp(600), (screenH() * 0.8f).toInt())
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

    // ------------------------------------------------------------------ orb + caption

    @SuppressLint("ClickableViewAccessibility")
    private fun createOrb() {
        if (orb != null) return
        val size = dp(92)
        val view = OrbView(this)
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(14)
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    tapOrb()
                }
                MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            true
        }
        wm.addView(view, params)
        orb = view
        view.alpha = 0f
        view.translationY = dp(30).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator()).start()
        createCaption()
    }

    private fun removeOrb() {
        orb?.let { safeRemove(it) }
        orb = null
        caption?.let { safeRemove(it) }
        caption = null
    }

    /** Text bubble above the orb; never takes touches, so it can't block the app underneath. */
    private fun createCaption() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60B1022"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#332A3A66"))
            }
            setPadding(dp(18), dp(12), dp(18), dp(14))
            alpha = 0f
            visibility = View.GONE
        }
        captionTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#67E8F9"))
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        captionText = TextView(this).apply {
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        box.addView(captionTitle)
        box.addView(captionText)
        val params = WindowManager.LayoutParams(
            minOf(screenW() - dp(32), dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(14 + 92 + 6)
        }
        wm.addView(box, params)
        caption = box
    }

    private fun showCaption(title: String, text: String?, autoHideMs: Long = 0) {
        captionTitle?.text = title.uppercase()
        captionText?.text = text ?: ""
        captionText?.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        setCaptionVisible(true)
        main.removeCallbacks(hideCaption)
        if (autoHideMs > 0) main.postDelayed(hideCaption, autoHideMs)
    }

    private fun setCaptionVisible(show: Boolean) {
        val c = caption ?: return
        if (show) {
            if (c.visibility != View.VISIBLE) {
                c.visibility = View.VISIBLE
                c.translationY = dp(10).toFloat()
                c.animate().alpha(1f).translationY(0f).setDuration(200).start()
            }
        } else {
            c.animate().alpha(0f).setDuration(220).withEndAction { c.visibility = View.GONE }.start()
        }
    }

    /** Orb tap: start listening, or stop if already listening. */
    private fun tapOrb() {
        if (!Prefs.aiEnabled(this)) {
            toast("AI off hai — panel se AI on karein")
            return
        }
        if (!micGranted()) {
            showCaption("Microphone", "AR Controller app khol kar microphone allow karein", 4000)
            return
        }
        if (!voiceStarted) {
            voice.start(false)
            voiceStarted = true
        }
        when (voice.state) {
            VoiceAssistant.State.LISTENING_COMMAND -> {
                executor.pendingReplyTo = null
                voice.cancelListening()
                setCaptionVisible(false)
            }
            VoiceAssistant.State.SPEAKING -> {
                voice.cancelListening()
                voice.listenForCommandNow()
            }
            else -> voice.listenForCommandNow()
        }
    }

    // ------------------------------------------------------------------ voice + AI

    override fun onLevel(level: Float) {
        orb?.level = level
    }

    override fun onStateChanged(state: VoiceAssistant.State, detail: String?) {
        orb?.mode = when (state) {
            VoiceAssistant.State.LISTENING_COMMAND -> OrbView.Mode.LISTENING
            VoiceAssistant.State.THINKING -> OrbView.Mode.THINKING
            VoiceAssistant.State.SPEAKING -> OrbView.Mode.SPEAKING
            else -> OrbView.Mode.IDLE
        }
        val c = when (state) {
            VoiceAssistant.State.LISTENING_COMMAND -> "#22C55E"
            VoiceAssistant.State.THINKING -> "#F59E0B"
            VoiceAssistant.State.SPEAKING -> "#38BDF8"
            else -> "#8B5CF6"
        }
        bubbleRing.setStroke(dp(2), Color.parseColor(c))
        when (state) {
            VoiceAssistant.State.LISTENING_COMMAND -> {
                val heard = detail?.takeIf { it.startsWith("\u201c") }?.trim('\u201c', '\u201d')
                showCaption(if (executor.pendingReplyTo != null) "Reply bolein" else "Listening", heard ?: "Boliye…")
            }
            VoiceAssistant.State.THINKING -> showCaption("Thinking", detail)
            VoiceAssistant.State.SPEAKING -> showCaption("AR", detail)
            VoiceAssistant.State.OFF -> {
                if (detail != null) showCaption("AR", detail, 3500)
                else if (caption?.visibility == View.VISIBLE) main.postDelayed(hideCaption, 3500)
            }
            else -> Unit
        }
        panel?.bridge?.emit("voice", JSONObject().put("state", state.name).put("detail", detail ?: ""))
    }

    override fun onCommand(text: String) {
        val t = text.trim()
        if (ScreenAgent.isRunning && Regex("^(stop|ruk|ruko|ruk jao|bas|cancel|band karo)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            ScreenAgent.cancel()
            voice.speak("Ruk gaya")
            return
        }
        // Answer to "Ali ko kya reply karun?"
        executor.pendingReplyTo?.let { who ->
            executor.pendingReplyTo = null
            if (Regex("^(cancel|rehne do|kuch nahi|nahi|no)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
                voice.speak("Theek hai")
                return
            }
            runCommand(AICommand("reply_message", who.ifBlank { null }, t))
            return
        }
        voice.setThinking(t)
        worker.execute {
            val cmd = runCatching { AIBrain.interpret(this, t) }.getOrElse { AICommand("chat", reply = "Error: ${it.message}") }
            main.post { runCommand(cmd) }
        }
    }

    override fun onAgentProgress(text: String) {
        orb?.mode = OrbView.Mode.THINKING
        showCaption("Working", text)
    }

    override fun onAgentDone(reply: String) {
        voice.speak(reply)
    }

    private fun runCommand(cmd: AICommand) {
        if (cmd.action !in KEEP_PANEL && cmd.action != "chat") {
            panel?.let { FloatingAppLauncher.anchor = it.rect() }
            closePanel()
        }
        main.postDelayed({
            val reply = runCatching { executor.execute(cmd) }.getOrElse { "Error: ${it.message}" }
            if (reply.startsWith(CommandExecutor.ASK_PREFIX)) voice.ask(reply.removePrefix(CommandExecutor.ASK_PREFIX))
            else voice.speak(reply)
        }, 150)
    }

    // ------------------------------------------------------------------ WhatsApp

    private fun onWhatsAppMessage(msg: JSONObject) {
        panel?.bridge?.emit("inbox", JSONObject())
        if (!Prefs.aiEnabled(this) || orb == null) return
        val sender = msg.optString("sender")
        val text = msg.optString("text")
        if (Prefs.announceMessages(this) && voice.state == VoiceAssistant.State.OFF) {
            voice.speak("$sender ka message: $text")
        } else {
            showCaption("WhatsApp · $sender", "$text\nReply ke liye orb dabayein aur bolein \"reply …\"", 7000)
        }
    }

    // ------------------------------------------------------------------ incoming call card

    override fun showIncoming(number: String, name: String?) {
        ringingNumber = number
        hideIncoming()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F20B1022"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#3A4A7A"))
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            elevation = dp(16).toFloat()
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val who = name ?: number.ifBlank { "Unknown" }
        top.addView(TextView(this).apply {
            text = who.take(1).uppercase()
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#3B82F6"))).apply {
                shape = GradientDrawable.OVAL
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        names.addView(TextView(this).apply {
            text = "INCOMING CALL"
            setTextColor(Color.parseColor("#67E8F9"))
            textSize = 11f
            letterSpacing = 0.12f
        })
        names.addView(TextView(this).apply {
            text = who
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (name != null && number.isNotBlank()) names.addView(TextView(this).apply {
            text = number
            setTextColor(Color.parseColor("#8A94B3"))
            textSize = 13f
        })
        top.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)

        callStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#C7D2FE"))
            textSize = 13f
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(callStatus)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        fun button(label: String, color: String, weight: Float = 1f, onTap: () -> Unit) {
            buttons.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(color))
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener { onTap() }
            }, LinearLayout.LayoutParams(0, dp(46), weight).apply { marginEnd = dp(8) })
        }
        button("Decline", "#DC2626") { calls.hangUp(); hideIncoming() }
        if (Prefs.aiEnabled(this)) button("AI answer", "#7C3AED", 1.25f) { calls.aiAnswer(ringingNumber) }
        button("Answer", "#16A34A") { calls.answer(); hideIncoming() }
        card.addView(buttons)
        callButtons = buttons

        card.addView(TextView(this).apply {
            text = "Busy hoon — SMS bhej kar band karein"
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                toast(calls.declineWithSms(ringingNumber))
                hideIncoming()
            }
        })

        val params = WindowManager.LayoutParams(
            minOf(screenW() - dp(20), dp(440)), ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }
        wm.addView(card, params)
        callCard = card
        card.translationY = -dp(40).toFloat()
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
    }

    override fun hideIncoming() {
        callCard?.let { safeRemove(it) }
        callCard = null
        callStatus = null
        callButtons = null
    }

    override fun aiStatus(text: String) {
        callButtons?.visibility = View.GONE
        callStatus?.apply {
            visibility = View.VISIBLE
            this.text = text
        }
        if (callCard == null) toast(text)
    }

    override fun callSaved(call: JSONObject) {
        panel?.bridge?.emit("inbox", JSONObject())
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
