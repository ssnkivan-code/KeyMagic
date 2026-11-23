package com.ssonkoict.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

private fun Context.dp(v: Float) = v * resources.displayMetrics.density
private fun Context.dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

internal fun roundBg(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

/** A small rounded text button used in the panels. */
@SuppressLint("ViewConstructor")
internal class PanelButton(context: Context, label: String, bold: Boolean = false, onClick: () -> Unit) : TextView(context) {
    init {
        text = label
        setTextColor(Theme.text)
        textSize = 15f
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        val p = context.dpi(12)
        setPadding(p, 0, p, 0)
        background = roundBg(Theme.special, context.dp(7f))
        setOnClickListener { onClick() }
    }
}

// --------------------------------------------------------------------- emoji

@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val recents: () -> List<String>,
    private val onEmoji: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onSpace: () -> Unit,
    private val onAbc: () -> Unit,
) : LinearLayout(context) {

    private val cell = context.dpi(46)
    private val tabs = LinearLayout(context)
    private val grid = GridView(context)
    private val adapter = GridAdapter()
    private var current = 1

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.background)

        EmojiData.groups.forEachIndexed { i, g ->
            tabs.addView(EmojiView(context).apply {
                emoji = g.icon
                setPadding(0, context.dpi(4), 0, context.dpi(4))
                setOnClickListener { select(i) }
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(40)))

        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = cell
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> adapter.items.getOrNull(pos)?.let(onEmoji) }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(context).apply {
            val m = context.dpi(4)
            setPadding(m, m, m, m)
            addView(PanelButton(context, "ABC", bold = true) { onAbc() }, LayoutParams(context.dpi(64), LayoutParams.MATCH_PARENT))
            addView(PanelButton(context, "space") { onSpace() }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(m, 0, m, 0) })
            addView(PanelButton(context, "⌫") { onBackspace() }, LayoutParams(context.dpi(64), LayoutParams.MATCH_PARENT))
        }
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(48)))
    }

    /** Opens on "recent" if you have any, otherwise smileys. */
    fun open() {
        select(if (recents().isNotEmpty()) 0 else 1)
    }

    private fun select(i: Int) {
        current = i
        for (t in 0 until tabs.childCount) {
            tabs.getChildAt(t).background = if (t == i) roundBg(Theme.special, context.dp(8f)) else null
        }
        adapter.items = if (i == 0) recents() else EmojiFont.filter(EmojiData.groups[i].emojis)
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
    }

    fun refreshRecents() {
        if (current == 0) select(0)
    }

    /** Redraw with the current emoji font (and hide emojis it doesn't have). */
    fun refreshFont() {
        for (t in 0 until tabs.childCount) tabs.getChildAt(t).invalidate()
        select(current)
    }

    private inner class GridAdapter : BaseAdapter() {
        var items: List<String> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView as? EmojiView) ?: EmojiView(context).apply {
                layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, cell)
            }
            v.emoji = items[position]
            return v
        }
    }
}

// ----------------------------------------------------------------- clipboard

data class ClipItem(val index: Int, val kind: Int, val content: String, val timeMs: Long)

