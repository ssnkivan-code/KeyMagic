package com.ssonkoict.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

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
 * Microsoft Fluent 3D emoji images (MIT licence), fetched by code point on first
 * use and cached on the phone (≈10 KB each). If an image isn't available
 * (offline, or Fluent has no picture for it – e.g. flags) the phone's own emoji
 * font is used instead.
 *
 * These pictures are only how emojis look on YOUR keyboard: the message carries
 * the normal Unicode emoji, and WhatsApp shows it in its own style.
 */
object EmojiImages {
    private const val SIZE = 128
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/shuding/fluentui-emoji-unicode/main/assets/%s_3d.png",
        "https://cdn.jsdelivr.net/gh/shuding/fluentui-emoji-unicode/assets/%s_3d.png",
    )

    @Volatile var enabled = true
    private var dir: File? = null
    private val mem = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val pending = HashMap<String, MutableList<() -> Unit>>() // main thread only
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context, enabled: Boolean) {
        this.enabled = enabled
        if (dir == null) dir = File(context.filesDir, "emoji3d").apply { mkdirs() }
    }

    /** Lowercase hex code points joined by "-", e.g. "1f44b-1f3fd". */
    fun code(emoji: String, keepVs16: Boolean): String {
        val parts = ArrayList<String>()
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            if (keepVs16 || cp != 0xFE0F) parts += Integer.toHexString(cp)
            i += Character.charCount(cp)
        }
        return parts.joinToString("-")
    }

    fun cached(emoji: String): Bitmap? = if (enabled) mem.get(emoji) else null

    /** Load in the background; [onReady] runs on the main thread if a picture was found. */
    fun request(emoji: String, onReady: () -> Unit) {
        if (!enabled || emoji.isEmpty() || mem.get(emoji) != null || missing.contains(emoji)) return
        val waiting = pending[emoji]
        if (waiting != null) {
            waiting += onReady
            return
        }
        pending[emoji] = mutableListOf(onReady)
        io.execute {
            val bmp = try { fetch(emoji) } catch (e: Exception) { null }
            main.post {
                val cbs = pending.remove(emoji) ?: mutableListOf()
                if (bmp != null) {
                    mem.put(emoji, bmp)
                    cbs.forEach { it() }
                } else {
                    missing += emoji
                }
            }
        }
    }

    /** Blocking: disk cache, else download. Used by "download all" in settings. */
    fun fetch(emoji: String): Bitmap? {
        val d = dir ?: return null
        val file = File(d, code(emoji, false) + ".webp")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
        }
        val codes = listOf(code(emoji, false), code(emoji, true)).distinct()
        for (c in codes) {
            for (src in SOURCES) {
                val bytes = download(String.format(src, c)) ?: continue
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 } // 256 → 128 px
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
                val scaled = if (bmp.width > SIZE) Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true) else bmp
                save(scaled, file)
                return scaled
            }
        }
        return null
    }

    fun isOnDisk(emoji: String): Boolean = dir?.let { File(it, code(emoji, false) + ".webp").exists() } == true

    fun diskBytes(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L

    private fun save(bmp: Bitmap, file: File) {
        try {
            file.outputStream().use { bmp.compress(webpFormat(), 85, it) }
        } catch (e: IOException) {
            file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private fun download(url: String): ByteArray? {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return null
        }
        return try {
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

/** Draws one emoji: the Fluent 3D picture when available, else the phone's emoji font. */
class EmojiView(context: Context) : View(context) {
    var emoji: String = ""
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dst = Rect()

    override fun onDraw(canvas: Canvas) {
        val e = emoji
        if (e.isEmpty()) return
        val size = minOf(width, height)
        val bmp = EmojiImages.cached(e)
        if (bmp != null) {
            val s = (size * 0.78f).toInt()
            val l = (width - s) / 2
            val t = (height - s) / 2
            dst.set(l, t, l + s, t + s)
            canvas.drawBitmap(bmp, null, dst, bmpPaint)
        } else {
            textPaint.textSize = size * 0.6f
            val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(e, width / 2f, y, textPaint)
            EmojiImages.request(e) { if (emoji == e) invalidate() }
        }
    }
}
