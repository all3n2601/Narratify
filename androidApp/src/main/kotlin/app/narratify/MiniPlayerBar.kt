package app.narratify

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

/**
 * The bar that keeps whatever is speaking within reach while the reader browses other pages.
 * It follows both sources of playback — a text narration held by [ReaderNarration] and an
 * audiobook owned by the media session — and disappears when neither is running.
 */
@SuppressLint("ViewConstructor")
class MiniPlayerBar(
    context: Context,
    private val palette: AppPalette,
    /** Skips binding the audiobook session when the library holds no audiobooks to control. */
    private val followAudiobooks: Boolean,
    private val onOpen: (MiniPlayer.State) -> Unit,
) : LinearLayout(context) {
    private val artwork = TextView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val playPause = android.widget.ImageView(context)
    private val close = android.widget.ImageView(context)
    private val progress = View(context)
    private val progressTrack = LinearLayout(context)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var current: MiniPlayer.State? = null
    private var released = false
    private var lastProgressWidth = -1

    private val narrationObserver: (ReaderNarration.Session?) -> Unit = { main.post { refresh() } }
    private val epubObserver: (EpubNarration.Session?) -> Unit = { main.post { refresh() } }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        background = surfaceShape(palette.feature, Radius.CARD)
        // The bar floats over content, so it keeps a small lift where nothing else in the app does.
        elevation = dp(8).toFloat()
        addView(progressTrack.apply {
            orientation = HORIZONTAL
            setBackgroundColor(palette.rule)
            addView(progress.apply { setBackgroundColor(palette.accent) }, LayoutParams(0, dp(2)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(2)))
        addView(row(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        ReaderNarration.observe(narrationObserver)
        EpubNarration.observe(epubObserver)
        if (followAudiobooks) connectAudiobookSession()
        main.post(ticker)
    }

    private fun row() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Gap.SM), dp(Gap.XS), dp(Gap.XS), dp(Gap.XS))
        isClickable = true
        isFocusable = true
        setOnClickListener { current?.let(onOpen) }
        addView(artwork.apply {
            textSize = Type.BODY_LARGE
            gravity = Gravity.CENTER
            setTextColor(palette.onFeature)
            typeface = Type.serif(Typeface.BOLD)
            includeFontPadding = false
            background = outlineShape(palette.featureMuted, Radius.COVER)
        }, LayoutParams(dp(34), dp(46)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title.apply {
                textSize = Type.LABEL
                setTextColor(palette.onFeature)
                typeface = Type.serif(Typeface.BOLD)
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(subtitle.apply {
                textSize = Type.MICRO
                setTextColor(palette.featureMuted)
                typeface = Type.sans()
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Gap.SM) })
        addView(playPause.apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(NarratifyIcon(NarratifyIcon.Glyph.PLAY, palette.accent))
            setOnClickListener { togglePlayback() }
            isClickable = true
            isFocusable = true
        }, LayoutParams(dp(48), dp(48)))
        addView(close.apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(NarratifyIcon(NarratifyIcon.Glyph.CLOSE, palette.featureMuted, strokeDp = 1.5f))
            contentDescription = "Stop playback"
            setOnClickListener { stopPlayback() }
            isClickable = true
            isFocusable = true
        }, LayoutParams(dp(44), dp(48)))
    }

    private fun connectAudiobookSession() {
        val future = MediaController.Builder(
            context.applicationContext,
            SessionToken(context.applicationContext, ComponentName(context.applicationContext, AudiobookPlaybackService::class.java)),
        ).buildAsync()
        future.addListener({
            if (released) {
                runCatching { future.get().release() }
                return@addListener
            }
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            refresh()
        }, MoreExecutors.directExecutor())
    }

    private fun audiobookSnapshot(): MiniPlayer.AudiobookSnapshot? {
        val player = controller ?: return null
        val item = player.currentMediaItem ?: return null
        return MiniPlayer.AudiobookSnapshot(
            publicationId = item.mediaId,
            title = item.mediaMetadata.title?.toString() ?: "Audiobook",
            playing = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
        )
    }

    private fun refresh() {
        if (released) return
        val state = MiniPlayer.resolve(ReaderNarration.session(), EpubNarration.session(), audiobookSnapshot())
        current = state
        if (state == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        artwork.text = state.title.take(1).uppercase()
        title.text = state.title
        subtitle.text = state.subtitle
        playPause.setImageDrawable(
            NarratifyIcon(
                if (state.playing) NarratifyIcon.Glyph.PAUSE else NarratifyIcon.Glyph.PLAY,
                palette.accent,
            ),
        )
        playPause.contentDescription = if (state.playing) "Pause" else "Resume"
        contentDescription = "${state.title}, ${state.subtitle}. Open"
        val fraction = state.progress
        progressTrack.visibility = if (fraction == null) GONE else VISIBLE
        if (fraction != null && width > 0) {
            // Only relayout when the bar actually moved: a layout pass every second can swallow
            // a tap that lands while it is in flight.
            val filled = (width * fraction).toInt()
            if (filled != lastProgressWidth) {
                lastProgressWidth = filled
                progress.layoutParams = progress.layoutParams.apply { this.width = filled }
                progress.requestLayout()
            }
        }
    }

    private fun togglePlayback() {
        when (current?.source) {
            MiniPlayer.Source.NARRATION -> {
                val narration = ReaderNarration.controller()
                TtsDiagnostics.recordEvent("Mini player toggle · controller=${narration != null} · state=${narration?.state()}")
                narration?.let {
                    if (it.state() == ReaderTtsController.State.PLAYING) it.pause() else it.resume()
                }
            }

            MiniPlayer.Source.EPUB -> {
                if (current?.playing == true) EpubNarration.pause() else EpubNarration.play()
            }

            MiniPlayer.Source.AUDIOBOOK -> controller?.let { if (it.isPlaying) it.pause() else it.play() }
            null -> Unit
        }
        refresh()
    }

    private fun stopPlayback() {
        when (current?.source) {
            MiniPlayer.Source.NARRATION -> ReaderNarration.release()
            MiniPlayer.Source.EPUB -> EpubNarration.stop()
            MiniPlayer.Source.AUDIOBOOK -> controller?.pause()
            null -> Unit
        }
        refresh()
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        ReaderNarration.stopObserving(narrationObserver)
        EpubNarration.stopObserving(epubObserver)
        controller?.release()
        controller = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
