package com.ssonkoict.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Draws whatever layout the Rust core reports and forwards taps as (row, col).
 * Holds no keyboard logic of its own. Its height is set from outside
 * ([keysHeight]) so it stays the same on every page – apps like WhatsApp
 * don't have to re-layout when you switch to symbols.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val core: KeyCore,
    private val onKey: (row: Int, col: Int) -> Unit,
    private val onKeyDown: (kind: Int) -> Unit,
) : View(context) {

    private class KeyBox(val row: Int, val col: Int, val kind: Int, val rect: RectF) {
        /** Faint light sheen across the top of the key (its own shader, made at layout time). */
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        val shadowRect = RectF()
    }

    private val dp = resources.displayMetrics.density
    private val hGap = 6 * dp      // space between keys in a row
    private val vGap = 11 * dp     // space between rows
    private val sidePad = 4 * dp
    private val topPad = 6 * dp
    private val bottomPad = 6 * dp
    private val corner = 9 * dp

    /** Total height in pixels, set by [KeyboardRoot]. */
    var keysHeight: Int = (4 * 54 * dp).toInt()
        set(v) {
            if (field != v) {
                field = v
                requestLayout()
            }
        }

    /** Number row is drawn a little shorter than letter rows. */
    var numberRowShown = false

    /** Text for the Enter key ("Done", "Send"…), or null for a return-arrow icon. */
    var enterLabel: String? = null
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    /**
     * True when the animated gradient is showing behind this view: skip the solid
     * background and let the gradient show softly through the keys.
     */
    var overGradient = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    /** Key popup: called with the pressed key's rect and label, then with null on release. */
    var onPreview: ((rect: RectF?, label: String) -> Unit)? = null

    /** Centre and width of each pressed key, for the background bloom. */
    var onPressAt: ((x: Float, y: Float, keyWidth: Float) -> Unit)? = null

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density.coerceAtMost(1.5f)
    }
    private val shadowDy = 1.4f * resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.text
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    private var boxes: List<KeyBox> = emptyList()

    /** Label size on letter keys (the popup draws its letter larger than this). */
    var charTextSize = 22 * dp
        private set
    private var letterRowHeight = 54 * dp

    private var pressed: KeyBox? = null
    private var pressedPointer = -1
    private var firedOnDown = false

    private val repeatDelete = object : Runnable {
        override fun run() {
            val box = pressed ?: return
            if (box.kind == KeyCore.KIND_BACKSPACE) {
                onKey(box.row, box.col)
                postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
    }

    /** Call after any state change (shift, page) so labels and layout update. */
    fun refresh() {
        layoutKeys()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), keysHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun layoutKeys() {
        if (width == 0 || height == 0) return
        val rows = core.rowCount().coerceAtLeast(1)
        val shortFirst = numberRowShown && core.page() == KeyCore.PAGE_LETTERS
        val weights = FloatArray(rows) { if (it == 0 && shortFirst) NUMBER_ROW_WEIGHT else 1f }
        // Pages with fewer rows get taller keys, so the overall height never changes.
        val unitH = (height - topPad - bottomPad) / weights.sum()
        letterRowHeight = unitH

        val rowUnits = IntArray(rows) { r -> (0 until core.keyCount(r)).sumOf { core.keyWidth(r, it) } }
        val maxUnits = (rowUnits.maxOrNull() ?: 0).coerceAtLeast(1)
        val unit = (width - 2 * sidePad) / maxUnits

        val list = ArrayList<KeyBox>()
        var y = topPad
        for (r in 0 until rows) {
            val rh = unitH * weights[r]
            var x = sidePad + (maxUnits - rowUnits[r]) * unit / 2f // centre short rows
            for (k in 0 until core.keyCount(r)) {
                val w = core.keyWidth(r, k) * unit
                val rect = RectF(x + hGap / 2, y + vGap / 2, x + w - hGap / 2, y + rh - vGap / 2)
                val box = KeyBox(r, k, core.keyKind(r, k), rect)
                box.shadowRect.set(rect.left, rect.top + shadowDy, rect.right, rect.bottom + shadowDy)
                if (Theme.sheenAlpha > 0) {
                    box.sheen.shader = LinearGradient(
                        0f, rect.top, 0f, rect.top + rect.height() * 0.6f,
                        Color.argb(Theme.sheenAlpha, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP,
                    )
                }
                list += box
                x += w
            }
            y += rh
        }
        boxes = list
    }

    override fun onDraw(canvas: Canvas) {
        if (!overGradient) canvas.drawColor(Theme.background)
        val shift = core.shiftState()
        val keyH = letterRowHeight - vGap
        charTextSize = (keyH * 0.56f).coerceIn(15 * dp, 27 * dp)
        val charSize = (keyH * 0.56f).coerceIn(15 * dp, 27 * dp)
        val smallSize = (keyH * 0.38f).coerceIn(11 * dp, 17 * dp)
        for (box in boxes) {
            keyPaint.color = when {
                box === pressed -> Theme.pressed
                box.kind == KeyCore.KIND_CHAR || box.kind == KeyCore.KIND_SPACE -> Theme.key
                else -> Theme.special
            }
            if (overGradient) keyPaint.alpha = Theme.keyAlphaOverGradient
            val isPressed = box === pressed
            // Depth: soft shadow below, key face, top sheen, hairline edge.
            if (!isPressed) {
                shadowPaint.color = Theme.keyShadow
                canvas.drawRoundRect(box.shadowRect, corner, corner, shadowPaint)
            }
            canvas.drawRoundRect(box.rect, corner, corner, keyPaint)
            if (!isPressed && Theme.sheenAlpha > 0) canvas.drawRoundRect(box.rect, corner, corner, box.sheen)
            borderPaint.color = Theme.keyBorder
            canvas.drawRoundRect(box.rect, corner, corner, borderPaint)

            when (box.kind) {
                KeyCore.KIND_SHIFT -> drawShift(canvas, box.rect, shift)
                KeyCore.KIND_BACKSPACE -> drawBackspace(canvas, box.rect)
                KeyCore.KIND_ENTER -> {
                    val label = enterLabel
                    when (label) {
                        null -> drawReturn(canvas, box.rect)
                        "Search" -> drawSearch(canvas, box.rect)
                        else -> drawLabel(canvas, box.rect, label, smallSize * 1.05f, Theme.accentLight)
                    }
                }
                KeyCore.KIND_CHAR -> drawLabel(canvas, box.rect, String(Character.toChars(core.keyLabel(box.row, box.col))), charSize, Theme.text)
                KeyCore.KIND_SPACE -> drawLabel(canvas, box.rect, "English (US)", smallSize * 0.95f, Theme.text)
                KeyCore.KIND_TO_SYMBOLS -> drawLabel(canvas, box.rect, if (core.page() == KeyCore.PAGE_LETTERS) "!#1" else "1/2", smallSize * 1.15f, Theme.text)
                KeyCore.KIND_TO_MORE -> drawLabel(canvas, box.rect, "2/2", smallSize * 1.15f, Theme.text)
                KeyCore.KIND_TO_LETTERS -> drawLabel(canvas, box.rect, "ABC", smallSize * 1.15f, Theme.text)
                KeyCore.KIND_EMOJI -> drawLabel(canvas, box.rect, "☺", charSize, Theme.text)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, r: RectF, text: String, size: Float, color: Int) {
        textPaint.textSize = size
        textPaint.color = color
        val baseline = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, r.centerX(), baseline, textPaint)
    }

    /** Outlined up-arrow; blue when on, filled blue with a bar when caps-locked. */
    private fun drawShift(canvas: Canvas, r: RectF, shift: Int) {
        val s = minOf(r.width(), r.height()) * 0.36f
        val cx = r.centerX()
        val cy = r.centerY() + s * 0.05f
        path.reset()
        path.moveTo(cx, cy - s * 0.62f)                  // tip
        path.lineTo(cx + s * 0.58f, cy + s * 0.02f)
        path.lineTo(cx + s * 0.26f, cy + s * 0.02f)
        path.lineTo(cx + s * 0.26f, cy + s * 0.48f)
        path.lineTo(cx - s * 0.26f, cy + s * 0.48f)
        path.lineTo(cx - s * 0.26f, cy + s * 0.02f)
        path.lineTo(cx - s * 0.58f, cy + s * 0.02f)
        path.close()
        val on = shift != KeyCore.SHIFT_OFF
        iconPaint.color = if (on) Theme.accentLight else Theme.text
        iconPaint.style = if (shift == KeyCore.SHIFT_LOCKED) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        canvas.drawPath(path, iconPaint)
        if (shift == KeyCore.SHIFT_LOCKED) {
            iconPaint.style = Paint.Style.STROKE
            canvas.drawLine(cx - s * 0.32f, cy + s * 0.75f, cx + s * 0.32f, cy + s * 0.75f, iconPaint)
        }
        iconPaint.style = Paint.Style.STROKE
    }

    /** Outlined delete key shape with an ×. */
    private fun drawBackspace(canvas: Canvas, r: RectF) {
        val s = minOf(r.width(), r.height()) * 0.34f
        val cx = r.centerX() + s * 0.1f
        val cy = r.centerY()
        val left = cx - s * 0.95f
        val right = cx + s * 0.75f
        val top = cy - s * 0.52f
        val bottom = cy + s * 0.52f
        path.reset()
        path.moveTo(left, cy)
        path.lineTo(left + s * 0.5f, top)
        path.lineTo(right, top)
        path.lineTo(right, bottom)
        path.lineTo(left + s * 0.5f, bottom)
        path.close()
        iconPaint.color = Theme.text
        iconPaint.style = Paint.Style.STROKE
        canvas.drawPath(path, iconPaint)
        val xc = (left + s * 0.5f + right) / 2 + s * 0.02f
        val d = s * 0.22f
        canvas.drawLine(xc - d, cy - d, xc + d, cy + d, iconPaint)
        canvas.drawLine(xc - d, cy + d, xc + d, cy - d, iconPaint)
    }

    /** Magnifier for search boxes. */
    private fun drawSearch(canvas: Canvas, r: RectF) {
        val s = minOf(r.width(), r.height()) * 0.3f
        val cx = r.centerX() - s * 0.15f
        val cy = r.centerY() - s * 0.15f
        iconPaint.color = Theme.accentLight
        iconPaint.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, s * 0.55f, iconPaint)
        canvas.drawLine(cx + s * 0.4f, cy + s * 0.4f, cx + s * 0.85f, cy + s * 0.85f, iconPaint)
    }

    /** ↵ for fields without an action (e.g. new line). */
    private fun drawReturn(canvas: Canvas, r: RectF) {
        val s = minOf(r.width(), r.height()) * 0.32f
        val cx = r.centerX()
        val cy = r.centerY()
        iconPaint.color = Theme.accentLight
        iconPaint.style = Paint.Style.STROKE
        path.reset()
        path.moveTo(cx + s * 0.7f, cy - s * 0.6f)
        path.lineTo(cx + s * 0.7f, cy + s * 0.15f)
        path.lineTo(cx - s * 0.7f, cy + s * 0.15f)
        canvas.drawPath(path, iconPaint)
        path.reset()
        path.moveTo(cx - s * 0.35f, cy - s * 0.22f)
        path.lineTo(cx - s * 0.72f, cy + s * 0.15f)
        path.lineTo(cx - s * 0.35f, cy + s * 0.52f)
        canvas.drawPath(path, iconPaint)
    }

    private fun hit(x: Float, y: Float): KeyBox? {
        // Taps in the gaps go to the nearest key in that row.
        val row = boxes.filter { y >= it.rect.top - vGap / 2 - 1 && y <= it.rect.bottom + vGap / 2 + 1 }
        return row.firstOrNull { x >= it.rect.left - hGap / 2 - 1 && x <= it.rect.right + hGap / 2 + 1 }
            ?: row.minByOrNull { minOf(kotlin.math.abs(x - it.rect.left), kotlin.math.abs(x - it.rect.right)) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Fast two-thumb typing: a new finger commits the previous key.
                releasePressed(fire = true)
                val i = e.actionIndex
                val box = hit(e.getX(i), e.getY(i)) ?: return true
                pressed = box
                pressedPointer = e.getPointerId(i)
                onKeyDown(box.kind)
                onPressAt?.invoke(box.rect.centerX(), box.rect.centerY(), box.rect.width())
                if (box.kind == KeyCore.KIND_CHAR) {
                    onPreview?.invoke(box.rect, String(Character.toChars(core.keyLabel(box.row, box.col))))
                }
                if (box.kind == KeyCore.KIND_BACKSPACE) {
                    onKey(box.row, box.col)
                    firedOnDown = true
                    postDelayed(repeatDelete, REPEAT_START_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == pressedPointer) releasePressed(fire = true)
            }
            MotionEvent.ACTION_CANCEL -> releasePressed(fire = false)
        }
        return true
    }

    private fun releasePressed(fire: Boolean) {
        val box = pressed ?: return
        removeCallbacks(repeatDelete)
        val shouldFire = fire && !firedOnDown
        if (box.kind == KeyCore.KIND_CHAR) onPreview?.invoke(null, "")
        pressed = null
        pressedPointer = -1
        firedOnDown = false
        if (shouldFire) onKey(box.row, box.col)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(repeatDelete)
        if (pressed != null) onPreview?.invoke(null, "")
        pressed = null
    }

    companion object {
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
        /** Number row height relative to a letter row (it's shorter, as on Samsung). */
        const val NUMBER_ROW_WEIGHT = 0.84f
        /** Letter row height in dp at 100 % size. */
        const val ROW_DP = 54f
        const val TOP_PAD_DP = 6f
        const val BOTTOM_PAD_DP = 6f
    }
}
