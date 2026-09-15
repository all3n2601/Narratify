package app.narratify.shared.align

/**
 * Canonical composition, NFC.
 *
 * An EPUB and a speech recognizer are independent pipelines with no shared normalization form, so
 * the same word can arrive precomposed from one and decomposed from the other. Composing both
 * before folding is what makes the comparison about the word rather than about its encoding. It
 * matters most in languages where diacritics carry meaning: decomposed Vietnamese folds "mã",
 * "mạ", and "mà" onto one key, which would make those texts unmatchable.
 */
internal expect fun String.canonicallyComposed(): String
