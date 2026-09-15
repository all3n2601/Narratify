package app.narratify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import java.io.Closeable

/** Blocking, bounded PCM stream used from the neural synthesis worker, never the UI thread. */
/**
 * [manageAudioFocus] is false when something else already owns focus for this audio — Readium's
 * media session does, and two focus owners in one process make each other pause.
 */
class StreamingPcmPlayer(
    context: Context,
    private val manageAudioFocus: Boolean = true,
    private val onFocusLost: () -> Unit,
) : Closeable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            // Abandoning focus makes the platform deliver a loss to this very listener, and that
            // callback can land after playback has been resumed and focus re-acquired. Acting on
            // it would pause the audio that just started, so losses only count while we hold it.
            if (holdsFocus && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                holdsFocus = false
                onFocusLost()
            }
        }
        .build()
    @Volatile private var holdsFocus = false
    @Volatile private var aborted = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var format: PcmFormat? = null
    @Volatile private var writtenFrames = 0L
    private val playbackClock = PlaybackFrameClock()

    @Synchronized
    fun start(requested: PcmFormat) {
        require(requested.encoding == PcmEncoding.SIGNED_INT_16_LE)
        if (format == requested && track != null) return
        closeTrack()
        val channelMask = if (requested.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(requested.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Unsupported PCM output format" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(requested.sampleRateHz)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes((minimum * 2).coerceAtMost(MAX_BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (manageAudioFocus && !holdsFocus) {
            check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus was denied" }
            holdsFocus = true
        }
        output.play()
        aborted = false
        format = requested
        track = output
        writtenFrames = 0
        playbackClock.reset()
    }

    /**
     * Blocks while the track drains, which is what paces synthesis against playback. Returns the
     * frames actually accepted, which is short of [bytes] when [abort] interrupts a long block so
     * that a pause reaches silence without waiting for the rest of the passage.
     *
     * The player lock is taken per block rather than for the whole passage: a reader's highlight
     * polls [playedFrames] several times a second, and it must never queue behind an audio write.
     */
    fun write(bytes: ByteArray): Long {
        val activeFormat = checkNotNull(format) { "PCM metadata must precede audio" }
        val frameBytes = 2 * activeFormat.channelCount
        require(bytes.size % frameBytes == 0) { "PCM block is not frame-aligned" }
        var offset = 0
        while (offset < bytes.size && !aborted) {
            val count = writeBlock(bytes, offset, minOf(WRITE_BLOCK_BYTES, bytes.size - offset))
            if (count <= 0) break
            offset += count
        }
        return (offset / frameBytes).toLong()
    }

    @Synchronized
    private fun writeBlock(bytes: ByteArray, offset: Int, count: Int): Int {
        val active = track ?: return -1
        val activeFormat = format ?: return -1
        val written = active.write(bytes, offset, count, AudioTrack.WRITE_BLOCKING)
        if (written < 0) check(aborted) { "AudioTrack write failed: $written" }
        if (written > 0) writtenFrames += written / (2L * activeFormat.channelCount)
        return written
    }

    /**
     * Interrupts a blocked [write] without taking the player lock, because the thread holding it
     * is the one that has to be released. Pausing the track makes the blocked write return.
     */
    fun abort() {
        aborted = true
        runCatching { track?.pause() }
    }

    /** Deliberately lock-free: this is polled from the UI thread while audio is being written. */
    fun playedFrames(): Long {
        val raw = track?.playbackHeadPosition?.toLong() ?: return 0L
        return playbackClock.advance(raw)
    }

    fun writtenFrames(): Long = writtenFrames

    /** Ends the current audio without giving up focus, so a pause can be resumed cleanly. */
    @Synchronized
    fun stopTrack() {
        closeTrack()
    }

    @Synchronized
    override fun close() {
        closeTrack()
        if (holdsFocus) {
            holdsFocus = false
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private fun closeTrack() {
        track?.runCatching { pause() }
        track?.runCatching { flush() }
        track?.release()
        track = null
        format = null
        writtenFrames = 0
        playbackClock.reset()
    }

    private companion object {
        const val MAX_BUFFER_BYTES = 1024 * 1024

        /** ~170 ms of 24 kHz mono audio: long enough to be efficient, short enough to yield. */
        const val WRITE_BLOCK_BYTES = 8 * 1024
    }
}
