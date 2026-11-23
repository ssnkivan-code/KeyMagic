package com.ssonkoict.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** What the keyboard UI asks the service to do. */
interface KeyboardActions {
    fun onKey(row: Int, col: Int)
    fun onKeyDown(kind: Int)
    fun onSuggestion(word: String)
    fun onForgetSuggestion(word: String)
    fun onEmoji(emoji: String, fromSuggestion: Boolean)
    fun onOtp(code: String)
    fun onEmojiBackspace()
    fun onEmojiSpace()
    fun onPasteClip(index: Int)
    fun onDeleteClip(index: Int)
    fun onClearClips()
    fun onToggleNumberRow()
    fun onHeightChanged(percent: Int)
    fun onOpenSettings()
    fun onMinimize()
    fun bottomSpaceDp(): Int
    fun onBottomSpaceChanged(dp: Int)
    fun clipItems(): List<ClipItem>
    fun recentEmojis(): List<String>
}

/**
 * The whole keyboard window: suggestion strip on top, then keys / emoji /
 * clipboard / resize panel. Every panel has exactly the same height, and the
 * strip is always there, so the keyboard never changes size while you type.
 * (Keyboards that grow/shrink, float, or draw outside their window are what
 * make WhatsApp's chat hide behind the keyboard.)
 */
@SuppressLint("ViewConstructor")
class KeyboardRoot(context: Context, core: KeyCore, private val actions: KeyboardActions) : LinearLayout(context) {

    private val dp = resources.displayMetrics.density
    private val stripHeight = (48 * dp).toInt()

    val keys = KeyboardView(context, core, actions::onKey, actions::onKeyDown)
    private val gradient = GradientBackgroundView(context)
    private var gradientOn = false
    val strip = SuggestionStrip(context, this)
    private val content = FrameLayout(context)
    private val emojiPanel = EmojiPanel(
        context,
        recents = actions::recentEmojis,
        onEmoji = { actions.onEmoji(it, fromSuggestion = false) },
        onBackspace = actions::onEmojiBackspace,
        onSpace = actions::onEmojiSpace,
        onAbc = { showKeys() },
    )
    private val clipPanel = ClipboardPanel(
        context,
        onPaste = { actions.onPasteClip(it); showKeys() },
        onDelete = { actions.onDeleteClip(it); refreshClipboard() },
        onClearAll = { actions.onClearClips(); refreshClipboard() },
        onAbc = { showKeys() },
    )
    private var resizePanel: ResizePanel? = null
    private var heightPercent = 100
    private var maxRows = 4
    private var numberRow = true

    var panel = PANEL_KEYS
        private set

