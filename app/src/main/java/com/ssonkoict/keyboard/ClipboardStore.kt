package com.ssonkoict.keyboard

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps the last 10 copied things – text, numbers, images and new screenshots –
 * in one list (the Rust core decides order and what gets dropped).
 *
 * Android only lets the *active* keyboard read the clipboard in the background,
 * so this works while VanKeyboard is your selected keyboard.
 */
class ClipboardStore(
    private val context: Context,
    private val core: KeyCore,
    private val onChanged: () -> Unit,
) {
    private val prefs = Prefs(context)
    private val dir = File(context.filesDir, "clip").apply { mkdirs() }
    private val dataFile = File(context.filesDir, "clipboard.txt")
    private val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private val scan = Runnable { scanScreenshots() }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    fun start() {
        if (dataFile.exists()) core.clipImport(dataFile.readText())
        removeOrphanFiles()
        cm.addPrimaryClipChangedListener(clipListener)
        registerScreenshotObserver()
    }

    fun stop() {
        cm.removePrimaryClipChangedListener(clipListener)
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        dataFile.writeText(core.clipExport())
        io.shutdown()
    }

    fun items(): List<ClipItem> =
        (0 until core.clipCount()).map { ClipItem(it, core.clipKind(it), core.clipContent(it), core.clipTime(it)) }

    // ------------------------------------------------------------- adding

    private fun add(kind: Int, content: String) {
        val freed = core.clipAdd(kind, content, System.currentTimeMillis())
        freed.forEach(::deleteOwnFile)
        save()
        onChanged()
    }

    private fun onClipChanged() {
        val maybeClip: ClipData? = try {
            cm.primaryClip
        } catch (e: SecurityException) {
            null // not the active keyboard right now
        }
        val clip = maybeClip ?: return
        val desc = clip.description
        if (desc.label?.toString() == OWN_LABEL || clip.itemCount == 0) return
        val sensitive = Build.VERSION.SDK_INT >= 33 &&
            desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        val item = clip.getItemAt(0)

        val uri = item.uri
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: (if (desc.mimeTypeCount > 0) desc.getMimeType(0) else null)
            if (mime != null && mime.startsWith("image/")) {
                if (prefs.clipboard && !sensitive) copyImage(uri, mime, KeyCore.CLIP_IMAGE)
                return
            }
        }
        val text = (item.text ?: item.htmlText)?.toString() ?: return
        // "Copy code" buttons in SMS notifications put the code on the clipboard –
        // offer it in the suggestion bar (works even on Android 15+).
        val trimmed = text.trim()
        val code = NativeCore.findOtp(text).ifEmpty { if (trimmed.matches(DIGITS_ONLY)) trimmed else "" }
        if (code.isNotEmpty() && prefs.otp) OtpStore.offer(code, "Copied")
        if (!prefs.clipboard || sensitive) return // passwords marked sensitive are never stored
        add(KeyCore.CLIP_TEXT, text)
    }

    private fun copyImage(uri: Uri, mime: String, kind: Int) {
        io.execute {
            val file = File(dir, "clip_${System.currentTimeMillis()}.${extFor(mime)}")
            val ok = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } != null && file.length() > 0
            } catch (e: Exception) {
                false
            }
            if (ok) main.post { add(kind, file.absolutePath) } else file.delete()
        }
    }

    // --------------------------------------------------------- screenshots

    fun hasPhotoPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return true
        return Build.VERSION.SDK_INT >= 34 &&
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerScreenshotObserver() {
        if (observer != null || !hasPhotoPermission()) return
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                // Wait for the screenshot file to finish writing.
                main.removeCallbacks(scan)
                main.postDelayed(scan, 1500)
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        observer = obs
    }

    /** Adds screenshots taken since the last check. Also called when the keyboard opens. */
    fun scanScreenshots() {
        if (!prefs.clipboard || !hasPhotoPermission()) return
        registerScreenshotObserver()
        io.execute {
            val found = queryNewScreenshots()
            if (found.isNotEmpty()) main.post { found.forEach { add(KeyCore.CLIP_SCREENSHOT, it) } }
        }
    }

    /** Runs on [io]. Returns copied file paths, oldest first. */
    private fun queryNewScreenshots(): List<String> {
        val last = prefs.lastScreenshotSec
        val firstRun = last == 0L
        val nowSec = System.currentTimeMillis() / 1000
        // First time: bring in up to 3 screenshots from the last 3 days.
        val since = if (firstRun) nowSec - 3 * 24 * 3600 else last
        val limit = if (firstRun) 3 else 10

        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE, pathCol,
        )
        var selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        if (Build.VERSION.SDK_INT >= 29) selection += " AND ${MediaStore.Images.Media.IS_PENDING} = 0"

        val hits = ArrayList<Triple<Uri, String, Long>>()
        var newest = last
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
                arrayOf(since.toString()), "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext() && hits.size < limit) {
                    val added = c.getLong(1)
                    if (added > newest) newest = added
                    val name = c.getString(2) ?: ""
                    val path = c.getString(4) ?: ""
                    if (!name.contains("screenshot", true) && !path.contains("screenshot", true)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    hits += Triple(uri, c.getString(3) ?: "image/png", added)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        prefs.lastScreenshotSec = if (firstRun) maxOf(newest, nowSec) else newest

        val out = ArrayList<String>()
        for ((uri, mime, added) in hits.reversed()) {
            val file = File(dir, "shot_$added.${extFor(mime)}")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                if (file.length() > 0) out += file.absolutePath else file.delete()
            } catch (e: Exception) {
                file.delete()
            }
        }
        return out
    }

    // -------------------------------------------------------- using items

    fun paste(index: Int, ime: InputMethodService) {
        val kind = core.clipKind(index)
        if (kind < 0) return
        val content = core.clipContent(index)
        if (kind == KeyCore.CLIP_TEXT) {
            ime.currentInputConnection?.commitText(content, 1)
        } else {
            commitImage(ime, File(content))
        }
        core.clipTouch(index, System.currentTimeMillis())
        save()
        onChanged()
    }

    /**
     * Sends an image straight into the app if it accepts images from keyboards;
     * otherwise puts it on the clipboard so you can long-press → Paste.
     */
    private fun commitImage(ime: InputMethodService, file: File) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = mimeFor(file)
        val info = ime.currentInputEditorInfo
        val ic = ime.currentInputConnection
        if (Build.VERSION.SDK_INT >= 25 && info != null && ic != null) {
            val accepted = info.contentMimeTypes?.any { ClipDescription.compareMimeTypes(mime, it) } == true
            if (accepted) {
                val content = InputContentInfo(uri, ClipDescription(file.name, arrayOf(mime)), null)
                if (ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) return
            }
        }
        cm.setPrimaryClip(ClipData(OWN_LABEL, arrayOf(mime), ClipData.Item(uri)))
        Toast.makeText(context, "Image copied – long-press the message box and tap Paste", Toast.LENGTH_LONG).show()
    }

    fun remove(index: Int) {
        deleteOwnFile(core.clipRemove(index))
        save()
        onChanged()
    }

    fun clear() {
        core.clipClear().forEach(::deleteOwnFile)
        save()
        onChanged()
    }

    // ------------------------------------------------------------- helpers

    private fun save() {
        val data = core.clipExport()
        if (!io.isShutdown) io.execute { dataFile.writeText(data) }
    }

    private fun deleteOwnFile(path: String) {
        if (path.isEmpty()) return
        val f = File(path)
        if (f.parentFile?.canonicalPath == dir.canonicalPath) f.delete()
    }

    private fun removeOrphanFiles() {
        val used = items().filter { it.kind != KeyCore.CLIP_TEXT }.map { File(it.content).canonicalPath }.toSet()
        dir.listFiles()?.filter { it.canonicalPath !in used }?.forEach { it.delete() }
    }

    private fun extFor(mime: String) = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }

    private fun mimeFor(file: File) = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    companion object {
        const val OWN_LABEL = "keymagic"
        private val DIGITS_ONLY = Regex("\\d{4,8}")
    }
}