@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val onPaste: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onClearAll: () -> Unit,
    private val onAbc: () -> Unit,
) : LinearLayout(context) {

    private val title = TextView(context)
    private val list = LinearLayout(context)
    private val thumbs = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.background)
        val m = context.dpi(4)

        val header = LinearLayout(context).apply {
            setPadding(m, m, m, m)
            gravity = Gravity.CENTER_VERTICAL
            addView(PanelButton(context, "ABC", bold = true) { onAbc() }, LayoutParams(context.dpi(64), LayoutParams.MATCH_PARENT))
            title.setTextColor(Theme.text)
            title.textSize = 15f
            title.gravity = Gravity.CENTER
            addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(PanelButton(context, "Clear all") { onClearAll() }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(44)))

        list.orientation = VERTICAL
        list.setPadding(m, 0, m, m)
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun show(items: List<ClipItem>) {
        title.text = "Clipboard  ${items.size}/10"
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Copied text, numbers, images and new screenshots appear here.\nThe 10 most recent are kept; the oldest is replaced."
                setTextColor(Theme.subText)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, context.dpi(24), 0, 0)
            })
            return
        }
        for (item in items) list.addView(row(item))
    }

    private fun row(item: ClipItem): View {
        val m = context.dpi(4)
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dpi(10), context.dpi(8), m, context.dpi(8))
            background = roundBg(Theme.key, context.dp(8f))
            setOnClickListener { onPaste(item.index) }
            setOnLongClickListener { onDelete(item.index); true }
        }
        if (item.kind == KeyCore.CLIP_TEXT) {
            row.addView(TextView(context).apply {
                text = item.content
                setTextColor(Theme.text)
                textSize = 14f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            val thumb = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            row.addView(thumb, LinearLayout.LayoutParams(context.dpi(56), context.dpi(56)))
            loadThumb(item.content, thumb)
            row.addView(TextView(context).apply {
                text = if (item.kind == KeyCore.CLIP_SCREENSHOT) "Screenshot" else "Image"
                setTextColor(Theme.text)
                textSize = 14f
                setPadding(context.dpi(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.addView(TextView(context).apply {
            text = DateUtils.getRelativeTimeSpanString(item.timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            setTextColor(Theme.subText)
            textSize = 11f
            setPadding(m * 2, 0, m, 0)
        })
        row.addView(TextView(context).apply {
            text = "✕"
            setTextColor(Theme.subText)
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { onDelete(item.index) }
        }, LinearLayout.LayoutParams(context.dpi(36), context.dpi(36)))
        return LinearLayout(context).apply {
            setPadding(0, m, 0, 0)
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun loadThumb(path: String, into: ImageView) {
        into.tag = path
        thumbs.execute {
            val bmp: Bitmap? = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 160 && bounds.outHeight / (sample * 2) >= 160) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) {
                null
            }
            main.post { if (into.tag == path && bmp != null) into.setImageBitmap(bmp) }
        }
    }
}

// -------------------------------------------------------------------- resize

@SuppressLint("ViewConstructor")
class ResizePanel(
    context: Context,
    initial: Int,
    private val onChange: (Int) -> Unit,
    private val numberRow: () -> Boolean,
    private val onToggleNumberRow: () -> Unit,
    private val bottomSpace: () -> Int,
    private val onBottomSpace: (Int) -> Unit,
    private val onDone: () -> Unit,
) : LinearLayout(context) {

    private lateinit var numberRowButton: PanelButton

    private val label = TextView(context)
    private val seek = SeekBar(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Theme.background)
        val p = context.dpi(16)
        setPadding(p, p, p, p)

        label.setTextColor(Theme.text)
        label.textSize = 16f
        label.gravity = Gravity.CENTER
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        seek.max = (Prefs.MAX_HEIGHT - Prefs.MIN_HEIGHT) / 5
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                val pct = Prefs.MIN_HEIGHT + progress * 5
                label.text = "Keyboard height  $pct%"
                if (fromUser) onChange(pct)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(48)))

        // Backup if the keys sit on the navigation bar: raise them a little.
        val spaceLabel = TextView(context).apply {
            setTextColor(Theme.text)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val spaceSeek = SeekBar(context).apply {
            max = 12 // 0–48 dp in 4 dp steps
            progress = bottomSpace() / 4
        }
        spaceLabel.text = "Bottom space  ${bottomSpace()} dp"
        spaceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                spaceLabel.text = "Bottom space  ${progress * 4} dp"
                if (fromUser) onBottomSpace(progress * 4)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        addView(spaceLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(spaceSeek, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(40)))

        numberRowButton = PanelButton(context, "") {
            onToggleNumberRow()
            post { updateNumberRowLabel() }
        }
        addView(numberRowButton, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(40)).apply { setMargins(0, 0, 0, context.dpi(12)) })
        updateNumberRowLabel()

        val buttons = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(PanelButton(context, "Reset") { setValue(100); onChange(100) }, LayoutParams(context.dpi(96), context.dpi(40)))
            addView(View(context), LayoutParams(context.dpi(16), 1))
            addView(PanelButton(context, "Done", bold = true) { onDone() }, LayoutParams(context.dpi(96), context.dpi(40)))
        }
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setValue(initial)
    }

    private fun updateNumberRowLabel() {
        numberRowButton.text = if (numberRow()) "Number row: On" else "Number row: Off"
    }

    fun setValue(pct: Int) {
        seek.progress = (pct.coerceIn(Prefs.MIN_HEIGHT, Prefs.MAX_HEIGHT) - Prefs.MIN_HEIGHT) / 5
        label.text = "Keyboard height  $pct%"
    }
}
