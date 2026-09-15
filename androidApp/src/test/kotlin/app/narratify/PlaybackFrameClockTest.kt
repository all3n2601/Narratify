package app.narratify

import org.junit.Test
import kotlin.test.assertEquals

/** AudioTrack reports a 32-bit head position; playback outlives it, so the wrap has to be kept. */
class PlaybackFrameClockTest {
    @Test
    fun framesAdvanceWithTheReportedHead() {
        val clock = PlaybackFrameClock()

        assertEquals(0L, clock.advance(0))
        assertEquals(1_024L, clock.advance(1_024))
        assertEquals(48_000L, clock.advance(48_000))
    }

    @Test
    fun wrappingPastThirtyTwoBitsKeepsCounting() {
        val clock = PlaybackFrameClock()
        val nearLimit = 0xFFFF_FF00L

        assertEquals(nearLimit, clock.advance(nearLimit))
        assertEquals(0x1_0000_0100L, clock.advance(0x100))
    }

    @Test
    fun smallBackwardsJitterIsNotMistakenForAWrap() {
        val clock = PlaybackFrameClock()
        clock.advance(10_000)

        assertEquals(9_990L, clock.advance(9_990))
    }

    @Test
    fun resettingStartsFromZeroAgain() {
        val clock = PlaybackFrameClock()
        clock.advance(0xFFFF_FF00L)
        clock.advance(0x100)

        clock.reset()

        assertEquals(500L, clock.advance(500))
    }
}
