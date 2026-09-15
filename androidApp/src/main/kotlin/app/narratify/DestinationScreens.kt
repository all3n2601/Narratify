package app.narratify

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

abstract class DestinationScreen(
    context: Context,
    private val selected: AppSection,
    private val onNavigate: (AppSection) -> Unit,
) : FrameLayout(context) {
    protected val tablet = resources.configuration.smallestScreenWidthDp >= 600

    /** Destinations share the library's enchanted shell; reading themes only style book pages. */
    protected val palette: AppPalette = enchantedLibraryPalette()
    protected val UI_INK get() = palette.ink
    protected val UI_MUTED get() = palette.muted
    protected val UI_CANVAS get() = palette.canvas
    protected val UI_SURFACE get() = palette.surface
    protected val UI_ACCENT get() = palette.accent

    protected fun install(content: View) {
        setBackgroundColor(UI_CANVAS)
        addView(
            EnchantedForestView(context),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        val shell = if (tablet) LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(navigationRail(), LinearLayout.LayoutParams(dp(96), LayoutParams.MATCH_PARENT))
            addView(context.ruleView(palette.rule), LinearLayout.LayoutParams(dp(1), LayoutParams.MATCH_PARENT))
            addView(content, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        } else LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBar(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        }
        addView(shell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }

    /** Same masthead as the library: kicker, serif title, standfirst, rule. */
    protected fun pageHeader(eyebrow: String, title: String, detail: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.brandMarkView(36), LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(
                context.eyebrow(eyebrow, UI_ACCENT),
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(Gap.XS)
                },
            )
        })
        addView(
            context.styledText(
                title,
                if (tablet) Type.DISPLAY_LARGE else Type.DISPLAY,
                UI_INK,
                Typeface.BOLD,
                serif = true,
            ),
            topMargin(Gap.XXS),
        )
        addView(
            context.styledText(detail, Type.BODY, UI_MUTED).apply { setLineSpacing(0f, 1.45f) },
            topMargin(Gap.XS),
        )
        addView(context.ruleView(palette.rule, topMarginDp = Gap.MD))
    }

    /** Hairline-outlined, not filled. A page of filled grey slabs was the old flatness problem. */
    protected fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Gap.MD), dp(Gap.MD), dp(Gap.MD), dp(Gap.MD))
        background = outlineShape(palette.outline, Radius.CARD)
    }

    /** A ruled sub-head, the way a spec sheet divides sections. */
    protected fun sectionTitle(value: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(context.eyebrow(value, UI_MUTED))
        addView(context.ruleView(palette.rule, topMarginDp = Gap.XS))
    }

    protected fun label(
        value: String,
        size: Float,
        color: Int = UI_INK,
        style: Int = Typeface.NORMAL,
        serif: Boolean = false,
    ) = context.styledText(value, size, color, style, serif)

    /** The one filled action per screen. Everything else stays outlined or plain. */
    protected fun primaryAction(title: String, enabled: Boolean = true, action: () -> Unit) =
        context.styledText(title, Type.BODY, if (enabled) palette.onAccent else UI_MUTED, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = if (enabled) surfaceShape(UI_ACCENT, Radius.INPUT) else outlineShape(palette.outline, Radius.INPUT)
            minHeight = dp(48)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            setOnClickListener { action() }
        }

    /** Removing or cancelling: outlined and set in the danger colour, never the loud filled fill. */
    protected fun destructiveAction(title: String, enabled: Boolean = true, action: () -> Unit) =
        context.styledText(title, Type.BODY, if (enabled) palette.danger else UI_MUTED, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = outlineShape(palette.outline, Radius.INPUT)
            minHeight = dp(48)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            setOnClickListener { action() }
        }

    /** A quiet text action: accent label, no container. */
    protected fun textAction(title: String, action: () -> Unit) =
        context.styledText(title, Type.LABEL, UI_ACCENT, Typeface.BOLD, tracking = Type.TRACKING_LABEL).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    protected fun topMargin(value: Int) = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(value) }
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun bottomBar() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = glassShape(0xF012302A.toInt(), 0xEC081E1A.toInt(), Radius.NONE, palette.outline)
        addView(context.ruleView(palette.rule))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            AppSection.entries.forEach { section ->
                addView(navItem(section), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun navigationRail() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(Gap.XS), dp(Gap.XL), dp(Gap.XS), dp(Gap.MD))
        background = glassShape(0xED102D28.toInt(), 0xE8071D19.toInt(), Radius.NONE, palette.outline)
        addView(context.brandMarkView(44), LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(Space(context), LinearLayout.LayoutParams(1, dp(Gap.XXL)))
        addView(navItem(AppSection.LIBRARY), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        addView(navItem(AppSection.DISCOVER), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        addView(navItem(AppSection.VOICES), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        addView(Space(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(navItem(AppSection.SETTINGS), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
    }

    private fun navItem(section: AppSection) =
        buildNavItem(context, palette, section, section == selected) { onNavigate(section) }
}

class VoicesScreen(
    context: Context,
    private val preferences: AppPreferences,
    onNavigate: (AppSection) -> Unit,
) : DestinationScreen(context, AppSection.VOICES, onNavigate) {
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val neuralPack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val packInstaller = NeuralVoicePackInstaller(context.applicationContext)
    private val installerExecutor = Executors.newSingleThreadExecutor()
    /**
     * The pack id whose install should stop, or [CANCEL_EVERY] for whatever is in flight.
     * Per-install rather than one screen-wide flag: a screen-wide flag let one card's Cancel stop
     * another card's download, and let starting a download resurrect one just cancelled.
     */
    private val cancelRequestedFor = AtomicReference<String?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var released = false
    /** Pack ids of every installed voice. Empty until the first storage scan finishes. */
    private var installedVoicePackIds: Set<String> = emptySet()
    /** Cached off-thread: verifying the shared model pack digests ~329 MB and would stall a render. */
    private var modelPackInstalled = false
    private var neuralPackChecking = true
    /** True while a license-notice directory lookup is in flight, so one tap opens one dialog. */
    private var licenseLookupPending = false
    /** Non-null while an install is in flight. Every other card's Download stays disabled. */
    private var downloadingPackId: String? = null

    /** Progress or an error belonging to exactly one card, for a single render pass. */
    private data class PackActivity(
        val packId: String,
        val progress: Pair<Long, Long>? = null,
        val error: String? = null,
    )

    init {
        val scroll = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(if (tablet) Gap.XXL else Gap.LG), dp(if (tablet) Gap.XL else Gap.MD), dp(if (tablet) Gap.XXL else Gap.LG), dp(Gap.SECTION))
                addView(pageHeader("VOICE STUDIO", "Offline voices", "Choose the voice Narratify uses for read aloud. Only voices installed on this device are shown."))
                addView(neuralPack, topMargin(Gap.XL))
                addView(sectionTitle("INSTALLED SYSTEM VOICES"), topMargin(Gap.XL))
                addView(list)
                addView(primaryAction("Manage Android speech voices") {
                    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        .onFailure { Toast.makeText(context, "Speech settings are unavailable.", Toast.LENGTH_SHORT).show() }
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Gap.LG) })
            })
        }
        renderNeuralPack()
        refreshInstalledState()
        list.addView(card().apply { addView(label("Checking installed voices…", Type.BODY, UI_MUTED)) })
        install(scroll)
        engine = TextToSpeech(context.applicationContext) { status ->
            post { if (status == TextToSpeech.SUCCESS) renderVoices() else renderUnavailable() }
        }
    }

    private fun renderVoices() {
        val voices = engine?.voices.orEmpty()
            .filter { !it.isNetworkConnectionRequired }
            .sortedWith(compareByDescending<Voice> { it.locale.language == Locale.getDefault().language }.thenBy { it.locale.displayName }.thenBy { it.name })
        list.removeAllViews()
        if (voices.isEmpty()) return renderUnavailable()
        val selectedVoiceId = (preferences.voiceSelection as? VoiceSelection.SystemVoice)?.voiceName
        voices.forEach { voice ->
            val selected = selectedVoiceId == voice.name
            list.addView(card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(voice.locale.getDisplayName(voice.locale), Type.BODY_LARGE, UI_INK, Typeface.BOLD, serif = true))
                    addView(label("${voice.name} · offline", Type.MICRO, UI_MUTED), topMargin(Gap.XXS))
                }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(RadioButton(context).apply {
                    tintTo(palette)
                    isChecked = selected
                    contentDescription = "Use ${voice.locale.displayName}"
                })
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    preferences.voiceSelection = VoiceSelection.SystemVoice(voice.name)
                    engine?.voice = voice
                    engine?.speak("Narratify reads privately, right on your device.", TextToSpeech.QUEUE_FLUSH, null, "voice-preview")
                    renderVoices()
                    renderNeuralPack()
                }
            }, topMargin(Gap.XS))
        }
    }

    private fun renderUnavailable() {
        list.removeAllViews()
        list.addView(card().apply { addView(label("No offline system voices are installed.", Type.BODY, UI_MUTED)) })
    }

    private fun renderNeuralPack(activity: PackActivity? = null) {
        neuralPack.removeAllViews()
        neuralPack.addView(sectionTitle("OFFLINE NEURAL VOICES"))
        NarratifyNeuralVoiceCatalog.kokoroVoices.forEach { entry ->
            val own = activity?.takeIf { it.packId == entry.packId }
            neuralPack.addView(
                neuralVoiceCard(entry = entry, progress = own?.progress, error = own?.error),
                topMargin(Gap.XS),
            )
        }
        neuralPack.addView(
            label(
                "Voices share one downloaded speech model. It is removed with the last voice. Every pack contains data only; Narratify verifies its approved size and SHA-256 before activation.",
                Type.MICRO,
                UI_MUTED,
            ),
            topMargin(Gap.SM),
        )
        neuralPack.addView(
            textAction("Licenses & attribution") { openLicenseNotices() },
            topMargin(Gap.XS),
        )
        // Offered only when there is storage to reclaim. The shared model has no card of its own,
        // so if a first install finishes the model and then fails on the voice, this is the only
        // way to get those 329 MB back.
        val reclaimable = !neuralPackChecking && downloadingPackId == null &&
            (installedVoicePackIds.isNotEmpty() || modelPackInstalled)
        if (reclaimable) {
            neuralPack.addView(
                destructiveAction("Remove all offline voices") { confirmRemoveAllNeuralPacks() },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Gap.MD) },
            )
        }
    }

    private fun neuralVoiceCard(
        entry: NeuralVoicePackCatalogEntry,
        progress: Pair<Long, Long>?,
        error: String?,
    ) = card().apply {
        val installed = entry.packId in installedVoicePackIds
        addView(label(entry.displayName, Type.TITLE, UI_INK, Typeface.BOLD, serif = true))
        addView(label(entry.detail, Type.BODY, UI_MUTED), topMargin(Gap.XS))

        val status = when {
            error != null -> error
            progress != null -> "Downloading ${formatMegabytes(progress.first)} of ${formatMegabytes(progress.second)}"
            neuralPackChecking -> "Checking installed packs…"
            installed -> "Downloaded · offline"
            !entry.canDownload -> "Awaiting licensing approval"
            // Once the shared model is on disk a voice is half a megabyte, and advising Wi-Fi for
            // that would be noise the reader learns to ignore.
            modelPackInstalled -> "${formatMegabytes(entry.downloadBytes(true))} download"
            else -> "${formatMegabytes(entry.downloadBytes(false))} download · Wi-Fi recommended"
        }
        addView(label(status, Type.LABEL, if (error == null) UI_ACCENT else palette.danger, Typeface.BOLD), topMargin(Gap.SM))

        val downloading = progress != null
        // The executor runs one install at a time, so a second card's Download would only queue
        // behind the first — with no progress and no way to cancel it once the first finishes.
        val elsewhereBusy = neuralPackChecking || (downloadingPackId != null && !downloading)
        val actionText = when {
            downloading -> "Cancel download"
            neuralPackChecking -> "Checking…"
            installed -> "Remove voice"
            entry.canDownload -> "Download"
            else -> "Download unavailable"
        }
        val actionEnabled = downloading || (!elsewhereBusy && (installed || entry.canDownload))
        val destructive = installed || downloading
        val action = {
            when {
                downloading -> cancelRequestedFor.set(entry.packId)
                installed -> removeNeuralPack(entry)
                else -> confirmNeuralPackDownload(entry)
            }
        }
        addView(
            if (destructive) destructiveAction(actionText, actionEnabled, action)
            else primaryAction(actionText, actionEnabled, action),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Gap.MD) },
        )
        if (installed) addView(useNeuralVoiceRow(entry), topMargin(Gap.SM))
    }

    /**
     * An installed voice is never forced on the reader: this row is the only thing that makes
     * Kokoro the primary voice, and unchecking it hands narration back to the system voices.
     */
    private fun useNeuralVoiceRow(entry: NeuralVoicePackCatalogEntry) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val chosen = preferences.voiceSelection == VoiceSelection.NeuralVoice(entry.packId)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Use as primary voice", Type.BODY, UI_INK, Typeface.BOLD))
            addView(label(if (chosen) "Narratify reads with this voice" else "Read aloud uses another voice", Type.MICRO, UI_MUTED), topMargin(Gap.XXS))
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(Switch(context).apply {
            tintTo(palette)
            isChecked = chosen
            contentDescription = "Use ${entry.displayName} as the primary voice"
            setOnCheckedChangeListener { _, value ->
                preferences.voiceSelection =
                    if (value) VoiceSelection.NeuralVoice(entry.packId) else VoiceSelection.Automatic
                renderNeuralPack()
                renderVoices()
            }
        })
    }

    private fun confirmNeuralPackDownload(entry: NeuralVoicePackCatalogEntry) {
        AlertDialog.Builder(context)
            .setTitle("Download ${entry.displayName}?")
            .setMessage("This data-only voice pack uses ${formatMegabytes(entry.downloadBytes(modelPackInstalled))}. Wi-Fi is recommended. After installation, narration stays on this device and works offline.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download") { _, _ -> downloadNeuralPack(entry) }
            .show()
    }

    private fun downloadNeuralPack(entry: NeuralVoicePackCatalogEntry) {
        // Clears only a stale cancellation aimed at this very pack, so starting one download can
        // never resurrect another the reader just cancelled.
        cancelRequestedFor.compareAndSet(entry.packId, null)
        downloadingPackId = entry.packId
        // An estimate, so the bar starts immediately; install() reports the authoritative total.
        renderNeuralPack(PackActivity(entry.packId, 0L to entry.downloadBytes(modelPackInstalled)))
        installerExecutor.execute {
            runCatching {
                packInstaller.install(
                    entry,
                    onProgress = { current, reported ->
                        mainHandler.post {
                            if (!released) renderNeuralPack(PackActivity(entry.packId, current to reported))
                        }
                    },
                    isCancelled = { cancelRequested(entry.packId) },
                )
            }.onSuccess {
                mainHandler.post {
                    if (!released) {
                        installedVoicePackIds = installedVoicePackIds + entry.packId
                        modelPackInstalled = true
                        downloadingPackId = null
                        // A freshly downloaded voice is what the reader asked for, so make it
                        // primary unless they had already pinned a specific system voice.
                        if (preferences.voiceSelection == VoiceSelection.Automatic) {
                            preferences.voiceSelection = VoiceSelection.NeuralVoice(entry.packId)
                        }
                        renderNeuralPack()
                        renderVoices()
                    }
                }
            }.onFailure { failure ->
                // A voice phase that fails or is cancelled can leave the shared model on disk with
                // nothing depending on it, so re-read installed state and let the bulk removal
                // offer to reclaim it.
                refreshInstalledState(
                    activity = PackActivity(
                        entry.packId,
                        error = failure.message ?: "The voice could not be downloaded",
                    ),
                    beforeRender = { downloadingPackId = null },
                )
            }
        }
    }

    private fun removeNeuralPack(entry: NeuralVoicePackCatalogEntry) {
        // Written on the installer thread, read on the main thread once the refresh lands.
        val removed = AtomicBoolean(false)
        refreshInstalledState(
            busy = true,
            work = { removed.set(packInstaller.remove(entry)) },
            beforeRender = {
                // Never leave the reader pointing at a voice that is no longer on disk.
                if (removed.get() && preferences.voiceSelection == VoiceSelection.NeuralVoice(entry.packId)) {
                    preferences.voiceSelection = VoiceSelection.Automatic
                }
                renderVoices()
            },
        )
    }

    /**
     * The one way installed state is re-read. [work] and the rescan both run on
     * [installerExecutor] — the scan digests the shared model, ~329 MB, so it must never run on a
     * render — and the two cached fields are republished on the main thread. Both halves are
     * wrapped and the busy state is always cleared, so work that throws can neither leave a card
     * stale forever nor leave the section stuck showing "Checking…".
     *
     * @param busy shows the checking state until the refresh lands, so a second tap cannot queue
     * another multi-gigabyte removal behind this one.
     */
    private fun refreshInstalledState(
        busy: Boolean = false,
        work: () -> Unit = {},
        activity: PackActivity? = null,
        beforeRender: () -> Unit = {},
    ) {
        if (busy) {
            // Posted rather than assigned inline: callers run on either thread, and only the main
            // thread may touch these views.
            mainHandler.post {
                if (!released) {
                    neuralPackChecking = true
                    renderNeuralPack()
                }
            }
        }
        installerExecutor.execute {
            runCatching { work() }
            val scanned = runCatching {
                packInstaller.installedPacks(
                    NarratifyNeuralVoiceCatalog.kokoroVoices,
                    NarratifyNeuralVoiceCatalog.kokoroModel,
                )
            }.getOrNull()
            mainHandler.post {
                if (!released) {
                    scanned?.let {
                        installedVoicePackIds = it.voicePackIds
                        modelPackInstalled = it.modelInstalled
                    }
                    neuralPackChecking = false
                    beforeRender()
                    renderNeuralPack(activity)
                }
            }
        }
    }

    /**
     * Opens the notices once the installed model pack's directory is known.
     *
     * The lookup runs on [installerExecutor]: resolving it verifies the pack it finds, digesting
     * the ~329 MB model, so a tap handler that resolved it inline would freeze the UI thread for
     * seconds. With nothing installed the directory is null and the dialog shows the built-in
     * notices alone, exactly as before.
     */
    private fun openLicenseNotices() {
        if (licenseLookupPending) return
        licenseLookupPending = true
        installerExecutor.execute {
            val directory = runCatching {
                packInstaller.installedDirectory(NarratifyNeuralVoiceCatalog.kokoroModel)
            }.getOrNull()
            mainHandler.post {
                licenseLookupPending = false
                if (!released) showLicenseNotices(context, directory)
            }
        }
    }

    private fun confirmRemoveAllNeuralPacks() {
        AlertDialog.Builder(context)
            .setTitle("Remove all offline voices?")
            .setMessage("This deletes every downloaded neural voice and the shared speech model they use. Read aloud goes back to an installed system voice. You can download them again at any time.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove all") { _, _ -> removeAllNeuralPacks() }
            .show()
    }

    /**
     * Spec: removes every voice and then the model. Removing the last voice already drops the
     * model, but a model left behind by a failed or cancelled voice download has no voice to take
     * it with it, so it is removed explicitly rather than stranded with no card to remove it from.
     */
    private fun removeAllNeuralPacks() {
        // A download in flight owns the same executor, so let it finish early rather than have it
        // reinstall what this removal is about to delete.
        cancelRequestedFor.set(CANCEL_EVERY)
        // Every removal re-scans storage and digests the shared model, so this takes seconds. The
        // busy state says so and keeps a second tap from queueing the same work behind this one.
        refreshInstalledState(
            busy = true,
            work = {
                try {
                    NarratifyNeuralVoiceCatalog.kokoroVoices.forEach { entry ->
                        runCatching { packInstaller.remove(entry) }
                    }
                    runCatching { packInstaller.remove(NarratifyNeuralVoiceCatalog.kokoroModel) }
                } finally {
                    // Released here, not on the main thread, so a download started afterwards is
                    // never cancelled by this removal's blanket request.
                    cancelRequestedFor.compareAndSet(CANCEL_EVERY, null)
                }
            },
            beforeRender = {
                // Never leave the reader pointing at a voice that is no longer on disk.
                val chosen = preferences.voiceSelection as? VoiceSelection.NeuralVoice
                if (chosen != null && chosen.packId !in installedVoicePackIds) {
                    preferences.voiceSelection = VoiceSelection.Automatic
                }
                renderVoices()
            },
        )
    }

    private fun formatMegabytes(bytes: Long): String = String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)

    private fun cancelRequested(packId: String): Boolean =
        cancelRequestedFor.get().let { it == packId || it == CANCEL_EVERY }

    fun release() {
        released = true
        cancelRequestedFor.set(CANCEL_EVERY)
        installerExecutor.shutdownNow()
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private companion object {
        /** Not a legal pack id, so it can only ever mean "stop whatever is in flight". */
        const val CANCEL_EVERY = "*"
    }
}

