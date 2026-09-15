package app.narratify

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One preparation path for every narrated surface: plain text, EPUB, system voice, neural voice. */
class SpeechPlannerTest {
    @Test
    fun passageIsNormalizedForSpeechButStillPointsAtTheOriginalText() {
        val text = "Dr. Mina paid $12.50 on Jan. 5."

        val passages = SpeechPlanner.plan(text)

        val speech = passages.joinToString(" ") { it.text }
        assertTrue(speech.contains("doctor"), speech)
        assertTrue(speech.contains("twelve dollars and fifty cents"), speech)
        assertTrue(speech.contains("January"), speech)
        val spoken = passages.firstNotNullOf { passage ->
            passage.tokens.firstOrNull { text.substring(it.sourceStart, it.sourceEnd) == "$12.50" }
                ?.let { passage.text.substring(it.spokenStart, it.spokenEnd) }
        }
        assertEquals("twelve dollars and fifty cents", spoken)
    }

    @Test
    fun planningFromAnOffsetKeepsOffsetsInTheWholeDocument() {
        val text = "First sentence. Second sentence."

        val passage = SpeechPlanner.plan(text, fromOffset = 16).single()

        assertEquals("Second", text.substring(passage.tokens.first().sourceStart, passage.tokens.first().sourceEnd))
        assertEquals(16, passage.sourceStart)
    }

    @Test
    fun everyPassageStaysInsideTheModelContextBudget() {
        val text = (1..40).joinToString(" ") { "The quick brown fox jumped over $it lazy dogs and kept running." }

        val passages = SpeechPlanner.plan(text)

        assertTrue(passages.isNotEmpty())
        assertTrue(passages.all { it.text.length <= SpeechPlanner.MAXIMUM_PASSAGE_CHARACTERS }, "a passage was too long")
    }

    @Test
    fun planningFromInsideASentenceBeginsAtThatSentence() {
        val text = "First sentence. Second sentence here."

        val passage = SpeechPlanner.plan(text, fromOffset = 24).single()

        assertEquals(16, passage.sourceStart)
        assertTrue(passage.text.startsWith("Second"), passage.text)
    }

    @Test
    fun openingPassageIsShortenedSoTheFirstWordsArriveSooner() {
        val text = "The harbour bell rang twice before the tide turned, and the lamplighter walked the long pier counting the boats that had not come home."

        val passages = SpeechPlanner.plan(text)

        assertTrue(passages.size >= 2, "the opening sentence was not split")
        assertTrue(
            passages.first().text.length <= SpeechPlanner.OPENING_PASSAGE_CHARACTERS,
            passages.first().text,
        )
        assertEquals(0, passages.first().sourceStart)
    }

    @Test
    fun onlyTheOpeningPassageIsShortened() {
        val text = "The harbour bell rang twice before the tide turned, and the lamplighter walked the long pier counting the boats that had not come home."

        val passages = SpeechPlanner.plan(text)

        assertTrue(
            passages[1].text.length > SpeechPlanner.OPENING_PASSAGE_CHARACTERS,
            "later passages should keep their full size",
        )
    }

    @Test
    fun shorteningTheOpeningLosesNoWords() {
        val text = "The harbour bell rang twice before the tide turned, and the lamplighter walked the long pier counting the boats that had not come home."

        val passages = SpeechPlanner.plan(text)

        val spoken = passages.flatMap { passage -> passage.tokens.map { text.substring(it.sourceStart, it.sourceEnd) } }
        val expected = SpeechPlanner.plan(text.replace(",", ""))
            .flatMap { passage -> passage.tokens.map { passage.text.substring(it.spokenStart, it.spokenEnd) } }
        assertEquals(expected.size, spoken.filter { it != "," }.size)
        assertEquals("home", spoken.last { it.first().isLetter() })
    }

    @Test
    fun punctuationIsMarkedSoHighlightingCanStayOnWords() {
        val text = "She waited, then left."

        val passage = SpeechPlanner.plan(text).first()

        val comma = passage.tokens.first { text.substring(it.sourceStart, it.sourceEnd) == "," }
        val word = passage.tokens.first { text.substring(it.sourceStart, it.sourceEnd) == "waited" }
        assertTrue(comma.isPunctuation)
        assertFalse(word.isPunctuation)
    }

    @Test
    fun paragraphBreakGetsASilentBeatAndNothingElseDoes() {
        val text = "First paragraph ends here.\n\nSecond paragraph starts here."

        val passages = SpeechPlanner.plan(text)

        assertEquals(2, passages.size)
        assertEquals(SpeechPlanner.PARAGRAPH_PAUSE_MILLIS, passages.first().pauseAfterMillis)
        assertEquals(0, passages.last().pauseAfterMillis)
    }
}
