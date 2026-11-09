package com.arcontroller

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Opens a third-party app as a floating box in the middle of the screen.
 *
 * Android has no public API to embed another app's UI inside our own window, so we ask the
 * system window manager to do it:
 *  1. FREEFORM  – ActivityOptions.setLaunchBounds() with a centred Rect. Honoured when freeform
 *                 windowing is enabled (Developer options → "Enable freeform windows", or OEM
 *                 pop-up view on Samsung / Xiaomi / OnePlus / etc.).
 *  2. SPLIT     – Fallback: toggle split screen via Accessibility and launch the app adjacent.
 *  3. FULLSCREEN – Last resort.
 * See README.md ("How the floating app window works") for the VirtualDisplay/Shizuku approach.
 */
object FloatingAppLauncher {

    enum class Mode { FREEFORM, SPLIT, FULLSCREEN, FAILED }

    // Hidden constant WindowConfiguration.WINDOWING_MODE_FREEFORM.
    private const val WINDOWING_MODE_FREEFORM = 5

    fun isFreeformSupported(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            Settings.Global.getInt(cr, "enable_freeform_support", 0) != 0 ||
            Settings.Global.getInt(cr, "force_resizable_activities", 0) != 0
    }

    /** Centred box: 86% width, 55% height. */
    fun centeredBounds(ctx: Context): Rect {
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels * 0.86f).toInt()
        val h = (dm.heightPixels * 0.55f).toInt()
        val left = (dm.widthPixels - w) / 2
        val top = (dm.heightPixels - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    fun launchFloating(ctx: Context, packageName: String): Mode {
        val launch = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return Mode.FAILED

        if (isFreeformSupported(ctx)) {
            return try {
                val options = ActivityOptions.makeBasic().setLaunchBounds(centeredBounds(ctx))
                forceFreeformMode(options)
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                ctx.startActivity(launch, options.toBundle())
                Mode.FREEFORM
            } catch (t: Throwable) {
                launchFullscreen(ctx, packageName)
            }
        }

        val a11y = ARAccessibilityService.instance
        if (a11y != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            a11y.global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    launch.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                    )
                    ctx.startActivity(launch)
                } catch (_: Throwable) {
                    launchFullscreen(ctx, packageName)
                }
            }, 700)
            return Mode.SPLIT
        }

        return launchFullscreen(ctx, packageName)
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

    /** Hidden API; silently ignored where non-SDK access is blocked (bounds alone usually suffice). */
    private fun forceFreeformMode(options: ActivityOptions) {
        try {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FREEFORM)
        } catch (_: Throwable) {
        }
    }
}
