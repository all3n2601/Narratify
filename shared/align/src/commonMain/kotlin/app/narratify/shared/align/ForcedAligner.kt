package app.narratify.shared.align

import com.narratify.domain.ChunkId

/**
 * Turns a chapter and a transcript of its narration into a time map.
 *
 * The only interesting decision here is what to do with the tokens that did not match. They get a
 * straight-line time between their matched neighbours, which is usually close and occasionally
 * seconds out, and they are flagged so nothing downstream can mistake the guess for a
 * measurement. The share of the chapter that did match then caps what the whole result is allowed
 * to claim: a book read at word granularity, an abridgement at sentence granularity, and a
 * narration of some other book refused entirely.
 */
object ForcedAligner {
    /**
     * Every granularity in the result is derived here, from the matched share, and never set
     * alongside it independently. No data class can check that rule: an `AlignedSpan` has no
     * reference to the [AlignmentOptions] that produced it, and a map read back from disk may have
     * been aligned under thresholds that have since changed, so recomputing and comparing would
     * reject old data that was correct when it was written.
     */
    fun align(
        bookTokens: List<BookToken>,
        hypothesis: List<AsrToken>,
        options: AlignmentOptions = AlignmentOptions(),
    ): AlignmentResult {
        if (bookTokens.isEmpty() || hypothesis.isEmpty()) return AlignmentResult.refused(bookTokens.size)

        val matches = AlignmentMatcher.match(
            bookTokens.map(BookToken::key),
            hypothesis.map { AlignmentKey.fold(it.text) },
        )
        if (matches.isEmpty()) return AlignmentResult.refused(bookTokens.size)

        val timings = timings(bookTokens.size, hypothesis, matches)
        val matchedRatio = matches.size.toDouble() / bookTokens.size
        val granularity = options.granularityFor(matchedRatio)
        if (granularity == AlignmentGranularity.NONE) {
            return AlignmentResult(timings, spans = emptyList(), matchedRatio = matchedRatio, granularity = granularity)
        }
        return AlignmentResult(
            timings = timings,
            spans = spans(bookTokens, timings, options),
            matchedRatio = matchedRatio,
            granularity = granularity,
        )
    }

    private fun timings(
        bookTokenCount: Int,
        hypothesis: List<AsrToken>,
        matches: List<Anchor>,
    ): List<TokenTiming> {
        val start = LongArray(bookTokenCount) { -1L }
        val end = LongArray(bookTokenCount) { -1L }
        for (anchor in matches) {
            start[anchor.bookIndex] = hypothesis[anchor.hypothesisIndex].startMs
            end[anchor.bookIndex] = hypothesis[anchor.hypothesisIndex].endMs
        }
        val matched = BooleanArray(bookTokenCount) { start[it] >= 0L }
        val first = matched.indexOfFirst { it }
        val last = matched.indexOfLast { it }

        // Outside the matched range there is no evidence at all, so hold at the nearest known
        // time. Extrapolating a reading rate outwards would invent seconds of audio and put the
        // highlight on words the narrator has not reached.
        for (index in 0 until first) {
            start[index] = start[first]
            end[index] = start[first]
        }
        for (index in last + 1 until bookTokenCount) {
            start[index] = end[last]
            end[index] = end[last]
        }

        var cursor = first
        while (cursor < last) {
            var next = cursor + 1
            while (!matched[next]) next += 1
            val gap = next - cursor
            if (gap > 1) {
                val available = (start[next] - end[cursor]).coerceAtLeast(0L)
                val step = available / gap
                for (offset in 1 until gap) {
                    start[cursor + offset] = end[cursor] + step * (offset - 1)
                    end[cursor + offset] = end[cursor] + step * offset
                }
            }
            cursor = next
        }

        return List(bookTokenCount) { TokenTiming(it, start[it], end[it], matched[it]) }
    }

    /**
     * Groups tokens back into the TTS chunks they came from, because their tokens are contiguous
     * so one scan is enough, and because sharing the unit with TTS keeps the two highlighters
     * consistent. A chunk approximates a sentence but is not one: `TtsTextPreparer` fuses
     * adjacent short sentences and splits long ones at a semicolon, comma, or failing that a
     * space. So a span can cover several sentences or end mid-clause, and a SENTENCE-granularity
     * claim is really a claim about this chunk.
     */
    private fun spans(
        bookTokens: List<BookToken>,
        timings: List<TokenTiming>,
        options: AlignmentOptions,
    ): List<AlignedSpan> = buildList {
        var index = 0
        var earliestStart = 0L
        var earliestEnd = 0L
        while (index < bookTokens.size) {
            val chunkId: ChunkId = bookTokens[index].chunkId
            var endExclusive = index
            while (endExclusive < bookTokens.size && bookTokens[endExclusive].chunkId == chunkId) {
                endExclusive += 1
            }
            val matched = (index until endExclusive).count { timings[it].matched }
            val ratio = matched.toDouble() / (endExclusive - index)
            // Interpolated times can overlap or nest; the map refuses both, so clamp each span to
            // start and end no earlier than the one before it.
            val startMs = maxOf(timings[index].startMs, earliestStart)
            val endMs = maxOf(timings[endExclusive - 1].endMs, startMs, earliestEnd)
            add(
                AlignedSpan(
                    bookTokenStart = index,
                    bookTokenEndExclusive = endExclusive,
                    startMs = startMs,
                    endMs = endMs,
                    matchedRatio = ratio,
                    granularity = options.granularityFor(ratio),
                ),
            )
            earliestStart = startMs
            earliestEnd = endMs
            index = endExclusive
        }
    }
}