    // ---- key popup (drawn by this view on top of everything, so it can rise
    // above the top row into the toolbar area without a separate window)
    var popupEnabled = true
    private var previewShown = false
    private var previewLabel = ""
    private val previewBubble = RectF()
    private val previewShadow = RectF()
    private val previewFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * dp }
    private val previewText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hidePreview = Runnable {
        previewShown = false
        invalidate()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.background)
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, stripHeight))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(gradient) // behind the keys: solid background + key-press blooms
        keys.onPressAt = gradient::onKeyPress
        keys.onPreview = ::showPreview
        content.addView(keys)
        content.addView(emojiPanel)
        content.addView(clipPanel)
        emojiPanel.visibility = View.GONE
        clipPanel.visibility = View.GONE

        // Android 15+ draws the keyboard edge-to-edge: keep keys above the
        // gesture / navigation bar instead of underneath it.
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT < 30) {
                @Suppress("DEPRECATION")
                setNavPad(insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    // ---- navigation bar: keep keys above it ----------------------------------
    //
    // WhatsApp lays its chat out once, as the keyboard appears. If the keyboard
    // then changes height (even by a few dp), the chat ends up partly hidden
    // until something else (notification shade, volume slider) makes it
    // re-measure. So the height must be FINAL before the first frame:
    //  - the spacing measured last time is applied up front (saved in Prefs),
    //  - re-measuring only corrects it if the phone's layout really changed,
    //  - any change while showing triggers [onHeightChangedWhileShown].

    private val prefsStore = Prefs(context)
    private var navPad = if (prefsStore.autoNavSpace) prefsStore.navPadPx.coerceAtLeast(0) else 0
    var autoNavSpace = prefsStore.autoNavSpace
        set(v) {
            field = v
            setNavPad(if (v) prefsStore.navPadPx.coerceAtLeast(0) else 0)
            if (v) post { measureNavOverlap() }
        }

    /** Extra space under the keys from Settings → Bottom space (backup). */
    var extraBottomPx = 0
        set(v) {
            if (field != v) {
                field = v
                applyBottomPadding()
            }
        }

    /** Called when the keyboard's height changed while visible (service re-sends insets). */
    var onHeightChangedWhileShown: (() -> Unit)? = null

    init {
        applyBottomPadding() // before the first layout
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed && Build.VERSION.SDK_INT >= 30 && autoNavSpace) post { measureNavOverlap() }
    }

    /**
     * Measures how much of this window lies behind the navigation bar. The
     * result is saved so next time it's applied before the keyboard is drawn.
     */
    private fun measureNavOverlap() {
        if (Build.VERSION.SDK_INT < 30 || !autoNavSpace || !isShown) return
        val wi = rootWindowInsets ?: return
        val nav = wi.getInsets(WindowInsets.Type.navigationBars()).bottom
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val screenBottom = wm.currentWindowMetrics.bounds.bottom
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val windowBottom = loc[1] + height // fixed: padding grows the view upwards
        val needed = (windowBottom - (screenBottom - nav)).coerceIn(0, nav)
        if (needed != prefsStore.navPadPx) prefsStore.navPadPx = needed
        setNavPad(needed)
    }

    private fun setNavPad(px: Int) {
        if (px == navPad) return
        navPad = px
        applyBottomPadding()
    }

    private fun applyBottomPadding() {
        val bottom = navPad + extraBottomPx
        if (paddingBottom != bottom) {
            setPadding(0, 0, 0, bottom)
            if (isShown) onHeightChangedWhileShown?.invoke()
        }
    }

    /** Recompute sizes after height %, number row or page-count changes. */
    fun applySize(heightPercent: Int, maxRows: Int, numberRow: Boolean) {
        this.heightPercent = heightPercent
        this.maxRows = maxRows
        this.numberRow = numberRow
        val rowPx = KeyboardView.ROW_DP * dp * heightPercent / 100f
        val rows = maxRows - if (numberRow) 1f - KeyboardView.NUMBER_ROW_WEIGHT else 0f
        val h = (rows * rowPx + (KeyboardView.TOP_PAD_DP + KeyboardView.BOTTOM_PAD_DP) * dp).toInt()
        keys.numberRowShown = numberRow
        val changed = keys.keysHeight != h
        keys.keysHeight = h
        if (changed && isShown) onHeightChangedWhileShown?.invoke()
        for (p in listOf<View?>(gradient, emojiPanel, clipPanel, resizePanel)) {
            p?.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h)
        }
        keys.refresh()
        requestLayout()
    }

    private fun showPreview(key: RectF?, label: String) {
        if (key == null) {
            // Linger briefly so very quick taps still show it.
            removeCallbacks(hidePreview)
            postDelayed(hidePreview, PREVIEW_LINGER_MS)
            return
        }
        if (!popupEnabled || panel != PANEL_KEYS) return
        removeCallbacks(hidePreview)
        val dx = (content.left + keys.left).toFloat()
        val dy = (content.top + keys.top).toFloat()
        val w = key.width() * 1.12f
        val h = key.height() * 1.35f
        val bottom = key.top + dy + key.height() * 0.12f // slight overlap with the key
        var left = key.centerX() + dx - w / 2
        left = left.coerceIn(2 * dp, width - w - 2 * dp) // keep inside the screen at the edges
        previewBubble.set(left, (bottom - h).coerceAtLeast(0f), left + w, bottom)
        previewShadow.set(previewBubble.left, previewBubble.top + 2 * dp, previewBubble.right, previewBubble.bottom + 2 * dp)
        previewLabel = label
        previewShown = true
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!previewShown) return
        val r = 10 * dp
        previewShadowPaint.color = Theme.keyShadow
        canvas.drawRoundRect(previewShadow, r, r, previewShadowPaint)
        previewFill.color = Theme.popup
        canvas.drawRoundRect(previewBubble, r, r, previewFill)
        previewBorder.color = Theme.keyBorder
        canvas.drawRoundRect(previewBubble, r, r, previewBorder)
        previewText.color = Theme.text
        previewText.textSize = keys.charTextSize * 1.35f
        val baseline = previewBubble.centerY() - (previewText.descent() + previewText.ascent()) / 2
        canvas.drawText(previewLabel, previewBubble.centerX(), baseline, previewText)
    }

    /** Gradient blooms on key presses (Settings → Look). Off = plain solid background. */
    fun setGradientBackground(on: Boolean) {
        gradientOn = on
        keys.overGradient = on
        gradient.animationEnabled = on
        gradient.refreshSystemState()
    }

    private fun show(which: Int) {
        panel = which
        keys.visibility = if (which == PANEL_KEYS) View.VISIBLE else View.INVISIBLE // keeps its height
        // Only behind the keys; stops animating while another panel is open.
        gradient.visibility = if (which == PANEL_KEYS) View.VISIBLE else View.INVISIBLE
        emojiPanel.visibility = if (which == PANEL_EMOJI) View.VISIBLE else View.GONE
        clipPanel.visibility = if (which == PANEL_CLIPBOARD) View.VISIBLE else View.GONE
        resizePanel?.visibility = if (which == PANEL_RESIZE) View.VISIBLE else View.GONE
        strip.setToolbarHighlight(which)
    }

    fun showKeys() {
        show(PANEL_KEYS)
        keys.refresh()
    }

    fun showEmoji() {
        emojiPanel.open()
        show(PANEL_EMOJI)
    }

    fun showClipboard() {
        refreshClipboard()
        show(PANEL_CLIPBOARD)
    }

    fun refreshClipboard() {
        clipPanel.show(actions.clipItems())
    }

    fun refreshRecentEmojis() = emojiPanel.refreshRecents()

    /** Emoji style or font changed in settings. */
    fun refreshEmojiFont() {
        emojiPanel.refreshFont()
        strip.invalidate()
    }

    fun showResize() {
        val p = resizePanel ?: ResizePanel(
            context, heightPercent, actions::onHeightChanged,
            numberRow = { numberRow }, onToggleNumberRow = actions::onToggleNumberRow,
            bottomSpace = actions::bottomSpaceDp, onBottomSpace = actions::onBottomSpaceChanged,
        ) { showKeys() }.also {
            resizePanel = it
            content.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, keys.keysHeight))
        }
        p.setValue(heightPercent)
        show(PANEL_RESIZE)
    }

    internal fun onTool(tool: Int) {
        when (tool) {
            TOOL_CLIPBOARD -> if (panel == PANEL_CLIPBOARD) showKeys() else showClipboard()
            TOOL_EMOJI -> if (panel == PANEL_EMOJI) showKeys() else showEmoji()
            TOOL_NUMBERS -> actions.onToggleNumberRow()
            TOOL_RESIZE -> if (panel == PANEL_RESIZE) showKeys() else showResize()
            TOOL_SETTINGS -> actions.onOpenSettings()
        }
    }

    internal fun act() = actions

    companion object {
        private const val PREVIEW_LINGER_MS = 70L
        const val PANEL_KEYS = 0
        const val PANEL_EMOJI = 1
        const val PANEL_CLIPBOARD = 2
        const val PANEL_RESIZE = 3

        const val TOOL_CLIPBOARD = 0
        const val TOOL_EMOJI = 1
        const val TOOL_NUMBERS = 2
        const val TOOL_RESIZE = 3
        const val TOOL_SETTINGS = 4
    }
}

