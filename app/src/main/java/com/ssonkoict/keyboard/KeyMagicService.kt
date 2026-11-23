package com.ssonkoict.keyboard

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.io.File
import java.util.concurrent.Executors

/** The input method: connects the Rust core + UI to the app you're typing in. */
class KeyMagicService : InputMethodService(), KeyboardActions {

    private lateinit var core: KeyCore
    private lateinit var prefs: Prefs
    private lateinit var feedback: Feedback
    private lateinit var clip: ClipboardStore
    private var root: KeyboardRoot? = null

    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val userFile by lazy { File(filesDir, "user_words.txt") }

    @Volatile private var dictReady = false
    private var learnedSinceSave = 0
    private var allowPredictions = true
    private var allowLearning = true
    private var spaceFromSuggestion = false
    private var lastSpaceAt = 0L

    private val otpListener: () -> Unit = { refreshStrip() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.HEIGHT, Prefs.NUMBER_ROW -> applyLayoutSettings()
            Prefs.HD_EMOJI -> EmojiImages.enabled = prefs.hdEmoji
            Prefs.GRADIENT_BG -> root?.setGradientBackground(prefs.gradientBackground)
            Prefs.KEY_POPUP -> root?.popupEnabled = prefs.keyPopup
            Prefs.AUTO_NAV_SPACE -> root?.autoNavSpace = prefs.autoNavSpace
            Prefs.BOTTOM_SPACE -> root?.extraBottomPx = (prefs.bottomSpaceDp * resources.displayMetrics.density).toInt()
            // Re-run the inset calculation right away so the app re-lays out.
            Prefs.SYSTEM_INSETS -> root?.requestLayout()
            Prefs.REQ_CLEAR_CLIP -> clip.clear()
            Prefs.REQ_CLEAR_WORDS -> {
                core.clearUser()
                saveUserData()
            }
        }
        refreshStrip()
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        core = KeyCore()
        feedback = Feedback(this, prefs)
        EmojiImages.init(this, prefs.hdEmoji)
        clip = ClipboardStore(this, core) {
            root?.let { if (it.panel == KeyboardRoot.PANEL_CLIPBOARD) it.refreshClipboard() }
        }
        clip.start()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        OtpStore.addListener(otpListener)

