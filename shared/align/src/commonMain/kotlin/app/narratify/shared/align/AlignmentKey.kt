package app.narratify.shared.align

/**
 * The single place where the book's spelling and the transcriber's spelling are reconciled.
 *
 * Folding is deliberately lossy and deliberately cheap: it runs once over every token of a book
 * and once over every token of its narration, so anything clever here is paid for a hundred
 * thousand times. A mark between two letters is kept and canonicalised, so a typesetter's "don’t"
 * and a transcriber's "don't" agree. The set is exactly the one `shared/text`'s lexer treats as
 * word-internal — apostrophe, right single quote, hyphen, period — because deleting any of them
 * collapses two real words onto one key: "we'll" onto "well", "re-form" onto "reform", "U.S" onto
 * "us". That costs matches, since a recognizer rarely writes the hyphen the page does. It is the
 * right trade here: a missed match lowers the granularity the result may claim, while a collision
 * puts a confident highlight on the wrong second.
 *
 * Both sides are canonically composed first, so a precomposed "café" and a decomposed one fold to
 * the same key. Note this composes rather than strips: "café" and "cafe" remain different words,
 * which is correct — a narrator who says one did not say the other.
 *
 * U+02BC MODIFIER LETTER APOSTROPHE is deliberately absent. Unicode classifies it as a letter, the
 * lexer keeps it as one, and in Uzbek and several romanizations it is a letter rather than
 * punctuation. Folding it to an apostrophe would erase a real distinction in those languages.
 */
object AlignmentKey {
    /**
     * The marks `shared/text`'s lexer keeps inside a word lexeme, each canonicalised to one form.
     * Folding preserves exactly this set so that two components cannot disagree about where a
     * word ends.
     */
    private val WORD_INTERNAL_MARKS = mapOf(
        '\'' to '\'', '’' to '\'',
        '-' to '-',
        '.' to '.',
    )

    fun fold(value: String): String {
        val composed = value.canonicallyComposed()
        return buildString(composed.length) {
            for (index in composed.indices) {
                val character = composed[index]
                val mark = WORD_INTERNAL_MARKS[character]
                when {
                    character.isLetterOrDigit() -> append(character.lowercaseChar())
                    mark != null && composed.isInsideWord(index) -> append(mark)
                }
            }
        }
    }

    /** A mark is part of a word only between two letters; elsewhere it is punctuation. */
    private fun String.isInsideWord(index: Int): Boolean =
        index > 0 && index + 1 < length && this[index - 1].isLetterOrDigit() && this[index + 1].isLetterOrDigit()
}
