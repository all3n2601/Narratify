package app.narratify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.util.LruCache
import kotlin.math.abs
import kotlin.math.max

/**
 * Cover stock: six cloth colours, all dark enough to carry foil-white type and warm enough to sit
 * on the app's paper without clashing. Assigned by id so a book keeps the same cover between runs.
 */
private val coverStock = listOf(
    0xFF6E2B22.toInt() to 0xFF3D1712.toInt(), // oxblood
    0xFF23453B.toInt() to 0xFF122620.toInt(), // forest
    0xFF2C3A57.toInt() to 0xFF161E2E.toInt(), // slate blue
    0xFF7A5518.toInt() to 0xFF3F2B0B.toInt(), // ochre
    0xFF4A2B45.toInt() to 0xFF261623.toInt(), // plum
    0xFF1F4048.toInt() to 0xFF102227.toInt(), // teal
)

/** Everything [BookCoverView] needs to draw a jacket, plus the progress the shelf shows under it. */
internal data class CoverArt(
    val title: String,
    val author: String,
    val eyebrow: String,
    val startColor: Int,
    val endColor: Int,
    val progress: Int,
    val id: String? = null,
    val coverUri: String? = null,
)

internal fun coverArtFor(book: LocalBook): CoverArt {
    val colors = coverStock[abs(book.id.hashCode()) % coverStock.size]
    return CoverArt(
        title = book.title,
        author = book.author ?: book.format,
        eyebrow = book.format.uppercase(),
        startColor = colors.first,
        endColor = colors.second,
        progress = book.progress,
        id = book.id,
        coverUri = book.coverUri,
    )
}

/** Generated jacket shown while a catalog thumbnail is loading, and when a source has no art. */
internal fun coverArtFor(book: CatalogBook): CoverArt {
    val colors = coverStock[(book.id.hashCode() and Int.MAX_VALUE) % coverStock.size]
    return CoverArt(
        title = book.title,
        author = book.author,
        eyebrow = book.sources.substringBefore(" · ").uppercase(),
        startColor = colors.first,
        endColor = colors.second,
        progress = 0,
        id = book.id,
    )
}

