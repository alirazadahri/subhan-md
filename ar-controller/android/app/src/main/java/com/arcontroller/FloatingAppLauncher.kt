package com.arcontroller

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Opens an app as a floating box in the middle of the screen. Never uses split screen.
 *
 *  1. FREEFORM – real app in a floating, resizable system window. Uses launch bounds plus the hidden
 *                ActivityOptions.setLaunchWindowingMode(FREEFORM); Android 11 only honours the latter
 *                via HiddenApiBypass. Needs freeform support switched on once (Developer options, or
 *                [enableFreeform] after an ADB grant), then a reboot.
 *  2. WEB      – when freeform is off: the app's website inside our own floating window
 *                ([FloatingWebWindow]). Works on every phone with just the overlay permission.
 *  3. ATTEMPTED – no web version: launch with freeform options anyway (some OEM ROMs honour it).
 */
object FloatingAppLauncher {

    enum class Mode { FREEFORM, WEB, ATTEMPTED, FULLSCREEN, FAILED }

    // Hidden constant WindowConfiguration.WINDOWING_MODE_FREEFORM.
    private const val WINDOWING_MODE_FREEFORM = 5

    @Volatile
    private var hiddenApiReady = false

    fun ensureHiddenApi() {
        if (hiddenApiReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        hiddenApiReady = try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (_: Throwable) {
            false
        }
    }

    fun isFreeformSupported(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            Settings.Global.getInt(cr, "enable_freeform_support", 0) != 0
    }

    fun canWriteSecureSettings(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /**
     * Turns on freeform windows without opening Developer options. Requires a one-time
     * `adb shell pm grant com.arcontroller android.permission.WRITE_SECURE_SETTINGS`. Reboot afterwards.
     */
    fun enableFreeform(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        Settings.Global.putInt(cr, "enable_freeform_support", 1) &&
            Settings.Global.putInt(cr, "force_resizable_activities", 1)
    } catch (_: Throwable) {
        false
    }

    /** Centred box: 86% width, 58% height. */
    fun centeredBounds(ctx: Context): Rect {
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels * 0.86f).toInt()
        val h = (dm.heightPixels * 0.58f).toInt()
        val left = (dm.widthPixels - w) / 2
        val top = (dm.heightPixels - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** Must be called on the main thread (may create an overlay window). */
    fun launchFloating(ctx: Context, packageName: String, label: String = packageName): Mode {
        if (isFreeformSupported(ctx) && launchFreeform(ctx, packageName)) return Mode.FREEFORM

        WebApps.forPackage(packageName, label)?.let { site ->
            val icon = runCatching { ctx.packageManager.getApplicationIcon(packageName) }.getOrNull()
            FloatingWebWindow.show(ctx, label, site.url, icon, site.desktop, packageName)
            return Mode.WEB
        }

        return if (launchFreeform(ctx, packageName)) Mode.ATTEMPTED else Mode.FAILED
    }

    fun floatWebsite(ctx: Context, urlOrQuery: String, title: String? = null) {
        val url = WebApps.normalizeUrl(urlOrQuery)
        FloatingWebWindow.show(ctx, title ?: (android.net.Uri.parse(url).host ?: "Web"), url)
    }

    private fun launchFreeform(ctx: Context, packageName: String): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        ensureHiddenApi()
        return try {
            val options = ActivityOptions.makeBasic().setLaunchBounds(centeredBounds(ctx))
            try {
                ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .invoke(options, WINDOWING_MODE_FREEFORM)
            } catch (_: Throwable) {
            }
            // MULTIPLE_TASK makes the system create a fresh (freeform) task even if the app is already
            // open full screen in the background.
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            ctx.startActivity(launch, options.toBundle())
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun launchFullscreen(ctx: Context, packageName: String): Mode {
        val launch = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return Mode.FAILED
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(launch)
            Mode.FULLSCREEN
        } catch (_: Throwable) {
            Mode.FAILED
        }
    }
}
