package com.arcontroller

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import java.util.concurrent.Executors

/** JS bridge: `NativeModules.ARController` (see src/native/ARController.ts). */
class ARControllerModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {

    private val worker = Executors.newSingleThreadExecutor()

    override fun getName() = "ARController"

    @ReactMethod
    fun getStatus(promise: Promise) {
        val map = Arguments.createMap().apply {
            putBoolean("overlay", canDrawOverlays())
            putBoolean("accessibility", ARAccessibilityService.isEnabled(ctx))
            putBoolean("accessibilityConnected", ARAccessibilityService.instance != null)
            putBoolean("freeform", FloatingAppLauncher.isFreeformSupported(ctx))
            putBoolean("devOptionsEnabled", devOptionsEnabled())
            putBoolean("canWriteSecureSettings", FloatingAppLauncher.canWriteSecureSettings(ctx))
            putBoolean("serviceRunning", OverlayService.isRunning)
            putInt("sdk", Build.VERSION.SDK_INT)
            putString("lastError", CrashReporter.read(ctx))
        }
        promise.resolve(map)
    }

    @ReactMethod
    fun clearLastError() {
        CrashReporter.clear(ctx)
    }

    @ReactMethod
    fun copyText(text: String) {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("AR", text))
    }

    @ReactMethod
    fun openOverlaySettings() {
        start(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
    }

    // Async @ReactMethods must return void: React Native refuses to load the whole module otherwise.
    @ReactMethod
    fun openAccessibilitySettings() {
        start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Android 13+ blocks Accessibility for sideloaded APKs until "Allow restricted settings" is tapped here. */
    @ReactMethod
    fun openAppDetails() {
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    /** Developer options are hidden until "Build number" is tapped 7 times, so open About phone in that case. */
    @ReactMethod
    fun openDeveloperOptions(promise: Promise) {
        if (devOptionsEnabled() && start(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), fallbackToSettings = false)) {
            promise.resolve("developer")
        } else {
            start(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            promise.resolve("about")
        }
    }

    private fun devOptionsEnabled() =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0

    /** One-tap freeform switch; works only after the one-time ADB grant of WRITE_SECURE_SETTINGS. */
    @ReactMethod
    fun enableFreeform(promise: Promise) {
        promise.resolve(FloatingAppLauncher.enableFreeform(ctx))
    }

    @ReactMethod
    fun startController(promise: Promise) {
        if (!canDrawOverlays()) {
            promise.reject("NO_OVERLAY", "Display over other apps permission is required")
            return
        }
        try {
            OverlayService.start(ctx)
            promise.resolve(true)
        } catch (t: Throwable) {
            promise.reject("START_FAILED", t.message, t)
        }
    }

    @ReactMethod
    fun stopController() {
        OverlayService.stop(ctx)
    }

    @ReactMethod
    fun getConfig(promise: Promise) {
        promise.resolve(Arguments.createMap().apply {
            putBoolean("hasApiKey", Prefs.apiKey(ctx).isNotBlank())
            putString("baseUrl", Prefs.baseUrl(ctx))
            putString("model", Prefs.model(ctx))
            putString("language", Prefs.language(ctx))
            putBoolean("wakeWordEnabled", Prefs.wakeWordEnabled(ctx))
            putBoolean("freeAiEnabled", Prefs.freeAiEnabled(ctx))
        })
    }

    @ReactMethod
    fun saveConfig(config: ReadableMap, promise: Promise) {
        fun str(k: String) = if (config.hasKey(k) && !config.isNull(k)) config.getString(k) else null
        Prefs.save(
            ctx,
            apiKey = str("apiKey")?.takeIf { it.isNotBlank() },
            baseUrl = str("baseUrl"),
            model = str("model"),
            language = str("language"),
            wakeWord = if (config.hasKey("wakeWordEnabled")) config.getBoolean("wakeWordEnabled") else null,
        )
        if (config.hasKey("freeAiEnabled")) Prefs.setFreeAi(ctx, config.getBoolean("freeAiEnabled"))
        OverlayService.send(ctx, OverlayService.ACTION_RELOAD_SETTINGS)
        promise.resolve(true)
    }

    @ReactMethod
    fun clearApiKey() {
        Prefs.save(ctx, "", null, null, null, null)
    }

    /** Runs a typed command through AI + executor, e.g. from the test box in the app. */
    @ReactMethod
    fun runCommand(text: String, promise: Promise) {
        worker.execute {
            try {
                val cmd = AIBrain.interpret(ctx, text)
                ctx.runOnUiQueueThread {
                    val reply = CommandExecutor(ctx).execute(cmd)
                    promise.resolve(Arguments.createMap().apply {
                        putString("action", cmd.action)
                        putString("target", cmd.target)
                        putString("reply", reply)
                    })
                }
            } catch (t: Throwable) {
                promise.reject("COMMAND_FAILED", t.message, t)
            }
        }
    }

    private fun canDrawOverlays() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    /** Tries each intent in order (OEM ROMs sometimes lack a screen). Never throws. */
    private fun start(vararg intents: Intent, fallbackToSettings: Boolean = true): Boolean {
        val activity = ctx.currentActivity
        val candidates = if (fallbackToSettings) intents.toList() + Intent(Settings.ACTION_SETTINGS) else intents.toList()
        for ((i, intent) in candidates.withIndex()) {
            try {
                if (activity != null) activity.startActivity(intent)
                else ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return i < intents.size
            } catch (_: Throwable) {
            }
        }
        return false
    }

    override fun invalidate() {
        worker.shutdownNow()
        super.invalidate()
    }
}
