package app.narratify

import java.io.StringReader
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pronunciation for words the dictionary does not list verbatim. Spelling a word out letter by
 * letter is the last resort, not the first, because prose is full of inflected forms and names.
 */
class EnglishPronunciationTest {
    private val dictionary = CmuDictionaryEncoder.load(
        StringReader(
            """
            cat K AE1 T
            glide G L AY1 D
            watch W AA1 CH
            stop S T AA1 P
            quick K W IH1 K
            """.trimIndent() + "\n",
        ),
    )
    private val encoder = EnglishArpabetEncoder(dictionary)

    @Test
    fun regularPluralIsTheBaseWordPlusItsSpokenEnding() {
        assertEquals(listOf("K", "AE1", "T", "S"), encoder.encode("cats", WordContext()))
        assertEquals(listOf("G", "L", "AY1", "D", "Z"), encoder.encode("glides", WordContext()))
        assertEquals(listOf("W", "AA1", "CH", "IH0", "Z"), encoder.encode("watches", WordContext()))
    }

    @Test
    fun pastAndProgressiveFormsReuseTheBaseWord() {
        assertEquals(listOf("W", "AA1", "CH", "T"), encoder.encode("watched", WordContext()))
        assertEquals(listOf("G", "L", "AY1", "D", "IH0", "D"), encoder.encode("glided", WordContext()))
        assertEquals(listOf("S", "T", "AA1", "P", "T"), encoder.encode("stopped", WordContext()))
        assertEquals(listOf("G", "L", "AY1", "D", "IH0", "NG"), encoder.encode("gliding", WordContext()))
    }

    @Test
    fun possessiveAndAdverbFormsReuseTheBaseWord() {
        assertEquals(listOf("K", "AE1", "T", "S"), encoder.encode("cat's", WordContext()))
        assertEquals(listOf("K", "W", "IH1", "K", "L", "IY0"), encoder.encode("quickly", WordContext()))
    }

    @Test
    fun unknownWordsAreSoundedOutFromTheirSpelling() {
        assertEquals(listOf("B", "L", "AE1", "N", "T"), encoder.encode("blant", WordContext()))
        assertEquals(listOf("S", "T", "EY1", "N"), encoder.encode("stane", WordContext()))
        assertEquals(listOf("F", "L", "IY1", "B"), encoder.encode("fleeb", WordContext()))
        assertEquals(listOf("SH", "OW1", "P"), encoder.encode("shope", WordContext()))
        assertEquals(listOf("B", "R", "IH1", "N", "D", "AH0", "L"), encoder.encode("brindle", WordContext()))
        assertEquals(listOf("R", "IH1", "P", "AH0", "L"), encoder.encode("ripple", WordContext()))
    }

    @Test
    fun shortAllCapsInitialismsAreStillSpelled() {
        assertEquals(CmuDictionaryEncoder.spell("fbi"), encoder.encode("FBI", WordContext()))
    }

    @Test
    fun nounAndVerbStressPairsFollowTheWordBeforeThem() {
        val encoder = EnglishArpabetEncoder(
            CmuDictionaryEncoder.load(StringReader("record R EH1 K ER0 D\nrecord(2) R IH0 K AO1 R D\n")),
        )

        assertEquals(listOf("R", "EH1", "K", "ER0", "D"), encoder.encode("record", WordContext(previous = "the")))
        assertEquals(listOf("R", "IH0", "K", "AO1", "R", "D"), encoder.encode("record", WordContext(previous = "to")))
    }

    @Test
    fun vowelHeteronymsFollowTheWordsAroundThem() {
        val encoder = EnglishArpabetEncoder(
            CmuDictionaryEncoder.load(
                StringReader(
                    "read R IY1 D\nread(2) R EH1 D\n" +
                        "live L IH1 V\nlive(2) L AY1 V\n" +
                        "use Y UW1 S\nuse(2) Y UW1 Z\n" +
                        "wound W UW1 N D\nwound(2) W AW1 N D\n",
                ),
            ),
        )

        assertEquals(listOf("R", "EH1", "D"), encoder.encode("read", WordContext(previous = "had")))
        assertEquals(listOf("R", "IY1", "D"), encoder.encode("read", WordContext(previous = "will")))
        assertEquals(listOf("L", "AY1", "V"), encoder.encode("live", WordContext(previous = "a")))
        assertEquals(listOf("L", "IH1", "V"), encoder.encode("live", WordContext(previous = "they")))
        assertEquals(listOf("Y", "UW1", "S"), encoder.encode("use", WordContext(previous = "the")))
        assertEquals(listOf("Y", "UW1", "Z"), encoder.encode("use", WordContext(previous = "to")))
        assertEquals(listOf("W", "AW1", "N", "D"), encoder.encode("wound", WordContext(previous = "he", next = "up")))
        assertEquals(listOf("W", "UW1", "N", "D"), encoder.encode("wound", WordContext(previous = "the", next = "healed")))
    }

    @Test
    fun functionWordsReduceInRunningSpeechButKeepTheirVowelBeforeAPause() {
        val encoder = EnglishArpabetEncoder(
            CmuDictionaryEncoder.load(StringReader("to T UW1\nto(2) T AH0\nsee S IY1\n")),
        )

        assertEquals(listOf("T", "AH0"), encoder.encode("to", WordContext(next = "see")))
        assertEquals(listOf("T", "UW1"), encoder.encode("to", WordContext(followedByPunctuation = true)))
    }
}
