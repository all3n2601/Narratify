package app.narratify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/** Charts read as "slower than playback" through colour, so these two stay outside the theme. */
internal const val CHART_WARN_LIGHT = 0xFFB3261E.toInt()
internal const val CHART_WARN_DARK = 0xFFF2B8B5.toInt()
internal const val CHART_GOOD_LIGHT = 0xFF3F7D58.toInt()
internal const val CHART_GOOD_DARK = 0xFF7BC49A.toInt()

/** How far above the measurements a reference marker may sit before it stops being drawn. */
private const val REFERENCE_HEADROOM = 1.5

/**
 * Hand-drawn charts keep the debug page dependency-free, and drawing straight onto a Canvas
 * avoids pulling a charting library into an app that ships model licences with it.
 */
abstract class DiagnosticsChart(context: Context, protected val theme: AppPalette) : View(context) {
    protected val warn: Int get() = if (theme.dark) CHART_WARN_DARK else CHART_WARN_LIGHT
    protected val good: Int get() = if (theme.dark) CHART_GOOD_DARK else CHART_GOOD_LIGHT

    protected val density = resources.displayMetrics.density
    protected var values: List<Double> = emptyList()
    protected var reference: Double? = null
    protected var formatter: (Double) -> String = { "%.2f".format(it) }

    protected val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.outline
        strokeWidth = density
    }
    protected val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = warn
        strokeWidth = density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    protected val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.muted
        textSize = 10f * density
    }

    fun setSeries(values: List<Double>, reference: Double? = null, formatter: (Double) -> String = this.formatter) {
        this.values = values
        this.reference = reference
        this.formatter = formatter
        invalidate()
    }

    /**
     * Scale to the data, not to the reference: a real-time factor of 0.02 against a 1.0 marker
     * would otherwise flatten every bar into the baseline. The marker is kept only while it stays
     * near the measurements, and [referenceVisible] tells the drawing code when it was dropped.
     */
    protected fun ceiling(): Double {
        val dataMax = values.maxOrNull() ?: 0.0
        val marker = reference
        return when {
            dataMax <= 0.0 -> marker?.takeIf { it > 0 } ?: 1.0
            marker != null && marker <= dataMax * REFERENCE_HEADROOM -> max(dataMax, marker)
            else -> dataMax
        }
    }

    protected fun referenceVisible(ceiling: Double): Boolean = reference?.let { it <= ceiling } == true

    protected fun drawEmpty(canvas: Canvas) {
        canvas.drawText(
            "No measurements yet — play a book or run the benchmark.",
            8 * density,
            height / 2f,
            labelPaint,
        )
    }

    protected fun drawScale(canvas: Canvas, top: Float, bottom: Float, ceiling: Double) {
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, axisPaint)
        canvas.drawText(formatter(ceiling), 2 * density, top + 10 * density, labelPaint)
        if (!referenceVisible(ceiling)) return
        val line = reference ?: return
        val y = bottom - ((line / ceiling).toFloat() * (bottom - top))
        canvas.drawLine(0f, y, width.toFloat(), y, referencePaint)
        canvas.drawText(formatter(line), width - 40 * density, y + 12 * density, labelPaint)
    }
}

/** One bar per utterance, coloured against the reference so slow passages are obvious. */
class BarSeriesChart(context: Context, theme: AppPalette) : DiagnosticsChart(context, theme) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return drawEmpty(canvas)
        val top = 4 * density
        val bottom = height - 12 * density
        val ceiling = ceiling()
        drawScale(canvas, top, bottom, ceiling)
        val slot = width.toFloat() / values.size
        val barWidth = (slot - 2 * density).coerceAtLeast(1.5f * density)
        values.forEachIndexed { index, value ->
            val ratio = (value / ceiling).toFloat().coerceIn(0f, 1f)
            val barTop = bottom - ratio * (bottom - top)
            barPaint.color = when {
                reference == null -> theme.accent
                value > reference!! -> warn
                else -> good
            }
            canvas.drawRoundRect(
                RectF(index * slot + density, barTop, index * slot + density + barWidth, bottom),
                2 * density,
                2 * density,
                barPaint,
            )
        }
    }
}

/** Latency reads better as a trend than as bars, so first-audio uses a filled line. */
class LineSeriesChart(context: Context, theme: AppPalette) : DiagnosticsChart(context, theme) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.accent
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.subtle }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return drawEmpty(canvas)
        val top = 4 * density
        val bottom = height - 12 * density
        val ceiling = ceiling()
        drawScale(canvas, top, bottom, ceiling)
        val step = if (values.size == 1) 0f else width.toFloat() / (values.size - 1)
        val line = Path()
        val fill = Path()
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) width / 2f else index * step
            val y = bottom - (value / ceiling).toFloat().coerceIn(0f, 1f) * (bottom - top)
            if (index == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, bottom)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(if (values.size == 1) width / 2f else (values.size - 1) * step, bottom)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
    }
}

/** A single arc for "how many seconds of speech each second of synthesis buys". */
class SpeedGaugeChart(context: Context, private val theme: AppPalette) : View(context) {
    private val density = resources.displayMetrics.density
    private var ratio = 0.0
    private var caption = ""

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.subtle
        style = Paint.Style.STROKE
        strokeWidth = 10 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.accent
        style = Paint.Style.STROKE
        strokeWidth = 10 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.ink
        textSize = 22f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val captionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.muted
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    /** [ratio] is speech seconds per synthesis second; the arc saturates at 8×. */
    fun setRatio(ratio: Double, caption: String) {
        this.ratio = ratio
        this.caption = caption
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 14 * density
        val box = RectF(inset, inset, width - inset, height * 1.35f - inset)
        canvas.drawArc(box, 180f, 180f, false, trackPaint)
        valuePaint.color = if (ratio >= 1.0) {
            if (theme.dark) CHART_GOOD_DARK else CHART_GOOD_LIGHT
        } else {
            if (theme.dark) CHART_WARN_DARK else CHART_WARN_LIGHT
        }
        canvas.drawArc(box, 180f, (ratio / 8.0).coerceIn(0.0, 1.0).toFloat() * 180f, false, valuePaint)
        canvas.drawText(
            if (ratio > 0) "%.1f×".format(ratio) else "—",
            width / 2f,
            height * 0.72f,
            valueText,
        )
        canvas.drawText(caption, width / 2f, height * 0.92f, captionText)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, (96 * density).toInt())
    }
}

internal fun chartBackground(color: Int, radiusDp: Float, strokeColor: Int) = android.graphics.drawable.GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusDp * android.content.res.Resources.getSystem().displayMetrics.density
    setStroke(1, strokeColor)
}
