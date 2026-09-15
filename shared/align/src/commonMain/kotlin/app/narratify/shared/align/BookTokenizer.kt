package app.narratify.shared.align

import app.narratify.shared.text.PreparedTtsChunk
import app.narratify.shared.text.TextPreparationOptions
import app.narratify.shared.text.TtsTextPreparer
import com.narratify.domain.ChunkId
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.SpokenToken
import com.narratify.domain.SpokenTokenFlag

/**
 * One word of the book as the narrator would have said it, with everything needed to point back
 * at the printed word it came from.
 */
data class BookToken(
    val index: Int,
    val key: String,
    val chunkId: ChunkId,
    val token: SpokenToken,
) {
    init {
        require(index >= 0) { "Book token index must be non-negative" }
        require(key.isNotEmpty()) { "Book token key must not be empty" }
    }
}

/**
 * Flattens the TTS spoken-token map into the sequence alignment compares against.
 *
 * Alignment deliberately reuses the TTS front end rather than tokenizing the book a second time.
 * A narrator reads "seven", not "7", and `TtsTextPreparer` is already the component that knows
 * that — a second tokenizer here would drift from it and silently lose matches on exactly the
 * tokens (numbers, abbreviations, roman numerals) that make the best anchors, because they are
 * the rarest words on the page.
 */
object BookTokenizer {
    fun tokenize(
        spans: List<SourceTextSpan>,
        options: TextPreparationOptions = TextPreparationOptions(),
    ): List<BookToken> = fromChunks(TtsTextPreparer.prepare(spans, options))

    fun fromChunks(chunks: List<PreparedTtsChunk>): List<BookToken> = buildList {
        for (chunk in chunks) {
            val chunkId = ChunkId(chunk.id)
            for (token in chunk.tokens) {
                if (SpokenTokenFlag.PUNCTUATION in token.flags) continue
                val key = AlignmentKey.fold(token.spokenText)
                if (key.isEmpty()) continue
                add(BookToken(index = size, key = key, chunkId = chunkId, token = token))
            }
        }
    }
}
