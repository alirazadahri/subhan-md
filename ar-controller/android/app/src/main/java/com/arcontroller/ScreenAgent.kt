package com.arcontroller

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * "Do anything" mode: reads the screen through Accessibility, asks the AI for the next step
 * (open app / tap / type / scroll / back …), performs it, and repeats until the goal is done.
 */
object ScreenAgent {

    interface Listener {
        fun onAgentProgress(text: String)
        fun onAgentDone(reply: String)
    }

    private const val MAX_STEPS = 15
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var runId = 0

    val isRunning get() = running
    @Volatile
    private var running = false

    fun cancel() {
        runId++
        running = false
    }

    fun start(ctx: Context, goal: String, listener: Listener) {
        val app = ctx.applicationContext
        val myRun = ++runId
        running = true
        Thread({ run(app, goal, myRun, listener) }, "ar-agent").start()
    }

    private fun run(ctx: Context, goal: String, myRun: Int, listener: Listener) {
        fun progress(t: String) = main.post { if (myRun == runId) listener.onAgentProgress(t) }
        fun finish(t: String) = main.post {
            if (myRun == runId) {
                running = false
                listener.onAgentDone(t)
            }
        }

        val a11y = ARAccessibilityService.instance
        if (a11y == null) {
            finish(CommandExecutor.A11Y_MISSING)
            return
        }
        if (!AIBrain.hasProvider(ctx)) {
            finish("Is kaam ke liye AI chahiye — app mein Free AI on karein ya API key daalein")
            return
        }

        val steps = mutableListOf<String>()
        for (i in 1..MAX_STEPS) {
            if (myRun != runId) return
            val screen = a11y.describeScreen()
            var step: org.json.JSONObject? = null
            var attempts = 0
            while (step == null) {
                try {
                    step = AIBrain.nextAgentStep(ctx, goal, screen.text, steps)
                } catch (r: AIBrain.RateLimited) {
                    if (++attempts > 3) {
                        finish("Free AI bahut slow hai — app mein free Groq key daalein")
                        return
                    }
                    progress("Free AI: ${r.waitSeconds} sec intezar…")
                    Thread.sleep((r.waitSeconds + 1) * 1000L)
                    if (myRun != runId) return
                } catch (t: Throwable) {
                    finish("AI se rabta nahi ho saka: ${t.message}")
                    return
                }
            }
            if (myRun != runId) return

            val action = step.optString("action")
            val id = step.optInt("id", -1)
            val text = step.optString("text", "")
            step.optString("say").takeIf { it.isNotBlank() }?.let { progress(it) }
            val node = screen.nodes.getOrNull(id - 1)

            val ok: Boolean = when (action) {
                "done" -> {
                    finish(step.optString("reply").ifBlank { "Ho gaya ✓" })
                    return
                }
                "open_app" -> {
                    val found = AppRepository.findByName(ctx, text)
                    found != null && FloatingAppLauncher.launchFullscreen(ctx, found.packageName) != FloatingAppLauncher.Mode.FAILED
                }
                "tap" -> node != null && a11y.tapNode(node)
                "type" -> node != null && a11y.typeInto(node, text)
                "enter" -> a11y.pressEnter()
                "scroll_down" -> a11y.scroll(true)
                "scroll_up" -> a11y.scroll(false)
                "back" -> a11y.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> a11y.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "wait" -> true
                else -> false
            }
            val desc = buildString {
                append(i).append(". ").append(action)
                if (id > 0) append(" [").append(id).append(']')
                if (text.isNotBlank()) append(" \"").append(text.take(40)).append('"')
                append(if (ok) " → ok" else " → FAILED")
            }
            steps += desc
            // Let the UI settle (app launches take longer).
            Thread.sleep(if (action == "open_app") 2500L else 1300L)
        }
        finish("Kaafi koshish ki, kaam poora nahi ho saka")
    }
}
