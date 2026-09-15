package app.narratify.shared.align

import com.narratify.domain.PublicationId
import com.narratify.domain.PublicationLocator
import com.narratify.domain.ResourceId
import com.narratify.domain.SemanticRole
import com.narratify.domain.SourceRange
import com.narratify.domain.SourceTextSpan
import com.narratify.domain.TextRange
import java.io.File
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The alignment quality gate.
 *
 * What this measures: whether the aligner recovers known word times from a transcript that has
 * been damaged in the ways real transcripts are damaged. What it does NOT measure: accuracy on
 * real audio. The narration in these fixtures is synthetic — durations are a linear function of
 * word length, there is no narrator, no microphone, and no acoustic model. A green run here means
 * the algorithm is sound, not that read-along works. That question belongs to the real-audio
 * evidence run described in `benchmarks/alignment/README.md`.
 */
class AlignmentGateTest {
    @Serializable
    private data class Gate(
        val medianAbsErrorMs: Long,
        val p95AbsErrorMs: Long,
        val minCoverage: Double,
        val falseSyncToleranceMs: Long,
        val maxFalseSyncOnsets: Int,
    )

    @Serializable
    private data class Case(val description: String, val reference: String, val expectedGranularity: AlignmentGranularity)

    @Serializable
    private data class Onset(val quote: String, val onsetMs: Long)

    @Serializable
    private data class Expected(val granularity: AlignmentGranularity, val onsets: List<Onset>)

    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = File("../../test-fixtures/alignment")
    private val publication = PublicationId("gate")
    private val resource = ResourceId("chapter-1.xhtml")

    /** One span per paragraph, which is the shape an EPUB resource arrives in. */
    private fun spansOf(text: String): List<SourceTextSpan> {
        val spans = mutableListOf<SourceTextSpan>()
        var offset = 0
        for (paragraph in text.split("\n\n")) {
            val trimmed = paragraph.trim()
            if (trimmed.isNotEmpty()) {
                val start = text.indexOf(trimmed, offset)
                spans.add(
                    SourceTextSpan(
                        displayText = trimmed,
                        locator = PublicationLocator(publicationId = publication, resourceId = resource),
                        semanticRole = SemanticRole.PARAGRAPH,
                        language = "en-US",
                        sourceRanges = listOf(SourceRange(resource, TextRange(start, start + trimmed.length))),
                    ),
                )
                offset = start + trimmed.length
            }
        }
        return spans
    }

    /** The index of the first book token of a quoted sentence opening. */
    private fun indexOfQuote(bookTokens: List<BookToken>, quote: String): Int {
        val wanted = quote.split(" ").map(AlignmentKey::fold).filter(String::isNotEmpty)
        val keys = bookTokens.map(BookToken::key)
        for (start in 0..keys.size - wanted.size) {
            if (keys.subList(start, start + wanted.size) == wanted) return start
        }
        return -1
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val position = ((sorted.size - 1) * fraction).toInt()
        return sorted[position]
    }

    @Test
    fun `every fixture case aligns within the gate`() {
        val gate = json.decodeFromString<Gate>(File(fixtures, "gate.json").readText())
        val caseDirs = File(fixtures, "cases").listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
        assertTrue(caseDirs.isNotEmpty(), "no fixture cases found in ${fixtures.absolutePath}")

        val errors = mutableListOf<Long>()
        val coverages = mutableListOf<Double>()
        var falseSync = 0
        val report = StringBuilder()

        for (caseDir in caseDirs) {
            val case = json.decodeFromString<Case>(File(caseDir, "case.json").readText())
            val expected = json.decodeFromString<Expected>(File(caseDir, "expected.json").readText())
            val hypothesis = json.decodeFromString<List<AsrToken>>(File(caseDir, "hypothesis.json").readText())
            val bookTokens = BookTokenizer.tokenize(spansOf(File(caseDir, case.reference).readText()))

            val result = ForcedAligner.align(bookTokens, hypothesis)

            assertEquals(
                case.expectedGranularity,
                result.granularity,
                "${caseDir.name}: ${case.description}",
            )
            if (case.expectedGranularity == AlignmentGranularity.WORD) {
                coverages.add(result.matchedRatio)
            }

            for (onset in expected.onsets) {
                val tokenIndex = indexOfQuote(bookTokens, onset.quote)
                assertTrue(tokenIndex >= 0, "${caseDir.name}: quote not found in the book: ${onset.quote}")
                val error = abs(result.timings[tokenIndex].startMs - onset.onsetMs)
                errors.add(error)
                val claimedWord = result.spans
                    .first { tokenIndex in it.bookTokenStart until it.bookTokenEndExclusive }
                    .granularity == AlignmentGranularity.WORD
                if (claimedWord && error > gate.falseSyncToleranceMs) falseSync += 1
            }
            report.append("${caseDir.name}: granularity=${result.granularity} matched=${result.matchedRatio}\n")
        }

        val sorted = errors.sorted()
        val median = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val coverage = coverages.average()
        report.append("median=${median}ms p95=${p95}ms coverage=$coverage falseSync=$falseSync\n")

        assertTrue(median <= gate.medianAbsErrorMs, "median onset error ${median}ms exceeds ${gate.medianAbsErrorMs}ms\n$report")
        assertTrue(p95 <= gate.p95AbsErrorMs, "p95 onset error ${p95}ms exceeds ${gate.p95AbsErrorMs}ms\n$report")
        assertTrue(coverage >= gate.minCoverage, "coverage $coverage is below ${gate.minCoverage}\n$report")
        assertTrue(
            falseSync <= gate.maxFalseSyncOnsets,
            "$falseSync onsets claimed word accuracy while being more than ${gate.falseSyncToleranceMs}ms wrong\n$report",
        )
    }
}
