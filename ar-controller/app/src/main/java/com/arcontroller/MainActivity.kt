package com.arcontroller

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }
        bridge = Bridge(this, web, this).also { it.attach() }
        setContentView(web)
        web.loadUrl("file:///android_asset/ui/index.html")
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
        val perms = names.mapNotNull { Bridge.RUNTIME[it] }
            .filter { it != "android.permission.POST_NOTIFICATIONS" || android.os.Build.VERSION.SDK_INT >= 33 }
        if (perms.isEmpty()) bridge.emit("refresh") else requestPermissions(perms.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        bridge.emit("refresh")
    }

    /** In-app assistant: the overlay assistant handles it when running, otherwise a local one-shot listener. */
    override fun listen() {
        if (OverlayService.isRunning) {
            OverlayService.send(this, OverlayService.ACTION_LISTEN)
            return
        }
        if (!Bridge.granted(this, "microphone")) {
            requestRuntimePermissions(listOf("microphone"))
            return
        }
        val v = voice ?: VoiceAssistant(this, this).also { voice = it; it.start(false) }
        v.listenForCommandNow()
    }

    override fun hostCommand(cmd: String, arg: String): String {
        if (cmd == "say") onCommand(arg)
        return ""
    }

    // ---------------------------------------------------------------- VoiceAssistant.Listener

    override fun onStateChanged(state: VoiceAssistant.State, detail: String?) {
        bridge.emit("voice", JSONObject().put("state", state.name).put("detail", detail ?: ""))
    }

    override fun onCommand(text: String) {
        bridge.emit("heard", JSONObject().put("text", text))
        voice?.setThinking(text)
        worker.execute {
            val cmd = runCatching { AIBrain.interpret(this, text) }.getOrElse { AICommand("chat", reply = "Error: ${it.message}") }
            runOnUiThread {
                val reply = runCatching { executor.execute(cmd) }.getOrElse { "Error: ${it.message}" }
                bridge.emit("reply", JSONObject().put("text", reply).put("action", cmd.action))
                voice?.speak(reply)
            }
        }
    }
}
