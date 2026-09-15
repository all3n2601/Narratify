package app.narratify

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Read-only view of what the speech pipeline is doing: which model is loaded, where it came
 * from, and how fast it produced audio. Everything shown is recorded by [TtsDiagnostics] during
 * real playback, or by the built-in benchmark when no book has been read yet.
 */
class TtsDiagnosticsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tts-benchmark") }
    private lateinit var content: LinearLayout
    private lateinit var preferences: AppPreferences
    private lateinit var palette: AppPalette
    private var benchmarkRunning = false

    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        palette = preferences.palette()
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(36))
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(palette.canvas)
                isVerticalScrollBarEnabled = false
                addView(content)
                setOnApplyWindowInsetsListener { view, insets ->
                    @Suppress("DEPRECATION")
                    view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                    insets
                }
            }
        )
        applyWindowPalette(palette)
        render()
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        val snapshot = TtsDiagnostics.snapshot()
        content.removeAllViews()
        content.addView(header())
        content.addView(actionRow(snapshot), topMargin(16))
        content.addView(engineCard(snapshot.engine), topMargin(16))
        content.addView(selectionCard(), topMargin(12))
        content.addView(rateCard(snapshot), topMargin(12))
        content.addView(chartsCard(snapshot), topMargin(12))
        content.addView(samplesCard(snapshot), topMargin(12))
        content.addView(assetsCard(snapshot.engine), topMargin(12))
        content.addView(eventsCard(snapshot), topMargin(12))
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label("DIAGNOSTICS", 12f, palette.accent, Typeface.BOLD).apply { letterSpacing = .18f })
        addView(label("Speech engine", 30f, palette.ink, Typeface.BOLD), topMargin(6))
        addView(
            label(
                "Live measurements from this process. Numbers reset when a new reading session starts.",
                14f,
                palette.muted,
            ),
            topMargin(6),
        )
    }

    private fun actionRow(snapshot: TtsDiagnosticsSnapshot) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            button(if (benchmarkRunning) "Running…" else "Run benchmark", filled = true) { runBenchmark() },
            LinearLayout.LayoutParams(0, dp(46), 1f),
        )
        addView(
            button("Copy report") { copyReport(snapshot) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) },
        )
        addView(
            button("Clear") {
                TtsDiagnostics.clear()
                render()
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) },
        )
    }

    private fun engineCard(engine: TtsEngineSnapshot?) = card().apply {
        addView(sectionTitle("ACTIVE ENGINE"))
        if (engine == null) {
            addView(
                label(
                    "No speech session yet. Open a book and press read aloud, or run the benchmark above.",
                    14f,
                    palette.muted,
                ),
                topMargin(8),
            )
            return@apply
        }
        addView(label(engine.engineLabel, 20f, palette.ink, Typeface.BOLD), topMargin(8))
        addView(label(engine.selectionReason, 13f, palette.accent), topMargin(4))
        addView(
            badge(if (engine.kind == TtsEngineKind.NEURAL) "NEURAL · ON DEVICE" else "SYSTEM TTS"),
            topMargin(10),
        )
        listOfNotNull(
            "Voice" to engine.voiceLabel,
            engine.voiceVersion?.let { "Voice version" to it },
            engine.packId?.let { "Pack" to "$it · ${engine.packVersion}" },
            engine.runtimeId?.let { "Runtime" to it },
            engine.modelId?.let { "Model" to "$it · ${engine.modelVersion}" },
            engine.licenseSpdxId?.let { "License" to it },
            engine.languageTags.takeIf { it.isNotEmpty() }?.let { "Languages" to it.joinToString() },
            engine.sampleRateHz?.let { "Audio" to "$it Hz · ${engine.channelCount} ch · ${engine.encoding}" },
            engine.modelLoadMillis?.let { "Model load" to "$it ms" },
            engine.openBreakdown?.let { "Pack verify" to "${it.verifyMillis} ms" },
            engine.openBreakdown?.let {
                "ONNX session build" to "${it.graphMillis} ms${it.graphSource?.let { source -> " · $source" }.orEmpty()}"
            },
            engine.openBreakdown?.let { "Voice style load" to "${it.voiceStyleMillis} ms" },
            engine.openBreakdown?.let { "Dictionary parse" to "${it.dictionaryMillis} ms" },
            engine.openBreakdown?.let { "Graph warm-up" to "${it.warmupMillis} ms" },
            engine.openBreakdown?.takeIf { it.otherMillis > 0 }?.let { "Open · unattributed" to "${it.otherMillis} ms" },
            engine.packDirectory?.let { "Pack directory" to it },
            engine.attribution?.let { "Attribution" to it },
        ).forEach { (name, value) -> addView(detailRow(name, value), topMargin(8)) }
    }

    private fun selectionCard() = card().apply {
        addView(sectionTitle("VOICE CHOICE"))
        val selection = preferences.voiceSelection
        val description = when (selection) {
            is VoiceSelection.NeuralVoice -> "Neural pack ${selection.packId}"
            is VoiceSelection.SystemVoice -> "System voice ${selection.voiceName}"
            VoiceSelection.Automatic -> "Automatic — neural when installed, otherwise a system voice"
        }
        addView(label(description, 15f, palette.ink, Typeface.BOLD), topMargin(8))
        addView(
            label("Stored as \"${selection.store()}\". Change it in Voices.", 12f, palette.muted),
            topMargin(4),
        )
        addView(
            label("Default speed ${"%.2f×".format(Locale.US, preferences.speechRate)}", 12f, palette.muted),
            topMargin(4),
        )
    }

    private fun rateCard(snapshot: TtsDiagnosticsSnapshot) = card().apply {
        addView(sectionTitle("RATES"))
        val aggregates = snapshot.aggregates
        if (aggregates == null) {
            addView(label("No completed utterances yet.", 14f, palette.muted), topMargin(8))
            return@apply
        }
        addView(
            SpeedGaugeChart(this@TtsDiagnosticsActivity, palette).apply {
                setRatio(aggregates.speedVersusRealTime, "speech seconds per second of synthesis")
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)).apply { topMargin = dp(8) },
        )
        addView(
            metricGrid(
                listOf(
                    "Utterances" to aggregates.utterances.toString(),
                    "Characters" to aggregates.characters.toString(),
                    "Audio produced" to formatSeconds(aggregates.audioMillis),
                    "Synthesis time" to formatSeconds(aggregates.synthesisMillis),
                    "Real-time factor" to "%.2f".format(aggregates.overallRealTimeFactor),
                    "Median RTF" to "%.2f".format(aggregates.medianRealTimeFactor),
                    "Worst RTF" to "%.2f".format(aggregates.worstRealTimeFactor),
                    "Median latency" to "${aggregates.medianFirstAudioMillis} ms",
                    "Worst latency" to "${aggregates.worstFirstAudioMillis} ms",
                    "Throughput" to "%.0f chars/s".format(aggregates.charactersPerSecond),
                )
            ),
            topMargin(12),
        )
        addView(
            label(
                "Real-time factor is synthesis wall time divided by the audio it produced. " +
                    "Below 1.00 means the engine generates speech faster than it is played.",
                12f,
                palette.muted,
            ),
            topMargin(10),
        )
    }

    private fun chartsCard(snapshot: TtsDiagnosticsSnapshot) = card().apply {
        addView(sectionTitle("PER UTTERANCE"))
        val samples = snapshot.samples
        addView(label("Real-time factor · red bars are slower than playback", 12f, palette.muted), topMargin(10))
        addView(
            BarSeriesChart(this@TtsDiagnosticsActivity, palette).apply {
                setSeries(samples.map { it.realTimeFactor }, reference = 1.0) { "%.1f".format(it) }
            },
            chartParams(),
        )
        addView(label("First-audio latency (ms)", 12f, palette.muted), topMargin(14))
        addView(
            LineSeriesChart(this@TtsDiagnosticsActivity, palette).apply {
                setSeries(samples.map { it.firstAudioMillis.toDouble() }, reference = 500.0) { "%.0f".format(it) }
            },
            chartParams(),
        )
        addView(label("Characters per second of synthesis", 12f, palette.muted), topMargin(14))
        addView(
            BarSeriesChart(this@TtsDiagnosticsActivity, palette).apply {
                setSeries(samples.map { it.charactersPerSecond }) { "%.0f".format(it) }
            },
            chartParams(),
        )
    }

    private fun samplesCard(snapshot: TtsDiagnosticsSnapshot) = card().apply {
        addView(sectionTitle("RECENT UTTERANCES"))
        val samples = snapshot.samples.takeLast(12).reversed()
        if (samples.isEmpty()) {
            addView(label("Nothing recorded yet.", 14f, palette.muted), topMargin(8))
            return@apply
        }
        addView(tableRow("#", "chars", "audio", "synth", "RTF", header = true), topMargin(8))
        samples.forEach { sample ->
            addView(
                tableRow(
                    sample.sequence.toString(),
                    sample.characters.toString(),
                    formatSeconds(sample.audioMillis),
                    "${sample.synthesisMillis} ms",
                    "%.2f".format(sample.realTimeFactor),
                ),
                topMargin(6),
            )
        }
    }

    private fun assetsCard(engine: TtsEngineSnapshot?) = card().apply {
        addView(sectionTitle("MODEL FILES"))
        val assets = engine?.assets.orEmpty()
        if (assets.isEmpty()) {
            addView(
                label("No downloaded model files are in use by the active engine.", 14f, palette.muted),
                topMargin(8),
            )
            return@apply
        }
        assets.forEach { asset ->
            addView(
                LinearLayout(this@TtsDiagnosticsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(asset.relativePath, 14f, palette.ink, Typeface.BOLD))
                    addView(
                        label(
                            "${"%.1f".format(asset.sizeBytes / 1_000_000.0)} MB · sha256 ${asset.sha256.take(16)}…",
                            11f,
                            palette.muted,
                        ),
                        topMargin(3),
                    )
                },
                topMargin(10),
            )
        }
    }

    private fun eventsCard(snapshot: TtsDiagnosticsSnapshot) = card().apply {
        addView(sectionTitle("EVENT LOG"))
        if (snapshot.events.isEmpty()) {
            addView(label("No events yet.", 14f, palette.muted), topMargin(8))
            return@apply
        }
        snapshot.events.reversed().forEach { event ->
            addView(
                label("+${event.elapsedMillis} ms · ${event.message}", 12f, palette.muted).apply {
                    typeface = Typeface.MONOSPACE
                },
                topMargin(6),
            )
        }
    }

    private fun runBenchmark() {
        if (benchmarkRunning) return
        benchmarkRunning = true
        render()
        val selection = preferences.voiceSelection
        worker.execute {
            val outcome = runCatching { TtsBenchmark.run(this, selection) }
            main.post {
                benchmarkRunning = false
                val message = outcome.getOrElse { failure ->
                    TtsDiagnostics.recordEvent("Benchmark failed: ${failure.message}")
                    failure.message ?: "The benchmark could not run"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun copyReport(snapshot: TtsDiagnosticsSnapshot) {
        val engine = snapshot.engine
        val report = buildString {
            appendLine("Narratify speech diagnostics")
            appendLine("engine: ${engine?.engineLabel ?: "none"} (${engine?.kind ?: "-"})")
            appendLine("voice: ${engine?.voiceLabel ?: "-"}")
            appendLine("reason: ${engine?.selectionReason ?: "-"}")
            appendLine("pack: ${engine?.packId ?: "-"} ${engine?.packVersion.orEmpty()}")
            appendLine("model: ${engine?.modelId ?: "-"} ${engine?.modelVersion.orEmpty()}")
            appendLine("audio: ${engine?.sampleRateHz ?: "-"} Hz ${engine?.channelCount ?: "-"} ch ${engine?.encoding.orEmpty()}")
            appendLine("model load: ${engine?.modelLoadMillis ?: "-"} ms")
            engine?.openBreakdown?.let {
                appendLine(
                    "open split: total=${it.totalMillis}ms verify=${it.verifyMillis}ms graph=${it.graphMillis}ms " +
                        "style=${it.voiceStyleMillis}ms dictionary=${it.dictionaryMillis}ms " +
                        "warmup=${it.warmupMillis}ms other=${it.otherMillis}ms" +
                        it.graphSource?.let { source -> " graphSource=$source" }.orEmpty()
                )
            }
            snapshot.aggregates?.let {
                appendLine(
                    "utterances=${it.utterances} audio=${it.audioMillis}ms synthesis=${it.synthesisMillis}ms " +
                        "rtf=${"%.3f".format(it.overallRealTimeFactor)} medianRtf=${"%.3f".format(it.medianRealTimeFactor)} " +
                        "worstRtf=${"%.3f".format(it.worstRealTimeFactor)} medianLatency=${it.medianFirstAudioMillis}ms " +
                        "worstLatency=${it.worstFirstAudioMillis}ms"
                )
            }
            snapshot.samples.forEach {
                appendLine(
                    "#${it.sequence} chars=${it.characters} tokens=${it.tokens} first=${it.firstAudioMillis}ms " +
                        "synth=${it.synthesisMillis}ms audio=${it.audioMillis}ms rtf=${"%.3f".format(it.realTimeFactor)}"
                )
            }
            snapshot.events.forEach { appendLine("+${it.elapsedMillis}ms ${it.message}") }
        }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Narratify diagnostics", report))
        Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun metricGrid(metrics: List<Pair<String, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        metrics.chunked(2).forEach { row ->
            addView(
                LinearLayout(this@TtsDiagnosticsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEach { (name, value) ->
                        addView(
                            LinearLayout(this@TtsDiagnosticsActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                background = chartBackground(palette.subtle, 12f, palette.outline)
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                addView(label(name.uppercase(Locale.US), 10f, palette.muted, Typeface.BOLD))
                                addView(label(value, 17f, palette.ink, Typeface.BOLD), topMargin(3))
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginEnd = dp(8)
                                topMargin = dp(8)
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun tableRow(vararg cells: String, header: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        cells.forEachIndexed { index, cell ->
            addView(
                label(cell, if (header) 11f else 13f, if (header) palette.muted else palette.ink, if (header) Typeface.BOLD else Typeface.NORMAL).apply {
                    typeface = if (header) typeface else Typeface.MONOSPACE
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (index == 0) .6f else 1f),
            )
        }
    }

    private fun detailRow(name: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(name.uppercase(Locale.US), 10f, palette.muted, Typeface.BOLD).apply { letterSpacing = .1f })
        addView(label(value, 14f, palette.ink).apply { typeface = Typeface.MONOSPACE }, topMargin(2))
    }

    private fun badge(text: String) = label(text, 10f, palette.onAccent, Typeface.BOLD).apply {
        background = chartBackground(palette.accent, 8f, palette.outline)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        letterSpacing = .1f
    }

    private fun button(text: String, filled: Boolean = false, action: () -> Unit) =
        label(text, 14f, if (filled) palette.onAccent else palette.accent, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = chartBackground(if (filled) palette.accent else palette.subtle, 14f, palette.outline)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = chartBackground(palette.surface, 20f, palette.outline)
    }

    private fun sectionTitle(text: String) = label(text, 11f, palette.accent, Typeface.BOLD).apply { letterSpacing = .16f }

    private fun label(text: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setTypeface(Typeface.DEFAULT, style)
    }

    private fun chartParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)).apply {
        topMargin = dp(6)
    }

    private fun topMargin(value: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(value) }

    private fun formatSeconds(millis: Long): String =
        if (millis >= 1_000) "%.1f s".format(millis / 1_000.0) else "$millis ms"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MILLIS = 1_000L
    }

    object Launcher {
        fun intent(context: Context): Intent = Intent(context, TtsDiagnosticsActivity::class.java)
    }
}
