package app.narratify.shared.align

/**
 * Pairs book tokens with hypothesis tokens, anchor first and exactly afterwards.
 *
 * Uniqueness is a property of the range being searched, not of the chapter, so a gap between two
 * anchors is worth anchoring again: "the" decides nothing across ten thousand tokens and decides
 * everything across eleven. Recursion stops when a range yields no anchors, and that range is
 * handed to exact alignment — or, if it is too large for exact alignment to be affordable, left
 * unmatched for the caller to interpolate across and mark as a guess.
 */
internal object AlignmentMatcher {
    /**
     * Each level strictly shrinks the range, so this bound is never reached by well-behaved text.
     * It exists so that adversarial input degrades into interpolation instead of a stack overflow.
     */
    private const val MAX_DEPTH = 32

    fun match(book: List<String>, hypothesis: List<String>): List<Anchor> {
        val matches = ArrayList<Anchor>()
        matchRange(book, hypothesis, 0, book.size, 0, hypothesis.size, depth = 0, into = matches)
        return matches
    }

    private fun matchRange(
        book: List<String>,
        hypothesis: List<String>,
        bookFrom: Int,
        bookTo: Int,
        hypothesisFrom: Int,
        hypothesisTo: Int,
        depth: Int,
        into: MutableList<Anchor>,
    ) {
        if (bookFrom >= bookTo || hypothesisFrom >= hypothesisTo) return

        val anchors = if (depth < MAX_DEPTH) {
            AnchorFinder.find(book, hypothesis, bookFrom, bookTo, hypothesisFrom, hypothesisTo)
        } else {
            emptyList()
        }
        if (anchors.isEmpty()) {
            BandedAligner.align(book, hypothesis, bookFrom, bookTo, hypothesisFrom, hypothesisTo)
                ?.let(into::addAll)
            return
        }

        var bookCursor = bookFrom
        var hypothesisCursor = hypothesisFrom
        for (anchor in anchors) {
            matchRange(
                book, hypothesis,
                bookCursor, anchor.bookIndex,
                hypothesisCursor, anchor.hypothesisIndex,
                depth + 1, into,
            )
            into.add(anchor)
            bookCursor = anchor.bookIndex + 1
            hypothesisCursor = anchor.hypothesisIndex + 1
        }
        matchRange(book, hypothesis, bookCursor, bookTo, hypothesisCursor, hypothesisTo, depth + 1, into)
    }
}
