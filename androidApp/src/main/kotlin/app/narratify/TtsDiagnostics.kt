package app.narratify

enum class TtsEngineKind { NEURAL, SYSTEM }

/**
 * Where the wall time of one neural session open actually went. Recorded per phase because the
 * cost of a "read aloud" tap has to be attributed to a phase — digest verification, graph build,
 * voice style, dictionary, warm-up — before any of them is worth optimising.
 */
data class TtsSessionOpenBreakdown(
    val totalMillis: Long,
    val verifyMillis: Long,
    val graphMillis: Long,
    val voiceStyleMillis: Long,
    val dictionaryMillis: Long,
    val warmupMillis: Long,
    /** How the graph was obtained, for example whether an ORT-optimised copy was reused. */
    val graphSource: String? = null,
) {
    /** Open time not covered by a named phase, so a split that does not add up is visible. */
    val otherMillis: Long
        get() = (totalMillis - verifyMillis - graphMillis - voiceStyleMillis - dictionaryMillis - warmupMillis)
            .coerceAtLeast(0)

    fun summary(): String = buildString {
        append("verify ${verifyMillis} ms · graph ${graphMillis} ms")
        graphSource?.let { append(" ($it)") }
        append(" · style ${voiceStyleMillis} ms · dictionary ${dictionaryMillis} ms")
        append(" · warm-up ${warmupMillis} ms · other ${otherMillis} ms")
    }
}

/** Everything the debug page needs to say exactly which model produced the audio. */
data class TtsEngineSnapshot(
    val kind: TtsEngineKind,
    val engineLabel: String,
    val voiceLabel: String,
    val selectionReason: String,
    val packId: String? = null,
    val packVersion: String? = null,
    val runtimeId: String? = null,
    val modelId: String? = null,
    val modelVersion: String? = null,
    val voiceId: String? = null,
    val voiceVersion: String? = null,
    val licenseSpdxId: String? = null,
    val attribution: String? = null,
    val languageTags: Set<String> = emptySet(),
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val encoding: String? = null,
    val packDirectory: String? = null,
    val assets: List<NeuralModelAsset> = emptyList(),
    val modelLoadMillis: Long? = null,
    val openBreakdown: TtsSessionOpenBreakdown? = null,
)

/** One completed synthesis unit. Durations are wall-clock; audio length comes from PCM byte counts. */
data class TtsUtteranceSample(
    val sequence: Int,
    val characters: Int,
    val tokens: Int,
    val firstAudioMillis: Long,
    val synthesisMillis: Long,
    val audioMillis: Long,
    val audioBytes: Long,
) {
    /** Synthesis wall time divided by produced audio time. Below 1.0 keeps up with playback. */
    val realTimeFactor: Double get() = if (audioMillis > 0) synthesisMillis.toDouble() / audioMillis else 0.0
    val charactersPerSecond: Double get() = if (synthesisMillis > 0) characters * 1_000.0 / synthesisMillis else 0.0
}

data class TtsAggregates(
    val utterances: Int,
    val characters: Long,
    val audioMillis: Long,
    val synthesisMillis: Long,
    val medianRealTimeFactor: Double,
    val worstRealTimeFactor: Double,
    val overallRealTimeFactor: Double,
    val medianFirstAudioMillis: Long,
    val worstFirstAudioMillis: Long,
) {
    val charactersPerSecond: Double get() = if (synthesisMillis > 0) characters * 1_000.0 / synthesisMillis else 0.0
    /** How many seconds of speech each second of synthesis buys. */
    val speedVersusRealTime: Double get() = if (overallRealTimeFactor > 0) 1.0 / overallRealTimeFactor else 0.0
}

data class TtsDiagnosticsEvent(val elapsedMillis: Long, val message: String)

data class TtsDiagnosticsSnapshot(
    val engine: TtsEngineSnapshot?,
    val samples: List<TtsUtteranceSample>,
    val events: List<TtsDiagnosticsEvent>,
    val aggregates: TtsAggregates?,
)

/**
 * Process-local recording of what the speech pipeline actually did. Bounded on purpose: a long
 * book must not grow the heap, so only the most recent utterances and events are kept while the
 * aggregate counters cover the whole session.
 */
object TtsDiagnostics {
    const val MAX_SAMPLES = 60
    const val MAX_EVENTS = 40

    /** Replaced in tests. Nanoseconds, monotonic. */
    internal var clockNanos: () -> Long = System::nanoTime

    private val lock = Any()
    private var engine: TtsEngineSnapshot? = null
    private var sessionStartNanos = 0L
    private var sequence = 0
    private val samples = ArrayDeque<TtsUtteranceSample>()
    private val events = ArrayDeque<TtsDiagnosticsEvent>()
    private var totalUtterances = 0
    private var totalCharacters = 0L
    private var totalAudioMillis = 0L
    private var totalSynthesisMillis = 0L

