package app.narratify

import android.content.ComponentName
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File
import java.util.concurrent.Executor

class NowPlayingScreen(
    context: Context,
    private val book: LocalBook,
    startPositionMs: Long,
    private val onBack: () -> Unit,
) : FrameLayout(context) {
    private val palette: AppPalette = AppPreferences(context).palette()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }
    private val art = coverArtFor(book)
    private val playButton = android.widget.ImageView(context).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setImageDrawable(NarratifyIcon(NarratifyIcon.Glyph.PLAY, palette.onAccent))
    }
    private val elapsed = label("0:00", Type.MICRO, palette.muted)
    private val remaining = label("−0:00", Type.MICRO, palette.muted)
    private val seek = SeekBar(context).apply {
        max = 1_000
        tintTo(palette)
    }
    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private var userSeeking = false
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            mainHandler.postDelayed(this, 500L)
        }
    }

    init {
        setBackgroundColor(palette.canvas)
        addView(buildLayout(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { userSeeking = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val duration = controller?.duration?.takeIf { it > 0L } ?: 0L
                controller?.seekTo(duration * seekBar.progress / seekBar.max)
                userSeeking = false
            }
        })
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess { mediaController ->
                controller = mediaController
                mediaController.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = updateProgress()
                })
                val item = MediaItem.Builder()
                    .setMediaId(book.id)
                    .setUri(Uri.fromFile(File(book.storageUri)))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(book.title)
                            .setArtist(book.author ?: "Narratify audiobook")
                            .setIsPlayable(true)
                            .build(),
                    )
                    .build()
                if (mediaController.currentMediaItem?.mediaId != book.id) {
                    mediaController.setMediaItem(item, startPositionMs)
                    mediaController.prepare()
                    mediaController.play()
                } else if (mediaController.playbackState == Player.STATE_IDLE) {
                    mediaController.prepare()
                }
                updateProgress()
            }
        }, mainExecutor)
        mainHandler.post(ticker)
    }

    fun close() {
        mainHandler.removeCallbacks(ticker)
        controller?.release()
        controller = null
        if (!controllerFuture.isDone) controllerFuture.cancel(false)
    }

    private fun buildLayout() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(
            dp(if (isTablet()) 84 else Gap.LG),
            dp(Gap.MD),
            dp(if (isTablet()) 84 else Gap.LG),
            dp(Gap.XXL),
        )
        // Masthead row, then a rule — the same header grammar as every other screen.
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(NarratifyIcon.Glyph.BACK, "Back to library") { onBack() })
            addView(
                context.eyebrow("Now playing", palette.accent).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(View(context), LinearLayout.LayoutParams(dp(48), 1))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(context.ruleView(palette.rule, topMarginDp = Gap.SM))

        // The jacket, at real book proportions, instead of a tinted square with a letter in it.
        val coverWidth = dp(if (isTablet()) 220 else 184)
        addView(
            BookCoverView(context, art),
            LinearLayout.LayoutParams(coverWidth, (coverWidth * 1.5f).toInt()).apply {
                topMargin = dp(if (isTablet()) Gap.SECTION else Gap.XXL)
            },
        )

        addView(
            label(book.title, if (isTablet()) Type.DISPLAY else Type.HEADING, palette.ink, Typeface.BOLD, serif = true)
                .apply { gravity = Gravity.CENTER },
            topMargin(Gap.XL),
        )
        addView(
            label(book.author ?: book.format, Type.BODY, palette.muted).apply { gravity = Gravity.CENTER },
            topMargin(Gap.XS),
        )

        addView(seek, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(Gap.XL) })
        addView(LinearLayout(context).apply {
            addView(elapsed, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            remaining.gravity = Gravity.END
            addView(remaining, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(skipButton(NarratifyIcon.Glyph.REWIND, "15", "Rewind 15 seconds") { controller?.seekBack() })
            addView(playButton.apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(palette.accent)
                }
                isClickable = true
                isFocusable = true
                contentDescription = "Play or pause"
                setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
            }, LinearLayout.LayoutParams(dp(68), dp(68)).apply { setMargins(dp(Gap.XXL), 0, dp(Gap.XXL), 0) })
            addView(skipButton(NarratifyIcon.Glyph.FORWARD, "30", "Forward 30 seconds") { controller?.seekForward() })
        }, topMargin(Gap.XL))

        addView(
            label("Saved offline · background playback enabled", Type.MICRO, palette.muted)
                .apply { gravity = Gravity.CENTER },
            topMargin(Gap.XXL),
        )
    }

    private fun iconButton(glyph: NarratifyIcon.Glyph, description: String, action: () -> Unit) =
        android.widget.ImageView(context).apply {
            setImageDrawable(NarratifyIcon(glyph, palette.accent))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(Gap.SM), dp(Gap.SM), dp(Gap.SM), dp(Gap.SM))
            isClickable = true
            isFocusable = true
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { action() }
        }

    /** Skip control: the circular arrow with its interval set inside it, as a transport dial. */
    private fun skipButton(glyph: NarratifyIcon.Glyph, seconds: String, description: String, action: () -> Unit) =
        FrameLayout(context).apply {
            addView(
                android.widget.ImageView(context).apply {
                    setImageDrawable(NarratifyIcon(glyph, palette.ink, strokeDp = 1.5f))
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                },
                LayoutParams(dp(34), dp(34), Gravity.CENTER),
            )
            addView(
                label(seconds, Type.MICRO, palette.ink, Typeface.BOLD).apply { gravity = Gravity.CENTER },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
            isClickable = true
            isFocusable = true
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            setOnClickListener { action() }
        }

    private fun updateProgress() {
        val player = controller ?: return
        playButton.setImageDrawable(
            NarratifyIcon(
                if (player.isPlaying) NarratifyIcon.Glyph.PAUSE else NarratifyIcon.Glyph.PLAY,
                palette.onAccent,
            ),
        )
        playButton.contentDescription = if (player.isPlaying) "Pause" else "Play"
        val duration = player.duration.takeIf { it > 0L } ?: book.durationUs?.div(1_000L) ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        if (!userSeeking && duration > 0L) seek.progress = ((position * seek.max) / duration).toInt()
        elapsed.text = formatTime(position)
        remaining.text = "−${formatTime((duration - position).coerceAtLeast(0L))}"
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        style: Int = Typeface.NORMAL,
        serif: Boolean = false,
    ) = context.styledText(value, size, color, style, serif)

    private fun topMargin(value: Int) = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(value) }
    private fun isTablet() = resources.configuration.smallestScreenWidthDp >= 600
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