/**
 * Top bar. Shows, in order of priority: a one-time code (OTP) chip, then word
 * and emoji predictions; when there's nothing to suggest it shows the toolbar
 * (emoji, clipboard, number row, settings, ⋯ = size & layout), Samsung-style.
 * While suggestions show, the round button on the left brings the toolbar back.
 */
@SuppressLint("ViewConstructor")
class SuggestionStrip(context: Context, private val root: KeyboardRoot) : LinearLayout(context) {

    private val dp = resources.displayMetrics.density
    private val content = LinearLayout(context)

    private var otp: OtpStore.Otp? = null
    private var otpHidden = false
    private var words: List<String> = emptyList()
    private var emojis: List<String> = emptyList()
    private var forceToolbar = false
    private var highlight = KeyboardRoot.PANEL_KEYS

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Theme.strip)
        val p = (6 * dp).toInt()
        setPadding(p, 0, p, 0)
        content.orientation = HORIZONTAL
        content.gravity = Gravity.CENTER_VERTICAL
        addView(content, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        // Minimize (hide keyboard) – always available at the right end.
        addView(IconButton(context, IconButton.HIDE).apply {
            contentDescription = "Hide keyboard"
            setOnClickListener { root.act().onMinimize() }
        }, LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { setMargins((4 * dp).toInt(), 0, 0, 0) })
        rebuild()
    }

    fun show(otp: OtpStore.Otp?, otpHidden: Boolean, words: List<String>, emojis: List<String>) {
        val changedSuggestions = words != this.words || emojis != this.emojis
        if (otp == this.otp && otpHidden == this.otpHidden && !changedSuggestions) return
        this.otp = otp
        this.otpHidden = otpHidden
        this.words = words
        this.emojis = emojis
        if (changedSuggestions && (words.isNotEmpty() || emojis.isNotEmpty())) forceToolbar = false
        rebuild()
    }

    fun setToolbarHighlight(panel: Int) {
        highlight = panel
        // In panels, show the toolbar so you can jump between them.
        forceToolbar = panel != KeyboardRoot.PANEL_KEYS
        rebuild()
    }

    private fun rebuild() {
        content.removeAllViews()
        val actions = root.act()
        val code = otp
        val showTools = forceToolbar || (words.isEmpty() && emojis.isEmpty())

        if (code != null && highlight == KeyboardRoot.PANEL_KEYS) {
            content.addView(chip("🔑 ${code.code}", accent = true, sub = code.source) { actions.onOtp(code.code) })
        } else if (otpHidden && highlight == KeyboardRoot.PANEL_KEYS && words.isEmpty()) {
            content.addView(chip("🔒 Code hidden by Android – tap for help", accent = false) { actions.onOpenSettings() })
            return
        }

        if (showTools) {
            addTool(IconButton.EMOJI, KeyboardRoot.TOOL_EMOJI, highlight == KeyboardRoot.PANEL_EMOJI)
            addTool(IconButton.CLIPBOARD, KeyboardRoot.TOOL_CLIPBOARD, highlight == KeyboardRoot.PANEL_CLIPBOARD)
            addTool(IconButton.NUMBERS, KeyboardRoot.TOOL_NUMBERS, false)
            addTool(IconButton.SETTINGS, KeyboardRoot.TOOL_SETTINGS, false)
            addTool(IconButton.MORE, KeyboardRoot.TOOL_RESIZE, highlight == KeyboardRoot.PANEL_RESIZE, circle = false)
            return
        }

        // Suggestions: a round button on the left brings the toolbar back.
        content.addView(IconButton(context, IconButton.GRID).apply {
            setOnClickListener {
                forceToolbar = true
                rebuild()
            }
        }, LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
        words.forEachIndexed { i, w ->
            if (i > 0) content.addView(divider())
            content.addView(TextView(context).apply {
                text = w
                setTextColor(Theme.text)
                textSize = 16f
                if (i == 0) typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { actions.onSuggestion(w) }
                setOnLongClickListener { actions.onForgetSuggestion(w); true }
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        for (e in emojis) {
            content.addView(EmojiView(context).apply {
                emoji = e
                setOnClickListener { actions.onEmoji(e, fromSuggestion = true) }
            }, LayoutParams((44 * dp).toInt(), LayoutParams.MATCH_PARENT))
        }
    }

    private fun addTool(icon: Int, tool: Int, active: Boolean, circle: Boolean = true) {
        val holder = FrameLayout(context)
        holder.addView(IconButton(context, icon, circle).apply {
            this.active = active
            setOnClickListener { root.onTool(tool) }
        }, FrameLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt(), Gravity.CENTER))
        content.addView(holder, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun chip(text: String, accent: Boolean, sub: String? = null, onClick: () -> Unit): View {
        val m = (5 * dp).toInt()
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(m * 2, 0, m * 2, 0)
            background = roundBg(if (accent) Theme.accent else Theme.toolButton, 16 * dp)
            addView(TextView(context).apply {
                this.text = text
                setTextColor(Theme.text)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            if (sub != null) {
                addView(TextView(context).apply {
                    this.text = "  $sub"
                    setTextColor(Theme.text)
                    alpha = 0.75f
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (32 * dp).toInt()).apply { setMargins(m, 0, m, 0) }
        }
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(Theme.divider)
        layoutParams = LayoutParams((1 * dp).toInt().coerceAtLeast(1), (20 * dp).toInt())
    }
}
