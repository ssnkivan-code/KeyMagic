package com.ssonkoict.keyboard

/** Raw JNI bindings to the Rust `keycore` library (see keycore/src/jni.rs). */
object NativeCore {
    init {
        System.loadLibrary("keycore")
    }

    @JvmStatic external fun create(): Long
    @JvmStatic external fun destroy(handle: Long)

    // keyboard engine
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun press(handle: Long, row: Int, col: Int, nowMs: Long): Int
    @JvmStatic external fun rowCount(handle: Long): Int
    @JvmStatic external fun maxRowCount(handle: Long): Int
    @JvmStatic external fun keyCount(handle: Long, row: Int): Int
    @JvmStatic external fun keyKind(handle: Long, row: Int, col: Int): Int
    @JvmStatic external fun keyLabel(handle: Long, row: Int, col: Int): Int
    @JvmStatic external fun keyWidth(handle: Long, row: Int, col: Int): Int
    @JvmStatic external fun shiftState(handle: Long): Int
    @JvmStatic external fun page(handle: Long): Int
    @JvmStatic external fun setPage(handle: Long, page: Int)
    @JvmStatic external fun setAutoShift(handle: Long, on: Boolean)
    @JvmStatic external fun setNumberRow(handle: Long, on: Boolean)

    // predictions
    @JvmStatic external fun loadDictionary(handle: Long, data: String): Int
    @JvmStatic external fun loadEmoji(handle: Long, data: String)
    @JvmStatic external fun suggest(handle: Long, before: String, max: Int): String
    @JvmStatic external fun suggestEmoji(handle: Long, before: String, max: Int): String
    @JvmStatic external fun learn(handle: Long, before: String)
    @JvmStatic external fun forget(handle: Long, word: String)
    @JvmStatic external fun clearUser(handle: Long)
    @JvmStatic external fun recordEmoji(handle: Long, emoji: String)
    @JvmStatic external fun frequentEmojis(handle: Long, max: Int): String
    @JvmStatic external fun exportUser(handle: Long): String
    @JvmStatic external fun importUser(handle: Long, data: String)

    // clipboard history
    @JvmStatic external fun clipAdd(handle: Long, kind: Int, content: String, timeMs: Long): String
    @JvmStatic external fun clipCount(handle: Long): Int
    @JvmStatic external fun clipKind(handle: Long, index: Int): Int
    @JvmStatic external fun clipContent(handle: Long, index: Int): String
    @JvmStatic external fun clipTime(handle: Long, index: Int): Long
    @JvmStatic external fun clipTouch(handle: Long, index: Int, timeMs: Long): Boolean
    @JvmStatic external fun clipRemove(handle: Long, index: Int): String
    @JvmStatic external fun clipClear(handle: Long): String
    @JvmStatic external fun clipExport(handle: Long): String
    @JvmStatic external fun clipImport(handle: Long, data: String)

    // OTP
    @JvmStatic external fun findOtp(text: String): String
}

private fun String.splitLines(): List<String> = if (isEmpty()) emptyList() else split('\n')

/** Friendly wrapper that owns one native core (engine + predictor + clipboard). */
class KeyCore : AutoCloseable {
    @Volatile private var handle: Long = NativeCore.create()

    fun reset() = NativeCore.reset(handle)
    fun press(row: Int, col: Int, nowMs: Long) = NativeCore.press(handle, row, col, nowMs)
    fun rowCount() = NativeCore.rowCount(handle)
    fun maxRowCount() = NativeCore.maxRowCount(handle)
    fun keyCount(row: Int) = NativeCore.keyCount(handle, row)
    fun keyKind(row: Int, col: Int) = NativeCore.keyKind(handle, row, col)
    fun keyLabel(row: Int, col: Int) = NativeCore.keyLabel(handle, row, col)
    fun keyWidth(row: Int, col: Int) = NativeCore.keyWidth(handle, row, col)
    fun shiftState() = NativeCore.shiftState(handle)
    fun page() = NativeCore.page(handle)
    fun setPage(page: Int) = NativeCore.setPage(handle, page)
    fun setAutoShift(on: Boolean) = NativeCore.setAutoShift(handle, on)
    fun setNumberRow(on: Boolean) = NativeCore.setNumberRow(handle, on)

    fun loadDictionary(data: String) = NativeCore.loadDictionary(handle, data)
    fun loadEmoji(data: String) = NativeCore.loadEmoji(handle, data)
    fun suggest(before: String, max: Int) = NativeCore.suggest(handle, before, max).splitLines()
    fun suggestEmoji(before: String, max: Int) = NativeCore.suggestEmoji(handle, before, max).splitLines()
    fun learn(before: String) = NativeCore.learn(handle, before)
    fun forget(word: String) = NativeCore.forget(handle, word)
    fun clearUser() = NativeCore.clearUser(handle)
    fun recordEmoji(emoji: String) = NativeCore.recordEmoji(handle, emoji)
    fun frequentEmojis(max: Int) = NativeCore.frequentEmojis(handle, max).splitLines()
    fun exportUser(): String = NativeCore.exportUser(handle)
    fun importUser(data: String) = NativeCore.importUser(handle, data)

    /** Returns file paths that are no longer needed (dropped images). */
    fun clipAdd(kind: Int, content: String, timeMs: Long) = NativeCore.clipAdd(handle, kind, content, timeMs).splitLines()
    fun clipCount() = NativeCore.clipCount(handle)
    fun clipKind(i: Int) = NativeCore.clipKind(handle, i)
    fun clipContent(i: Int): String = NativeCore.clipContent(handle, i)
    fun clipTime(i: Int) = NativeCore.clipTime(handle, i)
    fun clipTouch(i: Int, timeMs: Long) = NativeCore.clipTouch(handle, i, timeMs)
    fun clipRemove(i: Int): String = NativeCore.clipRemove(handle, i)
    fun clipClear() = NativeCore.clipClear(handle).splitLines()
    fun clipExport(): String = NativeCore.clipExport(handle)
    fun clipImport(data: String) = NativeCore.clipImport(handle, data)

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            NativeCore.destroy(h)
        }
    }

    companion object {
        // Keep in sync with keycore/src/lib.rs
        const val ACT_NONE = -1
        const val ACT_DELETE = -2
        const val ACT_ENTER = -3
        const val ACT_EMOJI = -4

        const val KIND_CHAR = 0
        const val KIND_SHIFT = 1
        const val KIND_BACKSPACE = 2
        const val KIND_ENTER = 3
        const val KIND_SPACE = 4
        const val KIND_TO_SYMBOLS = 5
        const val KIND_TO_MORE = 6
        const val KIND_TO_LETTERS = 7
        const val KIND_EMOJI = 8

        const val PAGE_LETTERS = 0
        const val PAGE_SYMBOLS = 1

        const val SHIFT_OFF = 0
        const val SHIFT_LOCKED = 2

        // keycore/src/clipboard.rs
        const val CLIP_TEXT = 0
        const val CLIP_IMAGE = 1
        const val CLIP_SCREENSHOT = 2
    }
}
