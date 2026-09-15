package app.narratify

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class TtsDiagnosticsTest {
    private var nanos = 0L

    private val neuralEngine = TtsEngineSnapshot(
        kind = TtsEngineKind.NEURAL,
        engineLabel = "Kokoro",
        voiceLabel = "af_heart",
        selectionReason = "Chosen in Voices",
        packId = "app.narratify.kokoro.en-us.heart",
        sampleRateHz = 24_000,
        channelCount = 1,
    )

    @Before
    fun start() {
        TtsDiagnostics.clockNanos = { nanos }
        TtsDiagnostics.clear()
    }

    @After
    fun stop() {
        TtsDiagnostics.clockNanos = System::nanoTime
        TtsDiagnostics.clear()
    }

    private fun advance(millis: Long) {
        nanos += millis * 1_000_000
    }

    @Test
    fun `an empty snapshot reports no engine and no samples`() {
        val snapshot = TtsDiagnostics.snapshot()

        assertNull(snapshot.engine)
        assertTrue(snapshot.samples.isEmpty())
        assertNull(snapshot.aggregates)
    }

    @Test
    fun `a recorded utterance derives latency, real-time factor, and audio length`() {
        TtsDiagnostics.beginSession(neuralEngine)
        val recorder = TtsDiagnostics.recordUtterance(characters = 40, tokens = 9)
        advance(120)
        recorder.firstAudio()
        // 24 kHz mono 16-bit: 48 000 bytes is exactly one second of audio.
        recorder.pcm(48_000)
        advance(280)
        recorder.complete(sampleRateHz = 24_000, channelCount = 1)

        val sample = TtsDiagnostics.snapshot().samples.single()
        assertEquals(120, sample.firstAudioMillis)
        assertEquals(400, sample.synthesisMillis)
        assertEquals(1_000, sample.audioMillis)
        assertEquals(0.4, sample.realTimeFactor, 1e-9)
        assertEquals(100.0, sample.charactersPerSecond, 1e-9)
    }

    @Test
    fun `aggregates summarise every completed utterance`() {
        TtsDiagnostics.beginSession(neuralEngine)
        listOf(100L to 48_000, 300L to 48_000, 200L to 96_000).forEach { (synthesis, bytes) ->
            val recorder = TtsDiagnostics.recordUtterance(characters = 10, tokens = 3)
            recorder.firstAudio()
            recorder.pcm(bytes)
            advance(synthesis)
            recorder.complete(sampleRateHz = 24_000, channelCount = 1)
        }

        val aggregates = TtsDiagnostics.snapshot().aggregates!!
        assertEquals(3, aggregates.utterances)
        assertEquals(4_000, aggregates.audioMillis)
        assertEquals(600, aggregates.synthesisMillis)
        assertEquals(0.1, aggregates.medianRealTimeFactor, 1e-9)
        assertEquals(0.3, aggregates.worstRealTimeFactor, 1e-9)
        assertEquals(0.15, aggregates.overallRealTimeFactor, 1e-9)
    }

    @Test
    fun `history stays bounded so a long book cannot grow the process`() {
        TtsDiagnostics.beginSession(neuralEngine)
        repeat(TtsDiagnostics.MAX_SAMPLES + 25) { index ->
            val recorder = TtsDiagnostics.recordUtterance(characters = index + 1, tokens = 1)
            recorder.firstAudio()
            recorder.pcm(4_800)
            advance(10)
            recorder.complete(sampleRateHz = 24_000, channelCount = 1)
        }

        val snapshot = TtsDiagnostics.snapshot()
        assertEquals(TtsDiagnostics.MAX_SAMPLES, snapshot.samples.size)
        assertEquals(TtsDiagnostics.MAX_SAMPLES + 25, snapshot.samples.last().sequence)
        assertEquals(TtsDiagnostics.MAX_SAMPLES + 25, snapshot.aggregates!!.utterances)
    }

    @Test
    fun `events are recorded newest last and bounded`() {
        TtsDiagnostics.beginSession(neuralEngine)
        repeat(TtsDiagnostics.MAX_EVENTS + 5) { TtsDiagnostics.recordEvent("event $it") }

        val events = TtsDiagnostics.snapshot().events
        assertEquals(TtsDiagnostics.MAX_EVENTS, events.size)
        assertTrue(events.last().message.endsWith("${TtsDiagnostics.MAX_EVENTS + 4}"))
    }

    @Test
    fun `beginning a session clears samples from the previous engine`() {
        TtsDiagnostics.beginSession(neuralEngine)
        TtsDiagnostics.recordUtterance(characters = 5, tokens = 1).also {
            it.firstAudio(); it.pcm(4_800); it.complete(24_000, 1)
        }

        TtsDiagnostics.beginSession(
            TtsEngineSnapshot(
                kind = TtsEngineKind.SYSTEM,
                engineLabel = "Android system TTS",
                voiceLabel = "en-us-x-sfg#female_1",
                selectionReason = "No neural pack installed",
            )
        )

        val snapshot = TtsDiagnostics.snapshot()
        assertEquals(TtsEngineKind.SYSTEM, snapshot.engine!!.kind)
        assertTrue(snapshot.samples.isEmpty())
    }

    @Test
    fun `a recorded session open keeps the phase split and reports the load time`() {
        TtsDiagnostics.beginSession(neuralEngine)

        TtsDiagnostics.recordSessionOpen(
            TtsSessionOpenBreakdown(
                totalMillis = 5_000,
                verifyMillis = 1_200,
                graphMillis = 2_000,
                voiceStyleMillis = 10,
                dictionaryMillis = 700,
                warmupMillis = 900,
            )
        )

        val engine = TtsDiagnostics.snapshot().engine!!
        assertEquals(5_000, engine.modelLoadMillis)
        val breakdown = engine.openBreakdown!!
        assertEquals(2_000, breakdown.graphMillis)
        assertEquals(190, breakdown.otherMillis)
        assertTrue(TtsDiagnostics.snapshot().events.last().message.contains("graph 2000 ms"))
    }

    @Test
    fun `an open split that over-accounts never reports negative unattributed time`() {
        val breakdown = TtsSessionOpenBreakdown(
            totalMillis = 100,
            verifyMillis = 80,
            graphMillis = 80,
            voiceStyleMillis = 0,
            dictionaryMillis = 0,
            warmupMillis = 0,
        )

        assertEquals(0, breakdown.otherMillis)
    }

    @Test
    fun `an unfinished utterance never reaches the snapshot`() {
        TtsDiagnostics.beginSession(neuralEngine)
        val recorder = TtsDiagnostics.recordUtterance(characters = 12, tokens = 2)
        recorder.firstAudio()
        recorder.pcm(9_600)

        assertTrue(TtsDiagnostics.snapshot().samples.isEmpty())
    }
}
