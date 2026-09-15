package app.narratify.shared.align

/**
 * The single place where the book's spelling and the transcriber's spelling are reconciled.
 *
 * Folding is deliberately lossy and deliberately cheap: it runs once over every token of a book
 * and once over every token of its narration, so anything clever here is paid for a hundred
 * thousand times. Dropping everything that is not a letter or a digit also drops the apostrophe,
 * which is the one character typesetters and transcribers reliably disagree about.
 *
 * Known limitation: text is compared in whatever normalization form it arrives in. A precomposed
 * "café" and a decomposed one fold to different keys, because the combining mark is not a letter
 * or a digit and is dropped while the precomposed letter is kept. Both sides are normally NFC, and
 * when they are not the cost is a lower matched ratio — which lowers the granularity the result is
 * allowed to claim rather than putting the highlight in the wrong place. Normalizing would need an
 * expect/actual per platform and is deliberately deferred.
 */
object AlignmentKey {
    fun fold(value: String): String = buildString(value.length) {
        for (character in value) {
            if (character.isLetterOrDigit()) append(character.lowercaseChar())
        }
    }
}
