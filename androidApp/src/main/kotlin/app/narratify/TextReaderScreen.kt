package app.narratify

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.narratify.domain.PlainTextDocument

class TextReaderScreen(
    context: Context,
    private val book: LocalBook,
    private val document: PlainTextDocument,
    initialPosition: StoredTextPosition?,
    private val autoPlay: Boolean = false,
    private val onPosition: (StoredTextPosition) -> Unit,
    onBack: () -> Unit,
    private val preferences: AppPreferences = AppPreferences(context),
) : FrameLayout(context) {
    private val body = TextView(context)
    private val scroll = ScrollView(context)
    private val renderedText = SpannableString(document.text)
    private val ttsStatus = TextView(context)
    private val playPause = TextView(context)
    private val speedLabel = TextView(context)
    private lateinit var tts: ReaderTtsController
    private var highlightBackground: BackgroundColorSpan? = null
    private var highlightUnderline: UnderlineSpan? = null
    private var lastReportedProblem: String? = null
    private val palette: AppPalette = preferences.palette()
    private val outlineEntries = ReaderOutline.fromText(document.outline)
    private val outlinePanel = ReaderOutlinePanel(context, palette, onSelect = ::openOutlineEntry)
    private val outlineDivider = View(context).apply { setBackgroundColor(palette.rule) }
    private val split = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private lateinit var topChrome: View
    private lateinit var bottomChrome: View
    private lateinit var restoreChromeButton: View
    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private var outlineOverlay: ReaderOutlineOverlay? = null
    private var fullscreen = false
    private var restoreOutlineAfterFullscreen = false

    private val listener = object : ReaderTtsController.Listener {
        override fun onState(state: ReaderTtsController.State, message: String) {
            ttsStatus.text = message
            // Read stays tappable while the engine loads: the request is queued rather than lost.
            val blocked = state == ReaderTtsController.State.UNAVAILABLE
            playPause.isEnabled = !blocked
            playPause.alpha = if (blocked) .45f else 1f
            playPause.text = if (state == ReaderTtsController.State.PLAYING) "Pause" else "Read"
            playPause.contentDescription = if (state == ReaderTtsController.State.PLAYING) "Pause read aloud" else "Start read aloud"
            if (state == ReaderTtsController.State.UNAVAILABLE || state == ReaderTtsController.State.ERROR) {
                reportNarrationProblem(state, message)
            }
        }

        override fun onSourceRange(start: Int, endExclusive: Int) {
            if (preferences.highlightWords) highlight(start, endExclusive)
        }
    }

    init {
        val colors = preferences.palette()
        setBackgroundColor(colors.canvas)
        keepScreenOn = preferences.keepScreenAwake
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            topChrome = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(colors.canvas)
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(Gap.XS), dp(Gap.XS), dp(Gap.MD), dp(Gap.XS))
                    addView(
                        android.widget.ImageView(context).apply {
                            setImageDrawable(NarratifyIcon(NarratifyIcon.Glyph.BACK, colors.accent))
                            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                            contentDescription = "Back to library"
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { save(); onBack() }
                        },
                        LinearLayout.LayoutParams(dp(48), dp(48)),
                    )
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(Gap.XXS), 0, 0, 0)
                        addView(context.styledText(document.title, Type.BODY_LARGE, colors.ink, Typeface.BOLD, serif = true).apply {
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        addView(
                            context.styledText("${book.format} · saved offline", Type.MICRO, colors.muted),
                            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(2)
                            },
                        )
                    }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    addView(
                        fullscreenButton(NarratifyIcon.Glyph.FULLSCREEN, "Enter fullscreen reading") {
                            setFullscreen(true)
                        },
                        LinearLayout.LayoutParams(dp(48), dp(48)),
                    )
                    addView(
                        android.widget.ImageView(context).apply {
                            setImageDrawable(NarratifyIcon(NarratifyIcon.Glyph.CONTENTS, colors.accent))
                            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                            contentDescription = "Contents"
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { toggleOutline() }
                        },
                        LinearLayout.LayoutParams(dp(48), dp(48)),
                    )
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(context.ruleView(colors.rule))
            }
            addView(topChrome, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            body.apply {
                setText(renderedText, TextView.BufferType.SPANNABLE)
                textSize = preferences.fontSize.toFloat()
                setTextColor(colors.ink)
                typeface = Type.serif()
                setLineSpacing(0f, preferences.lineSpacing / 100f)
                setPadding(dp(if (isTablet()) 96 else Gap.XL), dp(Gap.XXL), dp(if (isTablet()) 96 else Gap.XL), dp(96))
                setTextIsSelectable(true)
            }
            scroll.apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(body)
                setOnScrollChangeListener { _, _, _, _, _ -> save(); refreshOutlineHighlight() }
            }
            addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            bottomChrome = ttsControls()
            addView(bottomChrome, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        split.addView(root, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(split, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        restoreChromeButton = fullscreenButton(
            NarratifyIcon.Glyph.RESTORE,
            "Exit fullscreen reading",
        ) { setFullscreen(false) }.apply {
            visibility = View.GONE
            background = surfaceShape(colors.surface, Radius.INPUT, colors.outline)
            elevation = dp(4).toFloat()
        }
        addView(restoreChromeButton, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(Gap.SM)
            marginEnd = dp(Gap.SM)
        })
        outlinePanel.setEntries(outlineEntries, ReaderOutline.currentIndex(outlineEntries, 0))
        // A landscape reader who left the column open last time gets it back without asking.
        if (landscape && preferences.readerOutlineOpen) showOutlineColumn()
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        initialPosition?.let { stored ->
            val resolved = document.resolve(stored.anchor)
            body.post {
                val layout = body.layout ?: return@post
                val line = layout.getLineForOffset(resolved)
                scroll.scrollTo(0, (layout.getLineTop(line) + body.paddingTop).coerceAtLeast(0))
            }
        }
        if (ValueAnimator.areAnimatorsEnabled()) {
            alpha = 0f
            translationX = dp(24).toFloat()
            animate().alpha(1f).translationX(0f).setDuration(240).start()
        }
        tts = ReaderNarration.attach(context, book.id, document.title, document.text, listener, preferences.voiceSelection)
        applySpeed()
        // Opened from the player column: the request is queued if the engine is still loading.
        if (autoPlay && tts.state() != ReaderTtsController.State.PLAYING) post { tts.play(currentVisibleOffset()) }
    }

    /**
     * A one-line status label silently truncated the reason narration could not start, which read
     * as "nothing happened". The full message is shown once per distinct problem instead.
     */
    private fun reportNarrationProblem(state: ReaderTtsController.State, message: String) {
        if (message == lastReportedProblem) return
        lastReportedProblem = message
        val builder = android.app.AlertDialog.Builder(context)
            .setTitle(if (state == ReaderTtsController.State.UNAVAILABLE) "Read aloud is unavailable" else "Narration stopped")
            .setMessage(message)
            .setPositiveButton("Close", null)
        if (state == ReaderTtsController.State.UNAVAILABLE) {
            builder.setNeutralButton("Speech settings") { _, _ ->
                runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) }
            }
        }
        builder.show()
    }

    /**
     * Re-reads the preferences the reader may have changed in Settings while this screen stayed
     * in the back stack. Voice choice is deliberately excluded: swapping engines mid-passage
     * would drop the current position, so it applies the next time the book is opened.
     */
    fun applyPreferences() {
        val colors = preferences.palette()
        setBackgroundColor(colors.canvas)
        keepScreenOn = preferences.keepScreenAwake
        body.apply {
            textSize = preferences.fontSize.toFloat()
            setTextColor(colors.ink)
            setLineSpacing(0f, preferences.lineSpacing / 100f)
        }
        if (!preferences.highlightWords) clearHighlight()
        applySpeed()
    }

    /**
     * Re-sending the same speed would restart the current utterance on the neural engine, so a
     * returning screen only pushes a value that actually changed.
     */
    private fun applySpeed() {
        val wanted = preferences.speechRate
        if (kotlin.math.abs(tts.speed() - wanted) > SPEED_EPSILON) tts.setSpeed(wanted)
        speedLabel.text = String.format(java.util.Locale.US, "%.2f×", tts.speed())
    }

    private fun ttsControls(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.canvas)
        addView(context.ruleView(palette.rule))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Gap.MD), dp(Gap.SM), dp(Gap.MD), dp(Gap.SM))
            addView(playPause.apply {
                text = "Read"
                textSize = Type.BODY
                typeface = Type.sans(Typeface.BOLD)
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(palette.onAccent)
                background = surfaceShape(palette.accent, Radius.INPUT)
                isEnabled = false
                alpha = .45f
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (tts.state() == ReaderTtsController.State.PLAYING) tts.pause()
                    else tts.play(currentVisibleOffset())
                }
            }, LinearLayout.LayoutParams(dp(80), dp(48)))
            addView(
                controlButton("Stop", "Stop read aloud") {
                    tts.stop(); clearHighlight(); lastReportedProblem = null
                },
                LinearLayout.LayoutParams(dp(68), dp(48)).apply { marginStart = dp(Gap.XS) },
            )
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(speedLabel.apply {
                    text = "1.00×"
                    textSize = Type.LABEL
                    typeface = Type.sans(Typeface.BOLD)
                    includeFontPadding = false
                    setTextColor(palette.ink)
                    gravity = Gravity.CENTER
                })
                addView(ttsStatus.apply {
                    text = "Preparing offline system voice…"
                    textSize = Type.MICRO
                    typeface = Type.sans()
                    includeFontPadding = false
                    setTextColor(palette.muted)
                    gravity = Gravity.CENTER
                    maxLines = 2
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(Gap.SM)
                marginEnd = dp(Gap.SM)
            })
            addView(controlButton("−", "Decrease reading speed") { changeSpeed(-.1f) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(
                controlButton("+", "Increase reading speed") { changeSpeed(.1f) },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(Gap.XXS) },
            )
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun controlButton(label: String, description: String, action: () -> Unit) =
        context.styledText(label, Type.BODY, palette.ink, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = outlineShape(palette.outline, Radius.INPUT)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun fullscreenButton(
        glyph: NarratifyIcon.Glyph,
        description: String,
        action: () -> Unit,
    ) = android.widget.ImageView(context).apply {
        setImageDrawable(NarratifyIcon(glyph, palette.accent))
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(13), dp(13), dp(13), dp(13))
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun setFullscreen(enabled: Boolean) {
        if (fullscreen == enabled) return
        fullscreen = enabled
        if (enabled) {
            restoreOutlineAfterFullscreen = split.indexOfChild(outlinePanel) >= 0
            hideOutlineColumn()
            dismissOutlineOverlay()
        }
        topChrome.visibility = if (enabled) View.GONE else View.VISIBLE
        bottomChrome.visibility = if (enabled) View.GONE else View.VISIBLE
        restoreChromeButton.visibility = if (enabled) View.VISIBLE else View.GONE
        (context as? Activity)?.setReaderFullscreen(enabled, palette)
        if (!enabled && restoreOutlineAfterFullscreen && landscape) showOutlineColumn()
        if (!enabled) restoreOutlineAfterFullscreen = false
    }

    /** Back first restores the reader chrome; a second Back leaves the book. */
    fun exitFullscreenIfNeeded(): Boolean {
        if (!fullscreen) return false
        setFullscreen(false)
        return true
    }

    private fun changeSpeed(delta: Float) {
        val speed = (tts.speed() + delta).coerceIn(.5f, 2f)
        tts.setSpeed(speed)
        speedLabel.text = String.format(java.util.Locale.US, "%.2f×", speed)
    }

    private fun highlight(start: Int, endExclusive: Int) {
        clearHighlight()
        if (start !in 0 until document.text.length || endExclusive <= start) return
        val end = endExclusive.coerceAtMost(document.text.length)
        val displayedText = body.text as? Spannable ?: return
        highlightBackground = BackgroundColorSpan(palette.highlight).also {
            displayedText.setSpan(it, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        highlightUnderline = UnderlineSpan().also {
            displayedText.setSpan(it, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        body.invalidate()
        val layout = body.layout ?: return
        val lineTop = layout.getLineTop(layout.getLineForOffset(start)) + body.paddingTop
        if (lineTop < scroll.scrollY || lineTop > scroll.scrollY + scroll.height - dp(80)) {
            scroll.smoothScrollTo(0, (lineTop - dp(80)).coerceAtLeast(0))
        }
    }

    private fun clearHighlight() {
        val displayedText = body.text as? Spannable
        highlightBackground?.let { displayedText?.removeSpan(it) }
        highlightUnderline?.let { displayedText?.removeSpan(it) }
        highlightBackground = null
        highlightUnderline = null
        body.invalidate()
    }

    fun save() {
        val layout = body.layout ?: return
        val localY = (scroll.scrollY - body.paddingTop).coerceAtLeast(0)
        val line = layout.getLineForVertical(localY)
        val offset = layout.getLineStart(line).coerceIn(0, document.text.length)
        val range = (scroll.getChildAt(0)?.height ?: 0) - scroll.height
        val progression = if (range > 0) scroll.scrollY.toFloat() / range else 0f
        onPosition(StoredTextPosition(document.anchorAt(offset), progression))
    }

    /** Detaches the view; narration keeps running so Settings or the library do not silence it. */
    fun release() {
        if (fullscreen) setFullscreen(false)
        ReaderNarration.detach(listener)
        clearHighlight()
    }

    /**
     * Landscape puts the outline beside the page so it can be read at the same time; portrait has
     * no width to spare, so the same button opens it over the page instead.
     */
    private fun toggleOutline() {
        if (landscape) {
            if (split.indexOfChild(outlinePanel) >= 0) hideOutlineColumn() else showOutlineColumn()
            preferences.readerOutlineOpen = split.indexOfChild(outlinePanel) >= 0
        } else {
            if (outlineOverlay != null) dismissOutlineOverlay() else showOutlineOverlay()
        }
    }

    private fun showOutlineColumn() {
        if (split.indexOfChild(outlinePanel) >= 0) return
        split.addView(outlinePanel, 0, LinearLayout.LayoutParams(outlineColumnWidth(), LayoutParams.MATCH_PARENT))
        split.addView(outlineDivider, 1, LinearLayout.LayoutParams(dp(1), LayoutParams.MATCH_PARENT))
        refreshOutlineHighlight()
    }

    private fun hideOutlineColumn() {
        split.removeView(outlinePanel)
        split.removeView(outlineDivider)
    }

    private fun showOutlineOverlay() {
        val overlay = ReaderOutlineOverlay(
            context,
            palette,
            onSelect = { index -> openOutlineEntry(index) },
            onDismiss = ::dismissOutlineOverlay,
        )
        overlay.panel.setEntries(outlineEntries, ReaderOutline.currentIndex(outlineEntries, currentVisibleOffset()))
        outlineOverlay = overlay
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dismissOutlineOverlay() {
        outlineOverlay?.let { removeView(it) }
        outlineOverlay = null
    }

    /** Wide enough to hold a chapter title, never so wide that the page loses its measure. */
    private fun outlineColumnWidth(): Int =
        minOf(dp(320), (resources.displayMetrics.widthPixels * 0.32f).toInt())

    private fun openOutlineEntry(index: Int) {
        val target = outlineEntries.getOrNull(index)?.target as? OutlineTarget.Offset ?: return
        val layout = body.layout ?: return
        val offset = target.characterOffset.coerceIn(0, document.text.length)
        val line = layout.getLineForOffset(offset)
        scroll.smoothScrollTo(0, (layout.getLineTop(line) + body.paddingTop).coerceAtLeast(0))
        // The column stays put so the outline can be scanned while reading; the overlay is in the way.
        dismissOutlineOverlay()
        save()
    }

    private fun refreshOutlineHighlight() {
        outlinePanel.setCurrentIndex(ReaderOutline.currentIndex(outlineEntries, currentVisibleOffset()))
    }

    private fun currentVisibleOffset(): Int {
        val layout = body.layout ?: return 0
        val localY = (scroll.scrollY - body.paddingTop).coerceAtLeast(0)
        return layout.getLineStart(layout.getLineForVertical(localY)).coerceIn(0, document.text.length)
    }

    private fun isTablet() = resources.configuration.smallestScreenWidthDp >= 600
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SPEED_EPSILON = 0.001f
    }

}
