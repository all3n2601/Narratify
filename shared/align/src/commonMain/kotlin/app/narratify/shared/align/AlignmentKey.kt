package app.narratify.shared.align

/**
 * The single place where the book's spelling and the transcriber's spelling are reconciled.
 *
 * Folding is deliberately lossy and deliberately cheap: it runs once over every token of a book
 * and once over every token of its narration, so anything clever here is paid for a hundred
 * thousand times. Dropping everything that is not a letter or a digit also drops the apostrophe,
 * which is the one character typesetters and transcribers reliably disagree about.
 */
object AlignmentKey {
    fun fold(value: String): String = buildString(value.length) {
        for (character in value) {
            if (character.isLetterOrDigit()) append(character.lowercaseChar())
        }
    }
}
