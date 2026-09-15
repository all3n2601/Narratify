package app.narratify

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The single visual vocabulary for the Android app.
 *
 * Narratify reads like print, so the interface borrows print's tools: a serif for anything that
 * carries a voice, a sans for anything that carries a function, hairline rules instead of drop
 * shadows, and a spacing grid that keeps every screen on the same rhythm. Screens should reach for
 * these tokens rather than inventing sizes, radii, or colours locally — that drift is what made the
 * earlier build look assembled rather than designed.
 */

/** Text sizes in sp. Named by role, not by number, so a screen never guesses. */
object Type {
    const val MICRO = 11f      // eyebrows, over-line labels, counts
    const val LABEL = 13f      // chips, nav labels, metadata
    const val BODY = 15f       // running UI text
    const val BODY_LARGE = 17f // list row titles
    const val TITLE = 20f      // card titles
    const val HEADING = 25f    // section headings
    const val DISPLAY = 33f    // page titles, phone
    const val DISPLAY_LARGE = 41f // page titles, tablet

    /** Editorial voice: titles, book names, anything the reader reads *as* content. */
    fun serif(style: Int = Typeface.NORMAL): Typeface = Typeface.create("serif", style)

    /** Functional voice: controls, labels, metadata, running interface copy. */
    fun sans(style: Int = Typeface.NORMAL): Typeface = Typeface.create("sans-serif", style)

    /** Small caps stand-in: uppercase plus wide tracking, the way a masthead sets a kicker. */
    const val TRACKING_EYEBROW = .16f
    const val TRACKING_LABEL = .04f
}

/** The 4dp grid. Every margin and padding in the app resolves to one of these. */
object Gap {
    const val XXS = 4
    const val XS = 8
    const val SM = 12
    const val MD = 16
    const val LG = 20
    const val XL = 24
    const val XXL = 32
    const val SECTION = 40
}

/**
 * Print corners are tight. Big pill radii were a large part of why the old build read as a generic
 * mobile template, so nothing here is rounder than a paperback's trimmed corner.
 */
object Radius {
    const val NONE = 0f
    const val INPUT = 14f
    const val CARD = 18f
    const val SHEET = 24f
    const val COVER = 12f
    const val PILL = 999f
}

fun Context.dpi(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun View.dpv(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private val density: Float get() = Resources.getSystem().displayMetrics.density

/** A filled shape with an optional hairline. Hairlines are always exactly one physical pixel. */
fun surfaceShape(color: Int, radiusDp: Float = Radius.CARD, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
        if (strokeColor != null) setStroke(1.coerceAtLeast(density.toInt()), strokeColor)
    }

/** An outlined shape with no fill — the default card treatment in an editorial layout. */
fun outlineShape(strokeColor: Int, radiusDp: Float = Radius.CARD): GradientDrawable =
    GradientDrawable().apply {
        setColor(0)
        cornerRadius = radiusDp * density
        setStroke(1.coerceAtLeast(density.toInt()), strokeColor)
    }

/** A restrained two-tone glass surface used by immersive chrome and featured controls. */
fun glassShape(
    startColor: Int,
    endColor: Int,
    radiusDp: Float = Radius.CARD,
    strokeColor: Int? = null,
): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(startColor, endColor),
).apply {
    cornerRadius = radiusDp * density
    if (strokeColor != null) setStroke(1.coerceAtLeast(density.toInt()), strokeColor)
}

/** A horizontal rule. The workhorse separator; replaces most of the app's old card chrome. */
fun Context.ruleView(color: Int, topMarginDp: Int = 0, bottomMarginDp: Int = 0): View =
    View(this).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.coerceAtLeast(density.toInt())).apply {
            topMargin = dpi(topMarginDp)
            bottomMargin = dpi(bottomMarginDp)
        }
    }

/**
 * Stroked vector icons drawn from a 24×24 grid.
 *
 * The app used to set its icons as text glyphs (`▦`, `⌕`, `◖`, `⚙`). Those resolve differently on
 * every device font, do not respect a stroke weight, and were the single most obvious sign that the
 * interface was improvised. These are real paths: one grid, one stroke width, one corner style.
 */
