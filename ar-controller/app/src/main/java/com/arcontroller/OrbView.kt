package com.arcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Assistant orb: a slowly rotating sphere of glowing dots.
 * Idle = calm rotation, listening = dots react to the voice level, thinking = fast swirl,
 * speaking = gentle pulse.
 */
class OrbView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    /** 0..1 microphone level while listening. */
    var level = 0f
        set(value) {
            field = field * 0.6f + value * 0.4f
        }

    private val count = 170
    private val px = FloatArray(count)
    private val py = FloatArray(count)
    private val pz = FloatArray(count)
    private val sx = FloatArray(count)
    private val sy = FloatArray(count)
    private val sz = FloatArray(count)
    private val order = Array(count) { it }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()
    private var angle = 0f
    private var lastFrame = 0L

    init {
        // Fibonacci sphere: evenly spread points.
        val golden = PI * (3 - sqrt(5.0))
        for (i in 0 until count) {
            val y = 1 - (i / (count - 1f)) * 2
            val r = sqrt(1 - y * y)
            val theta = golden * i
            px[i] = (cos(theta) * r).toFloat()
            py[i] = y
            pz[i] = (sin(theta) * r).toFloat()
        }
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
        )
    }

    private val cyan = Color.parseColor("#22D3EE")
    private val blue = Color.parseColor("#3B82F6")
    private val violet = Color.parseColor("#A78BFA")
    private val amber = Color.parseColor("#FBBF24")
    private val pink = Color.parseColor("#F472B6")

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val t = (now - start) / 1e9f
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now

        val speed = when (mode) {
            Mode.IDLE -> 0.35f
            Mode.LISTENING -> 0.8f + level * 1.5f
            Mode.THINKING -> 2.6f
            Mode.SPEAKING -> 0.9f
        }
        angle += speed * dt

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val base = minOf(w, h) * 0.34f
        val radius = base * when (mode) {
            Mode.IDLE -> 1f + 0.03f * sin(t * 1.6f)
            Mode.LISTENING -> 1f + 0.22f * level
            Mode.THINKING -> 0.95f + 0.04f * sin(t * 6f)
            Mode.SPEAKING -> 1f + 0.07f * sin(t * 7f)
        }

        // Soft halo behind the sphere.
        val haloColor = when (mode) {
            Mode.THINKING -> amber
            Mode.SPEAKING -> violet
            else -> blue
        }
        val haloAlpha = when (mode) {
            Mode.IDLE -> 70
            Mode.LISTENING -> (110 + 110 * level).toInt()
            else -> 130
        }
        glow.shader = RadialGradient(
            cx, cy, minOf(w, h) / 2,
            intArrayOf(Color.argb(haloAlpha, Color.red(haloColor), Color.green(haloColor), Color.blue(haloColor)), Color.TRANSPARENT),
            floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, minOf(w, h) / 2, glow)

        val ca = cos(angle)
        val sa = sin(angle)
        val tilt = 0.45f
        val ct = cos(tilt)
        val st = sin(tilt)
        for (i in 0 until count) {
            // Rotate around Y, then tilt around X.
            val x1 = px[i] * ca + pz[i] * sa
            val z1 = -px[i] * sa + pz[i] * ca
            val y2 = py[i] * ct - z1 * st
            val z2 = py[i] * st + z1 * ct
            var r = radius
            if (mode == Mode.LISTENING) r *= 1f + level * 0.18f * sin(t * 9f + i * 0.7f)
            if (mode == Mode.THINKING) r *= 1f + 0.06f * sin(t * 5f + acos(py[i].coerceIn(-1f, 1f)) * 6f)
            sx[i] = cx + x1 * r
            sy[i] = cy + y2 * r
            sz[i] = z2
        }
        order.sortBy { sz[it] }
        val dotBase = minOf(w, h) * 0.022f
        for (i in order) {
            val depth = (sz[i] + 1f) / 2f // 0 back … 1 front
            val lat = (py[i] + 1f) / 2f
            var c = if (lat < 0.5f) mix(cyan, blue, lat * 2) else mix(blue, violet, (lat - 0.5f) * 2)
            if (mode == Mode.THINKING) c = mix(c, amber, 0.5f + 0.5f * sin(t * 3f + i * 0.2f))
            if (mode == Mode.SPEAKING) c = mix(c, pink, 0.25f + 0.25f * sin(t * 4f + lat * 6f))
            dot.color = c
            dot.alpha = (60 + 195 * depth).toInt()
            canvas.drawCircle(sx[i], sy[i], dotBase * (0.45f + 0.75f * depth), dot)
        }

        if (isShown) {
            // Idle at ~30 fps to save battery, smooth 60 fps when active.
            if (mode == Mode.IDLE) postInvalidateDelayed(33) else postInvalidateOnAnimation()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }
}
