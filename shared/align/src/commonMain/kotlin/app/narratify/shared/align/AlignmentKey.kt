package app.narratify.shared.align

/**
 * The single place where the book's spelling and the transcriber's spelling are reconciled.
 *
 * Folding is deliberately lossy and deliberately cheap: it runs once over every token of a book
 * and once over every token of its narration, so anything clever here is paid for a hundred
 * thousand times. An apostrophe between two letters is kept, folded to one canonical form so that
 * a typesetter's "don’t" and a transcriber's "don't" agree. Deleting it instead would make "we'll"
 * a homograph of "well", and a homograph is how a confident anchor lands on the wrong second.
 *
 * Both sides are canonically composed first, so a precomposed "café" and a decomposed one fold to
 * the same key. Note this composes rather than strips: "café" and "cafe" remain different words,
 * which is correct — a narrator who says one did not say the other.
 */
object AlignmentKey {
    fun fold(value: String): String {
        val composed = value.canonicallyComposed()
        return buildString(composed.length) {
            for (index in composed.indices) {
                val character = composed[index]
                when {
                    character.isLetterOrDigit() -> append(character.lowercaseChar())
                    character.isApostrophe() && composed.isInsideWord(index) -> append('\'')
                }
            }
        }
    }

    private fun Char.isApostrophe(): Boolean = this == '\'' || this == '’' || this == 'ʼ'

    /** An apostrophe is part of a word only between two letters; elsewhere it is a quotation mark. */
    private fun String.isInsideWord(index: Int): Boolean =
        index > 0 && index + 1 < length && this[index - 1].isLetterOrDigit() && this[index + 1].isLetterOrDigit()
}
