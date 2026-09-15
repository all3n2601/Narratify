package app.narratify

import android.content.Context
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.narratify.playback.ChapterMapping

/**
 * Shows which chapter is believed to narrate which part of the book, and lets the reader fix it.
 *
 * The mapping starts as a guess in reading order, which is right for most audiobooks and wrong for
 * any with front matter or a narrator's introduction. Rather than hiding that, the guess is shown
 * before it is used: one control shifts the whole book, which fixes the common case in a tap, and
 * a row can be pointed somewhere else or cleared entirely. Nothing is stored until Save.
 */
class NarrationMappingScreen(
    context: Context,
    private val bookTitle: String,
    private val narrationName: String,
    private val spineTitles: List<String>,
    initialMapping: ChapterMapping,
    private val onSave: (ChapterMapping) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {
    private val theme = enchantedLibraryPalette()
    private var mapping = initialMapping
    private val rowHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(theme.canvas)
        addView(
            ScrollView(context).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(Gap.LG), dp(Gap.LG), dp(Gap.LG), dp(Gap.XL))
                    addView(context.eyebrow("Check the chapters", theme.accent))
                    addView(
                        context.styledText(bookTitle, Type.TITLE, theme.ink, Typeface.BOLD, serif = true),
                        stack(Gap.XS),
                    )
                    addView(
                        context.styledText(
                            "$narrationName was paired with this book. Narratify matched its chapters in " +
                                "order. Check them and fix anything that looks wrong.",
                            Type.BODY,
                            theme.muted,
                        ),
                        stack(Gap.SM),
                    )
                    addView(offsetControls(), stack(Gap.LG))
                    addView(rowHost, stack(Gap.LG))
                    addView(context.primaryButton(theme, "Save") { onSave(mapping) }, stack(Gap.LG))
                    addView(context.secondaryButton(theme, "Cancel") { onCancel() }, stack(Gap.SM))
                })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        renderRows()
    }

    private fun offsetControls(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(context.primaryButton(theme, "Audio starts earlier") { shift(-1) })
        addView(
            context.primaryButton(theme, "Audio starts later") { shift(1) },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(Gap.SM)
            },
        )
    }

    private fun shift(delta: Int) {
        mapping = mapping.offsetBy(delta)
        renderRows()
    }

    private fun renderRows() {
        rowHost.removeAllViews()
        if (mapping.rows.isEmpty()) {
            rowHost.addView(
                context.styledText(
                    "This file has no chapter marks, so there is nothing to match. You can still " +
                        "listen to it alongside the book.",
                    Type.BODY,
                    theme.muted,
                ),
            )
            return
        }
        for (row in mapping.rows) {
            val chapterName = row.chapter.title.ifBlank { "Chapter ${row.chapter.index + 1}" }
            val target = row.spineIndex?.let { spineTitles.getOrNull(it) ?: "Section ${it + 1}" }
                ?: "Not matched"
            rowHost.addView(
                context.bookActionRow(
                    theme,
                    title = "$chapterName → $target",
                    detail = if (row.confirmed) "You set this" else "Matched in order",
                    color = if (row.spineIndex == null) theme.muted else theme.ink,
                ) { showRowPicker(row.chapter.index) },
                stack(Gap.XS),
            )
        }
    }

    private fun showRowPicker(chapterIndex: Int) {
        val labels = listOf("Not matched") + spineTitles.mapIndexed { index, title ->
            title.ifBlank { "Section ${index + 1}" }
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Which part of the book?")
            .setItems(labels.toTypedArray()) { _, which ->
                mapping = mapping.remap(chapterIndex, spineIndex = (which - 1).takeIf { it >= 0 })
                renderRows()
            }
            .show()
    }

    private fun stack(topDp: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(topDp)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