/** A dimensional hardcover with a visible page block, curved spine, and enchanted foil motif. */
@SuppressLint("ViewConstructor")
internal class BookCoverView(context: Context, private val art: CoverArt) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var coverGradient: LinearGradient? = null
    private var spineGradient: LinearGradient? = null
    private var pageGradient: LinearGradient? = null
    private var coverBitmap: Bitmap? = art.coverUri?.let(::loadCoverBitmap)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4EEE3.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Type.serif(Typeface.BOLD)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3F4EEE3.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Type.sans(Typeface.BOLD)
        letterSpacing = .14f
    }

    init {
        // The cast shadow and page block extend beyond the front board, so the view cannot clip to
        // a flat rectangular outline. The tile itself supplies the touch target.
        clipToOutline = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
        contentDescription = "Cover of ${art.title}"
    }

    /** Replaces the generated face when an asynchronous catalog thumbnail arrives. */
    fun setCoverBitmap(bitmap: Bitmap) {
        coverBitmap = bitmap
        invalidate()
    }

    /**
     * A cover is a physical object with a fixed trim, so unless the parent pins both axes the view
     * derives its height from its width. Letting a grid stretch it produced landscape "books".
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY || width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        setMeasuredDimension(width, (width * TRIM_RATIO).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        coverGradient = LinearGradient(
            w * .12f,
            0f,
            w * .9f,
            h.toFloat(),
            intArrayOf(lighten(art.startColor, .13f), art.startColor, art.endColor),
            floatArrayOf(0f, .42f, 1f),
            Shader.TileMode.CLAMP,
        )
        spineGradient = LinearGradient(
            w * .07f,
            0f,
            w * .24f,
            0f,
            intArrayOf(darken(art.endColor, .42f), darken(art.startColor, .12f), lighten(art.startColor, .12f)),
            null,
            Shader.TileMode.CLAMP,
        )
        pageGradient = LinearGradient(
            w * .86f,
            0f,
            w * .98f,
            0f,
            intArrayOf(0xFFC9B98F.toInt(), 0xFFF4E7C3.toInt(), 0xFF9F8D68.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val left = w * .065f
        val top = h * .025f
        val right = w * .9f
        val bottom = h * .94f
        val corner = max(dp(3).toFloat(), w * .035f)
        val spine = max(dp(8).toFloat(), w * .12f)

        // Soft offset shadows make the object hover above the shelf without a heavy black slab.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0x18000000
        canvas.drawRoundRect(RectF(left + w * .055f, top + h * .045f, w * .985f, h * .985f), corner * 1.3f, corner * 1.3f, paint)
        paint.color = 0x35000000
        canvas.drawRoundRect(RectF(left + w * .035f, top + h * .025f, w * .965f, h * .97f), corner, corner, paint)

        // The cream page block sits behind the front board and remains visible at the fore-edge.
        path.reset()
        path.moveTo(left + w * .055f, top + h * .026f)
        path.lineTo(w * .965f, top + h * .052f)
        path.lineTo(w * .965f, bottom + h * .016f)
        path.lineTo(left + w * .055f, bottom + h * .032f)
        path.close()
        paint.shader = pageGradient
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density * .45f)
        paint.color = 0x5B75684D
        repeat(5) { index ->
            val y = top + h * (.09f + index * .16f)
            canvas.drawLine(right + w * .012f, y, w * .958f, y + h * .006f, paint)
        }
        paint.style = Paint.Style.FILL

        // Front board: subtly skewed top and bottom edges sell perspective while preserving a
        // generous rectangular area for readable cover typography.
        path.reset()
        path.moveTo(left, top + h * .018f)
        path.lineTo(right, top)
        path.lineTo(right, bottom)
        path.lineTo(left, bottom + h * .022f)
        path.close()
        paint.shader = coverGradient
        canvas.drawPath(path, paint)
        paint.shader = null

        // A real embedded cover owns the full front face. The generated foil jacket remains only
        // as a fallback for plain text or publications without artwork.
        coverBitmap?.let { bitmap ->
            val facePath = Path().apply {
                moveTo(left + spine, top + h * .014f)
                lineTo(right, top)
                lineTo(right, bottom)
                lineTo(left + spine, bottom + h * .018f)
                close()
            }
            canvas.save()
            canvas.clipPath(facePath)
            drawCentreCropped(canvas, bitmap, RectF(left + spine, top, right, bottom + h * .018f))
            paint.shader = LinearGradient(
                left + spine,
                0f,
                right,
                0f,
                intArrayOf(0x50000000, 0x08FFFFFF, 0x1AFFFFFF),
                floatArrayOf(0f, .58f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(left + spine, top, right, bottom + h * .02f, paint)
            paint.shader = null
            canvas.restore()
        }

        path.reset()
        path.moveTo(left, top + h * .018f)
        path.lineTo(left + spine, top + h * .014f)
        path.lineTo(left + spine, bottom + h * .018f)
        path.lineTo(left, bottom + h * .022f)
        path.close()
        paint.shader = spineGradient
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.color = 0x35FFF4D2
        canvas.drawRect(left + spine - max(1f, density * .55f), top + h * .02f, left + spine, bottom + h * .015f, paint)

        val faceLeft = left + spine
        val faceRight = right
        val centre = faceLeft + (faceRight - faceLeft) / 2f
        val textWidth = (faceRight - faceLeft) * .78f
        if (coverBitmap == null) {
            // Fine foil border and corner botanicals replace the old rigid rectangular keyline.
            drawFoilFrame(canvas, faceLeft, faceRight, top, bottom, corner)
            drawEnchantedMotif(canvas, centre, top + (bottom - top) * .34f, (faceRight - faceLeft) * .27f)

            smallPaint.textSize = max(dp(6).toFloat(), w * .062f)
            drawFitted(canvas, art.eyebrow, centre, top + (bottom - top) * .19f, smallPaint, textWidth)

            val lines = fitTitle(art.title, textWidth, h * TITLE_BAND)
            val block = lines.size * titlePaint.textSize * LINE_RATIO
            lines.forEachIndexed { index, line ->
                canvas.drawText(line, centre, h * .58f - block / 2f + (index + .82f) * titlePaint.textSize * LINE_RATIO, titlePaint)
            }

            smallPaint.textSize = max(dp(6).toFloat(), w * .056f)
            drawFitted(canvas, art.author.uppercase(), centre, top + (bottom - top) * .86f, smallPaint, textWidth)
        }

        // A narrow reflected edge catches the forest's moonlight as the book tilts.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density * .7f)
        paint.color = 0x55E2F1C9
        path.reset()
        path.moveTo(left + spine, top + h * .014f)
        path.lineTo(right, top)
        path.lineTo(right, bottom)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawCentreCropped(canvas: Canvas, bitmap: Bitmap, destination: RectF) {
        val targetRatio = destination.width() / destination.height()
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val source = if (bitmapRatio > targetRatio) {
            val width = (bitmap.height * targetRatio).toInt().coerceAtLeast(1)
            val left = (bitmap.width - width) / 2
            Rect(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / targetRatio).toInt().coerceAtLeast(1)
            val top = (bitmap.height - height) / 2
            Rect(0, top, bitmap.width, top + height)
        }
        paint.shader = null
        paint.alpha = 255
        canvas.drawBitmap(bitmap, source, destination, paint)
    }

    private fun drawFoilFrame(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, radius: Float) {
        val insetX = (right - left) * .09f
        val insetY = (bottom - top) * .065f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density * .65f)
        paint.color = 0x70E5CF92
        canvas.drawRoundRect(RectF(left + insetX, top + insetY, right - insetX, bottom - insetY), radius, radius, paint)
        paint.style = Paint.Style.FILL

        listOf(-1f, 1f).forEach { side ->
            val anchorX = if (side < 0) left + insetX else right - insetX
            val anchorY = bottom - insetY
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, density * .7f)
            path.reset()
            path.moveTo(anchorX, anchorY)
            path.quadTo(anchorX - side * (right - left) * .05f, anchorY - (bottom - top) * .08f, anchorX - side * (right - left) * .02f, anchorY - (bottom - top) * .15f)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            repeat(3) { leaf ->
                val y = anchorY - (bottom - top) * (.055f + leaf * .034f)
                val x = anchorX - side * (right - left) * (.014f + leaf * .004f)
                canvas.save()
                canvas.rotate(side * (28f + leaf * 7f), x, y)
                canvas.drawOval(RectF(x - radius * .55f, y - radius * .22f, x + radius * .55f, y + radius * .22f), paint)
                canvas.restore()
            }
        }
    }

    private fun drawEnchantedMotif(canvas: Canvas, x: Float, y: Float, radius: Float) {
        val variant = abs((art.id ?: art.title).hashCode()) % 3
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density * .72f)
        paint.color = 0x78F0D99A
        when (variant) {
            0 -> {
                canvas.drawCircle(x, y, radius * .46f, paint)
                canvas.drawArc(RectF(x - radius, y - radius * .64f, x + radius, y + radius * .64f), 205f, 130f, false, paint)
            }
            1 -> {
                path.reset()
                path.moveTo(x, y + radius * .72f)
                path.quadTo(x - radius * .08f, y, x + radius * .08f, y - radius * .72f)
                canvas.drawPath(path, paint)
                repeat(3) { leaf ->
                    val offset = radius * (.18f + leaf * .22f)
                    canvas.drawOval(RectF(x - radius * .55f, y - offset, x - radius * .06f, y - offset + radius * .22f), paint)
                    canvas.drawOval(RectF(x + radius * .06f, y - offset * .8f, x + radius * .55f, y - offset * .8f + radius * .22f), paint)
                }
            }
            else -> {
                val points = arrayOf(-.72f to .28f, -.24f to -.46f, .18f to .08f, .7f to -.35f)
                path.reset()
                points.forEachIndexed { index, point ->
                    val px = x + radius * point.first
                    val py = y + radius * point.second
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    canvas.drawCircle(px, py, max(1.5f, radius * .055f), paint)
                }
                canvas.drawPath(path, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xA8FFE3A6.toInt()
        canvas.drawCircle(x + radius * .76f, y - radius * .62f, max(1.2f, radius * .045f), paint)
    }

    /**
     * Sets [titlePaint] to the largest size at which the title fits both [maxWidth] and
     * [maxHeight], then returns the wrapped lines at that size.
     */
    private fun fitTitle(title: String, maxWidth: Float, maxHeight: Float): List<String> {
        var size = max(dp(9).toFloat(), width * .15f)
        val floor = max(dp(7).toFloat(), width * .07f)
        var lines: List<String>
        while (true) {
            titlePaint.textSize = size
            lines = wrap(title, maxWidth)
            val fitsWidth = lines.all { titlePaint.measureText(it) <= maxWidth }
            val fitsHeight = lines.size * size * LINE_RATIO <= maxHeight
            if ((fitsWidth && fitsHeight) || size <= floor) break
            size *= .92f
        }
        // A title long enough to still overflow at the floor size is truncated rather than run on.
        val maxLines = (maxHeight / (titlePaint.textSize * LINE_RATIO)).toInt().coerceAtLeast(1)
        if (lines.size <= maxLines) return lines
        return lines.take(maxLines).toMutableList().also { kept ->
            kept[kept.lastIndex] = ellipsize(kept.last(), maxWidth)
        }
    }

    /** Greedy wrap at the paint's current size. */
    private fun wrap(title: String, maxWidth: Float): List<String> {
        val words = title.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf(title)
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (titlePaint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun ellipsize(line: String, maxWidth: Float): String {
        var trimmed = line
        while (trimmed.isNotEmpty() && titlePaint.measureText("$trimmed…") > maxWidth) {
            trimmed = trimmed.dropLast(1).trimEnd()
        }
        return "$trimmed…"
    }

    private fun drawFitted(canvas: Canvas, value: String, x: Float, y: Float, textPaint: Paint, maxWidth: Float) {
        if (value.isBlank()) return
        val originalSize = textPaint.textSize
        val measured = textPaint.measureText(value)
        if (measured > maxWidth) textPaint.textSize *= maxWidth / measured
        canvas.drawText(value, x, y, textPaint)
        textPaint.textSize = originalSize
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val density get() = resources.displayMetrics.density

    private fun darken(color: Int, amount: Float): Int = blend(color, Color.BLACK, amount)
    private fun lighten(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val value = amount.coerceIn(0f, 1f)
        fun channel(shift: Int) = (((from shr shift) and 0xff) * (1f - value) + ((to shr shift) and 0xff) * value).toInt()
        return Color.rgb(channel(16), channel(8), channel(0))
    }

    private companion object {
        /** Height over width. Roughly a trade paperback. */
        const val TRIM_RATIO = 1.5f
        /** Share of the cover height the title block may occupy. */
        const val TITLE_BAND = .38f
        const val LINE_RATIO = 1.12f

        private val bitmapCache = object : LruCache<String, Bitmap>(12 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }

        private fun loadCoverBitmap(uri: String): Bitmap? {
            bitmapCache.get(uri)?.takeIf { !it.isRecycled }?.let { return it }
            val decoded = BitmapFactory.decodeFile(uri) ?: return null
            bitmapCache.put(uri, decoded)
            return decoded
        }
    }
}

/** A progress rule: a hairline that fills with accent. Replaces the Material progress bar. */
@SuppressLint("ViewConstructor")
internal class ProgressRule(
    context: Context,
    private val progress: Int,
    private val filled: Int,
    private val track: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        paint.color = track
        paint.alpha = 80
        canvas.drawRect(0f, 0f, width.toFloat(), h, paint)
        paint.color = filled
        paint.alpha = 255
        canvas.drawRect(0f, 0f, width * (progress.coerceIn(0, 100) / 100f), h, paint)
    }
}
