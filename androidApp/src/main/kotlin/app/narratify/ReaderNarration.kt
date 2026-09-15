package app.narratify

import android.content.Context

/**
 * Keeps one narration alive across screens. Rebuilding the reader view — going to Settings and
 * back, or browsing the library — used to release the speech controller and silence playback, so
 * the session is owned here instead of by the view. The last state and highlight are replayed to
 * whichever screen attaches next, so a returning reader is immediately in sync.
 */
object ReaderNarration {
    /** What the mini player needs to describe a running narration. */
    data class Session(
        val bookId: String,
        val title: String,
        val state: ReaderTtsController.State,
        val message: String,
    )

    private val lock = Any()
    private var bookId: String? = null
    private var bookTitle: String = ""
    private var controller: ReaderTtsController? = null
    private var uiListener: ReaderTtsController.Listener? = null
    private val observers = java.util.concurrent.CopyOnWriteArrayList<(Session?) -> Unit>()
    private var lastState = ReaderTtsController.State.INITIALIZING
    private var lastMessage = ""
    private var lastRange: Pair<Int, Int>? = null

    /** Forwards to whichever screen is attached and remembers the latest values for the next one. */
    private val relay = object : ReaderTtsController.Listener {
        override fun onState(state: ReaderTtsController.State, message: String) {
            val target = synchronized(lock) {
                lastState = state
                lastMessage = message
                if (state == ReaderTtsController.State.STOPPED) lastRange = null
                uiListener
            }
            target?.onState(state, message)
            notifyObservers()
        }

        override fun onSourceRange(start: Int, endExclusive: Int) {
            val target = synchronized(lock) {
                lastRange = start to endExclusive
                uiListener
            }
            target?.onSourceRange(start, endExclusive)
        }
    }

    fun attach(
        context: Context,
        bookId: String,
        title: String,
        text: String,
        listener: ReaderTtsController.Listener,
        selection: VoiceSelection,
    ): ReaderTtsController {
        synchronized(lock) { bookTitle = title }
        return attach(bookId, listener) {
            AndroidReaderTtsController(context, bookId, text, relay, selection)
        }
    }

    internal fun attach(
        bookId: String,
        listener: ReaderTtsController.Listener,
        create: () -> ReaderTtsController,
    ): ReaderTtsController {
        val (active, replayState, replayMessage, replayRange) = synchronized(lock) {
            if (this.bookId != bookId) {
                controller?.release()
                controller = null
                lastState = ReaderTtsController.State.INITIALIZING
                lastMessage = ""
                lastRange = null
            }
            this.bookId = bookId
            uiListener = listener
            val existing = controller
            val active = existing ?: create().also { controller = it }
            Replay(active, lastState.takeIf { existing != null }, lastMessage, lastRange.takeIf { existing != null })
        }
        replayState?.let { listener.onState(it, replayMessage) }
        replayRange?.let { listener.onSourceRange(it.first, it.second) }
        notifyObservers()
        return active
    }

    /** The running narration, or null when nothing is loaded. */
    fun session(): Session? = synchronized(lock) {
        val id = bookId ?: return@synchronized null
        if (controller == null) return@synchronized null
        Session(id, bookTitle, lastState, lastMessage)
    }

    fun controller(): ReaderTtsController? = synchronized(lock) { controller }

    /** Used by the mini player, which outlives any single reader screen. */
    fun observe(observer: (Session?) -> Unit) {
        observers += observer
        observer(session())
    }

    fun stopObserving(observer: (Session?) -> Unit) {
        observers -= observer
    }

    private fun notifyObservers() {
        val session = session()
        observers.forEach { it(session) }
    }

    /** Leaves narration running; only the screen goes away. */
    fun detach(listener: ReaderTtsController.Listener) {
        synchronized(lock) {
            if (uiListener === listener) uiListener = null
        }
    }

    /** The relay handed to controllers; exposed so tests can drive it like a real engine would. */
    internal fun relayForTest(): ReaderTtsController.Listener = relay

    fun isActive(bookId: String): Boolean = synchronized(lock) {
        this.bookId == bookId && controller != null
    }

    /** Called when the host activity is destroyed: nothing should keep speaking after that. */
    fun release() {
        val active = synchronized(lock) {
            val existing = controller
            controller = null
            bookId = null
            uiListener = null
            lastState = ReaderTtsController.State.INITIALIZING
            lastMessage = ""
            lastRange = null
            bookTitle = ""
            existing
        }
        active?.release()
        notifyObservers()
    }

    private data class Replay(
        val controller: ReaderTtsController,
        val state: ReaderTtsController.State?,
        val message: String,
        val range: Pair<Int, Int>?,
    )
}
