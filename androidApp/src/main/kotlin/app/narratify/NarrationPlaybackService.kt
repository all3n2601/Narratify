package app.narratify

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps EPUB narration running when the reader is not on screen. The player is Readium's own
 * media3 adapter from [EpubNarration], so the notification, lock-screen controls, and background
 * lifetime come from the platform rather than from a hand-rolled foreground service.
 */
class NarrationPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        EpubNarration.rememberContext(this)
        val player = EpubNarration.mediaPlayer()
        if (player == null) {
            // Nothing to host: the narration ended between starting the service and binding here.
            stopSelf()
            return
        }
        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setSessionActivity(readerIntent())
            .build()
            // Registering the session is what makes media3 post the media notification and hold
            // the service in the foreground; waiting for a controller to connect never happens
            // here, because nothing in the app connects to this session.
            .also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away should not leave a book reading to an empty room.
        EpubNarration.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun readerIntent(): PendingIntent {
        val book = EpubNarration.currentBook()
        val intent = book
            ?.let { (id, title) -> EpubReaderActivity.intent(this, id, title) }
            ?: Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val SESSION_ID = "narratify-epub-narration"
    }
}
