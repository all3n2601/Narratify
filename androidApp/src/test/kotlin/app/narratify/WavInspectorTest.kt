package app.narratify

import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class WavInspectorTest {
    private fun wav(
        sampleRateHz: Int = 22_050,
        channels: Int = 1,
        bits: Int = 16,
        dataBytes: Int = 44_100,
        declaredDataSize: Int? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(value: Int) = repeat(4) { out.write((value shr (it * 8)) and 0xff) }
        fun short(value: Int) = repeat(2) { out.write((value shr (it * 8)) and 0xff) }
        out.write("RIFF".toByteArray())
        int(36 + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        int(16)
        short(1)
        short(channels)
        int(sampleRateHz)
        int(sampleRateHz * channels * bits / 8)
        short(channels * bits / 8)
        short(bits)
        out.write("data".toByteArray())
        int(declaredDataSize ?: dataBytes)
        repeat(dataBytes) { out.write(0) }
        return out.toByteArray()
    }

    @Test
    fun `duration comes from the sample rate and data size`() {
        val info = WavInspector.inspect(wav())!!

        assertEquals(22_050, info.sampleRateHz)
        assertEquals(1, info.channelCount)
        assertEquals(1_000, info.durationMillis)
    }

    @Test
    fun `stereo frames count once`() {
        val info = WavInspector.inspect(wav(sampleRateHz = 16_000, channels = 2, dataBytes = 64_000))!!

        assertEquals(1_000, info.durationMillis)
    }

    @Test
    fun `a truncated file is measured by what is actually present`() {
        val info = WavInspector.inspect(wav(dataBytes = 22_050, declaredDataSize = 44_100))!!

        assertEquals(500, info.durationMillis)
    }

    @Test
    fun `non-wav input is rejected instead of guessed`() {
        assertNull(WavInspector.inspect(ByteArray(200)))
        assertNull(WavInspector.inspect("not audio at all".toByteArray()))
    }
}
