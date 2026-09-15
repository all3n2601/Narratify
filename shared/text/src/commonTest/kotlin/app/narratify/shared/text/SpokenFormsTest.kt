package app.narratify.shared.text

import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator
import com.narratify.domain.ResourceId
import com.narratify.domain.SemanticRole
import com.narratify.domain.SourceRange
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.SourceTextSpan as Span
import com.narratify.domain.TextDirection
import com.narratify.domain.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

/** Written forms that a reader pronounces differently from their literal characters. */
class SpokenFormsTest {
    @Test
    fun ordinalSuffixesAreSpokenAsOrdinals() {
        val chunk = prepare("She finished 3rd on the 22nd.").single()

        assertEquals("third", spoken(chunk, "3rd"))
        assertEquals("twenty second", spoken(chunk, "22nd"))
    }

    @Test
    fun fourDigitYearsAreSpokenInPairs() {
        val chunk = prepare("Between 1905 and 1984 and 1900 and 2010 and 2005.").single()

        assertEquals("nineteen oh five", spoken(chunk, "1905"))
        assertEquals("nineteen eighty four", spoken(chunk, "1984"))
        assertEquals("nineteen hundred", spoken(chunk, "1900"))
        assertEquals("twenty ten", spoken(chunk, "2010"))
        assertEquals("two thousand five", spoken(chunk, "2005"))
    }

    @Test
    fun quantitiesThatOnlyLookLikeYearsKeepCardinalForm() {
        val chunk = prepare("It cost $1984 and weighed 1,984 grams and 19845 grains.").single()

        assertEquals("one thousand nine hundred eighty four dollars", spoken(chunk, "$1984"))
        assertEquals("one thousand nine hundred eighty four", spoken(chunk, "1,984"))
        assertEquals("nineteen thousand eight hundred forty five", spoken(chunk, "19845"))
    }

    @Test
    fun sentenceFinalNumberDoesNotSwallowThePeriod() {
        val chunk = prepare("They waited 12.").single()

        assertEquals("twelve", spoken(chunk, "12"))
    }

    @Test
    fun clockTimesAreSpokenAsTimes() {
        val chunk = prepare("Trains leave at 3:30 and 10:05 and 7:00.").single()

        assertEquals("three thirty", spoken(chunk, "3:30"))
        assertEquals("ten oh five", spoken(chunk, "10:05"))
        assertEquals("seven o'clock", spoken(chunk, "7:00"))
    }

    @Test
    fun monthAbbreviationsAreSpokenInFull() {
        val chunk = prepare("Filed Jan. 5 and Sept. 9 and Dec. 24.").single()

        assertEquals("January", spoken(chunk, "Jan."))
        assertEquals("September", spoken(chunk, "Sept."))
        assertEquals("December", spoken(chunk, "Dec."))
    }

    @Test
    fun structuralRomanNumeralsAreSpokenAsNumbers() {
        val chunk = prepare("Chapter XIV opens part IX.").single()

        assertEquals("fourteen", spoken(chunk, "XIV"))
        assertEquals("nine", spoken(chunk, "IX"))
    }

    @Test
    fun romanLookalikesOutsideStructureKeepTheirLetters() {
        val chunk = prepare("The XL shirt from DC cost more than the CD.").single()

        assertEquals("XL", spoken(chunk, "XL"))
        assertEquals("DC", spoken(chunk, "DC"))
        assertEquals("CD", spoken(chunk, "CD"))
    }

    private fun spoken(chunk: PreparedTtsChunk, display: String) =
        chunk.tokens.first { it.displayText == display }.spokenText

    private fun prepare(text: String, language: String = "en-US") =
        TtsTextPreparer.prepare(listOf(span(text, language = language)))

    private fun span(text: String, language: String? = "en-US"): Span {
        val resource = ResourceId("chapter")
        return SourceTextSpan(
            displayText = text,
            locator = PublicationLocator(PublicationId("book"), resourceId = resource, resourceIndex = 0),
            semanticRole = SemanticRole.PARAGRAPH,
            language = language,
            direction = TextDirection.LEFT_TO_RIGHT,
            sourceRanges = listOf(SourceRange(resource, TextRange(0, text.length))),
        )
    }
}
