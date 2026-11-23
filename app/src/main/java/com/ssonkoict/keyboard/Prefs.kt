package com.ssonkoict.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** All keyboard settings, shared by the keyboard service and the settings screen. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun setInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()

    /** Keyboard height, percent of normal (70–140). */
    var heightPercent: Int
        get() = int(HEIGHT, 100).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        set(v) = setInt(HEIGHT, v.coerceIn(MIN_HEIGHT, MAX_HEIGHT))

    var numberRow: Boolean get() = bool(NUMBER_ROW, true); set(v) = setBool(NUMBER_ROW, v)
    var predictions: Boolean get() = bool(PREDICTIONS, true); set(v) = setBool(PREDICTIONS, v)
    var emojiPredictions: Boolean get() = bool(EMOJI_PREDICTIONS, true); set(v) = setBool(EMOJI_PREDICTIONS, v)
    var otp: Boolean get() = bool(OTP, true); set(v) = setBool(OTP, v)
    var clipboard: Boolean get() = bool(CLIPBOARD, true); set(v) = setBool(CLIPBOARD, v)
    /** [EMOJI_STYLE_FONT] = the emoji font you picked (default once one is chosen), [EMOJI_STYLE_SYSTEM] = the phone's own emojis. */
    var emojiStyle: Int get() = int(EMOJI_STYLE, EMOJI_STYLE_FONT); set(v) = setInt(EMOJI_STYLE, v)

    /** Bumped when a new emoji font is imported or removed, so the running keyboard reloads it. */
    fun notifyEmojiFontChanged() = sp.edit().putLong(EMOJI_FONT_CHANGED, System.currentTimeMillis()).apply()

    /** Slowly animated gradient behind the keys. Off = the original solid background. */
    var gradientBackground: Boolean get() = bool(GRADIENT_BG, true); set(v) = setBool(GRADIENT_BG, v)

    /** Extra space under the keys, in dp (backup if keys sit on the navigation bar). */
    var bottomSpaceDp: Int get() = int(BOTTOM_SPACE, 0).coerceIn(0, 48); set(v) = setInt(BOTTOM_SPACE, v.coerceIn(0, 48))

    /**
     * Chat-app fix mode. false = exact (recommended): report the keyboard's exact top.
     * true = backup: let Android compute it the default way.
     */
    var systemInsets: Boolean get() = bool(SYSTEM_INSETS, false); set(v) = setBool(SYSTEM_INSETS, v)

    /** Measure and add navigation-bar spacing automatically (off = only "Bottom space" is used). */
    var autoNavSpace: Boolean get() = bool(AUTO_NAV_SPACE, true); set(v) = setBool(AUTO_NAV_SPACE, v)

    /**
     * Last measured navigation-bar spacing in px (-1 = never measured). Re-used
     * before the keyboard is first drawn, so its height is final when it appears.
     */
    var navPadPx: Int get() = int(NAV_PAD_PX, -1); set(v) = setInt(NAV_PAD_PX, v)

    /** Bubble above the key while you press it. */
    var keyPopup: Boolean get() = bool(KEY_POPUP, true); set(v) = setBool(KEY_POPUP, v)

    var vibrate: Boolean get() = bool(VIBRATE, true); set(v) = setBool(VIBRATE, v)
    /** 1–100; default is a firm knock. */
    var vibrateStrength: Int get() = int(VIBRATE_STRENGTH, 90).coerceIn(1, 100); set(v) = setInt(VIBRATE_STRENGTH, v.coerceIn(1, 100))

    var sound: Boolean get() = bool(SOUND, false); set(v) = setBool(SOUND, v)
    /** 0–100. */
    var soundVolume: Int get() = int(SOUND_VOLUME, 40).coerceIn(0, 100); set(v) = setInt(SOUND_VOLUME, v.coerceIn(0, 100))

    /** Newest screenshot already added to the clipboard (MediaStore DATE_ADDED, seconds). */
    var lastScreenshotSec: Long
        get() = sp.getLong(LAST_SCREENSHOT, 0L)
        set(v) = sp.edit().putLong(LAST_SCREENSHOT, v).apply()

    /** The settings screen asks the running keyboard to clear data by bumping these. */
    fun requestClearClipboard() = sp.edit().putLong(REQ_CLEAR_CLIP, System.currentTimeMillis()).apply()
    fun requestClearWords() = sp.edit().putLong(REQ_CLEAR_WORDS, System.currentTimeMillis()).apply()

    companion object {
        const val HEIGHT = "height_percent"
        const val NUMBER_ROW = "number_row"
        const val PREDICTIONS = "predictions"
        const val EMOJI_PREDICTIONS = "emoji_predictions"
        const val OTP = "otp"
        const val CLIPBOARD = "clipboard"
        const val EMOJI_STYLE = "emoji_style"
        const val EMOJI_FONT_CHANGED = "emoji_font_changed"
        const val EMOJI_STYLE_FONT = 0
        const val EMOJI_STYLE_SYSTEM = 1
        const val GRADIENT_BG = "gradient_background"
        const val KEY_POPUP = "key_popup"
        const val BOTTOM_SPACE = "bottom_space_dp"
        const val SYSTEM_INSETS = "system_insets"
        const val AUTO_NAV_SPACE = "auto_nav_space"
        const val NAV_PAD_PX = "nav_pad_px"
        const val VIBRATE = "vibrate"
        const val VIBRATE_STRENGTH = "vibrate_strength_v2"
        const val SOUND = "sound"
        const val SOUND_VOLUME = "sound_volume"
        const val LAST_SCREENSHOT = "last_screenshot_sec"
        const val REQ_CLEAR_CLIP = "req_clear_clip"
        const val REQ_CLEAR_WORDS = "req_clear_words"

        const val MIN_HEIGHT = 70
        const val MAX_HEIGHT = 140
    }
}

