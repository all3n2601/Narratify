package app.narratify

/**
 * Turns AudioTrack's 32-bit head position into a monotonic frame count. Its own lock is held for
 * arithmetic only, so the thread reading playback progress never waits behind a blocking write.
 */
internal class PlaybackFrameClock {
    private val lock = Any()
    private var wrapBase = 0L
    private var lastRawHead = 0L

    fun advance(rawHead: Long): Long = synchronized(lock) {
        val raw = rawHead and UINT32_MASK
        if (raw < lastRawHead && lastRawHead - raw > WRAP_THRESHOLD) wrapBase += UINT32_RANGE
        lastRawHead = raw
        wrapBase + raw
    }

    fun reset() = synchronized(lock) {
        wrapBase = 0L
        lastRawHead = 0L
    }

    private companion object {
        const val UINT32_MASK = 0xffff_ffffL
        const val UINT32_RANGE = 0x1_0000_0000L
        const val WRAP_THRESHOLD = 0x8000_0000L
    }
}
