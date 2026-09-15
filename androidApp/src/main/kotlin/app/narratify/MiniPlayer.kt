package app.narratify

import java.util.Locale

/**
 * Decides what the bottom bar shows. Kept separate from the view so the rules — which source
 * wins, when a bar is worth showing at all — are testable without a device.
 */
object MiniPlayer {
    enum class Source { NARRATION, EPUB, AUDIOBOOK }

    data class AudiobookSnapshot(
        val publicationId: String,
        val title: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    )

    data class State(
        val source: Source,
        val id: String,
        val title: String,
        val subtitle: String,
        val playing: Boolean,
        val progress: Float? = null,
    )

    fun resolve(
        narration: ReaderNarration.Session?,
        epub: EpubNarration.Session?,
        audiobook: AudiobookSnapshot?,
    ): State? {
        narration?.takeIf { it.state in ACTIVE_NARRATION }?.let { session ->
            return State(
                source = Source.NARRATION,
                id = session.bookId,
                title = session.title.ifBlank { "Reading aloud" },
                subtitle = session.message,
                playing = session.state == ReaderTtsController.State.PLAYING,
            )
        }
        epub?.let { session ->
            return State(
                source = Source.EPUB,
                id = session.bookId,
                title = session.title.ifBlank { "Reading aloud" },
                subtitle = session.message,
                playing = session.playing,
            )
        }
        // A stopped audiobook sitting at zero is not something the reader left running.
        audiobook?.takeIf { it.playing || it.positionMs > 0 }?.let { book ->
            return State(
                source = Source.AUDIOBOOK,
                id = book.publicationId,
                title = book.title,
                subtitle = if (book.durationMs > 0) {
                    "${clock(book.positionMs)} / ${clock(book.durationMs)}"
                } else {
                    clock(book.positionMs)
                },
                playing = book.playing,
                progress = if (book.durationMs > 0) {
                    (book.positionMs.toFloat() / book.durationMs).coerceIn(0f, 1f)
                } else {
                    null
                },
            )
        }
        return null
    }

    private val ACTIVE_NARRATION = setOf(
        ReaderTtsController.State.PLAYING,
        ReaderTtsController.State.PAUSED,
    )

    private fun clock(millis: Long): String {
        val totalSeconds = (millis / 1_000).coerceAtLeast(0)
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
