package app.narratify

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.narratify.shared.data.AndroidDatabaseFactory
import app.narratify.shared.data.LocalLibraryStore

class AudiobookPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var library: LocalLibraryStore
    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val positionSaver = object : Runnable {
        override fun run() {
            persistPosition()
            handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        library = LocalLibraryStore(AndroidDatabaseFactory.create(this))
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(15_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.containsAny(
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY,
                        )
                    ) persistPosition()
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        updateSessionActivity(player.currentMediaItem?.mediaId)
                    }
                }
            })
        }
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity(null))
            .build()
        handler.post(positionSaver)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPosition()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(positionSaver)
        persistPosition()
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }

    private fun persistPosition() {
        if (!::player.isInitialized) return
        val publicationId = player.currentMediaItem?.mediaId?.takeIf(String::isNotBlank) ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return
        runCatching {
            library.saveAudioPosition(
                publicationId = publicationId,
                positionMs = player.currentPosition.coerceIn(0L, duration),
                durationMs = duration,
                now = System.currentTimeMillis(),
            )
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun updateSessionActivity(publicationId: String?) {
        mediaSession?.setSessionActivity(sessionActivity(publicationId))
    }

    private fun sessionActivity(publicationId: String?): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            publicationId?.takeIf(String::isNotBlank)?.let {
                putExtra(MainActivity.EXTRA_OPEN_AUDIOBOOK_ID, it)
            }
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
