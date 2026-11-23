package com.ssonkoict.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.os.Build
import android.view.View
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** The emoji list (from assets/emoji.tsv, built from Unicode CLDR data). */
object EmojiData {
    class Group(val id: String, val icon: String, val emojis: MutableList<String> = ArrayList())

    /** Tab order. "recent" is filled from usage at runtime. */
    val groups = listOf(
        Group("recent", "🕘"), Group("smileys", "😀"), Group("people", "👋"), Group("nature", "🐻"),
        Group("food", "🍔"), Group("travel", "🚗"), Group("activities", "⚽"), Group("objects", "💡"),
        Group("symbols", "🔣"), Group("flags", "🏁"),
    )

    @Volatile var raw: String = ""
        private set

    @Synchronized
    fun load(context: Context) {
        if (raw.isNotEmpty()) return
        val text = context.assets.open("emoji.tsv").bufferedReader().use { it.readText() }
        val byId = groups.associateBy { it.id }
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            if (p.size >= 2) byId[p[0]]?.emojis?.add(p[1])
        }
        raw = text
    }

    fun all(): List<String> = groups.flatMap { it.emojis }
}

/**
 * The emoji font (Settings → Emoji style). Two sources, in order:
 *  1. a font the user picked on the phone (copied into private storage), else
 *  2. a font built into the APK (assets/emoji_font.ttf, added at build time
 *     from `emoji.font` in local.properties – never stored in git).
 * The asset is stored uncompressed, so it is memory-mapped, not read into RAM.
 *
 * When "My emoji font" is active, ONLY that font draws emojis – no system
 * fallback – and emojis the font doesn't have are hidden. Otherwise the
 * phone's own emojis are used.
 *
 * This only changes how emojis look on the keyboard: messages carry normal
 * Unicode emojis, which the receiving app draws in its own style.
 */
object EmojiFont {
    private const val FILE_NAME = "emoji_font.ttf"
    private const val NAME_FILE = "emoji_font.name"
    private const val ASSET = "emoji_font.ttf"

    /** Font used to draw emojis, or null = the phone's own emojis. */
    @Volatile var typeface: Typeface? = null
        private set

    /** True when the custom font is active and has no system fallback (strict mode). */
    @Volatile private var strict = false
    private val support = ConcurrentHashMap<String, Boolean>()
    private val probe = Paint()

    fun file(context: Context) = File(context.filesDir, FILE_NAME)
    /** A font the user picked on the phone. */
    fun hasFont(context: Context) = file(context).isFile

    /** Size of the font built into the APK, or -1 if there is none (or it was compressed). */
    fun builtInSize(context: Context): Long = try {
        context.assets.openFd(ASSET).use { it.length }
    } catch (e: IOException) {
        -1L
    }
    fun hasBuiltIn(context: Context) = builtInSize(context) > 0
    fun fontName(context: Context): String? =
        File(context.filesDir, NAME_FILE).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    /** (Re)loads according to the settings. Cheap: the font file is memory-mapped, not read in. */
    @Synchronized
    fun reload(context: Context, prefs: Prefs) {
        // Leftover pictures from the old Fluent 3D emoji option.
        File(context.filesDir, "emoji3d").takeIf { it.exists() }?.deleteRecursively()

        support.clear()
        val f = file(context)
        typeface = null
        strict = false
        if (prefs.emojiStyle != Prefs.EMOJI_STYLE_FONT) return
        val picked = f.isFile
        if (!picked && !hasBuiltIn(context)) return
        typeface = try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Only this font – a missing emoji is never drawn in another style.
                val font = if (picked) Font.Builder(f).build() else Font.Builder(context.assets, ASSET).build()
                Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build().also { strict = true }
            } else if (picked) {
                Typeface.createFromFile(f)
            } else {
                Typeface.createFromAsset(context.assets, ASSET)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Whether [emoji] can be shown. Always true for the phone's own emojis. */
    fun supports(emoji: String): Boolean {
        val tf = typeface ?: return true
        if (!strict) return true
        return support.getOrPut(emoji) {
            synchronized(probe) {
                probe.typeface = tf
                probe.hasGlyph(emoji)
            }
        }
    }

    fun filter(emojis: List<String>): List<String> =
        if (typeface == null || !strict) emojis else emojis.filter { supports(it) }

    /**
     * Copies a font chosen with the system file picker into the app. Blocking –
     * call off the main thread. Returns null on success, else an error message.
     */
    fun import(context: Context, uri: Uri, displayName: String?, onProgress: (Long, Long) -> Unit): String? {
        val dest = file(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            val input = context.contentResolver.openInputStream(uri) ?: return "Couldn't open that file."
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= (4 shl 20)) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            val problem = checkFormat(tmp)
            if (problem != null) {
                tmp.delete()
                return problem
            }
            val test = try { Typeface.createFromFile(tmp) } catch (e: Exception) { null }
            if (test == null || !Paint().apply { typeface = test }.hasGlyph("😀")) {
                tmp.delete()
                return "That font doesn't contain emojis."
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return "Couldn't save the font."
            }
            File(context.filesDir, NAME_FILE).writeText(displayName ?: "Emoji font")
            return null
        } catch (e: IOException) {
            tmp.delete()
            return "Couldn't copy the font (${e.message ?: "read error"}). Is there enough free space?"
        } catch (e: SecurityException) {
            tmp.delete()
            return "No permission to read that file."
        }
    }

    fun remove(context: Context) {
        file(context).delete()
        File(context.filesDir, NAME_FILE).delete()
        typeface = null
        strict = false
        support.clear()
    }

    /**
     * Android can draw colour emojis only from CBDT (bitmap) or COLR (vector)
     * tables. Apple's sbix and OpenType-SVG fonts would show blank/black glyphs.
     */
    private fun checkFormat(f: File): String? {
        RandomAccessFile(f, "r").use { r ->
            if (r.length() < 12) return "That isn't a font file."
            var offset = 0L
            val tag = r.readInt()
            if (tag == 0x74746366) { // "ttcf" collection: look at the first font
                r.seek(12)
                offset = r.readInt().toLong() and 0xFFFFFFFFL
                r.seek(offset)
                r.readInt()
            } else if (tag != 0x00010000 && tag != 0x4F54544F && tag != 0x74727565) { // TrueType, "OTTO", "true"
                return "That isn't a .ttf / .otf font file."
            }
            r.seek(offset + 4)
            val count = r.readUnsignedShort()
            val tables = HashSet<String>()
            for (i in 0 until count) {
                r.seek(offset + 12 + 16L * i)
                val b = ByteArray(4)
                r.readFully(b)
                tables += String(b, Charsets.US_ASCII)
            }
            if ("CBDT" in tables || "COLR" in tables) return null
            if ("sbix" in tables || "SVG " in tables) {
                return "This emoji font uses a format Android can't draw (sbix/SVG). Use a CBDT or COLR version, " +
                    "e.g. an \"Android\" or \"Windows\" build of the font."
            }
            return "That font has no colour emojis Android can draw."
        }
    }
}

/** Draws one emoji with the chosen emoji font, or the phone's own emojis. */
class EmojiView(context: Context) : View(context) {
    var emoji: String = ""
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        val e = emoji
        if (e.isEmpty() || !EmojiFont.supports(e)) return
        val size = minOf(width, height)
        textPaint.typeface = EmojiFont.typeface
        textPaint.textSize = size * 0.6f
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(e, width / 2f, y, textPaint)
    }
}
