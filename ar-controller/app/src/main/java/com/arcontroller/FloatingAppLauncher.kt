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
 *                via HiddenApiBypass. Needs freeform support switched on once in Developer options,
 *                then a reboot.
 *  2. WEB      – when freeform is off: the app's website inside our own floating window
 *                ([FloatingWebWindow]). Works on every phone with just the overlay permission.
 *  3. NEEDS_FREEFORM – no web version and freeform off: nothing is opened full screen; the UI explains the setup.
 */
object FloatingAppLauncher {

    enum class Mode { FREEFORM, WEB, NEEDS_FREEFORM, FULLSCREEN, FAILED }

    // Hidden constant WindowConfiguration.WINDOWING_MODE_FREEFORM.
    private const val WINDOWING_MODE_FREEFORM = 5

    @Volatile
    private var hiddenApiReady = false

    /**
     * HiddenApiBypass patches ART memory and can crash natively (uncatchable) on some OEM ROMs.
     * A persisted "in progress" marker detects such a crash, so it's never attempted again on that phone.
     */
    fun ensureHiddenApi(ctx: Context) {
        if (hiddenApiReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val prefs = ctx.applicationContext.getSharedPreferences("ar_safety", Context.MODE_PRIVATE)
        if (prefs.getBoolean("bypass_crashed", false)) return
        if (prefs.getBoolean("bypass_in_progress", false)) {
            prefs.edit().putBoolean("bypass_crashed", true).putBoolean("bypass_in_progress", false).commit()
            return
        }
        prefs.edit().putBoolean("bypass_in_progress", true).commit()
        hiddenApiReady = try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions;")
        } catch (_: Throwable) {
            false
        }
        prefs.edit().putBoolean("bypass_in_progress", false).commit()
    }

    fun isFreeformSupported(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            Settings.Global.getInt(cr, "enable_freeform_support", 0) != 0
    }

    /** Window bounds: around [anchor] (where the panel/bubble is) when given, else centred. */
    @Volatile
    var anchor: Rect? = null

    fun centeredBounds(ctx: Context): Rect {
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels * 0.86f).toInt()
        val h = (dm.heightPixels * 0.55f).toInt()
        val a = anchor
        val left = if (a != null) (a.centerX() - w / 2).coerceIn(0, dm.widthPixels - w) else (dm.widthPixels - w) / 2
        val top = if (a != null) a.top.coerceIn(dm.heightPixels / 12, dm.heightPixels - h - dm.heightPixels / 12) else (dm.heightPixels - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Must be called on the main thread (may create an overlay window).
     * Never falls back to full screen: without freeform and without a web version → NEEDS_FREEFORM.
     */
    fun launchFloating(ctx: Context, packageName: String, label: String = packageName): Mode {
        if (isFreeformSupported(ctx) && launchFreeform(ctx, packageName)) return Mode.FREEFORM

        WebApps.forPackage(packageName, label)?.let { site ->
            val icon = runCatching { ctx.packageManager.getApplicationIcon(packageName) }.getOrNull()
            FloatingWebWindow.show(ctx, label, site.url, icon, site.desktop, packageName, anchor)
            return Mode.WEB
        }
        return Mode.NEEDS_FREEFORM
    }

    fun floatWebsite(ctx: Context, urlOrQuery: String, title: String? = null) {
        val url = WebApps.normalizeUrl(urlOrQuery)
        FloatingWebWindow.show(ctx, title ?: (android.net.Uri.parse(url).host ?: "Web"), url, anchor = anchor)
    }

    private fun launchFreeform(ctx: Context, packageName: String): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        // Only needed (and only risked) once the user has actually enabled freeform windows.
        if (isFreeformSupported(ctx)) ensureHiddenApi(ctx)
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