class SettingsScreen(
    context: Context,
    private val preferences: AppPreferences,
    onNavigate: (AppSection) -> Unit,
    private val onRefresh: () -> Unit,
) : DestinationScreen(context, AppSection.SETTINGS, onNavigate) {
    private val mainHandler = Handler(Looper.getMainLooper())
    /** True while a license-notice directory lookup is in flight, so one tap opens one dialog. */
    private var licenseLookupPending = false

    init { install(buildPage()) }

    /**
     * Opens the notices once the installed model pack's directory is known.
     *
     * The lookup runs off the main thread: resolving it verifies the pack it finds, digesting the
     * ~329 MB model, so a tap handler that resolved it inline would freeze the UI thread for
     * seconds. This screen is rebuilt on every preference change, so the lookup gets a one-shot
     * thread rather than an executor that would outlive the view. With nothing installed the
     * directory is null and the dialog shows the built-in notices alone, exactly as before.
     */
    private fun openLicenseNotices() {
        if (licenseLookupPending) return
        licenseLookupPending = true
        val installer = NeuralVoicePackInstaller(context.applicationContext)
        Thread({
            val directory = runCatching {
                installer.installedDirectory(NarratifyNeuralVoiceCatalog.kokoroModel)
            }.getOrNull()
            mainHandler.post {
                licenseLookupPending = false
                if (isAttachedToWindow) showLicenseNotices(context, directory)
            }
        }, "license-notices").start()
    }

    @Suppress("DEPRECATION")
    private fun buildPage() = android.widget.ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (tablet) Gap.XXL else Gap.LG), dp(if (tablet) Gap.XL else Gap.MD), dp(if (tablet) Gap.XXL else Gap.LG), dp(Gap.SECTION))
            addView(pageHeader("PREFERENCES", "Settings", "Tune reading, narration, and library behavior. Changes are stored only on this device."))
            addView(sectionTitle("READING"), topMargin(Gap.XL))
            addView(themeCard())
            addView(sliderCard("Text size", "${preferences.fontSize} pt", 15, 32, preferences.fontSize) { value, detail ->
                preferences.fontSize = value; detail.text = "$value pt"
            }, topMargin(Gap.SM))
            addView(sliderCard("Line spacing", "${preferences.lineSpacing}%", 110, 200, preferences.lineSpacing) { value, detail ->
                preferences.lineSpacing = value; detail.text = "$value%"
            }, topMargin(Gap.SM))
            addView(toggleCard("Keep screen awake", "While a text reader is open", preferences.keepScreenAwake) { preferences.keepScreenAwake = it }, topMargin(Gap.SM))
            addView(sectionTitle("NARRATION"), topMargin(Gap.XL))
            addView(sliderCard("Default speed", String.format(Locale.US, "%.2f×", preferences.speechRate), 50, 200, (preferences.speechRate * 100).toInt()) { value, detail ->
                preferences.speechRate = value / 100f; detail.text = String.format(Locale.US, "%.2f×", value / 100f)
            })
            addView(toggleCard("Word highlighting", "Follow spoken words in the text", preferences.highlightWords) { preferences.highlightWords = it }, topMargin(Gap.SM))
            addView(sectionTitle("LIBRARY"), topMargin(Gap.XL))
            addView(toggleCard("Compact bookshelf", "Show more covers in each row", preferences.compactLibrary) { preferences.compactLibrary = it; onRefresh() })
            addView(toggleCard("Newest imports first", "Turn off for alphabetical order", preferences.sortNewestFirst) { preferences.sortNewestFirst = it; onRefresh() }, topMargin(Gap.SM))
            addView(sectionTitle("PRIVACY & STORAGE"), topMargin(Gap.XL))
            addView(card().apply {
                addView(label("Local by design", Type.BODY_LARGE, UI_INK, Typeface.BOLD, serif = true))
                addView(label("Books, positions, preferences, and voice choices remain in Narratify's private app storage. No account or cloud sync is used.", Type.BODY, UI_MUTED), topMargin(Gap.XS))
            })
            addView(sectionTitle("DIAGNOSTICS"), topMargin(Gap.XL))
            addView(card().apply {
                addView(label("Speech engine details", Type.BODY_LARGE, UI_INK, Typeface.BOLD, serif = true))
                addView(label("Which voice model is loaded, where it came from, and how fast it renders audio.", Type.BODY, UI_MUTED), topMargin(Gap.XS))
                addView(textAction("Open") { context.startActivity(TtsDiagnosticsActivity.Launcher.intent(context)) }, topMargin(Gap.XS))
                isClickable = true
                isFocusable = true
                setOnClickListener { context.startActivity(TtsDiagnosticsActivity.Launcher.intent(context)) }
            })
            addView(sectionTitle("LEGAL"), topMargin(Gap.XL))
            addView(card().apply {
                addView(label("Licenses & notices", Type.BODY_LARGE, UI_INK, Typeface.BOLD, serif = true))
                addView(label("Kokoro-82M, ONNX Runtime, and the pronunciation dictionary shipped with the downloadable speech model.", Type.BODY, UI_MUTED), topMargin(Gap.XS))
                addView(textAction("Open") { openLicenseNotices() }, topMargin(Gap.XS))
                isClickable = true
                isFocusable = true
                setOnClickListener { openLicenseNotices() }
            })
            addView(label("Restore default settings", Type.BODY, palette.danger, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = outlineShape(palette.outline, Radius.INPUT)
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                setOnClickListener { preferences.reset(); onRefresh() }
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Gap.XXL) })
            addView(label("Narratify 0.1 · private reader", Type.MICRO, UI_MUTED).apply { gravity = Gravity.CENTER }, topMargin(Gap.XS))
        })
    }

    private fun themeCard() = card().apply {
        addView(label("Reading theme", Type.BODY_LARGE, UI_INK, Typeface.BOLD, serif = true))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            ReaderTheme.entries.forEach { theme ->
                val active = theme == preferences.readerTheme
                addView(label(theme.label, Type.LABEL, if (active) palette.onAccent else UI_INK, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    background = if (active) surfaceShape(UI_ACCENT, Radius.INPUT) else outlineShape(palette.outline, Radius.INPUT)
                    contentDescription = if (active) "${theme.label} theme, selected" else "${theme.label} theme"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { preferences.readerTheme = theme; onRefresh() }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(Gap.XS); topMargin = dp(Gap.SM) })
            }
        })
    }

    private fun sliderCard(title: String, value: String, minimum: Int, maximum: Int, current: Int, changed: (Int, TextView) -> Unit) = card().apply {
        val detail = label(value, Type.BODY, UI_ACCENT, Typeface.BOLD)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, Type.BODY_LARGE, UI_INK, Typeface.BOLD), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(detail)
        })
        addView(SeekBar(context).apply {
            tintTo(palette)
            max = maximum - minimum
            progress = current - minimum
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) changed(progress + minimum, detail) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, topMargin(Gap.SM))
    }

    private fun toggleCard(title: String, detail: String, checked: Boolean, changed: (Boolean) -> Unit) = card().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, Type.BODY_LARGE, UI_INK, Typeface.BOLD))
            addView(label(detail, Type.LABEL, UI_MUTED), topMargin(Gap.XXS))
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(Switch(context).apply {
            tintTo(palette)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        })
    }
}
