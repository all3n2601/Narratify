package app.narratify.shared.align

/**
 * Exact alignment for one gap between anchors.
 *
 * This is ordinary Levenshtein with a traceback that reports only the substitutions that were
 * actually equal, so a misheard word comes back unmatched rather than confidently wrong. It is
 * quadratic in both directions, which is affordable for the gaps anchoring leaves behind and
 * ruinous for anything larger, so it refuses rather than trying: a returned `null` means the
 * caller should narrow the range or interpolate across it.
 */
internal object BandedAligner {
    /** Roughly 1 MB of int cells. A gap this size means anchoring failed, not that the book is hard. */
    const val MAX_CELLS: Long = 250_000L

    fun align(
        book: List<String>,
        hypothesis: List<String>,
        bookFrom: Int,
        bookTo: Int,
        hypothesisFrom: Int,
        hypothesisTo: Int,
    ): List<Anchor>? {
        val rows = bookTo - bookFrom
        val columns = hypothesisTo - hypothesisFrom
        if (rows <= 0 || columns <= 0) return emptyList()
        if (rows.toLong() * columns.toLong() > MAX_CELLS) return null

        val cost = Array(rows + 1) { IntArray(columns + 1) }
        for (row in 0..rows) cost[row][0] = row
        for (column in 0..columns) cost[0][column] = column
        for (row in 1..rows) {
            for (column in 1..columns) {
                val same = book[bookFrom + row - 1] == hypothesis[hypothesisFrom + column - 1]
                cost[row][column] = minOf(
                    cost[row - 1][column - 1] + if (same) 0 else 1,
                    cost[row - 1][column] + 1,
                    cost[row][column - 1] + 1,
                )
            }
        }

        val matches = ArrayDeque<Anchor>()
        var row = rows
        var column = columns
        while (row > 0 && column > 0) {
            val bookIndex = bookFrom + row - 1
            val hypothesisIndex = hypothesisFrom + column - 1
            val same = book[bookIndex] == hypothesis[hypothesisIndex]
            when {
                cost[row][column] == cost[row - 1][column - 1] + if (same) 0 else 1 -> {
                    if (same) matches.addFirst(Anchor(bookIndex, hypothesisIndex))
                    row -= 1
                    column -= 1
                }
                cost[row][column] == cost[row - 1][column] + 1 -> row -= 1
                else -> column -= 1
            }
        }
        return matches.toList()
    }
}
