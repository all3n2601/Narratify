package app.narratify.shared.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmilClockTest {
    @Test
    fun `zero is written in full rather than abbreviated`() {
        assertEquals("0:00:00.000", SmilClock.format(0L))
    }

    @Test
    fun `milliseconds keep three digits`() {
        assertEquals("0:00:03.120", SmilClock.format(3_120L))
        assertEquals("0:00:03.007", SmilClock.format(3_007L))
    }

    @Test
    fun `minutes and seconds keep two digits`() {
        assertEquals("0:02:05.000", SmilClock.format(125_000L))
    }

    @Test
    fun `hours are not padded and do not wrap`() {
        assertEquals("1:02:03.456", SmilClock.format(3_723_456L))
        assertEquals("27:00:00.000", SmilClock.format(97_200_000L))
    }

    @Test
    fun `a negative time has no meaning and is refused`() {
        assertFailsWith<IllegalArgumentException> { SmilClock.format(-1L) }
    }
}
