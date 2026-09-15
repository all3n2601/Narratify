package app.narratify

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * A lightweight, procedural 2.5D forest for the library shell.
 *
 * The scene is split into depth planes. Each plane drifts at a different rate, creating parallax
 * without a game engine or a large 3D asset. The nearest canopy responds most to occasional wind
 * gusts while tiny fireflies move independently above every layer. Everything is decorative and
 * automatically becomes still when Android animations are disabled.
 */
internal class EnchantedForestView(context: Context) : View(context) {
    private data class Tree(
        val x: Float,
        val height: Float,
        val width: Float,
        val phase: Float,
    )

    private data class Firefly(
        val x: Float,
        val y: Float,
        val radius: Float,
        val phase: Float,
        val driftX: Float,
        val driftY: Float,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val distantTrees = mutableListOf<Tree>()
    private val middleTrees = mutableListOf<Tree>()
    private val nearTrees = mutableListOf<Tree>()
    private val fireflies = mutableListOf<Firefly>()
    private var skyShader: Shader? = null
    private var skyGlowShader: Shader? = null
    private var moonGlowShader: Shader? = null
    private var moonCoreShader: Shader? = null
    private var moonBeamShader: Shader? = null
    private var mistShader: Shader? = null
    private var scrimShader: Shader? = null
    private var elapsedSeconds = 0f
    private var parallaxX = 0f
    private var parallaxY = 0f
    private var targetParallaxX = 0f
    private var targetParallaxY = 0f
    private var scrollProgress = 0f
    private var targetScrollProgress = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var touchAttraction = 0f
    private var touchActive = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchTimeMs = 0L
    private var touchTravel = 0f
    private var swipeWind = 0f
    private var moonPulseStartedAt = Float.NEGATIVE_INFINITY
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        skyShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF071B25.toInt(), 0xFF0A2928.toInt(), 0xFF031310.toInt()),
            floatArrayOf(0f, .52f, 1f),
            Shader.TileMode.CLAMP,
        )
        skyGlowShader = RadialGradient(
            w * .72f, h * .13f, w * .62f,
            intArrayOf(0x3D7FAD9C, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        val moonX = w * MOON_X
        val moonY = h * MOON_Y
        val moonRadius = w * MOON_RADIUS
        moonGlowShader = RadialGradient(
            moonX, moonY, moonRadius * 6.8f,
            intArrayOf(0x59FFF1BF, 0x268CD1BF, Color.TRANSPARENT),
            floatArrayOf(0f, .28f, 1f),
            Shader.TileMode.CLAMP,
        )
        moonCoreShader = RadialGradient(
            moonX - moonRadius * .28f,
            moonY - moonRadius * .32f,
            moonRadius * 1.4f,
            intArrayOf(0xFFFFF6D9.toInt(), 0xFFFFE5A6.toInt(), 0xFFD0C997.toInt()),
            floatArrayOf(0f, .62f, 1f),
            Shader.TileMode.CLAMP,
        )
        moonBeamShader = LinearGradient(
            moonX, moonY, moonX - w * .16f, h * .7f,
            intArrayOf(0x24DFF3CF, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        mistShader = RadialGradient(
            0f, 0f, w * .42f,
            intArrayOf(0x185FA697, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        scrimShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x24000000, 0x3D000000, 0x8A01100D.toInt()),
            floatArrayOf(0f, .42f, 1f),
            Shader.TileMode.CLAMP,
        )
        distantTrees.replaceWith(treeLine(16, .34f, .20f, .33f, 13))
        middleTrees.replaceWith(treeLine(12, .48f, .29f, .44f, 29))
        nearTrees.replaceWith(treeLine(9, .66f, .38f, .58f, 47))
        fireflies.clear()
        repeat(if (resources.configuration.smallestScreenWidthDp >= 600) 24 else 16) { index ->
            val seed = pseudo(index + 91)
            fireflies += Firefly(
                x = .035f + pseudo(index * 5 + 2) * .93f,
                y = .08f + pseudo(index * 7 + 3) * .82f,
                radius = .75f + seed * 1.65f,
                phase = pseudo(index * 11 + 5) * (PI * 2).toFloat(),
                driftX = 5f + pseudo(index * 13 + 7) * 12f,
                driftY = 4f + pseudo(index * 17 + 9) * 10f,
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startScene()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun startScene() {
        if (!ValueAnimator.areAnimatorsEnabled() || animator != null) return
        var lastNanos = System.nanoTime()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val now = System.nanoTime()
                if (now - lastNanos < FRAME_INTERVAL_NANOS) return@addUpdateListener
                elapsedSeconds += ((now - lastNanos) / 1_000_000_000f).coerceAtMost(.05f)
                lastNanos = now
                parallaxX += (targetParallaxX - parallaxX) * .055f
                parallaxY += (targetParallaxY - parallaxY) * .055f
                scrollProgress += (targetScrollProgress - scrollProgress) * .09f
                touchAttraction += ((if (touchActive) 1f else 0f) - touchAttraction) * .13f
                swipeWind *= .94f
                if (abs(swipeWind) < .002f) swipeWind = 0f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        drawSky(canvas)
        drawMist(canvas)
        val wind = windAt(elapsedSeconds) + swipeWind
        drawTreeLayer(canvas, distantTrees, .43f, 0xFF102F32.toInt(), .22f, wind * .25f)
        // The moon sits inside the scene rather than on top of it: distant trees recede behind its
        // halo while the middle and near layers remain silhouetted in front.
        drawMoonlight(canvas)
        drawTreeLayer(canvas, middleTrees, .64f, 0xFF092620.toInt(), .55f, wind * .58f)
        drawTreeLayer(canvas, nearTrees, .84f, 0xFF041A16.toInt(), 1f, wind)
        canvas.save()
        canvas.translate(0f, -scrollProgress * height * .02f)
        drawCanopy(canvas, wind)
        canvas.restore()
        drawFireflies(canvas)
        drawReadingScrim(canvas)
    }

    private fun drawSky(canvas: Canvas) {
        paint.alpha = 255
        paint.shader = skyShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = skyGlowShader
        canvas.drawRect(0f, 0f, width.toFloat(), height * .7f, paint)
        paint.shader = null
    }

    private fun drawMoonlight(canvas: Canvas) {
        val moonX = width * MOON_X
        val moonY = height * MOON_Y
        val moonRadius = width * MOON_RADIUS
        val scrollLift = -scrollProgress * height * .009f
        val pulseAge = elapsedSeconds - moonPulseStartedAt
        val pulse = if (pulseAge in 0f..MOON_PULSE_SECONDS) {
            sin((pulseAge / MOON_PULSE_SECONDS) * PI).toFloat().coerceAtLeast(0f)
        } else 0f

        canvas.save()
        canvas.translate(0f, scrollLift)
        paint.alpha = 255
        paint.shader = moonGlowShader
        canvas.drawCircle(moonX, moonY, moonRadius * 6.8f, paint)
        if (pulse > 0f) {
            paint.alpha = (pulse * 118f).toInt()
            canvas.drawCircle(moonX, moonY, moonRadius * 6.8f, paint)
        }
        paint.alpha = 255
        paint.shader = moonCoreShader
        canvas.drawCircle(moonX, moonY, moonRadius, paint)
        paint.shader = null

        // Low-contrast markings give the full moon volume without making it visually busy.
        paint.color = 0x247C826D
        canvas.drawCircle(moonX - moonRadius * .28f, moonY + moonRadius * .12f, moonRadius * .16f, paint)
        canvas.drawCircle(moonX + moonRadius * .24f, moonY - moonRadius * .24f, moonRadius * .11f, paint)
        canvas.drawCircle(moonX + moonRadius * .17f, moonY + moonRadius * .31f, moonRadius * .09f, paint)

        // A broad, diffuse shaft catches the mist and visually connects the moon to the trees.
        paint.shader = moonBeamShader
        path.reset()
        path.moveTo(moonX - moonRadius * .7f, moonY + moonRadius * .5f)
        path.lineTo(moonX + moonRadius * .25f, moonY + moonRadius * .7f)
        path.lineTo(width * .68f, height * .72f)
        path.lineTo(width * .39f, height * .72f)
        path.close()
        canvas.drawPath(path, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun drawMist(canvas: Canvas) {
        paint.alpha = 255
        repeat(3) { index ->
            val phase = elapsedSeconds * (.035f + index * .009f) + index * 1.7f
            val x = width * (.2f + index * .31f) + sin(phase) * width * .08f
            val y = height * (.22f + index * .13f) -
                scrollProgress * height * (.006f + index * .004f)
            paint.shader = mistShader
            canvas.save()
            canvas.translate(x, y)
            canvas.drawOval(-width * .46f, -height * .06f, width * .46f, height * .06f, paint)
            canvas.restore()
        }
        paint.shader = null
    }

    private fun drawTreeLayer(
        canvas: Canvas,
        trees: List<Tree>,
        baseFraction: Float,
        color: Int,
        depth: Float,
        wind: Float,
    ) {
        val scrollLift = scrollProgress * height * (.01f + depth * .05f)
        val baseY = height * baseFraction + parallaxY * depth * 7f - scrollLift
        trees.forEach { tree ->
            // Leave one irregular opening in the middle canopy so the moon reads as luminous;
            // nearer branches still cross its lower halo and keep it embedded in the forest.
            if (depth in .5f.. .6f && abs(tree.x - MOON_X) < .075f) return@forEach
            paint.color = color
            val cameraDrift = sin(elapsedSeconds * .075f + depth) * width * .008f * depth
            val x = tree.x * width + parallaxX * depth * 18f + cameraDrift
            val treeHeight = tree.height * height
            val trunkWidth = tree.width * width
            val sway = (sin(elapsedSeconds * .42f + tree.phase) * .18f + wind) * trunkWidth * depth
            paint.strokeWidth = max(2f, trunkWidth * .16f)
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x, baseY, x + sway, baseY - treeHeight, paint)
            drawConiferCrown(x, baseY - treeHeight, treeHeight, trunkWidth, sway, tree.phase)
            canvas.drawPath(path, paint)
            drawPineNeedleTexture(canvas, x, baseY - treeHeight, treeHeight, trunkWidth, sway, tree.phase, depth)
            drawMoonlitBoughs(canvas, x, baseY - treeHeight, treeHeight, trunkWidth, sway, tree.phase, depth)
        }
    }

    /**
     * A fir silhouette: tiers of branches that step outward towards the ground, each one notched
     * back in above the last, over a bare length of trunk. Built without allocating, because this
     * runs for every tree on every frame.
     */
    private fun drawConiferCrown(
        x: Float,
        crownTop: Float,
        treeHeight: Float,
        trunkWidth: Float,
        sway: Float,
        textureSeed: Float,
    ) {
        val crownHeight = treeHeight * .86f
        path.reset()
        path.moveTo(x + sway, crownTop)
        for (tier in 1..CROWN_TIERS) {
            path.lineTo(
                x + bendAt(sway, tier) + branchWidthAt(trunkWidth, tier, textureSeed, side = 1f),
                tierY(crownTop, crownHeight, tier),
            )
            if (tier < CROWN_TIERS) notch(x, crownTop, crownHeight, trunkWidth, sway, tier, 1f, textureSeed)
        }
        for (tier in CROWN_TIERS downTo 1) {
            path.lineTo(
                x + bendAt(sway, tier) - branchWidthAt(trunkWidth, tier, textureSeed, side = -1f),
                tierY(crownTop, crownHeight, tier),
            )
            if (tier > 1) notch(x, crownTop, crownHeight, trunkWidth, sway, tier - 1, -1f, textureSeed)
        }
        path.close()
    }

    /** The step back towards the trunk that separates one tier of branches from the next. */
    private fun notch(
        x: Float,
        crownTop: Float,
        crownHeight: Float,
        trunkWidth: Float,
        sway: Float,
        tier: Int,
        side: Float,
        textureSeed: Float,
    ) {
        path.lineTo(
            x + bendAt(sway, tier) + side * branchWidthAt(trunkWidth, tier, textureSeed, side) * .43f,
            tierY(crownTop, crownHeight, tier) - crownHeight * .035f,
        )
    }

    private fun tierY(crownTop: Float, crownHeight: Float, tier: Int) =
        crownTop + crownHeight * (tier / CROWN_TIERS.toFloat())

    private fun halfWidthAt(trunkWidth: Float, tier: Int) =
        trunkWidth * (.18f + .76f * (tier / CROWN_TIERS.toFloat()))

    private fun branchWidthAt(trunkWidth: Float, tier: Int, seed: Float, side: Float): Float {
        val irregularity = .9f + sin(seed * 3.1f + tier * 1.73f + side * .8f) * .09f
        return halfWidthAt(trunkWidth, tier) * irregularity
    }

    /** Sway is strongest at the tip and dies out where the trunk meets the ground. */
    private fun bendAt(sway: Float, tier: Int) = sway * (1f - tier / CROWN_TIERS.toFloat())

    /** Fine branch strokes break up the flat silhouette without adding costly bitmap foliage. */
    private fun drawPineNeedleTexture(
        canvas: Canvas,
        x: Float,
        crownTop: Float,
        treeHeight: Float,
        trunkWidth: Float,
        sway: Float,
        seed: Float,
        depth: Float,
    ) {
        val crownHeight = treeHeight * .86f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(.7f, density * (.72f + depth * .22f))
        paint.color = Color.argb((18 + depth * 10).toInt(), 129, 179, 151)
        for (tier in 2..CROWN_TIERS step 2) {
            val centerX = x + bendAt(sway, tier)
            val y = tierY(crownTop, crownHeight, tier) - crownHeight * .02f
            listOf(-1f, 1f).forEach { side ->
                val reach = branchWidthAt(trunkWidth, tier, seed, side) * .86f
                path.reset()
                path.moveTo(centerX, y - crownHeight * .018f)
                path.quadTo(
                    centerX + side * reach * .48f,
                    y + crownHeight * .012f,
                    centerX + side * reach,
                    y + crownHeight * .026f,
                )
                canvas.drawPath(path, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    /** Light touches only the branch tips facing the moon, avoiding the old wireframe outline. */
    private fun drawMoonlitBoughs(
        canvas: Canvas,
        x: Float,
        crownTop: Float,
        treeHeight: Float,
        trunkWidth: Float,
        sway: Float,
        seed: Float,
        depth: Float,
    ) {
        val moonSide = if (x < width * MOON_X) 1f else -1f
        val crownHeight = treeHeight * .86f
        val glowAlpha = ((1f - depth) * 62f).toInt().coerceIn(9, 42)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(1f, density * (1.28f - depth * .42f))
        paint.color = Color.argb(glowAlpha, 197, 231, 189)
        for (tier in 2..CROWN_TIERS step 2) {
            val centerX = x + bendAt(sway, tier)
            val y = tierY(crownTop, crownHeight, tier) - crownHeight * .028f
            val reach = branchWidthAt(trunkWidth, tier, seed, moonSide)
            path.reset()
            path.moveTo(centerX + moonSide * reach * .48f, y)
            path.quadTo(
                centerX + moonSide * reach * .72f,
                y + crownHeight * .014f,
                centerX + moonSide * reach,
                y + crownHeight * .026f,
            )
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawCanopy(canvas: Canvas, wind: Float) {
        drawHangingPineBoughs(canvas, fromLeft = true, wind)
        drawHangingPineBoughs(canvas, fromLeft = false, wind)
    }

    private fun drawHangingPineBoughs(canvas: Canvas, fromLeft: Boolean, wind: Float) {
        val direction = if (fromLeft) 1f else -1f
        repeat(4) { branch ->
            val seed = pseudo(201 + branch + if (fromLeft) 0 else 19)
            val startX = if (fromLeft) -width * .025f else width * 1.025f
            val startY = height * (.008f + branch * .018f)
            val reach = width * (.13f + seed * .055f)
            val sway = sin(elapsedSeconds * (.23f + seed * .08f) + branch) * density * 2f + wind * density * 7f
            val endX = startX + direction * reach + sway
            val endY = startY + height * (.025f + branch * .012f)

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = density * (2.2f + seed * 1.7f)
            paint.color = if (branch % 2 == 0) 0xFA061D18.toInt() else 0xEE0A2921.toInt()
            path.reset()
            path.moveTo(startX, startY)
            path.quadTo(startX + direction * reach * .55f, startY + height * .004f, endX, endY)
            canvas.drawPath(path, paint)

            paint.style = Paint.Style.FILL
            repeat(5) { cluster ->
                val progress = (cluster + 1f) / 6f
                val cx = startX + (endX - startX) * progress
                val cy = startY + (endY - startY) * progress
                val needle = width * (.016f + seed * .008f) * (1f - progress * .32f)
                path.reset()
                path.moveTo(cx - direction * needle * .18f, cy)
                path.lineTo(cx + direction * needle, cy + needle * .82f)
                path.lineTo(cx + direction * needle * .28f, cy + needle * .16f)
                path.lineTo(cx - direction * needle * .62f, cy + needle * .7f)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawFireflies(canvas: Canvas) {
        fireflies.forEach { fly ->
            var x = fly.x * width + sin(elapsedSeconds * .31f + fly.phase) * fly.driftX * density
            var y = fly.y * height + cos(elapsedSeconds * .23f + fly.phase * .83f) * fly.driftY * density -
                scrollProgress * height * .018f
            val dx = touchX - x
            val dy = touchY - y
            val distance = hypot(dx, dy)
            val attractionRadius = width * .31f
            if (touchAttraction > .001f && distance < attractionRadius) {
                val influence = touchAttraction * (1f - distance / attractionRadius).pow(2)
                x += dx * influence * .34f
                y += dy * influence * .34f
                if (distance > 1f) {
                    val orbit = sin(elapsedSeconds * 3.2f + fly.phase) * density * 8f * influence
                    x += -dy / distance * orbit
                    y += dx / distance * orbit
                }
            }
            x += swipeWind * density * (7f + fly.radius * 2.4f)
            val pulse = ((sin(elapsedSeconds * 1.35f + fly.phase) + 1f) * .5f).pow(2)
            val alpha = (45 + pulse * 185).toInt()
            val r = fly.radius * density
            paint.color = Color.argb((alpha * .16f).toInt(), 255, 220, 126)
            canvas.drawCircle(x, y, r * 5.4f, paint)
            paint.color = Color.argb(alpha, 255, 229, 151)
            canvas.drawCircle(x, y, r, paint)
        }
    }

    private fun drawReadingScrim(canvas: Canvas) {
        paint.alpha = 255
        paint.shader = scrimShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        observeTouch(event)
        return false
    }

    /**
     * Observes a gesture routed by the library shell without consuming it. This lets buttons,
     * search, and the shelf keep their normal touch behavior while the scenery reacts underneath.
     */
    fun observeTouch(event: MotionEvent, offsetX: Float = 0f, offsetY: Float = 0f) {
        if (!ValueAnimator.areAnimatorsEnabled() || width <= 0 || height <= 0) return
        val x = event.x + offsetX
        val y = event.y + offsetY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = x
                touchY = y
                lastTouchX = x
                lastTouchY = y
                lastTouchTimeMs = event.eventTime
                touchTravel = 0f
                touchActive = true
                targetParallaxX = ((x / width) - .5f).coerceIn(-.5f, .5f)
                targetParallaxY = ((y / height) - .5f).coerceIn(-.5f, .5f)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                val dt = ((event.eventTime - lastTouchTimeMs).coerceAtLeast(1L)) / 1_000f
                touchTravel += hypot(dx, dy)
                touchX = x
                touchY = y
                targetParallaxX = ((x / width) - .5f).coerceIn(-.5f, .5f)
                targetParallaxY = ((y / height) - .5f).coerceIn(-.5f, .5f)
                if (abs(dx) > density) {
                    val velocity = dx / width / dt
                    swipeWind = (swipeWind * .38f + velocity * .48f).coerceIn(-1.2f, 1.2f)
                }
                lastTouchX = x
                lastTouchY = y
                lastTouchTimeMs = event.eventTime
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    touchTravel < density * 14f &&
                    hypot(x - width * MOON_X, y - height * MOON_Y) < width * MOON_RADIUS * 1.7f
                ) {
                    moonPulseStartedAt = elapsedSeconds
                }
                touchActive = false
                targetParallaxX = 0f
                targetParallaxY = 0f
            }
        }
        invalidate()
    }

    /** The library shelf drives a small, depth-weighted vertical camera shift as it scrolls. */
    fun setScrollOffset(scrollY: Int) {
        if (!ValueAnimator.areAnimatorsEnabled() || height <= 0) return
        targetScrollProgress = (scrollY / height.toFloat()).coerceIn(0f, 1.25f)
    }

    private fun treeLine(count: Int, baseHeight: Float, variation: Float, widthScale: Float, seed: Int): List<Tree> =
        List(count) { index ->
            val random = pseudo(seed + index * 7)
            Tree(
                x = -0.04f + index / (count - 1f) * 1.08f,
                height = baseHeight + (random - .5f) * variation,
                width = (.13f + pseudo(seed + index * 11) * .08f) * widthScale,
                phase = pseudo(seed + index * 17) * (PI * 2).toFloat(),
            )
        }

    private fun MutableList<Tree>.replaceWith(values: List<Tree>) {
        clear()
        addAll(values)
    }

    /** A smooth breeze with short, infrequent gusts rather than constant exaggerated swaying. */
    private fun windAt(seconds: Float): Float {
        val breeze = sin(seconds * .22f) * .16f
        val gustWave = max(0f, sin(seconds * .095f - 1.1f)).pow(7) * .78f
        return breeze + gustWave
    }

    private fun pseudo(seed: Int): Float = abs(sin(seed * 12.9898f) * 43758.5453f) % 1f

    private val density get() = resources.displayMetrics.density

    private companion object {
        /** Enough irregular branch tiers to read as a mature pine at phone and tablet sizes. */
        const val CROWN_TIERS = 7

        /** 30 FPS is smooth for ambient motion and materially cheaper than a game-style loop. */
        const val FRAME_INTERVAL_NANOS = 32_000_000L

        const val MOON_X = .73f
        const val MOON_Y = .115f
        const val MOON_RADIUS = .061f
        const val MOON_PULSE_SECONDS = 1.15f
    }
}
