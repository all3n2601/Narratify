package app.narratify.playback

/** One chapter and the spine item it is believed to narrate. */
data class ChapterMappingRow(
    val chapter: AudioChapter,
    val spineIndex: Int?,
    val confirmed: Boolean,
)

/**
 * Which chapter narrates which part of the book.
 *
 * The first guess is positional, which is right for most audiobooks and wrong for any with front
 * matter, a narrator's introduction, or two printed chapters read as one. Since no rule fixes
 * every case, the mapping is something a reader can correct rather than something the app insists
 * on: [offsetBy] handles the common whole-book shift in one gesture, [remap] handles the rest, and
 * a corrected row is marked so a later shift does not undo the correction.
 */
data class ChapterMapping(val rows: List<ChapterMappingRow>) {
    val hasAnySpineLink: Boolean get() = rows.any { it.spineIndex != null }

    fun offsetBy(delta: Int): ChapterMapping = ChapterMapping(
        rows.map { row ->
            if (row.confirmed) {
                row
            } else {
                // A shifted row that falls outside the book is cleared rather than clamped:
                // clamping would stack several chapters on one spine item and look intentional.
                row.copy(spineIndex = row.spineIndex?.plus(delta)?.takeIf { it >= 0 })
            }
        },
    )

    fun remap(chapterIndex: Int, spineIndex: Int?): ChapterMapping = ChapterMapping(
        rows.map { row ->
            if (row.chapter.index == chapterIndex) row.copy(spineIndex = spineIndex, confirmed = true) else row
        },
    )

    fun chapterForSpine(spineIndex: Int): ChapterMappingRow? = rows.firstOrNull { it.spineIndex == spineIndex }

    companion object {
        fun positional(chapters: List<AudioChapter>, spineCount: Int): ChapterMapping = ChapterMapping(
            chapters.map { chapter ->
                ChapterMappingRow(
                    chapter = chapter,
                    spineIndex = chapter.index.takeIf { it < spineCount },
                    confirmed = false,
                )
            },
        )
    }
}
