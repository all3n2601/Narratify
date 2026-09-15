package app.narratify.shared.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** Narration always begins at a sentence, never halfway through one. */
class SentenceBoundaryTest {
    @Test
    fun offsetInsideASentenceSnapsBackToItsStart() {
        val text = "First sentence. Second sentence here."

        assertEquals(16, TtsTextPreparer.sentenceStart(text, 24))
    }

    @Test
    fun offsetAlreadyOnASentenceStaysWhereItIs() {
        val text = "First sentence. Second sentence here."

        assertEquals(16, TtsTextPreparer.sentenceStart(text, 16))
        assertEquals(0, TtsTextPreparer.sentenceStart(text, 0))
    }

    @Test
    fun abbreviationInsideTheSentenceIsNotMistakenForItsStart() {
        val text = "The bell rang. Dr. Mina arrived at 8 a.m. and waited."

        assertEquals(15, TtsTextPreparer.sentenceStart(text, 40))
    }
}
