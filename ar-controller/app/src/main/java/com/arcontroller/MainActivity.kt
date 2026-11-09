package com.arcontroller

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Main screen: the HTML UI in assets/ui/index.html, talking to native code through [Bridge]. */
class MainActivity : Activity(), Bridge.Host, VoiceAssistant.Listener {

    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private val executor by lazy { CommandExecutor(this) }
    private var voice: VoiceAssistant? = null
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the previous run crashed (including native crashes), show the report first.
        if (savedInstanceState == null && CrashReporter.captureLastExit(this)) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#05070F"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Call recordings live in app storage and are played by the Inbox page.
            settings.allowFileAccess = true
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }
        bridge = Bridge(this, web, this).also { it.attach() }
        setContentView(web)
        val tab = intent?.getStringExtra("tab").orEmpty()
        web.loadUrl("file:///android_asset/ui/index.html" + if (tab.isNotBlank()) "#$tab" else "")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("tab")?.takeIf { it.isNotBlank() }?.let {
            bridge.emit("tab", JSONObject().put("tab", it))
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a Settings screen: refresh permission state.
        if (::bridge.isInitialized) bridge.emit("refresh")
    }

    override fun onDestroy() {
        voice?.stop()
        worker.shutdownNow()
        if (::bridge.isInitialized) {
            bridge.alive = false
            web.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.AR_back ? AR_back() : false") { handled ->
            if (handled != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ---------------------------------------------------------------- Bridge.Host

    override fun executor() = executor

    override fun requestRuntimePermissions(names: List<String>) {
        val perms = names.flatMap { Bridge.RUNTIME[it].orEmpty() }
            .filter { it != "android.permission.POST_NOTIFICATIONS" || Build.VERSION.SDK_INT >= 33 }
            .distinct()
        if (perms.isEmpty()) bridge.emit("refresh") else requestPermissions(perms.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        bridge.emit("refresh")
        OverlayService.send(this, OverlayService.ACTION_RELOAD_SETTINGS)
    }

    private fun ensureVoice(): VoiceAssistant? {
        if (!Bridge.granted(this, "microphone")) {
            requestRuntimePermissions(listOf("microphone"))
            return null
        }
        return voice ?: VoiceAssistant(this, this).also { voice = it; it.start(false) }
    }

    /** The orb on the home screen: one tap = listen once. */
    override fun listen() {
        val v = ensureVoice() ?: return
        if (v.state == VoiceAssistant.State.LISTENING_COMMAND) v.cancelListening() else v.listenForCommandNow()
    }

    override fun hostCommand(cmd: String, arg: String): String {
        when (cmd) {
            "speak" -> {
                val fresh = voice == null
                // A just-created TTS engine needs a moment before it can speak.
                ensureVoice()?.let { v -> web.postDelayed({ v.speak(arg) }, if (fresh) 900 else 0) }
            }
            "replyVoice" -> {
                executor.pendingReplyTo = arg
                ensureVoice()?.ask("$arg ko kya reply karun?")
            }
        }
        return ""
    }

    // ---------------------------------------------------------------- VoiceAssistant.Listener

    override fun onLevel(level: Float) {
        bridge.emit("level", JSONObject().put("v", level.toDouble()))
    }

    override fun onStateChanged(state: VoiceAssistant.State, detail: String?) {
        bridge.emit("voice", JSONObject().put("state", state.name).put("detail", detail ?: ""))
    }

    override fun onCommand(text: String) {
        bridge.emit("heard", JSONObject().put("text", text))
        executor.pendingReplyTo?.let { who ->
            executor.pendingReplyTo = null
            val reply = executor.execute(AICommand("reply_message", who.ifBlank { null }, text))
            bridge.emit("reply", JSONObject().put("text", reply))
            bridge.emit("inbox", JSONObject())
            voice?.speak(reply)
            return
        }
        voice?.setThinking(text)
        worker.execute {
            val cmd = runCatching { AIBrain.interpret(this, text) }.getOrElse { AICommand("chat", reply = "Error: ${it.message}") }
            runOnUiThread {
                val reply = runCatching { executor.execute(cmd) }.getOrElse { "Error: ${it.message}" }
                if (reply.startsWith(CommandExecutor.ASK_PREFIX)) {
                    val q = reply.removePrefix(CommandExecutor.ASK_PREFIX)
                    bridge.emit("reply", JSONObject().put("text", q))
                    voice?.ask(q)
                } else {
                    bridge.emit("reply", JSONObject().put("text", reply).put("action", cmd.action))
                    voice?.speak(reply)
                }
            }
        }
    }
}
