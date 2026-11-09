package com.arcontroller

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * JavaScript ⇄ native bridge exposed as `window.ARNative` to every HTML screen (main app, control
 * panel, assistant sheet). Results of slow calls are delivered through `window.__arcb(id, json)`.
 * JavascriptInterface methods run on a binder thread, so anything touching UI is posted to main.
 */
class Bridge(private val ctx: Context, private val web: WebView, private val host: Host) {

    /** Screen-specific behaviour (permission dialogs need an Activity, the panel can close itself…). */
    interface Host {
        fun requestRuntimePermissions(names: List<String>) {}
        fun hostCommand(cmd: String, arg: String): String = ""
        fun listen() {}
        fun executor(): CommandExecutor
    }

    private val main = Handler(Looper.getMainLooper())

    /** Set false before the WebView is destroyed so late callbacks are dropped. */
    @Volatile
    var alive = true

    companion object {
        private val worker = Executors.newFixedThreadPool(2)
        @Volatile
        private var iconCache: Map<String, String> = emptyMap()

        /** Runtime permission groups shown in the UI → Android permissions requested together. */
        val RUNTIME: Map<String, List<String>> = mapOf(
            "microphone" to listOf(Manifest.permission.RECORD_AUDIO),
            "contacts" to listOf(Manifest.permission.READ_CONTACTS),
            "phone" to listOfNotNull(
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Manifest.permission.ANSWER_PHONE_CALLS else null,
            ),
            "sms" to listOf(Manifest.permission.SEND_SMS),
            "notifications" to listOf("android.permission.POST_NOTIFICATIONS"),
        )

        fun granted(ctx: Context, name: String): Boolean {
            if (name == "notifications" && Build.VERSION.SDK_INT < 33) return true
            val perms = RUNTIME[name] ?: return false
            return perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        }

        fun canDrawOverlays(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
        fun canWriteSettings(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(ctx)
        fun hasDndAccess(ctx: Context) =
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    }

    // ---------------------------------------------------------------- helpers

    fun emit(type: String, data: JSONObject = JSONObject()) = main.post {
        if (alive) web.evaluateJavascript("window.AR_event && AR_event(${JSONObject.quote(type)}, ${data})", null)
    }

    private fun callback(id: String, payload: Any) = main.post {
        if (!alive) return@post
        val json = when (payload) {
            is JSONObject, is JSONArray -> payload.toString()
            else -> JSONObject.quote(payload.toString())
        }
        web.evaluateJavascript("window.__arcb && __arcb(${JSONObject.quote(id)}, $json)", null)
    }

    private fun onMain(block: () -> Unit) = main.post { runCatching(block).onFailure { CrashReporter.save(ctx, "Bridge", it) } }

    private fun startIntent(vararg intents: Intent) {
        for (i in intents.toList() + Intent(Settings.ACTION_SETTINGS)) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- status & config

    @JavascriptInterface
    fun status(): String = JSONObject().apply {
        put("overlay", canDrawOverlays(ctx))
        put("accessibility", ARAccessibilityService.isEnabled(ctx))
        RUNTIME.keys.forEach { put(it, granted(ctx, it)) }
        put("writeSettings", canWriteSettings(ctx))
        put("dnd", hasDndAccess(ctx))
        put("notificationAccess", NotificationWatcher.isEnabled(ctx))
        put("ai", Prefs.aiEnabled(ctx))
        put("freeform", FloatingAppLauncher.isFreeformSupported(ctx))
        put("devOptions", Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0)
        put("serviceRunning", OverlayService.isRunning)
        put("sdk", Build.VERSION.SDK_INT)
        put("battery", battery())
        put("version", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "")
        put("lastError", CrashReporter.read(ctx) ?: JSONObject.NULL)
    }.toString()

    private fun battery(): Int =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    @JavascriptInterface
    fun config(): String = JSONObject().apply {
        put("hasApiKey", Prefs.apiKey(ctx).isNotBlank())
        put("baseUrl", Prefs.baseUrl(ctx))
        put("model", Prefs.model(ctx))
        put("language", Prefs.language(ctx))
        put("wakeWord", Prefs.wakeWordEnabled(ctx))
        put("freeAi", Prefs.freeAiEnabled(ctx))
        put("ai", Prefs.aiEnabled(ctx))
        put("callCard", Prefs.callCard(ctx))
        put("announce", Prefs.announceMessages(ctx))
        put("greeting", Prefs.callGreeting(ctx))
        put("farewell", Prefs.callFarewell(ctx))
    }.toString()

    @JavascriptInterface
    fun saveConfig(json: String): String {
        val o = JSONObject(json)
        fun s(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
        Prefs.save(
            ctx,
            apiKey = s("apiKey")?.takeIf { it.isNotBlank() },
            baseUrl = s("baseUrl"),
            model = s("model"),
            language = s("language"),
            wakeWord = null,
        )
        if (o.has("freeAi")) Prefs.setFreeAi(ctx, o.getBoolean("freeAi"))
        if (o.has("ai")) Prefs.setAiEnabled(ctx, o.getBoolean("ai"))
        if (o.has("callCard")) Prefs.setCallCard(ctx, o.getBoolean("callCard"))
        if (o.has("announce")) Prefs.setAnnounceMessages(ctx, o.getBoolean("announce"))
        Prefs.setCallTexts(ctx, s("greeting"), s("farewell"))
        if (o.optBoolean("clearKey")) Prefs.save(ctx, "", null, null, null, null)
        AIBrain.clearHistory()
        OverlayService.send(ctx, OverlayService.ACTION_RELOAD_SETTINGS)
        return "ok"
    }

    // ---------------------------------------------------------------- permissions

    @JavascriptInterface
    fun request(what: String) = onMain {
        val pkg = Uri.parse("package:${ctx.packageName}")
        when (what) {
            "overlay" -> startIntent(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            "accessibility" -> startIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "writeSettings" -> startIntent(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg), Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
            "dnd" -> startIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            "notificationAccess" -> startIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "appDetails" -> startIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            "devOptions" -> {
                val enabled = Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
                startIntent(
                    if (enabled) Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) else Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
                )
            }
            "all" -> host.requestRuntimePermissions(RUNTIME.keys.filterNot { granted(ctx, it) })
            else -> if (what in RUNTIME) host.requestRuntimePermissions(listOf(what))
        }
    }

    // ---------------------------------------------------------------- controller

    @JavascriptInterface
    fun startController(): String {
        if (!canDrawOverlays(ctx)) return "no_overlay"
        return try {
            OverlayService.start(ctx)
            "ok"
        } catch (t: Throwable) {
            t.message ?: "error"
        }
    }

    @JavascriptInterface
    fun stopController() {
        OverlayService.stop(ctx)
    }

    // ---------------------------------------------------------------- AI & actions

    /** Natural-language command: interpret (local parser first, AI if needed) and execute. */
    @JavascriptInterface
    fun run(text: String, cbId: String) {
        worker.execute {
            try {
                val cmd = AIBrain.interpret(ctx, text)
                main.post {
                    val reply = runCatching { host.executor().execute(cmd) }.getOrElse { "Error: ${it.message}" }
                    callback(cbId, JSONObject().put("action", cmd.action).put("target", cmd.target ?: "").put("reply", reply))
                }
            } catch (t: Throwable) {
                callback(cbId, JSONObject().put("action", "error").put("reply", t.message ?: "error"))
            }
        }
    }

    /** Direct action from a panel button (no AI round-trip). */
    @JavascriptInterface
    fun action(name: String, arg: String, cbId: String) = onMain {
        val reply = host.hostCommand("action:$name", arg).ifEmpty {
            host.executor().execute(AICommand(name, target = arg.ifBlank { null }, text = arg.ifBlank { null }))
        }
        callback(cbId, reply)
    }

    @JavascriptInterface
    fun apps(cbId: String) {
        worker.execute {
            val list = runCatching { AppRepository.load(ctx) }.getOrDefault(emptyList())
            val cache = iconCache.toMutableMap()
            val arr = JSONArray()
            for (app in list) {
                val icon = cache.getOrPut(app.packageName) { iconDataUri(app) }
                arr.put(JSONObject().put("label", app.label).put("pkg", app.packageName).put("icon", icon))
            }
            iconCache = cache
            callback(cbId, arr)
        }
    }

    private fun iconDataUri(app: InstalledApp): String = try {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = app.icon.mutate()
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Throwable) {
        ""
    }

    /** mode: "float" (floating box) or "full". */
    @JavascriptInterface
    fun launch(pkg: String, mode: String, cbId: String) = onMain {
        val label = AppRepository.load(ctx).firstOrNull { it.packageName == pkg }?.label ?: pkg
        val reply = host.hostCommand("launch:$mode", pkg).ifEmpty {
            if (mode == "full") {
                FloatingAppLauncher.launchFullscreen(ctx, pkg)
                ""
            } else {
                host.executor().floatMessageFor(label, FloatingAppLauncher.launchFloating(ctx, pkg, label)) ?: ""
            }
        }
        callback(cbId, reply)
    }

    @JavascriptInterface
    fun listen() = onMain { host.listen() }

    @JavascriptInterface
    fun host(cmd: String, arg: String): String {
        var result = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            result = runCatching { host.hostCommand(cmd, arg) }.getOrDefault("")
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    @JavascriptInterface
    fun copy(text: String) = onMain {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("AR", text))
    }

    @JavascriptInterface
    fun clearError() = CrashReporter.clear(ctx)

    // ---------------------------------------------------------------- inbox (AI calls + WhatsApp)

    @JavascriptInterface
    fun calls(): String = Store.calls(ctx).toString()

    @JavascriptInterface
    fun messages(): String = Store.messages(ctx).toString()

    @JavascriptInterface
    fun deleteCall(id: String) = Store.deleteCall(ctx, id)

    @JavascriptInterface
    fun clearMessages() = Store.clearMessages(ctx)

    @JavascriptInterface
    fun canReply(sender: String): Boolean = NotificationWatcher.canReply(sender)

    /** Reads text aloud (call summaries, messages). */
    @JavascriptInterface
    fun speak(text: String) = onMain { host.hostCommand("speak", text) }

    /** Listen by voice and send the result as a WhatsApp reply to [sender]. */
    @JavascriptInterface
    fun replyByVoice(sender: String) = onMain { host.hostCommand("replyVoice", sender) }

    @JavascriptInterface
    fun openUrl(url: String) = onMain { startIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    @SuppressLint("JavascriptInterface")
    fun attach() = web.addJavascriptInterface(this, "ARNative")
}
