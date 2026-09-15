package app.narratify

import java.io.StringReader
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KokoroEnglishPhonemizerTest {
    private val phones = mapOf(
        "hello" to listOf("HH", "AH0", "L", "OW1"),
        "world" to listOf("W", "ER1", "L", "D"),
    )
    private val subject = KokoroEnglishPhonemizer { word, _ -> phones[word].orEmpty() }

    @Test
    fun retainsSourceRangesAcrossWordsAndPunctuation() {
        val result = subject.encode("Hello world!")
        assertTrue(result.tokenIds.isNotEmpty())
        assertEquals(0..4, result.tokenSourceRanges.first())
        assertTrue(result.tokenSourceRanges.any { it == 6..10 })
        assertEquals(11..11, result.tokenSourceRanges.last())
        assertEquals(result.tokenIds.size, result.tokenSourceRanges.size)
    }

    @Test
    fun passageLongerThanTheModelContextFailsInsteadOfLosingWords() {
        val overflow = List(200) { "hello" }.joinToString(" ")

        val error = assertFailsWith<PassageTooLongForModel> { subject.encode(overflow) }

        assertEquals(510, error.limit)
        assertTrue(error.message!!.contains("510"))
    }

    @Test
    fun passageThatFitsTheModelContextIsEncodedWhole() {
        val result = subject.encode(List(50) { "hello" }.joinToString(" "))

        assertEquals(result.tokenIds.size, result.tokenSourceRanges.size)
        assertTrue(result.tokenIds.size in 200..510)
    }

    @Test
    fun dictionaryProducesKokoroTokensAndFallsBackForUnknownWords() {
        val dictionary = CmuDictionaryEncoder.load(StringReader("reads R IY1 D Z\noffline AO1 F L AY2 N\n"))
        val result = KokoroEnglishPhonemizer(EnglishArpabetEncoder(dictionary)).encode("Narratify reads offline.")

        assertTrue(result.tokenIds.size > 10)
        assertEquals(0..8, result.tokenSourceRanges.first())
    }

    /**
     * Golden token ids from the pinned Kokoro vocab (hexgrad/Kokoro-82M config.json) and the
     * Misaki phone table: A = eɪ, I = aɪ, W = aʊ, Y = ɔɪ, O = oʊ.
     */
    @Test
    fun mapsDiphthongsToTheKokoroSymbolTheyActuallySpell() {
        val phones = mapOf(
            "day" to listOf("D", "EY1"),
            "my" to listOf("M", "AY1"),
            "boy" to listOf("B", "OY1"),
        )
        val subject = KokoroEnglishPhonemizer { word, _ -> phones[word].orEmpty() }

        val result = subject.encode("day my boy")

        assertEquals(
            listOf(46L, 156L, 24L, 16L, 55L, 156L, 25L, 16L, 44L, 156L, 41L),
            result.tokenIds.toList(),
        )
    }

    @Test
    fun heteronymsAreResolvedFromTheNeighbouringWords() {
        val dictionary = CmuDictionaryEncoder.load(
            StringReader("read R IY1 D\nread(2) R EH1 D\nhad HH AE1 D\nwill W IH1 L\n"),
        )
        val subject = KokoroEnglishPhonemizer(EnglishArpabetEncoder(dictionary))

        // ɹ ˈ ɛ d for the past tense, ɹ ˈ i d for the present.
        assertEquals(listOf(123L, 156L, 86L, 46L), subject.encode("had read").tokenIds.takeLast(4))
        assertEquals(listOf(123L, 156L, 51L, 46L), subject.encode("will read").tokenIds.takeLast(4))
    }

    @Test
    fun unstressedRColouredVowelUsesTheReducedSymbol() {
        val phones = mapOf("butter" to listOf("B", "AH1", "T", "ER0"), "her" to listOf("HH", "ER1"))
        val subject = KokoroEnglishPhonemizer { word, _ -> phones[word].orEmpty() }

        // b ˈ ʌ t ɚ  — the final syllable is unstressed, so ɚ rather than ɜɹ.
        assertEquals(listOf(44L, 156L, 138L, 62L, 85L), subject.encode("butter").tokenIds.toList())
        // h ˈ ɜ ɹ — stressed, so the full form is kept.
        assertEquals(listOf(50L, 156L, 87L, 123L), subject.encode("her").tokenIds.toList())
    }
}