/**
 * Colours used across the keyboard. Follows the phone's dark / light mode
 * (Samsung-style: black keyboard in dark mode, white in light mode).
 */
object Theme {
    var dark = true
        private set
    var background = 0
        private set
    var strip = 0
        private set
    var toolButton = 0
        private set
    var key = 0
        private set
    var special = 0
        private set
    var pressed = 0
        private set
    var accent = 0
        private set
    var accentLight = 0
        private set
    var text = 0
        private set
    var subText = 0
        private set
    var divider = 0
        private set
    /** Key-press bloom colours; opposite entries are complementary pairs. */
    var bloomPalette = IntArray(0)
        private set
    /** Key depth: soft shadow under each key, top sheen, hairline edge. */
    var keyShadow = 0
        private set
    /** Key-press popup bubble colour. */
    var popup = 0
        private set
    var keyBorder = 0
        private set
    var sheenAlpha = 0
        private set
    /** Key opacity over the blooms (keeps labels crisp). */
    var keyAlphaOverGradient = 225
        private set
    /** How strong blooms are (lighter on white so they stay soft). */
    var bloomStrength = 0.9f
        private set

    init {
        apply(dark = true)
    }

    fun apply(dark: Boolean) {
        this.dark = dark
        fun c(s: String) = Color.parseColor(s)
        if (dark) {
            background = c("#000000"); strip = c("#1C1C1E"); toolButton = c("#2C2C2E")
            key = c("#2C2C2E"); special = c("#1C1C1E"); pressed = c("#48484A")
            accent = c("#3D6FD9"); accentLight = c("#6E9BF5")
            text = c("#F2F2F2"); subText = c("#A1A1A6"); divider = c("#3A3A3C")
            bloomPalette = intArrayOf(c("#3D6FD9"), c("#1FA39A"), c("#7B5BE0"), c("#E0773D"), c("#2E9E5B"), c("#B0478F"))
            bloomStrength = 0.9f
            keyShadow = Color.argb(150, 0, 0, 0); keyBorder = Color.argb(18, 255, 255, 255); sheenAlpha = 14
            keyAlphaOverGradient = 225
            popup = c("#3A3A3C")
        } else {
            // After Samsung Keyboard (light): grey key area, white letter keys, grey function keys.
            background = c("#E4E4E9"); strip = c("#F6F6F8"); toolButton = c("#E6E6EA")
            key = c("#FDFDFF"); special = c("#D1D1D7"); pressed = c("#BFBFC6")
            accent = c("#2F5FD0"); accentLight = c("#3A74E6")
            text = c("#1C1C1E"); subText = c("#6B6B73"); divider = c("#D0D0D6")
            keyShadow = Color.argb(40, 0, 0, 0); keyBorder = Color.argb(14, 0, 0, 0); sheenAlpha = 0
            keyAlphaOverGradient = 238
            popup = c("#FFFFFF")
            bloomPalette = intArrayOf(c("#7FA6FF"), c("#5FD0C4"), c("#B39BFF"), c("#FFB07A"), c("#7EDB9F"), c("#F59AC8"))
            bloomStrength = 0.75f
        }
    }
}
