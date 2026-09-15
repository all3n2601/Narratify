package app.narratify

/** UI-facing speech contract shared by system and neural on-device engines. */
interface ReaderTtsController {
    interface Listener {
        fun onState(state: State, message: String)
        fun onSourceRange(start: Int, endExclusive: Int)
    }

    enum class State { INITIALIZING, READY, PLAYING, PAUSED, STOPPED, UNAVAILABLE, ERROR }

    fun play(fromOffset: Int)

    /**
     * Continues from wherever playback stopped. Controllers track their own position, so callers
     * that did not start the passage — the mini player, for one — do not have to guess an offset.
     */
    fun resume() = play(0)

    fun pause()
    fun stop()
    fun setSpeed(value: Float)
    fun speed(): Float
    fun state(): State
    fun release()
}
