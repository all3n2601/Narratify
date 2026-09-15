package app.narratify

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle

class MainActivity : Activity() {
    private lateinit var repository: LocalLibraryRepository
    private lateinit var preferences: AppPreferences
    private var reader: TextReaderScreen? = null
    private var nowPlaying: NowPlayingScreen? = null
    private var voices: VoicesScreen? = null
    private var catalog: CatalogScreen? = null
    private var section = AppSection.LIBRARY
    private var miniPlayer: MiniPlayerBar? = null
    private var miniPlayerPalette: AppPalette? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LocalLibraryRepository(this)
        preferences = AppPreferences(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { navigateBack() }
        }
        showSection(AppSection.LIBRARY)
        openAudiobookFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Settings live on another screen, so an open reader re-reads them when it comes back.
        reader?.applyPreferences()
    }

    private fun showLibrary() {
        showSection(AppSection.LIBRARY)
    }

    /**
     * Wraps a destination so the mini player floats above its own bottom bar (or beside the
     * tablet rail) instead of being rebuilt into every screen's layout.
     */
    private fun withMiniPlayer(
        content: android.view.View,
        showBar: Boolean = true,
        palette: AppPalette = preferences.palette(),
    ): android.view.View {
        if (!showBar) {
            releaseMiniPlayer()
            return content
        }
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        // One bar, moved between destinations: rebuilding it would reconnect to the media
        // session on every navigation.
        if (miniPlayer == null || miniPlayerPalette != palette) {
            miniPlayer?.release()
            miniPlayer = MiniPlayerBar(
                context = this,
                palette = palette,
                followAudiobooks = repository.books().any { it.format in AUDIO_FORMATS },
                onOpen = ::openFromMiniPlayer,
            )
            miniPlayerPalette = palette
        }
        val bar = requireNotNull(miniPlayer)
        (bar.parent as? android.view.ViewGroup)?.removeView(bar)
        return android.widget.FrameLayout(this).apply {
            addView(content, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            addView(
                bar,
                android.widget.FrameLayout.LayoutParams(MATCH, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.BOTTOM
                    leftMargin = dp(if (tablet) 116 else 12)
                    rightMargin = dp(12)
                    bottomMargin = dp(if (tablet) 16 else 84)
                },
            )
        }
    }

    private fun openFromMiniPlayer(state: MiniPlayer.State) {
        repository.books().firstOrNull { it.id == state.id }?.let(::openBook)
    }

    private fun showSection(destination: AppSection) {
        reader?.save()
        // Only the view goes away; ReaderNarration keeps the speech session running.
        reader?.release()
        reader = null
        nowPlaying?.close()
        nowPlaying = null
        voices?.release()
        voices = null
        catalog?.release()
        catalog = null
        section = destination
        when (destination) {
            AppSection.LIBRARY -> setContentView(
                withMiniPlayer(
                    LibraryScreen(
                        context = this,
                        localBooks = repository.books(),
                        hiddenBooks = repository.hiddenBooks(),
                        onImport = ::openPicker,
                        onOpenLocal = ::openBook,
                        onPlayLocal = { openBook(it, autoPlay = true) },
                        onSetHiddenLocal = ::setBookHidden,
                        onRemoveLocal = ::removeBook,
                        onNavigate = ::showSection,
                        preferences = preferences,
                    ),
                    showBar = !hasLibraryPlayerColumn(),
                    palette = enchantedLibraryPalette(),
                ),
            )
            AppSection.DISCOVER -> {
                catalog = CatalogScreen(this, repository, ::showSection, ::showLibrary)
                setContentView(withMiniPlayer(requireNotNull(catalog), palette = enchantedLibraryPalette()))
            }
            AppSection.VOICES -> {
                voices = VoicesScreen(this, preferences, ::showSection)
                setContentView(withMiniPlayer(requireNotNull(voices), palette = enchantedLibraryPalette()))
            }
            AppSection.SETTINGS -> setContentView(
                withMiniPlayer(
                    SettingsScreen(this, preferences, ::showSection) {
                        showSection(AppSection.SETTINGS)
                    },
                    palette = enchantedLibraryPalette(),
                ),
            )
        }
        applyWindowPalette(enchantedLibraryPalette())
    }

    private fun openPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/epub+zip", "text/plain", "text/markdown",
                "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/x-m4b", "audio/audiobook",
                "application/octet-stream",
            ))
        }, IMPORT_BOOK)
    }

    @Deprecated("Activity result API requires an additional AndroidX dependency; this callback remains lifecycle-safe for this single picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_BOOK && resultCode == RESULT_OK) {
            val result = data?.data?.let(repository::import)
            if (result?.isSuccess == true) showLibrary()
            else showLibraryWithError(result?.exceptionOrNull()?.message ?: "No file was selected.")
        }
    }

    private fun showLibraryWithError(message: String) {
        section = AppSection.LIBRARY
        setContentView(withMiniPlayer(
            LibraryScreen(
                context = this,
                localBooks = repository.books(),
                hiddenBooks = repository.hiddenBooks(),
                onImport = ::openPicker,
                onOpenLocal = ::openBook,
                onPlayLocal = { openBook(it, autoPlay = true) },
                onSetHiddenLocal = ::setBookHidden,
                onRemoveLocal = ::removeBook,
                initialError = message,
                onNavigate = ::showSection,
                preferences = preferences,
            ),
            palette = enchantedLibraryPalette(),
        ))
        applyWindowPalette(enchantedLibraryPalette())
    }

    private fun setBookHidden(book: LocalBook, hidden: Boolean) {
        repository.setHidden(book.id, hidden)
            .onSuccess { showLibrary() }
            .onFailure { showLibraryWithError(it.message ?: "The book could not be updated.") }
    }

    private fun removeBook(book: LocalBook) {
        if (ReaderNarration.isActive(book.id)) ReaderNarration.release()
        if (EpubNarration.session()?.bookId == book.id) EpubNarration.stop()
        if (book.format in AUDIO_FORMATS) {
            stopService(Intent(this, AudiobookPlaybackService::class.java))
        }
        releaseMiniPlayer()
        repository.delete(book.id)
            .onSuccess { showLibrary() }
            .onFailure { showLibraryWithError(it.message ?: "The book could not be removed.") }
    }

    private fun releaseMiniPlayer() {
        miniPlayer?.let { bar ->
            (bar.parent as? android.view.ViewGroup)?.removeView(bar)
            bar.release()
        }
        miniPlayer = null
        miniPlayerPalette = null
    }

    /** The landscape library already shows a full player column; a floating bar would repeat it. */
    private fun hasLibraryPlayerColumn(): Boolean =
        resources.configuration.smallestScreenWidthDp >= 600 && resources.configuration.screenWidthDp >= 840

    private fun openBook(book: LocalBook) = openBook(book, autoPlay = false)

    /** [autoPlay] comes from the player column, where pressing play should start reading at once. */
    private fun openBook(book: LocalBook, autoPlay: Boolean) {
        releaseMiniPlayer()
        if (book.format == "EPUB") {
            startActivity(EpubReaderActivity.intent(this, book.id, book.title, autoPlay))
            return
        }
        if (book.format in AUDIO_FORMATS) {
            nowPlaying = NowPlayingScreen(this, book, repository.audioPositionMs(book.id), ::showLibrary)
            setContentView(requireNotNull(nowPlaying))
            return
        }
        runCatching { repository.document(book.id) }
            .onSuccess { document ->
                reader = TextReaderScreen(this, book, document, repository.position(book.id), autoPlay,
                    { repository.savePosition(book.id, it) }, ::showLibrary, preferences)
                setContentView(requireNotNull(reader))
            }
            .onFailure { showLibraryWithError(it.message ?: "This book could not be opened.") }
    }

    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) navigateBack()
        else super.onBackPressed()
    }

    private fun navigateBack() {
        if (reader?.exitFullscreenIfNeeded() == true) return
        if (reader != null || nowPlaying != null || section != AppSection.LIBRARY) showLibrary() else finishAfterTransition()
    }

    override fun onPause() {
        reader?.save()
        super.onPause()
    }

    override fun onRestart() {
        super.onRestart()
        if (reader == null && nowPlaying == null) showSection(section)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showLibrary()
        openAudiobookFromIntent(intent)
    }

    private fun openAudiobookFromIntent(source: Intent) {
        val publicationId = source.getStringExtra(EXTRA_OPEN_AUDIOBOOK_ID) ?: return
        source.removeExtra(EXTRA_OPEN_AUDIOBOOK_ID)
        repository.books()
            .firstOrNull { it.id == publicationId && it.format in AUDIO_FORMATS }
            ?.let(::openBook)
    }

    override fun onDestroy() {
        releaseMiniPlayer()
        // A rotation destroys the activity too, so narration only ends when the user is leaving.
        if (isFinishing) ReaderNarration.release()
        reader?.release()
        reader = null
        nowPlaying?.close()
        nowPlaying = null
        voices?.release()
        voices = null
        catalog?.release()
        catalog = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        private const val IMPORT_BOOK = 2001
        internal const val EXTRA_OPEN_AUDIOBOOK_ID = "app.narratify.extra.OPEN_AUDIOBOOK_ID"
        private val AUDIO_FORMATS = setOf("MP3", "M4A", "M4B")
    }
}
