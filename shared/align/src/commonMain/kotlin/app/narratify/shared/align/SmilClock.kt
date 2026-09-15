package app.narratify.shared.align

/**
 * The SMIL clock value, `H:MM:SS.mmm`.
 *
 * Hours are not padded and deliberately do not wrap at 24: an audiobook is routinely longer than
 * a day, and a clip that wrapped would point at the beginning of the file instead of the end.
 */
object SmilClock {
    fun format(milliseconds: Long): String {
        require(milliseconds >= 0) { "A clip time must be non-negative" }
        val fraction = milliseconds % 1000
        val totalSeconds = milliseconds / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return "$hours:${pad(minutes, 2)}:${pad(seconds, 2)}.${pad(fraction, 3)}"
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')
}