        // Word list (~1.3 MB) and emoji data load in the background.
        bg.execute {
            try {
                EmojiData.load(this)
                core.loadEmoji(EmojiData.raw)
                core.loadDictionary(assets.open("words.txt").bufferedReader().use { it.readText() })
                if (userFile.exists()) core.importUser(userFile.readText())
                dictReady = true
                main.post { refreshStrip() }
            } catch (e: Exception) {
                // Predictions just stay off if assets can't be read.
            }
        }
    }

    override fun onDestroy() {
        saveUserData(sync = true)
        OtpStore.removeListener(otpListener)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        clip.stop()
        bg.shutdown()
        core.close()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // Android re-creates the input view when dark/light mode changes.
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        Theme.apply(dark = night != Configuration.UI_MODE_NIGHT_NO)
        val r = KeyboardRoot(this, core, this)
        r.onHeightChangedWhileShown = { scheduleInsetsRefresh(0) }
        root = r
        applyLayoutSettings()
        return r
    }

    // --- WhatsApp fix: never use the full-screen "extract" mode, and report the
    // exact top of the keyboard so the app resizes its chat above it.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        // Backup mode (Settings → Chat apps): keep Android's own calculation.
        if (prefs.systemInsets) return
        val r = root ?: return
        if (isFullscreenMode || !r.isShown || r.height == 0) return
        val loc = IntArray(2)
        r.getLocationInWindow(loc)
        outInsets.contentTopInsets = loc[1]
        outInsets.visibleTopInsets = loc[1]
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT
    }

    // ---- WhatsApp: make sure the app gets the final keyboard size -----------

    private val refreshInsets = Runnable {
        val w = window?.window ?: return@Runnable
        // Re-applying the window's layout makes Android re-run the inset
        // calculation and send the result to the app again – the same nudge a
        // notification-shade pull gives WhatsApp.
        w.attributes = w.attributes
        root?.requestLayout()
    }

    private fun scheduleInsetsRefresh(delayMs: Long) {
        main.removeCallbacks(refreshInsets)
        main.postDelayed(refreshInsets, delayMs)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Once more after the slide-in animation, in case the app laid out mid-animation.
        if (!prefs.systemInsets) scheduleInsetsRefresh(INSETS_REFRESH_AFTER_SHOW_MS)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        core.setNumberRow(prefs.numberRow)
        core.reset()
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
        val emailOrUrl = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI
            )
        when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME ->
                core.setPage(KeyCore.PAGE_SYMBOLS)
        }
        allowPredictions = cls == InputType.TYPE_CLASS_TEXT && !password && !emailOrUrl
        allowLearning = allowPredictions && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        spaceFromSuggestion = false
        root?.keys?.enterLabel = enterLabelFor(info)
        applyLayoutSettings()
        root?.showKeys()
        updateAutoShift()
        clip.scanScreenshots()
        refreshStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (learnedSinceSave > 0) saveUserData()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (updateAutoShift()) root?.keys?.refresh()
        refreshStrip()
    }

    private fun applyLayoutSettings() {
        core.setNumberRow(prefs.numberRow)
        root?.applySize(prefs.heightPercent, core.maxRowCount(), prefs.numberRow)
        root?.setGradientBackground(prefs.gradientBackground)
        root?.popupEnabled = prefs.keyPopup
        root?.extraBottomPx = (prefs.bottomSpaceDp * resources.displayMetrics.density).toInt()
        root?.autoNavSpace = prefs.autoNavSpace
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyDown(kind: Int) = feedback.onKeyDown(kind)

    override fun onKey(row: Int, col: Int) {
        val ic = currentInputConnection
        val result = core.press(row, col, SystemClock.uptimeMillis())
        if (ic != null) {
            when (result) {
                KeyCore.ACT_NONE -> Unit
                KeyCore.ACT_DELETE -> {
                    spaceFromSuggestion = false
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
                KeyCore.ACT_ENTER -> {
                    spaceFromSuggestion = false
                    handleEnter(ic)
                }
                KeyCore.ACT_EMOJI -> root?.showEmoji()
                else -> typeChar(ic, result)
            }
            if (result != KeyCore.ACT_NONE) updateAutoShift()
        }
        root?.keys?.refresh()
        refreshStrip()
    }

    private fun typeChar(ic: InputConnection, codePoint: Int) {
        val ch = String(Character.toChars(codePoint))
        val now = SystemClock.uptimeMillis()
        val isPunct = ch in ".,!?;:"
        ic.beginBatchEdit()
        if (isPunct && spaceFromSuggestion) {
            // "hello |" + "," → "hello, "
            ic.deleteSurroundingText(1, 0)
            ic.commitText("$ch ", 1)
        } else if (ch == " " && now - lastSpaceAt < 500 && endsWithWordThenSpace(textBefore(3))) {
            // double space → ". "
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
        } else {
            ic.commitText(ch, 1)
        }
        ic.endBatchEdit()
        lastSpaceAt = if (ch == " ") now else 0L
        spaceFromSuggestion = false
        if (!Character.isLetterOrDigit(codePoint) && ch != "'") learn()
    }

    private fun endsWithWordThenSpace(s: String) =
        s.length >= 2 && s.last() == ' ' && Character.isLetterOrDigit(s[s.length - 2])

    /** "Done", "Send", "Go"… like Samsung; null = return arrow (new line). */
    private fun enterLabelFor(info: EditorInfo): String? {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null
        info.actionLabel?.toString()?.takeIf { it.isNotBlank() && it.length <= 8 }?.let { return it }
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> null
        }
    }

    private fun handleEnter(ic: InputConnection) {
        val info = currentInputEditorInfo
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noEnterAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) // WhatsApp: new line or send, per its own setting
        } else {
            ic.performEditorAction(action)
        }
    }

    /** Shift on at the start of a sentence, if the field asks for it. Returns true if changed. */
    private fun updateAutoShift(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val ic = currentInputConnection ?: return false
        if (core.page() != KeyCore.PAGE_LETTERS) return false
        val isText = (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
        val caps = isText && ic.getCursorCapsMode(info.inputType) != 0
        val before = core.shiftState()
        core.setAutoShift(caps)
        return before != core.shiftState()
    }

    // --------------------------------------------------------- predictions

    private fun textBefore(n: Int = 64): String =
        currentInputConnection?.getTextBeforeCursor(n, 0)?.toString() ?: ""

    private fun refreshStrip() {
        val r = root ?: return
        val otp = if (prefs.otp) OtpStore.current() else null
        val hidden = prefs.otp && OtpStore.recentlyHidden()
        var words = emptyList<String>()
        var emojis = emptyList<String>()
        if (dictReady && allowPredictions && currentInputConnection != null) {
            val before = textBefore()
            if (prefs.predictions) words = core.suggest(before, 3)
            if (prefs.emojiPredictions) emojis = core.suggestEmoji(before, 2)
        }
        r.strip.show(otp, hidden, words, emojis)
    }

    private fun learn() {
        if (!dictReady || !allowLearning || !prefs.predictions) return
        core.learn(textBefore())
        if (++learnedSinceSave >= 25) saveUserData()
    }

    /** UTF-16 length of the word right before the cursor. */
    private fun partialWordLength(before: String): Int {
        var i = before.length
        while (i > 0) {
            val cp = Character.codePointBefore(before, i)
            if (!Character.isLetterOrDigit(cp) && cp != '\''.code) break
            i -= Character.charCount(cp)
        }
        return before.length - i
    }

    override fun onSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val len = partialWordLength(textBefore())
        ic.beginBatchEdit()
        if (len > 0) ic.deleteSurroundingText(len, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        spaceFromSuggestion = true
        learn()
        updateAutoShift()
        root?.keys?.refresh()
        refreshStrip()
    }

    override fun onForgetSuggestion(word: String) {
        core.forget(word)
        saveUserData()
        refreshStrip()
    }

    override fun onEmoji(emoji: String, fromSuggestion: Boolean) {
        val ic = currentInputConnection ?: return
        val before = textBefore(1)
        val text = if (fromSuggestion && before.isNotEmpty() && !before.last().isWhitespace()) " $emoji" else emoji
        ic.commitText(text, 1)
        core.recordEmoji(emoji)
        spaceFromSuggestion = false
        learnedSinceSave++
        root?.refreshRecentEmojis()
        refreshStrip()
    }

    override fun onEmojiBackspace() {
        feedback.onKeyDown(KeyCore.KIND_BACKSPACE)
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onEmojiSpace() {
        feedback.onKeyDown(KeyCore.KIND_SPACE)
        currentInputConnection?.commitText(" ", 1)
    }

    override fun onOtp(code: String) {
        currentInputConnection?.commitText(code, 1)
        OtpStore.consume()
        refreshStrip()
    }

    override fun recentEmojis(): List<String> = core.frequentEmojis(48)

    private fun saveUserData(sync: Boolean = false) {
        learnedSinceSave = 0
        val data = core.exportUser()
        if (sync || bg.isShutdown) {
            userFile.writeText(data)
        } else {
            bg.execute { userFile.writeText(data) }
        }
    }

    // ----------------------------------------------------------- clipboard

    override fun clipItems(): List<ClipItem> = clip.items()
    override fun onPasteClip(index: Int) = clip.paste(index, this)
    override fun onDeleteClip(index: Int) = clip.remove(index)
    override fun onClearClips() = clip.clear()

    // -------------------------------------------------------------- toolbar

    override fun onToggleNumberRow() {
        prefs.numberRow = !prefs.numberRow // listener re-applies the layout
    }

    override fun onHeightChanged(percent: Int) {
        prefs.heightPercent = percent
    }

    override fun onMinimize() {
        root?.showKeys()
        requestHideSelf(0)
    }

    override fun bottomSpaceDp(): Int = prefs.bottomSpaceDp

    override fun onBottomSpaceChanged(dp: Int) {
        prefs.bottomSpaceDp = dp
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        requestHideSelf(0)
    }

    private companion object {
        /** Slightly longer than the IME slide-in animation. */
        const val INSETS_REFRESH_AFTER_SHOW_MS = 350L
    }
}
