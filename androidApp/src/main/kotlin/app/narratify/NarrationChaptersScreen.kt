package app.narratify

import android.content.Context
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.narratify.shared.data.StoredChapter

/**
 * The chapters of a paired narration.
 *
 * A row says where it came from. A chapter Narratify matched in reading order is a guess, and
 * saying so is what keeps a wrong jump legible as a mapping to fix rather than as a bug in the
 * reader — the same reason the aligner lowers its granularity instead of overstating a match.
 */
class NarrationChaptersScreen(
    context: Context,
    private val bookTitle: String,
    private val chapters: List<StoredChapter>,
    private val spineTitles: List<String>,
    private val onPlay: (StoredChapter) -> Unit,
    private val onEditMapping: () -> Unit,
) : FrameLayout(context) {
    private val theme = enchantedLibraryPalette()

    init {
        setBackgroundColor(theme.canvas)
        addView(
            ScrollView(context).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(Gap.LG), dp(Gap.LG), dp(Gap.LG), dp(Gap.XL))
                    addView(context.eyebrow("Chapters", theme.accent))
                    addView(
                        context.styledText(bookTitle, Type.TITLE, theme.ink, Typeface.BOLD, serif = true),
                        stack(Gap.XS),
                    )
                    if (chapters.isEmpty()) {
                        addView(
                            context.styledText(
                                "This narration has no chapter marks, so it plays as one piece.",
                                Type.BODY,
                                theme.muted,
                            ),
                            stack(Gap.SM),
                        )
                    } else {
                        for (chapter in chapters) {
                            val name = chapter.title?.takeIf(String::isNotBlank)
                                ?: "Chapter ${chapter.index + 1}"
                            val target = chapter.spineIndex?.let {
                                spineTitles.getOrNull(it)?.takeIf(String::isNotBlank) ?: "Section ${it + 1}"
                            }
                            addView(
                                context.bookActionRow(
                                    theme,
                                    title = name,
                                    detail = when {
                                        target == null -> "Audio only — not matched to the book"
                                        chapter.confirmed -> "Opens $target"
                                        else -> "Opens $target — matched in order"
                                    },
                                    color = theme.ink,
                                ) { onPlay(chapter) },
                                stack(Gap.XS),
                            )
                        }
                        addView(context.secondaryButton(theme, "Fix the matching") { onEditMapping() }, stack(Gap.LG))
                    }
                })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun stack(topDp: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(topDp)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
