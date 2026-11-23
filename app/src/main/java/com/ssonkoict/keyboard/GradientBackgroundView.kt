package com.ssonkoict.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Background behind the keys.
 *
 * Idle: a plain solid background (black in dark mode, white in light mode), and
 * nothing is animating – no frames are drawn at all.
 *
 * Each key press starts its own short gradient "bloom" at that key: two
 * complementary colours swirl around each other, blend, grow outwards and fade
 * away (~1.3 s). Several fast presses each keep their own bloom for their full
 * time, all animating together, then the background is solid again.
 *
 * Smoothness: blooms fade in and out with easing (no pops), frames are tied to
 * the display refresh while anything is animating, and the keys themselves are
 * never redrawn by this view. Reduced motion / Battery Saver: no blooms.
 */
class GradientBackgroundView(context: Context) : View(context) {

    /** A bloom from one key press: two colour fields that orbit each other. */
    private class Bloom {
        val shaderA = unitGradient()
        val shaderB = unitGradient()
        val paintA = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true; shader = shaderA }
        val paintB = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true; shader = shaderB }
        val matrixA = Matrix()
        val matrixB = Matrix()
        var x = 0f
        var y = 0f
        var startMs = 0L
        var size = 100f // width of the pressed key
        var spin = 0.0 // start angle, so neighbouring blooms don't look identical
        var active = false
    }

    private val blooms = Array(MAX_BLOOMS) { Bloom() }
    private var colorIndex = 0

    var animationEnabled = true
        set(v) {
            field = v
            if (!v) clear()
        }

    private var motionAllowed = true

    init {
        // Not interactive; touches go to the keys above.
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Called by the keys view with the centre and width of the pressed key. */
    fun onKeyPress(x: Float, y: Float, keyWidth: Float) {
        if (!animationEnabled || !motionAllowed || !isShown) return
        val now = SystemClock.uptimeMillis()
        // A free slot, or (only if 12 blooms are already running) the oldest one.
        val b = blooms.firstOrNull { !it.active } ?: blooms.minByOrNull { it.startMs }!!
        b.x = x
        b.y = y
        b.startMs = now
        b.spin = (now % 6283) / 1000.0
        b.active = true
        b.size = keyWidth

        // Next pair of complementary colours from the palette.
        val pal = Theme.bloomPalette
        val a = pal[colorIndex % pal.size]
        val c = pal[(colorIndex + pal.size / 2) % pal.size]
        colorIndex++
        b.paintA.colorFilter = PorterDuffColorFilter(a, PorterDuff.Mode.SRC_IN)
        b.paintB.colorFilter = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Theme.background)
        val now = SystemClock.uptimeMillis()
        var anyActive = false
        for (b in blooms) {
            if (!b.active) continue
            val f = (now - b.startMs) / BLOOM_MS.toFloat()
            if (f >= 1f) {
                b.active = false
                continue
            }
            anyActive = true
            drawBloom(canvas, b, f)
        }
        // Keep animating only while something is visible; idle = no frames.
        if (anyActive) postInvalidateOnAnimation()
    }

    private fun drawBloom(canvas: Canvas, b: Bloom, f: Float) {
        // Size: grows quickly then settles (ease-out).
        val grow = 1f - (1f - f) * (1f - f) * (1f - f)
        val r = b.size * (0.7f + 1.8f * grow)
        // Opacity: smooth fade-in over the first 12 %, long smooth fade-out.
        val fadeIn = smoothstep(min(f / 0.12f, 1f))
        val fadeOut = 1f - smoothstep(f)
        val alpha = (255 * Theme.bloomStrength * fadeIn * fadeOut).toInt()
        // The two colours orbit the key centre, so they swirl and blend.
        val angle = b.spin + f * PI * 1.2
        val orbit = r * 0.28f
        val ax = b.x + orbit * cos(angle).toFloat()
        val ay = b.y + orbit * 0.6f * sin(angle).toFloat()
        val bx = b.x - orbit * cos(angle).toFloat()
        val by = b.y - orbit * 0.6f * sin(angle).toFloat()

        place(b.shaderA, b.matrixA, ax, ay, r)
        b.paintA.shader = b.shaderA
        b.paintA.alpha = alpha
        canvas.drawCircle(ax, ay, r, b.paintA)

        place(b.shaderB, b.matrixB, bx, by, r * 0.9f)
        b.paintB.shader = b.shaderB
        b.paintB.alpha = alpha
        canvas.drawCircle(bx, by, r * 0.9f, b.paintB)
    }

    private fun place(s: Shader, m: Matrix, x: Float, y: Float, r: Float) {
        m.setScale(r, r)
        m.postTranslate(x, y)
        s.setLocalMatrix(m)
    }

    private fun smoothstep(t: Float) = t * t * (3f - 2f * t)

    private fun clear() {
        blooms.forEach { it.active = false }
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) clear()
    }

    /** Call when the keyboard opens, in case reduced motion / Battery Saver changed. */
    fun refreshSystemState() {
        motionAllowed = !reducedMotion() && !powerSaving()
        if (!motionAllowed) clear()
    }

    /** Android's "Remove animations" (accessibility) or animator scale set to off. */
    private fun reducedMotion(): Boolean {
        if (Build.VERSION.SDK_INT >= 26) return !ValueAnimator.areAnimatorsEnabled()
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return scale == 0f
    }

    private fun powerSaving(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    private companion object {
        const val MAX_BLOOMS = 12
        const val BLOOM_MS = 1300L

        /** Unit-radius soft spot: tinted by a colour filter, placed by a matrix. */
        fun unitGradient() = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.WHITE, Color.argb(170, 255, 255, 255), Color.argb(60, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
}
