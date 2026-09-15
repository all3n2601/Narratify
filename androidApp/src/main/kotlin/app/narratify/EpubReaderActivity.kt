package app.narratify

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import app.narratify.shared.data.StoredChapter
import java.util.Locale
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.getOrElse

/** Chromeless Readium host. Narratify owns the controls and position persistence. */
@OptIn(ExperimentalReadiumApi::class)
class EpubReaderActivity : FragmentActivity(), EpubNavigatorFragment.Listener {
    private lateinit var repository: LocalLibraryRepository
    private var opened: OpenedEpub? = null
    private var navigator: EpubNavigatorFragment? = null
    private var ttsInitializing = false
    private var ttsPlaying = false
    private lateinit var narrationButton: ImageButton
    private lateinit var controlBar: LinearLayout
    private lateinit var topBar: View
    private lateinit var restoreChromeButton: View
    private lateinit var root: FrameLayout
    private lateinit var navigatorContainer: FrameLayout
    private lateinit var outlinePanel: ReaderOutlinePanel
    private var outlineEntries: List<OutlineEntry> = emptyList()
    private var outlineLinks: Map<String, Link> = emptyMap()
    private var outlineOverlay: ReaderOutlineOverlay? = null
    private lateinit var playPauseButton: TextView
    private lateinit var narrationStatus: TextView
    private lateinit var speedLabel: TextView
    private lateinit var chapterAudioButton: View
    private var fullscreen = false
    private var restoreOutlineAfterFullscreen = false
    /** The EPUB chrome follows the same theme as the rest of the app, not a fixed cream bar. */
    private val readerPalette: AppPalette by lazy { AppPreferences(this).palette() }

