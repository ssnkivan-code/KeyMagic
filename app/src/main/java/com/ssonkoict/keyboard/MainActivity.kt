package com.ssonkoict.keyboard

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/**
 * Setup + settings, One UI style: a header, a "Get started" card with
 * progress, and rounded grouped setting cards. Follows dark / light mode and
 * draws edge-to-edge (Android 15) with proper insets.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var feedback: Feedback
    private lateinit var col: LinearLayout
    private lateinit var setupHolder: LinearLayout
    private lateinit var emojiHolder: LinearLayout
    private var importing = false
    private var importStatus: String? = null

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    // Page colours (on top of the keyboard Theme).
    private var pageBg = 0
    private var cardBg = 0
    private var divider = 0

    private val adbCommand get() =
        "adb shell cmd appops set --user 0 $packageName RECEIVE_SENSITIVE_NOTIFICATIONS allow"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        Theme.apply(dark)
        pageBg = if (dark) Color.parseColor("#000000") else Color.parseColor("#F2F2F6")
        cardBg = if (dark) Color.parseColor("#1C1C1E") else Color.WHITE
        divider = if (dark) Color.parseColor("#2C2C2E") else Color.parseColor("#ECECF0")

        prefs = Prefs(this)
        feedback = Feedback(this, prefs)
        EmojiFont.reload(this, prefs)

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageBg)
            isVerticalScrollBarEnabled = false
            addView(col)
        }
        // Android 15 draws apps edge-to-edge: keep content clear of the status & navigation bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                i.top to i.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        header()
        setupHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(setupHolder)
        tryItCard()
        settings()
        footer()

        setContentView(scroll)
        // Must run after setContentView: window.insetsController is null until the decor view exists.
        styleSystemBars(dark)
    }

    override fun onResume() {
        super.onResume()
        buildSetup() // re-check each step when coming back from Android settings
    }

    @Deprecated("Activity result API needs androidx.activity; plain Activity is fine here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FONT && resultCode == RESULT_OK) data?.data?.let { importFont(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        buildSetup()
    }

    @Suppress("DEPRECATION")
    private fun styleSystemBars(dark: Boolean) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = pageBg
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        } else if (!dark) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    // ------------------------------------------------------------- header

    private fun header() {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(20), px(4), px(20))
        }
        val logo = TextView(this).apply {
            text = "K"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#4F7CF7"), Color.parseColor("#8A5CF6")),
            ).apply { cornerRadius = 16 * dp }
        }
        row.addView(logo, LinearLayout.LayoutParams(px(56), px(56)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), 0, 0, 0)
            addView(text("KeyMagic", 28f, Theme.text, bold = true))
            addView(text("Your keyboard, your way", 14f, Theme.subText))
        }
        row.addView(texts)
        col.addView(row)
    }

    // -------------------------------------------------------------- setup

    private fun buildSetup() {
        setupHolder.removeAllViews()
        val steps = listOf(
            Step("Turn on KeyMagic", "Enable it in Android's keyboard list", isKeyboardEnabled()) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            Step("Choose KeyMagic", "Make it the keyboard you type with", isKeyboardSelected()) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            },
            Step("Allow photos", "So new screenshots appear in your clipboard", hasPhotoPermission()) {
                val perms = when {
                    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                requestPermissions(perms, 1)
            },
            Step("Allow notification access", "To show OTP codes from SMS and apps", hasNotificationAccess()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        )
        val done = steps.count { it.done }

        val card = card()
        if (done == steps.size) {
            card.addView(row(padV = 16) {
                addView(badge("✓", Theme.accent), LinearLayout.LayoutParams(px(36), px(36)))
                addView(column(start = 14) {
                    addView(text("You're all set", 17f, Theme.text, bold = true))
                    addView(text("KeyMagic is ready. Adjust anything below.", 13f, Theme.subText))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        } else {
            card.addView(column(padH = 18, padTop = 16) {
                addView(text("Get started", 20f, Theme.text, bold = true))
                addView(text("$done of ${steps.size} done", 13f, Theme.subText).apply { setPadding(0, px(2), 0, px(10)) })
                addView(progressBar(done.toFloat() / steps.size))
            })
            steps.forEachIndexed { i, st ->
                if (i > 0) card.addView(innerDivider(start = 68))
                card.addView(stepRow(i + 1, st))
            }
            card.addView(innerDivider(start = 0))
            card.addView(linkRow("Notification access says “Restricted setting”?") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Tap ⋮ (top right) → Allow restricted settings", Toast.LENGTH_LONG).show()
            })
        }
        setupHolder.addView(card)

        if (Build.VERSION.SDK_INT >= 35) setupHolder.addView(otpHelpCard())
    }

    private class Step(val title: String, val subtitle: String, val done: Boolean, val action: () -> Unit)

    private fun stepRow(n: Int, st: Step) = row(padV = 14) {
        addView(
            if (st.done) badge("✓", Theme.accent) else badge("$n", if (Theme.dark) Color.parseColor("#3A3A3C") else Color.parseColor("#E5E5EA"), Theme.text),
            LinearLayout.LayoutParams(px(32), px(32)),
        )
        addView(column(start = 18) {
            addView(text(st.title, 16f, if (st.done) Theme.subText else Theme.text, bold = !st.done))
            addView(text(if (st.done) "Done" else st.subtitle, 13f, Theme.subText))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!st.done) addView(text("›", 24f, Theme.subText))
        setOnClickListener { st.action() }
    }

    private fun otpHelpCard(): View {
        val card = card()
        card.addView(column(padH = 18, padTop = 16) {
            addView(text("OTP codes on Android 15+", 16f, Theme.text, bold = true))
            addView(text(
                "Android hides codes in notifications from apps you install yourself. Run this once from a PC " +
                    "(USB debugging on), then restart the phone. Until then, tap “Copy code” on the SMS – " +
                    "KeyMagic shows copied codes too.",
                13f, Theme.subText,
            ).apply { setPadding(0, px(4), 0, px(10)) })
            addView(TextView(this@MainActivity).apply {
                text = adbCommand
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(Theme.text)
                setTextIsSelectable(true)
                setPadding(px(12), px(10), px(12), px(10))
                background = rounded(if (Theme.dark) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F2F6"), 10f)
            })
        })
        card.addView(linkRow("Copy command") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        })
        return card
    }

    // ------------------------------------------------------------- try it

    private fun tryItCard() {
        val card = card()
        card.addView(column(padH = 18, padTop = 16, padBottom = 16) {
            addView(text("Try it", 16f, Theme.text, bold = true).apply { setPadding(0, 0, 0, px(10)) })
            addView(EditText(this@MainActivity).apply {
                hint = "Tap here and type…"
                textSize = 16f
                setTextColor(Theme.text)
                setHintTextColor(Theme.subText)
                setPadding(px(14), px(12), px(14), px(12))
                background = rounded(if (Theme.dark) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F2F6"), 12f)
            })
        })
        col.addView(card)
    }

    // ----------------------------------------------------------- settings

    private fun settings() {
        group("Typing") {
            switchRow("Word predictions", "Suggests words as you type and learns yours", { prefs.predictions }) { prefs.predictions = it }
            switchRow("Emoji predictions", "Suggests emojis for words like “love”", { prefs.emojiPredictions }) { prefs.emojiPredictions = it }
            switchRow("Number row", "Show 1–0 above the letters", { prefs.numberRow }) { prefs.numberRow = it }
            switchRow("OTP codes", "Offer codes from SMS and app notifications", { prefs.otp }) { prefs.otp = it }
            switchRow("Clipboard history", "Keep your last 10 copied items", { prefs.clipboard }) { prefs.clipboard = it }
        }

        group("Look") {
            switchRow("Key popup", "Show a bubble above the key you press", { prefs.keyPopup }) { prefs.keyPopup = it }
            switchRow("Gradient on key presses", "Soft colour bloom; off with Battery Saver", { prefs.gradientBackground }) { prefs.gradientBackground = it }
            sliderRow("Keyboard height", Prefs.MIN_HEIGHT, Prefs.MAX_HEIGHT, 5, "%", { prefs.heightPercent }) { prefs.heightPercent = it }
        }

        group("Emoji") {
            choiceRow(
                "Emoji style",
                listOf("My emoji font", "System emojis"),
                { prefs.emojiStyle },
            ) {
                prefs.emojiStyle = it
                EmojiFont.reload(this@MainActivity, prefs)
                refreshEmojiExtras()
            }
            emojiHolder = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                tag = NO_DIVIDER // it draws its own divider
            }
            addView(emojiHolder)
        }
        refreshEmojiExtras()

        group("Feel & sound") {
            switchRow("Vibration", "A crisp knock on every key", { prefs.vibrate }) { prefs.vibrate = it }
            sliderRow("Vibration strength", 1, 100, 1, "%", { prefs.vibrateStrength }) {
                prefs.vibrateStrength = it
                feedback.vibrate(it)
            }
            switchRow("Key sounds", null, { prefs.sound }) { prefs.sound = it }
            sliderRow("Sound volume", 0, 100, 5, "%", { prefs.soundVolume }) {
                prefs.soundVolume = it
                feedback.click(KeyCore.KIND_CHAR, it)
            }
        }

        group("Chat apps & navigation bar") {
            switchRow(
                "Backup chat fix",
                "Turn on if a WhatsApp chat hides behind the keyboard, then reopen the chat",
                { prefs.systemInsets },
            ) { prefs.systemInsets = it }
            switchRow(
                "Automatic navigation bar spacing",
                "Turn off if the chat still jumps; then set Bottom space by hand",
                { prefs.autoNavSpace },
            ) { prefs.autoNavSpace = it }
            sliderRow("Bottom space", 0, 48, 4, " dp", { prefs.bottomSpaceDp }) { prefs.bottomSpaceDp = it }
        }

        group("Your data") {
            actionRow("Clear clipboard history", null) {
                prefs.requestClearClipboard()
                Toast.makeText(this@MainActivity, "Clipboard cleared", Toast.LENGTH_SHORT).show()
            }
            actionRow("Forget learned words", "Long-press a suggestion to forget just one", destructive = true) {
                prefs.requestClearWords()
                Toast.makeText(this@MainActivity, "Learned words cleared", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Font status + choose / change / remove, under the Emoji style switch. */
    private fun refreshEmojiExtras() {
        if (!::emojiHolder.isInitialized) return
        emojiHolder.removeAllViews()
        val hasFont = EmojiFont.hasFont(this)
        val builtIn = EmojiFont.builtInSize(this)
        emojiHolder.addView(innerDivider(start = 18))

        if (prefs.emojiStyle == Prefs.EMOJI_STYLE_SYSTEM) {
            emojiHolder.addView(column(padH = 18, padTop = 12, padBottom = 14) {
                addView(text("Uses your phone's own emojis.", 13f, Theme.subText))
            })
            return
        }

        val status = when {
            importing -> importStatus ?: "Copying font…"
            hasFont -> "${EmojiFont.fontName(this) ?: "Emoji font"} · " +
                Formatter.formatShortFileSize(this, EmojiFont.file(this).length()) +
                if (EmojiFont.typeface == null) " – couldn't load it, choose it again" else ""
            builtIn > 0 -> "Using the built-in emoji font (${Formatter.formatShortFileSize(this, builtIn)})" +
                if (EmojiFont.typeface == null) " – couldn't load it" else ""
            else -> "No font chosen yet – the phone's own emojis are used until you pick one."
        }
        emojiHolder.addView(row(padV = 14) {
            addView(column {
                addView(text(if (hasFont || builtIn > 0) "Change emoji font" else "Choose emoji font", 16f, Theme.accentLight))
                addView(text(status, 13f, Theme.subText).apply { setPadding(0, px(2), px(8), 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { pickFont() }
        })
        if (hasFont && !importing) {
            emojiHolder.addView(innerDivider(start = 18))
            emojiHolder.addView(row(padV = 14) {
                addView(text(if (builtIn > 0) "Back to built-in emoji font" else "Remove emoji font", 16f, Color.parseColor("#E5484D")))
                setOnClickListener {
                    EmojiFont.remove(this@MainActivity)
                    prefs.notifyEmojiFontChanged()
                    refreshEmojiExtras()
                }
            })
        }
        emojiHolder.addView(innerDivider(start = 18))
        emojiHolder.addView(column(padH = 18, padTop = 12, padBottom = 14) {
            addView(text(
                (if (builtIn > 0) "Optional: pick another colour emoji .ttf from your phone. " else "Pick a colour emoji .ttf from your phone. ") +
                    "Only that font draws emojis on the keyboard; " +
                    "emojis it doesn't have are hidden. Others still see their own app's emojis.",
                13f, Theme.subText,
            ))
        })
    }

    private fun pickFont() {
        if (importing) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // fonts come as font/ttf, application/x-font-ttf or octet-stream
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_FONT)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker found on this phone", Toast.LENGTH_LONG).show()
        }
    }

    private fun importFont(uri: Uri) {
        importing = true
        importStatus = "Copying font…"
        refreshEmojiExtras()
        val name = try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        Thread {
            val error = EmojiFont.import(this, uri, name) { done, total ->
                val msg = if (total > 0) "Copying font… ${done * 100 / total}%" else
                    "Copying font… ${Formatter.formatShortFileSize(this, done)}"
                runOnUiThread {
                    importStatus = msg
                    refreshEmojiExtras()
                }
            }
            runOnUiThread {
                importing = false
                importStatus = null
                if (error == null) {
                    prefs.emojiStyle = Prefs.EMOJI_STYLE_FONT
                    EmojiFont.reload(this, prefs)
                    prefs.notifyEmojiFontChanged()
                    Toast.makeText(this, "Emoji font ready", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
                refreshEmojiExtras()
            }
        }.start()
    }

    private fun footer() {
        col.addView(text("KeyMagic ${versionName()}", 12f, Theme.subText).apply {
            gravity = Gravity.CENTER
            setPadding(0, px(24), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    // ------------------------------------------------------------- checks

    private fun isKeyboardEnabled(): Boolean =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.packageName == packageName }

    private fun isKeyboardSelected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun hasPhotoPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    }

    // ------------------------------------------------------------ widgets

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * dp
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cardBg, 22f)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 0, 0, px(14)) }
    }

    private fun row(padV: Int = 14, body: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(18), px(padV), px(18), px(padV))
        body()
    }

    private fun column(
        start: Int = 0, padH: Int = 0, padTop: Int = 0, padBottom: Int = 0,
        body: LinearLayout.() -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(start + padH), px(padTop), px(padH), px(padBottom))
        body()
    }

    private fun badge(label: String, bg: Int, fg: Int = Color.WHITE) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
        }
    }

    private fun progressBar(fraction: Float): View {
        val track = LinearLayout(this).apply {
            background = rounded(if (Theme.dark) Color.parseColor("#3A3A3C") else Color.parseColor("#E5E5EA"), 3f)
            setPadding(0, 0, 0, 0)
        }
        val fill = View(this).apply { background = rounded(Theme.accentLight, 3f) }
        track.addView(fill, LinearLayout.LayoutParams(0, px(6), fraction.coerceIn(0.02f, 1f)))
        track.addView(View(this), LinearLayout.LayoutParams(0, px(6), (1f - fraction).coerceAtLeast(0f)))
        return LinearLayout(this).apply {
            setPadding(0, 0, 0, px(8))
            addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6)))
        }
    }

    private fun innerDivider(start: Int) = View(this).apply {
        setBackgroundColor(divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, px(1) / 2)).apply {
            setMargins(px(start), 0, 0, 0)
        }
    }

    private fun linkRow(label: String, onClick: () -> Unit) = row(padV = 14) {
        addView(text(label, 15f, Theme.accentLight), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { onClick() }
    }

    /** A titled group of settings in one rounded card. */
    private fun group(title: String, body: LinearLayout.() -> Unit) {
        col.addView(text(title, 13f, Theme.subText, bold = true).apply { setPadding(px(18), px(10), 0, px(8)) })
        val card = card()
        card.body()
        // dividers between rows
        val rows = (0 until card.childCount).map { card.getChildAt(it) }
        card.removeAllViews()
        rows.forEachIndexed { i, v ->
            if (i > 0 && v.tag != NO_DIVIDER) card.addView(innerDivider(start = 18))
            card.addView(v)
        }
        col.addView(card)
    }

    private fun LinearLayout.switchRow(title: String, subtitle: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = Switch(this@MainActivity).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, checked -> set(checked) }
        }
        addView(row {
            addView(titles(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.isChecked = !sw.isChecked }
        })
    }

    private fun LinearLayout.sliderRow(title: String, min: Int, max: Int, step: Int, unit: String, get: () -> Int, set: (Int) -> Unit) {
        val value = text("${get()}$unit", 15f, Theme.accentLight, bold = true)
        val seek = SeekBar(this@MainActivity).apply {
            this.max = (max - min) / step
            progress = (get() - min) / step
            setPadding(0, px(8), 0, px(4))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                value.text = "${min + p * step}$unit"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = set(min + s.progress * step)
        })
        addView(column(padH = 18, padTop = 14, padBottom = 10) {
            addView(LinearLayout(this@MainActivity).apply {
                addView(text(title, 16f, Theme.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            })
            addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    /** Segmented control (pill buttons). */
    private fun LinearLayout.choiceRow(title: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) {
        val pills = LinearLayout(this@MainActivity).apply {
            background = rounded(if (Theme.dark) Color.parseColor("#2C2C2E") else Color.parseColor("#EEEEF2"), 12f)
            setPadding(px(3), px(3), px(3), px(3))
        }
        fun paint() {
            for (i in 0 until pills.childCount) {
                val t = pills.getChildAt(i) as TextView
                val on = i == get()
                t.background = if (on) rounded(if (Theme.dark) Color.parseColor("#48484A") else Color.WHITE, 10f) else null
                t.setTextColor(if (on) Theme.text else Theme.subText)
            }
        }
        options.forEachIndexed { i, label ->
            pills.addView(text(label, 14f, Theme.text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(9), 0, px(9))
                setOnClickListener {
                    set(i)
                    paint()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint()
        addView(column(padH = 18, padTop = 14, padBottom = 14) {
            addView(text(title, 16f, Theme.text).apply { setPadding(0, 0, 0, px(10)) })
            addView(pills, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun LinearLayout.actionRow(title: String, subtitle: String?, destructive: Boolean = false, onClick: () -> Unit) {
        addView(row {
            addView(column {
                addView(text(title, 16f, if (destructive) Color.parseColor("#E5484D") else Theme.text))
                if (subtitle != null) addView(text(subtitle, 13f, Theme.subText))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { onClick() }
        })
    }

    private fun titles(title: String, subtitle: String?) = column {
        addView(text(title, 16f, Theme.text))
        if (subtitle != null) addView(text(subtitle, 13f, Theme.subText).apply { setPadding(0, px(2), px(8), 0) })
    }

    private companion object {
        const val NO_DIVIDER = "no-divider"
        const val REQ_PICK_FONT = 41
    }
}
