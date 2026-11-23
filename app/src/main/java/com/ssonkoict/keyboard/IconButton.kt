package com.ssonkoict.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Round toolbar button with a simple line icon, like Samsung Keyboard's toolbar. */
@SuppressLint("ViewConstructor")
class IconButton(context: Context, private val icon: Int, private val circle: Boolean = true) : View(context) {

    var active = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val dp = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 2 * dp
        if (circle) {
            bg.color = Theme.toolButton
            canvas.drawCircle(cx, cy, r, bg)
        }
        val c = if (active) Theme.accentLight else Theme.text
        line.color = c
        fill.color = c
        text.color = c
        val s = r * 0.52f // icon half-size
        when (icon) {
            EMOJI -> {
                canvas.drawCircle(cx, cy, s, line)
                canvas.drawCircle(cx - s * 0.36f, cy - s * 0.22f, 1.6f * dp, fill)
                canvas.drawCircle(cx + s * 0.36f, cy - s * 0.22f, 1.6f * dp, fill)
                rect.set(cx - s * 0.5f, cy - s * 0.3f, cx + s * 0.5f, cy + s * 0.55f)
                canvas.drawArc(rect, 25f, 130f, false, line)
            }
            CLIPBOARD -> {
                rect.set(cx - s * 0.72f, cy - s * 0.82f, cx + s * 0.72f, cy + s * 0.92f)
                canvas.drawRoundRect(rect, 3 * dp, 3 * dp, line)
                rect.set(cx - s * 0.34f, cy - s * 0.95f, cx + s * 0.34f, cy - s * 0.55f)
                canvas.drawRoundRect(rect, 2 * dp, 2 * dp, line)
            }
            SETTINGS -> {
                val teeth = 8
                for (i in 0 until teeth) {
                    val a = Math.toRadians(i * 360.0 / teeth)
                    val x1 = cx + (s * 0.62f) * Math.cos(a).toFloat()
                    val y1 = cy + (s * 0.62f) * Math.sin(a).toFloat()
                    val x2 = cx + (s * 0.98f) * Math.cos(a).toFloat()
                    val y2 = cy + (s * 0.98f) * Math.sin(a).toFloat()
                    line.strokeWidth = 3.4f * dp
                    canvas.drawLine(x1, y1, x2, y2, line)
                }
                line.strokeWidth = 2f * dp
                canvas.drawCircle(cx, cy, s * 0.66f, line)
                canvas.drawCircle(cx, cy, s * 0.26f, line)
            }
            NUMBERS -> {
                text.textSize = r * 0.62f
                canvas.drawText("123", cx, cy - (text.descent() + text.ascent()) / 2, text)
            }
            MORE -> for (i in -1..1) canvas.drawCircle(cx + i * 7 * dp, cy, 2.2f * dp, fill)
            HIDE -> {
                // chevron down
                canvas.drawLine(cx - s * 0.6f, cy - s * 0.25f, cx, cy + s * 0.35f, line)
                canvas.drawLine(cx, cy + s * 0.35f, cx + s * 0.6f, cy - s * 0.25f, line)
            }
            GRID -> {
                val q = s * 0.42f
                for (dx in listOf(-1, 1)) for (dy in listOf(-1, 1)) {
                    rect.set(cx + dx * s * 0.5f - q, cy + dy * s * 0.5f - q, cx + dx * s * 0.5f + q, cy + dy * s * 0.5f + q)
                    canvas.drawRoundRect(rect, 2 * dp, 2 * dp, line)
                }
            }
        }
    }

    companion object {
        const val EMOJI = 0
        const val CLIPBOARD = 1
        const val SETTINGS = 2
        const val NUMBERS = 3
        const val MORE = 4
        const val GRID = 5
        const val HIDE = 6
    }
}