    override fun onCreate(savedInstanceState: Bundle?) {
        repository = LocalLibraryRepository(this)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val result = runCatching {
            requireNotNull(bookId) { "The EPUB library entry is missing." }
            repository.openEpub(bookId)
        }
        opened = result.getOrNull()
        opened?.let {
            supportFragmentManager.fragmentFactory = it.navigatorFactory.createFragmentFactory(
                initialLocator = it.initialLocator,
                listener = this,
            )
        }
        super.onCreate(savedInstanceState)

        if (opened == null) {
            Toast.makeText(this, result.exceptionOrNull()?.message ?: "This EPUB could not be opened.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        root = FrameLayout(this).apply { id = ROOT_ID }
        navigatorContainer = FrameLayout(this).apply { id = NAVIGATOR_ID }
        // The page has to end above the controls, otherwise the last line sits under them.
        root.addView(
            navigatorContainer,
            FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = dp(CONTROL_BAR_HEIGHT_DP) },
        )
        topBar = makeTopBar()
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, dp(72)).apply { gravity = Gravity.TOP })
        controlBar = makeControlBar()
        root.addView(
            controlBar,
            FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM },
        )
        restoreChromeButton = makeFullscreenButton(
            NarratifyIcon.Glyph.RESTORE,
            "Exit fullscreen reading",
        ) { setFullscreen(false) }.apply {
            visibility = View.GONE
            background = surfaceShape(readerPalette.surface, Radius.INPUT, readerPalette.outline)
            elevation = dp(4).toFloat()
        }
        root.addView(restoreChromeButton, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(Gap.SM)
            marginEnd = dp(Gap.SM)
        })
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) {
                    setFullscreen(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            supportFragmentManager.commitNow {
                add(NAVIGATOR_ID, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
            }
        }
        navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
        buildOutline()
        applyReaderPreferences()
        observeOutlinePosition()
        requestNotificationPermission()
        // A chapter tap in the Chapters screen: move the reader to the matched section, the same
        // way tapping that section in the outline already does.
        intent.getIntExtra(EXTRA_TARGET_SPINE_INDEX, -1).takeIf { it >= 0 }?.let(::openOutlineEntry)
        updateChapterAudioAvailability()
        // Opened from the library player column: start narrating without a second tap.
        if (intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)) controlBar.post { toggleNarration() }
    }

    /**
     * The EPUB view is Readium's, so Narratify's reading settings have to be handed to it
     * explicitly. Publisher styles are disabled because Readium ignores text size and line
     * spacing while they are on.
     */
    private fun applyReaderPreferences() {
        val preferences = AppPreferences(this)
        applyWindowPalette(readerPalette)
        window.decorView.keepScreenOn = preferences.keepScreenAwake
        val theme = when (preferences.readerTheme) {
            ReaderTheme.LIGHT -> Theme.LIGHT
            ReaderTheme.SEPIA -> Theme.SEPIA
            ReaderTheme.DARK, ReaderTheme.BLACK -> Theme.DARK
        }
        navigator?.submitPreferences(
            EpubPreferences(
                backgroundColor = if (preferences.readerTheme == ReaderTheme.BLACK) ReadiumColor(android.graphics.Color.BLACK) else null,
                textColor = if (preferences.readerTheme == ReaderTheme.BLACK) ReadiumColor(0xFFE6E1E5.toInt()) else null,
                fontSize = preferences.fontSize / DEFAULT_FONT_SIZE_PT,
                lineHeight = preferences.lineSpacing / 100.0,
                publisherStyles = false,
                theme = theme,
            )
        )
    }

    override fun onStart() {
        super.onStart()
        EpubNarration.observe(narrationObserver)
    }

    override fun onStop() {
        EpubNarration.stopObserving(narrationObserver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyReaderPreferences()
    }

    private fun toggleNarration() {
        val session = EpubNarration.session()
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return
        if (session != null && EpubNarration.isActive(bookId)) {
            if (session.playing) EpubNarration.pause() else EpubNarration.play()
            return
        }
        if (ttsInitializing) return
        ttsInitializing = true
        narrationButton.isEnabled = false
        lifecycleScope.launch {
            val outcome = EpubNarration.start(
                context = this@EpubReaderActivity,
                bookId = bookId,
                title = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty(),
                initialLocator = navigator?.currentLocator?.value,
                preferences = AppPreferences(this@EpubReaderActivity),
            )
            ttsInitializing = false
            narrationButton.isEnabled = true
            outcome.onFailure { failure ->
                Toast.makeText(
                    this@EpubReaderActivity,
                    failure.message ?: "Read aloud could not start for this book.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * The EPUB reader used to expose a single speaker icon, so speed, stop, and skipping were
     * unreachable while a book was being read. This is the same control set as the text reader,
     * plus utterance skipping, which only Readium can offer.
     */
    private fun makeControlBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(readerPalette.surface)
        padDp(Gap.SM, Gap.XS, Gap.SM, Gap.XS)
        playPauseButton = styledText("Read", Type.BODY, readerPalette.onAccent, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = surfaceShape(readerPalette.accent, Radius.INPUT)
        }
        addView(
            touchTarget(playPauseButton, "Start read aloud") { toggleNarration() },
            LinearLayout.LayoutParams(dp(84), dp(46)),
        )
        addView(
            touchTarget(
                styledText("Stop", Type.LABEL, readerPalette.accent, Typeface.BOLD),
                "Stop read aloud",
            ) { stopNarration() },
            LinearLayout.LayoutParams(dp(64), dp(46)).apply { marginStart = dp(Gap.XXS) },
        )
        addView(
            touchTarget(iconView(NarratifyIcon.Glyph.REWIND, readerPalette.accent, 20), "Previous sentence") {
                EpubNarration.skipToPrevious()
            },
            LinearLayout.LayoutParams(dp(48), dp(46)),
        )
        addView(
            touchTarget(iconView(NarratifyIcon.Glyph.FORWARD, readerPalette.accent, 20), "Next sentence") {
                EpubNarration.skipToNext()
            },
            LinearLayout.LayoutParams(dp(48), dp(46)),
        )
        narrationStatus = styledText("Read aloud is idle", Type.MICRO, readerPalette.muted).apply { maxLines = 2 }
        speedLabel = styledText(formatSpeed(AppPreferences(context).speechRate), Type.LABEL, readerPalette.ink, Typeface.BOLD)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(narrationStatus)
                addView(speedLabel)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(Gap.XS)
                marginEnd = dp(Gap.XS)
            },
        )
        addView(
            touchTarget(styledText("−", Type.BODY_LARGE, readerPalette.accent, Typeface.BOLD), "Decrease reading speed") {
                changeSpeed(-.1f)
            },
            LinearLayout.LayoutParams(dp(46), dp(46)),
        )
        addView(
            touchTarget(styledText("+", Type.BODY_LARGE, readerPalette.accent, Typeface.BOLD), "Increase reading speed") {
                changeSpeed(.1f)
            },
            LinearLayout.LayoutParams(dp(46), dp(46)),
        )
        // Offered only when this section has a matched narration chapter; see
        // updateChapterAudioAvailability(). Hidden by default so it never flashes before the
        // first position check runs.
        chapterAudioButton = touchTarget(
            iconView(NarratifyIcon.Glyph.PLAY, readerPalette.accent, 18),
            "Play the narration for this section",
        ) {
            narrationChapterForCurrentPosition()?.let(::playNarrationChapter)
        }.apply { visibility = View.GONE }
        addView(chapterAudioButton, LinearLayout.LayoutParams(dp(48), dp(46)))
    }

    private fun changeSpeed(delta: Float) {
        val preferences = AppPreferences(this)
        val speed = (preferences.speechRate + delta).coerceIn(.5f, 2f)
        preferences.speechRate = speed
        speedLabel.text = formatSpeed(speed)
        // Readium applies preferences live, so an open book changes pace without restarting.
        EpubNarration.setSpeed(speed.toDouble())
    }

    private fun stopNarration() {
        EpubNarration.stop()
        setNarrationPlaying(false)
        narrationStatus.text = "Read aloud is idle"
    }

    /**
     * Background narration posts a media notification. Without the runtime permission it still
     * plays, so this is asked for once and never blocks starting a book.
     */
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
    }

    private fun formatSpeed(value: Float): String = String.format(Locale.US, "%.2f×", value)

    /** The bar mirrors whatever the holder reports, whoever started it. */
    private val narrationObserver: (EpubNarration.Session?) -> Unit = { session ->
        runOnUiThread {
            val mine = session?.takeIf { it.bookId == intent.getStringExtra(EXTRA_BOOK_ID) }
            setNarrationPlaying(mine?.playing == true)
            narrationStatus.text = when {
                mine == null -> "Read aloud is idle"
                mine.playing -> "${mine.message} · ${formatSpeed(AppPreferences(this).speechRate)}"
                else -> mine.message
            }
        }
    }

    private fun setNarrationPlaying(playing: Boolean) {
        ttsPlaying = playing
        narrationButton.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_lock_silent_mode_off
        )
        narrationButton.contentDescription = if (playing) "Pause narration" else "Read aloud"
        if (::playPauseButton.isInitialized) {
            playPauseButton.text = if (playing) "Pause" else "Read"
            (playPauseButton.parent as? View)?.contentDescription =
                if (playing) "Pause read aloud" else "Start read aloud"
        }
    }

    /**
     * A publication that ships no table of contents still has a reading order, which is a
     * coarser but honest outline. Neither is allowed to stop the book from opening.
     */
    private fun buildOutline() {
        outlinePanel = ReaderOutlinePanel(this, readerPalette, onSelect = ::openOutlineEntry)
        val publication = opened?.publication ?: return
        val tableOfContents = runCatching { publication.tableOfContents }.getOrDefault(emptyList())
        val readingOrder = runCatching { publication.readingOrder }.getOrDefault(emptyList())
        outlineEntries = ReaderOutline.fromTableOfContents(convert(tableOfContents), convert(readingOrder))
        outlineLinks = buildMap { collect(tableOfContents, this); collect(readingOrder, this) }
        outlinePanel.setEntries(outlineEntries, currentOutlineIndex())
        if (isLandscape() && AppPreferences(this).readerOutlineOpen) showOutlineColumn()
    }

    private fun convert(links: List<Link>): List<OutlineLink> = links.map {
        OutlineLink(title = it.title, href = it.url().toString(), children = convert(it.children))
    }

    private fun collect(links: List<Link>, into: MutableMap<String, Link>) {
        links.forEach {
            into.putIfAbsent(it.url().toString(), it)
            collect(it.children, into)
        }
    }

    private fun observeOutlinePosition() {
        val locators = navigator?.currentLocator ?: return
        lifecycleScope.launch {
            locators.collect {
                outlinePanel.setCurrentIndex(ReaderOutline.currentIndex(outlineEntries, it.href.toString()))
                updateChapterAudioAvailability()
            }
        }
    }

    private fun currentOutlineIndex(): Int {
        val href = navigator?.currentLocator?.value?.href?.toString() ?: return -1
        return ReaderOutline.currentIndex(outlineEntries, href)
    }

    /**
     * The outline index the reading order already gives every section, reused as the "spine
     * index" a narration chapter is matched against — the same index [narrationSpineTitles] in
     * `MainActivity` labels and [ChapterMapping] stores.
     */
    private fun currentSpineIndex(): Int? = currentOutlineIndex().takeIf { it >= 0 }

    /**
     * Only shown when a chapter actually maps to this spine item. A book with a narration whose
     * chapters were never matched will not offer anything here, which is correct: there is nothing
     * to seek to, and a button that guessed would be worse than no button.
     */
    private fun narrationChapterForCurrentPosition(): StoredChapter? {
        val spineIndex = currentSpineIndex() ?: return null
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return null
        return repository.chapterForSpine(bookId, spineIndex)
    }

    private fun updateChapterAudioAvailability() {
        if (!::chapterAudioButton.isInitialized) return
        chapterAudioButton.visibility = if (narrationChapterForCurrentPosition() != null) View.VISIBLE else View.GONE
    }

    /** Hands off to `MainActivity`, which owns the audio session; the reader has no player of its own. */
    private fun playNarrationChapter(chapter: StoredChapter) {
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_AUDIOBOOK_ID, bookId)
            putExtra(MainActivity.EXTRA_CHAPTER_START_MS, chapter.startMs)
        })
    }

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Landscape shows the outline beside the page; portrait has no width to spare and shows it
     * over the page instead.
     */
    private fun toggleOutline() {
        if (isLandscape()) {
            if (outlinePanel.parent != null) hideOutlineColumn() else showOutlineColumn()
            AppPreferences(this).readerOutlineOpen = outlinePanel.parent != null
        } else {
            if (outlineOverlay != null) dismissOutlineOverlay() else showOutlineOverlay()
        }
    }

    private fun showOutlineColumn() {
        if (outlinePanel.parent != null) return
        val width = outlineColumnWidth()
        root.addView(
            outlinePanel,
            FrameLayout.LayoutParams(width, MATCH).apply {
                gravity = Gravity.START or Gravity.TOP
                topMargin = dp(72)
                bottomMargin = dp(CONTROL_BAR_HEIGHT_DP)
            },
        )
        setNavigatorInset(width)
    }

    private fun hideOutlineColumn() {
        if (outlinePanel.parent == null) return
        root.removeView(outlinePanel)
        setNavigatorInset(0)
    }

    /** The page is narrowed rather than covered, so the outline can be read alongside it. */
    private fun setNavigatorInset(left: Int) {
        val params = navigatorContainer.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = left
        navigatorContainer.layoutParams = params
    }

    private fun setFullscreen(enabled: Boolean) {
        if (fullscreen == enabled) return
        fullscreen = enabled
        if (enabled) {
            restoreOutlineAfterFullscreen = outlinePanel.parent != null
            hideOutlineColumn()
            dismissOutlineOverlay()
        }
        topBar.visibility = if (enabled) View.GONE else View.VISIBLE
        controlBar.visibility = if (enabled) View.GONE else View.VISIBLE
        restoreChromeButton.visibility = if (enabled) View.VISIBLE else View.GONE
        (navigatorContainer.layoutParams as FrameLayout.LayoutParams).also { params ->
            params.bottomMargin = if (enabled) 0 else dp(CONTROL_BAR_HEIGHT_DP)
            navigatorContainer.layoutParams = params
        }
        setReaderFullscreen(enabled, readerPalette)
        if (!enabled && restoreOutlineAfterFullscreen && isLandscape()) showOutlineColumn()
        if (!enabled) restoreOutlineAfterFullscreen = false
    }

    private fun showOutlineOverlay() {
        val overlay = ReaderOutlineOverlay(
            this,
            readerPalette,
            onSelect = ::openOutlineEntry,
            onDismiss = ::dismissOutlineOverlay,
        )
        overlay.panel.setEntries(outlineEntries, currentOutlineIndex())
        outlineOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun dismissOutlineOverlay() {
        outlineOverlay?.let { root.removeView(it) }
        outlineOverlay = null
    }

    private fun outlineColumnWidth(): Int =
        minOf(dp(320), (resources.displayMetrics.widthPixels * 0.32f).toInt())

    private fun openOutlineEntry(index: Int) {
        val target = outlineEntries.getOrNull(index)?.target as? OutlineTarget.Resource ?: return
        outlineLinks[target.href]?.let { navigator?.go(it, animated = true) }
        dismissOutlineOverlay()
    }

    /**
     * The activity keeps itself across rotation, so nothing rebuilds the layout for it. The
     * outline has to be moved between its column and its overlay by hand.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dismissOutlineOverlay()
        hideOutlineColumn()
        if (!fullscreen && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            AppPreferences(this).readerOutlineOpen
        ) {
            showOutlineColumn()
        }
    }

    private fun makeTopBar(): View = FrameLayout(this).apply {
        setPadding(dp(12))
        setBackgroundColor(readerPalette.surface)
        addView(ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "Back to library"
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finishAfterTransition() }
        }, FrameLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        addView(TextView(context).apply {
            text = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
            textSize = 17f
            setTextColor(readerPalette.ink)
            gravity = Gravity.CENTER
            maxLines = 1
        }, FrameLayout.LayoutParams(MATCH, dp(48)).apply {
            gravity = Gravity.CENTER
            marginStart = dp(64)
            marginEnd = dp(160)
        })
        addView(makeFullscreenButton(
            NarratifyIcon.Glyph.FULLSCREEN,
            "Enter fullscreen reading",
        ) { setFullscreen(true) }, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            marginEnd = dp(96)
        })
        addView(ImageButton(context).apply {
            setImageDrawable(NarratifyIcon(NarratifyIcon.Glyph.CONTENTS, readerPalette.ink))
            contentDescription = "Contents"
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { toggleOutline() }
        }, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            marginEnd = dp(48)
        })
        narrationButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            contentDescription = "Read aloud"
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { toggleNarration() }
        }
        addView(narrationButton, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
    }

    private fun makeFullscreenButton(
        glyph: NarratifyIcon.Glyph,
        description: String,
        action: () -> Unit,
    ): ImageButton = ImageButton(this).apply {
        setImageDrawable(NarratifyIcon(glyph, readerPalette.ink))
        setPadding(dp(13))
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        contentDescription = description
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    override fun onPause() {
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val locator = navigator?.currentLocator?.value
        if (bookId != null && locator != null) repository.saveEpubPosition(bookId, locator)
        super.onPause()
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
            .onFailure {
                Toast.makeText(this, "No app is available to open this link.", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroy() {
        if (fullscreen) setReaderFullscreen(false, readerPalette)
        // Narration belongs to EpubNarration now: leaving the reader must not silence the book.
        val publication = opened?.publication
        opened = null
        super.onDestroy()
        publication?.close()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_BOOK_ID = "book-id"
        private const val EXTRA_BOOK_TITLE = "book-title"
        private const val EXTRA_AUTO_PLAY = "auto-play"
        private const val EXTRA_TARGET_SPINE_INDEX = "target-spine-index"
        private const val NAVIGATOR_TAG = "narratify-epub-navigator"
        private const val ROOT_ID = 0x4e415201
        private const val NAVIGATOR_ID = 0x4e415202
        private const val MATCH = -1
        private const val DEFAULT_FONT_SIZE_PT = 20.0
        private const val CONTROL_BAR_HEIGHT_DP = 62

        fun intent(
            context: Context,
            bookId: String,
            title: String,
            autoPlay: Boolean = false,
            chapterSpineIndex: Int? = null,
        ): Intent =
            Intent(context, EpubReaderActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_BOOK_TITLE, title)
                .putExtra(EXTRA_AUTO_PLAY, autoPlay)
                .apply { chapterSpineIndex?.let { putExtra(EXTRA_TARGET_SPINE_INDEX, it) } }
    }
}