    /** A single synthesis unit in flight. Only complete units reach the snapshot. */
    class UtteranceRecorder internal constructor(
        private val startNanos: Long,
        private val characters: Int,
        private val tokens: Int,
    ) {
        private var firstAudioNanos: Long? = null
        private var audioBytes = 0L
        private var finished = false

        fun firstAudio() {
            if (firstAudioNanos == null) firstAudioNanos = clockNanos()
        }

        fun pcm(bytes: Int) {
            firstAudio()
            audioBytes += bytes
        }

        fun complete(sampleRateHz: Int, channelCount: Int, bytesPerSample: Int = 2) {
            if (finished) return
            finished = true
            val now = clockNanos()
            val frameBytes = (bytesPerSample * channelCount).coerceAtLeast(1)
            val frames = audioBytes / frameBytes
            val audioMillis = if (sampleRateHz > 0) frames * 1_000 / sampleRateHz else 0L
            record(
                characters = characters,
                tokens = tokens,
                firstAudioMillis = ((firstAudioNanos ?: now) - startNanos) / 1_000_000,
                synthesisMillis = (now - startNanos) / 1_000_000,
                audioMillis = audioMillis,
                audioBytes = audioBytes,
            )
        }

        /** Used by the system engine, which reports spoken duration instead of PCM. */
        fun completeWithAudioMillis(audioMillis: Long) {
            if (finished) return
            finished = true
            val now = clockNanos()
            record(
                characters = characters,
                tokens = tokens,
                firstAudioMillis = ((firstAudioNanos ?: now) - startNanos) / 1_000_000,
                synthesisMillis = (now - startNanos) / 1_000_000,
                audioMillis = audioMillis,
                audioBytes = audioBytes,
            )
        }
    }

    fun beginSession(snapshot: TtsEngineSnapshot) {
        synchronized(lock) {
            engine = snapshot
            sessionStartNanos = clockNanos()
            sequence = 0
            samples.clear()
            events.clear()
            totalUtterances = 0
            totalCharacters = 0
            totalAudioMillis = 0
            totalSynthesisMillis = 0
        }
        recordEvent("${snapshot.engineLabel} selected · ${snapshot.selectionReason}")
    }

    /** Keeps the engine identity while replacing details discovered later, such as load time. */
    fun updateEngine(transform: (TtsEngineSnapshot) -> TtsEngineSnapshot) {
        synchronized(lock) { engine = engine?.let(transform) }
    }

    /**
     * Records one session open and its phase split. Called by the runtime itself so every caller
     * that opens a pack — reader, EPUB narration, benchmark — reports the same numbers.
     */
    fun recordSessionOpen(breakdown: TtsSessionOpenBreakdown) {
        updateEngine { it.copy(modelLoadMillis = breakdown.totalMillis, openBreakdown = breakdown) }
        recordEvent("Session open ${breakdown.totalMillis} ms · ${breakdown.summary()}")
    }

    fun recordUtterance(characters: Int, tokens: Int): UtteranceRecorder =
        UtteranceRecorder(clockNanos(), characters, tokens)

    fun recordEvent(message: String) {
        synchronized(lock) {
            val elapsed = if (sessionStartNanos == 0L) 0L else (clockNanos() - sessionStartNanos) / 1_000_000
            events.addLast(TtsDiagnosticsEvent(elapsed, message))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    fun snapshot(): TtsDiagnosticsSnapshot = synchronized(lock) {
        TtsDiagnosticsSnapshot(
            engine = engine,
            samples = samples.toList(),
            events = events.toList(),
            aggregates = aggregates(),
        )
    }

    fun clear() {
        synchronized(lock) {
            engine = null
            sessionStartNanos = 0
            sequence = 0
            samples.clear()
            events.clear()
            totalUtterances = 0
            totalCharacters = 0
            totalAudioMillis = 0
            totalSynthesisMillis = 0
        }
    }

    private fun record(
        characters: Int,
        tokens: Int,
        firstAudioMillis: Long,
        synthesisMillis: Long,
        audioMillis: Long,
        audioBytes: Long,
    ) {
        synchronized(lock) {
            sequence++
            samples.addLast(
                TtsUtteranceSample(
                    sequence = sequence,
                    characters = characters,
                    tokens = tokens,
                    firstAudioMillis = firstAudioMillis,
                    synthesisMillis = synthesisMillis,
                    audioMillis = audioMillis,
                    audioBytes = audioBytes,
                )
            )
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
            totalUtterances++
            totalCharacters += characters
            totalAudioMillis += audioMillis
            totalSynthesisMillis += synthesisMillis
        }
    }

    private fun aggregates(): TtsAggregates? {
        if (totalUtterances == 0) return null
        val factors = samples.map { it.realTimeFactor }.sorted()
        val latencies = samples.map { it.firstAudioMillis }.sorted()
        return TtsAggregates(
            utterances = totalUtterances,
            characters = totalCharacters,
            audioMillis = totalAudioMillis,
            synthesisMillis = totalSynthesisMillis,
            medianRealTimeFactor = factors.median(),
            worstRealTimeFactor = factors.maxOrNull() ?: 0.0,
            overallRealTimeFactor = if (totalAudioMillis > 0) totalSynthesisMillis.toDouble() / totalAudioMillis else 0.0,
            medianFirstAudioMillis = latencies.median().toLong(),
            worstFirstAudioMillis = latencies.maxOrNull() ?: 0L,
        )
    }

    private fun List<Double>.median(): Double = when {
        isEmpty() -> 0.0
        size % 2 == 1 -> this[size / 2]
        else -> (this[size / 2 - 1] + this[size / 2]) / 2
    }

    @JvmName("medianOfLongs")
    private fun List<Long>.median(): Double = map(Long::toDouble).median()
}