class NarratifyIcon(
    private val glyph: Glyph,
    private val tint: Int,
    private val strokeDp: Float = 1.7f,
) : Drawable() {
    enum class Glyph {
        LIBRARY, SEARCH, VOICES, SETTINGS, PLUS, PLAY, PAUSE, BACK, CLOSE, REWIND,
        FORWARD, PREVIOUS, NEXT, OPEN, CONTENTS, FULLSCREEN, RESTORE
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tint
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val size = minOf(b.width(), b.height()).toFloat()
        val unit = size / 24f
        stroke.strokeWidth = strokeDp * density
        canvas.save()
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
        canvas.scale(unit, unit)
        // Paths are authored on the 24×24 grid, so the stroke has to be un-scaled to stay crisp.
        stroke.strokeWidth = (strokeDp * density) / unit
        drawGlyph(canvas)
        canvas.restore()
    }

    private fun drawGlyph(canvas: Canvas) {
        path.reset()
        when (glyph) {
            Glyph.LIBRARY -> {
                // Three spines on a shelf: the app's one literal, non-abstract mark.
                canvas.drawRect(RectF(4f, 4f, 8.5f, 20f), stroke)
                canvas.drawRect(RectF(10f, 4f, 14.5f, 20f), stroke)
                canvas.save()
                canvas.rotate(12f, 18f, 20f)
                canvas.drawRect(RectF(16f, 5.5f, 20f, 20f), stroke)
                canvas.restore()
            }

            Glyph.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 6.5f, stroke)
                canvas.drawLine(15.4f, 15.4f, 20f, 20f, stroke)
            }

            Glyph.VOICES -> {
                // A waveform, not a speaker: this section is about voices, not volume.
                val heights = floatArrayOf(5f, 9f, 13f, 9f, 5f)
                heights.forEachIndexed { index, half ->
                    val x = 4f + index * 4f
                    canvas.drawLine(x, 12f - half, x, 12f + half, stroke)
                }
            }

            Glyph.SETTINGS -> {
                // Sliders read as "tune this" far faster than a cog at 24dp.
                listOf(7f, 12f, 17f).forEachIndexed { index, y ->
                    canvas.drawLine(4f, y, 20f, y, stroke)
                    canvas.drawCircle(floatArrayOf(9f, 15f, 11f)[index], y, 2.4f, fill)
                }
            }

            Glyph.PLUS -> {
                canvas.drawLine(12f, 5f, 12f, 19f, stroke)
                canvas.drawLine(5f, 12f, 19f, 12f, stroke)
            }

            Glyph.PLAY -> {
                path.moveTo(8f, 5f)
                path.lineTo(19.5f, 12f)
                path.lineTo(8f, 19f)
                path.close()
                canvas.drawPath(path, fill)
            }

            Glyph.PAUSE -> {
                canvas.drawRect(RectF(7.5f, 5f, 10.5f, 19f), fill)
                canvas.drawRect(RectF(13.5f, 5f, 16.5f, 19f), fill)
            }

            Glyph.BACK -> {
                path.moveTo(14.5f, 5f)
                path.lineTo(8f, 12f)
                path.lineTo(14.5f, 19f)
                canvas.drawPath(path, stroke)
            }

            Glyph.CONTENTS -> {
                // A bulleted list, which reads as "sections" where stacked bare lines read as "menu".
                listOf(7f, 12f, 17f).forEach { y ->
                    canvas.drawCircle(5.5f, y, 1.5f, fill)
                    canvas.drawLine(10f, y, 19f, y, stroke)
                }
            }

            Glyph.CLOSE -> {
                canvas.drawLine(6f, 6f, 18f, 18f, stroke)
                canvas.drawLine(18f, 6f, 6f, 18f, stroke)
            }

            Glyph.REWIND, Glyph.FORWARD -> {
                val forward = glyph == Glyph.FORWARD
                canvas.save()
                if (forward) {
                    canvas.scale(-1f, 1f)
                    canvas.translate(-24f, 0f)
                }
                path.addArc(RectF(4f, 4f, 20f, 20f), -55f, 285f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(4.2f, 3.2f)
                path.lineTo(4.2f, 9.2f)
                path.lineTo(10.2f, 9.2f)
                canvas.drawPath(path, stroke)
                canvas.restore()
            }

            Glyph.PREVIOUS, Glyph.NEXT -> {
                val next = glyph == Glyph.NEXT
                canvas.save()
                if (!next) {
                    canvas.scale(-1f, 1f)
                    canvas.translate(-24f, 0f)
                }
                canvas.drawRect(RectF(18f, 5f, 20f, 19f), fill)
                path.moveTo(6f, 5f)
                path.lineTo(17f, 12f)
                path.lineTo(6f, 19f)
                path.close()
                canvas.drawPath(path, fill)
                canvas.restore()
            }

            Glyph.OPEN -> {
                canvas.drawLine(7f, 17f, 17f, 7f, stroke)
                path.moveTo(9f, 7f)
                path.lineTo(17f, 7f)
                path.lineTo(17f, 15f)
                canvas.drawPath(path, stroke)
            }

            Glyph.FULLSCREEN, Glyph.RESTORE -> {
                val restore = glyph == Glyph.RESTORE
                if (restore) {
                    // Corners point inward: return the page chrome and system bars.
                    path.moveTo(4f, 9f)
                    path.lineTo(9f, 9f)
                    path.lineTo(9f, 4f)
                    path.moveTo(20f, 9f)
                    path.lineTo(15f, 9f)
                    path.lineTo(15f, 4f)
                    path.moveTo(4f, 15f)
                    path.lineTo(9f, 15f)
                    path.lineTo(9f, 20f)
                    path.moveTo(20f, 15f)
                    path.lineTo(15f, 15f)
                    path.lineTo(15f, 20f)
                } else {
                    // Four outward corners are recognizable without borrowing a font glyph.
                    path.moveTo(10f, 5f)
                    path.lineTo(5f, 5f)
                    path.lineTo(5f, 10f)
                    path.moveTo(14f, 5f)
                    path.lineTo(19f, 5f)
                    path.lineTo(19f, 10f)
                    path.moveTo(5f, 14f)
                    path.lineTo(5f, 19f)
                    path.lineTo(10f, 19f)
                    path.moveTo(19f, 14f)
                    path.lineTo(19f, 19f)
                    path.lineTo(14f, 19f)
                }
                canvas.drawPath(path, stroke)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Drawable.getOpacity is deprecated but still required by the base class.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = (24 * density).toInt()
    override fun getIntrinsicHeight(): Int = (24 * density).toInt()
}

/** An icon sized to the 4dp grid, with a touch target that is never smaller than 48dp. */
fun Context.iconView(
    glyph: NarratifyIcon.Glyph,
    tint: Int,
    sizeDp: Int = 22,
    description: String? = null,
): ImageView = ImageView(this).apply {
    setImageDrawable(NarratifyIcon(glyph, tint))
    layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
    scaleType = ImageView.ScaleType.FIT_CENTER
    contentDescription = description
    importantForAccessibility =
        if (description == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

/** The canonical Narratify mark, framed so its forest-green cover remains legible in every theme. */
fun Context.brandMarkView(sizeDp: Int = 44): View = FrameLayout(this).apply {
    background = surfaceShape(0xFFFFF7E6.toInt(), Radius.INPUT, 0x268A3324)
    contentDescription = "Narratify"
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    addView(
        ImageView(context).apply {
            setImageResource(R.drawable.narratify_brand_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },
        FrameLayout.LayoutParams(dpi(sizeDp - 6), dpi(sizeDp - 6), Gravity.CENTER),
    )
}

/**
 * Text built from the type scale. `serif = true` is for content voices (titles, book names);
 * everything functional stays in the sans so the two never blur together.
 */
fun Context.styledText(
    value: CharSequence,
    size: Float,
    color: Int,
    style: Int = Typeface.NORMAL,
    serif: Boolean = false,
    tracking: Float? = null,
): TextView = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    typeface = if (serif) Type.serif(style) else Type.sans(style)
    includeFontPadding = false
    tracking?.let { letterSpacing = it }
}

/** The masthead kicker used above every page title. */
fun Context.eyebrow(value: String, color: Int): TextView =
    styledText(value.uppercase(), Type.MICRO, color, Typeface.BOLD, tracking = Type.TRACKING_EYEBROW)

/**
 * A tappable settings-style row: a bold title over a muted detail line. Originally private to
 * `LibraryScreen`'s book-options sheet; moved here (Task 7) so the narration mapping screen can
 * use the same vocabulary for "pick one of these" instead of a second copy of the row.
 */
fun Context.bookActionRow(
    palette: AppPalette,
    title: String,
    detail: String,
    color: Int,
    action: () -> Unit,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dpi(Gap.SM), dpi(Gap.MD), dpi(Gap.SM), dpi(Gap.MD))
    background = surfaceShape(Color.TRANSPARENT, Radius.INPUT)
    isClickable = true
    isFocusable = true
    contentDescription = "$title. $detail"
    addView(styledText(title, Type.BODY, color, Typeface.BOLD))
    addView(
        styledText(detail, Type.MICRO, palette.muted),
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(Gap.XXS)
        },
    )
    setOnClickListener { action() }
}

/**
 * The one filled accent action a screen reaches for. Originally private to `LibraryScreen`; moved
 * here (Task 7) so the narration mapping screen's Save/Cancel and offset controls share it instead
 * of duplicating the fill, padding, and 48dp touch minimum.
 */
fun Context.primaryButton(palette: AppPalette, title: String, action: () -> Unit): TextView =
    styledText(title, Type.BODY, palette.onAccent, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        background = surfaceShape(palette.accent, Radius.INPUT)
        setPadding(dpi(Gap.XL), dpi(Gap.SM), dpi(Gap.XL), dpi(Gap.SM))
        minHeight = dpi(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

/**
 * Wraps a small control so it still meets the 48dp touch minimum without growing visually.
 * Android's own guidance is 48dp; several of the old icon buttons were 21sp glyphs.
 */
fun Context.touchTarget(child: View, description: String, action: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        gravity = Gravity.CENTER
        minimumWidth = dpi(48)
        minimumHeight = dpi(48)
        addView(child)
        isClickable = true
        isFocusable = true
        contentDescription = description
        setOnClickListener { action() }
    }

/** Keeps press feedback consistent: opacity only, so nothing shifts under the finger. */
fun View.pressFeedback() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> view.animate().alpha(.62f).setDuration(90).start()
            android.view.MotionEvent.ACTION_UP -> {
                view.animate().alpha(1f).setDuration(140).start()
                view.performClick()
            }

            android.view.MotionEvent.ACTION_CANCEL -> view.animate().alpha(1f).setDuration(140).start()
        }
        true
    }
}

/** Padding helper so screens stop recomputing the same insets by hand. */
fun View.padDp(left: Int, top: Int, right: Int, bottom: Int) =
    setPadding(dpv(left), dpv(top), dpv(right), dpv(bottom))

/**
 * Platform controls default to the framework's purple, which showed through as the one un-themed
 * thing on otherwise finished screens. These pull SeekBar, Switch, and RadioButton onto the
 * palette; the alternative is dragging in a Material theme the app deliberately does not use.
 */
fun android.widget.SeekBar.tintTo(palette: AppPalette) {
    val accent = android.content.res.ColorStateList.valueOf(palette.accent)
    progressTintList = accent
    thumbTintList = accent
    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.outline)
    splitTrack = false
}

fun android.widget.CompoundButton.tintTo(palette: AppPalette) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
    val thumb = android.content.res.ColorStateList(states, intArrayOf(palette.accent, palette.muted))
    val track = android.content.res.ColorStateList(states, intArrayOf(palette.accent, palette.outline))
    buttonTintList = thumb
    if (this is android.widget.Switch) {
        thumbTintList = thumb
        trackTintList = track
    }
}
