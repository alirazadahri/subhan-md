package com.arcontroller

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Catches any Java/JS crash, saves the stack trace and shows it on [CrashActivity] (separate process),
 * so a crash produces a readable error instead of the app silently closing.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                save(app, "Crash on thread ${thread.name}", error)
                app.startActivity(
                    Intent(app, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun save(ctx: Context, title: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append("AR Controller ").append(appVersion(ctx)).append(" · Android ").append(Build.VERSION.RELEASE)
            append(" (SDK ").append(Build.VERSION.SDK_INT).append(") · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append("\n").append(title).append("\n\n").append(sw.toString().take(12000))
        }
        runCatching { File(ctx.filesDir, FILE).writeText(text) }
    }

    fun read(ctx: Context): String? = runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }

    private fun appVersion(ctx: Context) =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}

/** Plain native screen (no React Native) showing the last crash with Copy / Restart buttons. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReporter.read(this) ?: "No crash report."
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070B18"))
            setPadding(dp(18), dp(36), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "AR Controller crash ho gaya 😕"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Neeche \"Copy error\" dabayein aur ye error chat mein bhej dein, taake fix ho sake."
            setTextColor(Color.parseColor("#8B97B5"))
            textSize = 14f
            setPadding(0, dp(6), 0, dp(12))
        })
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1528"))
            addView(TextView(this@CrashActivity).apply {
                text = report
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "Copy error"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AR crash", report))
                Toast.makeText(this@CrashActivity, "Copied ✓", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Restart app"
            setOnClickListener {
                CrashReporter.clear(this@CrashActivity)
                startActivity(Intent(this@CrashActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                Process.killProcess(Process.myPid())
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        setContentView(root)
    }
}
