package app.narratify.shared.align

/** A book token index paired with the hypothesis token index that says the same word. */
internal data class Anchor(val bookIndex: Int, val hypothesisIndex: Int)

/**
 * Finds the matches that need no search.
 *
 * Words occurring exactly once on each side of a range can only correspond to each other, so they
 * are free. A handful will still be wrong — a transcriber's "sleight" landing where the book says
 * "slate" — and a wrong anchor drags every interpolated token between it and its neighbours to the
 * wrong second.
 *
 * Keeping only the longest run that moves forward on both sides does not catch that. It detects
 * wrong order, not wrong words: an anchor is discarded only when keeping it would make the
 * timeline run backwards, which a real reading never does. A wrong anchor sitting in an
 * order-consistent position survives untouched. Stopping two different words from folding to one
 * key in the first place is [AlignmentKey]'s job, not this one's.
 *
 * This is O(range) per call, and the caller is what keeps that affordable: [AlignmentMatcher]
 * recurses over disjoint sub-ranges under a depth cap, so total work across a chapter is bounded
 * by that cap times the token count rather than by its square. Calling this on overlapping ranges
 * would quietly make alignment quadratic.
 */
internal object AnchorFinder {
    fun find(
        book: List<String>,
        hypothesis: List<String>,
        bookFrom: Int,
        bookTo: Int,
        hypothesisFrom: Int,
        hypothesisTo: Int,
    ): List<Anchor> {
        if (bookFrom >= bookTo || hypothesisFrom >= hypothesisTo) return emptyList()

        val bookCounts = HashMap<String, Int>()
        for (index in bookFrom until bookTo) {
            bookCounts[book[index]] = (bookCounts[book[index]] ?: 0) + 1
        }
        val hypothesisCounts = HashMap<String, Int>()
        val hypothesisIndices = HashMap<String, Int>()
        for (index in hypothesisFrom until hypothesisTo) {
            val key = hypothesis[index]
            hypothesisCounts[key] = (hypothesisCounts[key] ?: 0) + 1
            hypothesisIndices[key] = index
        }

        val candidates = ArrayList<Anchor>()
        for (index in bookFrom until bookTo) {
            val key = book[index]
            if (bookCounts[key] != 1 || hypothesisCounts[key] != 1) continue
            candidates.add(Anchor(index, hypothesisIndices.getValue(key)))
        }
        return longestForwardRun(candidates)
    }

    /**
     * Patience sorting over the hypothesis indices. The candidates already ascend by book index,
     * so the longest increasing subsequence of hypothesis indices is the largest set of anchors
     * that can all be true at once.
     */
    private fun longestForwardRun(candidates: List<Anchor>): List<Anchor> {
        if (candidates.isEmpty()) return emptyList()
        val pileTops = ArrayList<Int>()
        val previous = IntArray(candidates.size) { -1 }
        for ((position, candidate) in candidates.withIndex()) {
            var low = 0
            var high = pileTops.size
            while (low < high) {
                val middle = (low + high) / 2
                if (candidates[pileTops[middle]].hypothesisIndex < candidate.hypothesisIndex) {
                    low = middle + 1
                } else {
                    high = middle
                }
            }
            if (low > 0) previous[position] = pileTops[low - 1]
            if (low == pileTops.size) pileTops.add(position) else pileTops[low] = position
        }
        val run = ArrayDeque<Anchor>()
        var cursor = pileTops.last()
        while (cursor != -1) {
            run.addFirst(candidates[cursor])
            cursor = previous[cursor]
        }
        return run.toList()
    }
}
