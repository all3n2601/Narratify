package app.narratify

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The reader's table of contents. One view serves both presentations: a column beside the page in
 * landscape, and a panel over the page in portrait. The rows are identical either way, so the
 * outline behaves the same however the device is held.
 */
class ReaderOutlinePanel(
    context: Context,
    private val palette: AppPalette,
    private val onSelect: (Int) -> Unit,
    private val onClose: (() -> Unit)? = null,
) : LinearLayout(context) {
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(rows)
    }
    private val empty = context.styledText(
        "No sections in this book",
        Type.BODY,
        palette.muted,
    ).apply {
        setPadding(dpv(Gap.MD), dpv(Gap.LG), dpv(Gap.MD), dpv(Gap.LG))
        visibility = View.GONE
    }
    private var currentIndex = -1

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.surface)
        addView(header(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(context.ruleView(palette.rule))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun header(context: Context): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpv(Gap.MD), dpv(Gap.SM), dpv(Gap.XS), dpv(Gap.SM))
        addView(
            context.styledText("CONTENTS", Type.MICRO, palette.muted, Typeface.BOLD, tracking = Type.TRACKING_EYEBROW),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        onClose?.let { close ->
            addView(
                ImageView(context).apply {
                    setImageDrawable(NarratifyIcon(NarratifyIcon.Glyph.CLOSE, palette.muted))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    contentDescription = "Close contents"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { close() }
                },
                LayoutParams(dpv(40), dpv(40)),
            )
        }
    }

    /** Replaces the rows. An outline too short to be useful shows the empty state instead. */
    fun setEntries(entries: List<OutlineEntry>, currentIndex: Int) {
        this.currentIndex = currentIndex
        rows.removeAllViews()
        val worthShowing = ReaderOutline.isWorthShowing(entries)
        empty.visibility = if (worthShowing) View.GONE else View.VISIBLE
        scroll.visibility = if (worthShowing) View.VISIBLE else View.GONE
        if (!worthShowing) return

        entries.forEachIndexed { index, entry ->
            rows.addView(
                row(entry, index == currentIndex) { onSelect(index) },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        revealCurrent()
    }

    /** Moves the highlight as the reader moves, without rebuilding every row. */
    fun setCurrentIndex(index: Int) {
        if (index == currentIndex) return
        val previous = currentIndex
        currentIndex = index
        listOfNotNull(rows.getChildAt(previous), rows.getChildAt(index)).forEach { child ->
            val isCurrent = rows.indexOfChild(child) == index
            child.setBackgroundColor(if (isCurrent) palette.highlight else Color.TRANSPARENT)
            (child as? TextView)?.setTextColor(if (isCurrent) palette.ink else palette.muted)
            child.typefaceBold(isCurrent)
        }
        revealCurrent()
    }

    /**
     * A long book opens hundreds of rows above the reader's place, so the panel is useless unless
     * it starts where they are.
     */
    private fun revealCurrent() {
        val child = rows.getChildAt(currentIndex) ?: return
        scroll.post { scroll.scrollTo(0, (child.top - scroll.height / 3).coerceAtLeast(0)) }
    }

    private fun row(entry: OutlineEntry, isCurrent: Boolean, onClick: () -> Unit): View =
        context.styledText(
            entry.title,
            Type.BODY,
            if (isCurrent) palette.ink else palette.muted,
            if (isCurrent) Typeface.BOLD else Typeface.NORMAL,
            serif = true,
        ).apply {
            // Depth is capped: a deeply nested outline would otherwise indent its titles off-screen.
            val indent = dpv(Gap.MD) + dpv(Gap.MD) * entry.depth.coerceAtMost(3)
            setPadding(indent, dpv(Gap.SM), dpv(Gap.MD), dpv(Gap.SM))
            if (isCurrent) setBackgroundColor(palette.highlight)
            isClickable = true
            isFocusable = true
            contentDescription = if (isCurrent) "${entry.title}, current section" else entry.title
            setOnClickListener { onClick() }
        }

    private fun View.typefaceBold(bold: Boolean) {
        (this as? TextView)?.typeface = Type.serif(if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}

/**
 * Portrait presentation: the same panel over a scrim. The project has no Material Components
 * dependency and draws its own chrome, so this is a plain view rather than a bottom sheet dialog.
 */
class ReaderOutlineOverlay(
    context: Context,
    palette: AppPalette,
    onSelect: (Int) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    val panel = ReaderOutlinePanel(context, palette, onSelect, onClose = { onDismiss() })

    init {
        setBackgroundColor(SCRIM)
        isClickable = true
        setOnClickListener { onDismiss() }
        panel.background = surfaceShape(palette.surface, Radius.SHEET)
        // Stops taps inside the panel from reaching the scrim behind it.
        panel.isClickable = true
        addView(
            panel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.BOTTOM
                topMargin = dpv(96)
            },
        )
    }

    private companion object {
        const val SCRIM = 0x99000000.toInt()
    }
}
