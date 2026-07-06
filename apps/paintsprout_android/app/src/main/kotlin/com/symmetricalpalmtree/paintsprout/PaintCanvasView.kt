package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap
import com.symmetricalpalmtree.paintsprout.paint.GpuRender
import com.symmetricalpalmtree.paintsprout.paint.PigmentMixer
import com.symmetricalpalmtree.paintsprout.paint.Stroke
import com.symmetricalpalmtree.paintsprout.paint.StrokePoint
import com.symmetricalpalmtree.paintsprout.paint.StrokeRenderer
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.Tool
import com.symmetricalpalmtree.paintsprout.paint.ToothCache
import com.symmetricalpalmtree.paintsprout.paint.Vec2
import com.symmetricalpalmtree.paintsprout.paint.buildSurfaceVisual
import com.symmetricalpalmtree.paintsprout.paint.resolveDensity
import com.symmetricalpalmtree.paintsprout.paint.resolveWidth
import com.symmetricalpalmtree.paintsprout.paint.strokeRegionPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The drawing surface — the native counterpart of Flutter's `DrawingCanvas`.
 *
 * Two [Bitmap]s at buffer resolution: [surfaceBmp] (the base you paint on) and
 * [paintBmp] (committed strokes, transparent where unpainted so the surface
 * shows through). The stroke under the pointer is previewed on top in [onDraw]
 * until pointer-up, then baked into [paintBmp] off-thread. Only stylus input
 * draws; touch is ignored (touch gestures come in a later stage).
 *
 * Stage 2 keeps the surface a flat white fill and draws only solid strokes;
 * procedural surfaces, the tooth, and the other tools arrive in Stage 3.
 */
class PaintCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** The active drawing tool. */
    var tool: Tool = Tool.PEN

    /** Deposit color (ARGB). Ignored by the eraser. */
    @ColorInt
    var strokeColor: Int = Color.BLACK

    /** Base size override; null uses the tool's default. */
    var baseSize: Float? = null

    /** The surface being painted on. Change via [setSurface]. */
    var surface: SurfaceKind = SurfaceKind.PAPER
        private set

    /** Background color for the Plain surface (ignored by textured surfaces). */
    @ColorInt
    var plainColor: Int = Color.WHITE

    private fun sizeFor(t: Tool): Float = baseSize ?: t.defaultSize

    // --- Buffers ------------------------------------------------------------
    private var surfaceBmp: Bitmap? = null
    private var paintBmp: Bitmap? = null
    private var bufW = 0
    private var bufH = 0
    private val srcRect = Rect()
    private val dstRect = Rect()

    // --- Live stroke --------------------------------------------------------
    private var active: Stroke? = null
    private var activePointerId = INVALID_POINTER
    private val unbaked = mutableListOf<Stroke>()
    private var pressureMax = 1.0f

    // --- Baking -------------------------------------------------------------
    private var baking = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Spectral pigment-mixing shader source (res/raw/pigment_mix.agsl), loaded
     * once. Null if it fails to load, in which case watercolor falls back to a
     * plain over-composite (no true pigment mixing).
     */
    private val pigmentAgsl: String? by lazy {
        runCatching {
            resources.openRawResource(R.raw.pigment_mix).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        bufW = (w * SUPER_SAMPLE).roundToInt()
        bufH = (h * SUPER_SAMPLE).roundToInt()
        srcRect.set(0, 0, bufW, bufH)
        dstRect.set(0, 0, w, h)

        // A flat placeholder surface so drawing works immediately; the textured
        // surface is built off-thread and swapped in a frame later.
        val placeholder = createBitmap(bufW, bufH).apply { Canvas(this).drawColor(plainColor) }
        val newPaint = createBitmap(bufW, bufH) // transparent

        surfaceBmp?.recycle()
        paintBmp?.recycle()
        surfaceBmp = placeholder
        paintBmp = newPaint
        unbaked.clear()
        active = null
        invalidate()
        regenerateSurface()
    }

    /** Switches the surface, rebuilding the base layer (keeps the paint). */
    fun setSurface(kind: SurfaceKind) {
        surface = kind
        regenerateSurface()
    }

    /**
     * Builds the (procedural) surface visual off-thread and swaps it in. Also
     * ensures the tooth textures are baked (idempotent).
     */
    private fun regenerateSurface() {
        val w = bufW
        val h = bufH
        if (w <= 0 || h <= 0) return
        val kind = surface
        val pc = plainColor
        scope.launch {
            val bmp = withContext(Dispatchers.Default) {
                ToothCache.init()
                buildSurfaceVisual(kind, w, h, pc)
            }
            if (bufW != w || bufH != h) { // size changed while building
                bmp.recycle()
                return@launch
            }
            val old = surfaceBmp
            surfaceBmp = bmp
            old?.recycle()
            invalidate()
        }
    }

    // --- Drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val surfaceLayer = surfaceBmp ?: return
        val paintLayer = paintBmp ?: return

        // Base surface, scaled from the buffer to the view.
        canvas.drawBitmap(surfaceLayer, srcRect, dstRect, null)

        // Composite the paint in an isolated layer so an eraser stroke (CLEAR)
        // punches through to reveal the surface, not the window behind the view.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawBitmap(paintLayer, srcRect, dstRect, null)
        // Live strokes are in view (logical) coordinates, drawn straight onto the
        // view canvas — SUPER_SAMPLE only affects the buffer bitmaps above.
        for (s in unbaked) StrokeRenderer.paintStroke(canvas, s, surface)
        active?.let { StrokeRenderer.paintStroke(canvas, it, surface) }
        canvas.restoreToCount(layer)
    }

    // --- Input --------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        if (!isStylus(event.getToolType(actionIndex))) {
            return false // Stage 2: stylus only; touch gestures land later.
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (active != null) return true
                activePointerId = event.getPointerId(actionIndex)
                pressureMax = event.device
                    ?.getMotionRange(MotionEvent.AXIS_PRESSURE)
                    ?.max
                    ?.takeIf { it > 0f } ?: 1.0f
                val color = if (tool == Tool.ERASER) Color.WHITE else strokeColor
                active = Stroke(tool, color, seed = Random.nextInt()).apply {
                    add(sample(event, actionIndex))
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = active ?: return true
                val pi = event.findPointerIndex(activePointerId)
                if (pi < 0) return true
                // MotionEvent batches samples between frames — replay the history
                // for smooth, high-rate capture, then the current sample.
                for (h in 0 until event.historySize) {
                    stroke.add(sampleHistorical(event, pi, h))
                }
                stroke.add(sample(event, pi))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val stroke = active
                if (stroke != null && event.getPointerId(actionIndex) == activePointerId) {
                    unbaked.add(stroke)
                    active = null
                    activePointerId = INVALID_POINTER
                    kickBake()
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    private fun sample(e: MotionEvent, pi: Int): StrokePoint =
        buildPoint(
            e.getX(pi), e.getY(pi),
            e.getPressure(pi), e.getAxisValue(MotionEvent.AXIS_TILT, pi),
        )

    private fun sampleHistorical(e: MotionEvent, pi: Int, h: Int): StrokePoint =
        buildPoint(
            e.getHistoricalX(pi, h), e.getHistoricalY(pi, h),
            e.getHistoricalPressure(pi, h),
            e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pi, h),
        )

    private fun buildPoint(x: Float, y: Float, rawPressure: Float, tilt: Float): StrokePoint {
        val pressure = if (pressureMax > 0f) (rawPressure / pressureMax).coerceIn(0f, 1f) else 1f
        val width = resolveWidth(tool, sizeFor(tool), pressure, tilt)
        val density = resolveDensity(tool, pressure)
        return StrokePoint(Vec2(x, y), width, density)
    }

    // --- Baking -------------------------------------------------------------

    /**
     * Bakes any finished-but-unbaked strokes into [paintBmp]. Composites a fresh
     * buffer off-thread (a copy of the paint plus the strokes), then swaps it in
     * on the main thread — mirroring Flutter's immutable-image bakes, so the
     * paint layer is never mutated while [onDraw] reads it.
     */
    private fun kickBake() {
        if (baking || unbaked.isEmpty()) return
        val base = paintBmp ?: return
        baking = true
        val batch = unbaked.toList()
        scope.launch {
            val next = withContext(Dispatchers.Default) {
                // Start from a copy of the committed paint, then fold in each
                // stroke in order. Ordinary strokes deposit directly; a watercolor
                // stroke reads the paint underneath and mixes spectrally.
                var cur = createBitmap(bufW, bufH)
                Canvas(cur).drawBitmap(base, 0f, 0f, null)
                for (s in batch) {
                    if (s.tool == Tool.WATERCOLOR) {
                        val mixed = compositeWatercolor(cur, s)
                        if (mixed !== cur) {
                            cur.recycle()
                            cur = mixed
                        }
                    } else {
                        Canvas(cur).apply {
                            save()
                            scale(SUPER_SAMPLE, SUPER_SAMPLE)
                            StrokeRenderer.paintStroke(this, s, surface)
                            restore()
                        }
                    }
                }
                cur
            }
            val old = paintBmp
            paintBmp = next
            unbaked.subList(0, batch.size).clear()
            old?.recycle()
            baking = false
            invalidate()
            if (unbaked.isNotEmpty()) kickBake()
        }
    }

    /**
     * Composites one watercolor stroke, mirroring `_compositeWatercolor` in the
     * Flutter reference: within the brush region the existing paint is diluted
     * (faded toward the surface) and pushed outward (blurred bloom), then the new
     * pigment is deposited and mixed spectrally so its colour bleeds into what was
     * there. Runs on the bake thread. Returns a new bitmap (never recycles [base]).
     */
    private fun compositeWatercolor(base: Bitmap, stroke: Stroke): Bitmap {
        val backdrop = wetBackdrop(base, stroke)

        val wash = createBitmap(bufW, bufH)
        Canvas(wash).apply {
            scale(SUPER_SAMPLE, SUPER_SAMPLE)
            StrokeRenderer.paintStroke(this, stroke, surface)
        }

        val agsl = pigmentAgsl
        val result = if (agsl == null) {
            overComposite(backdrop, wash)
        } else {
            runCatching { PigmentMixer.mix(agsl, backdrop, wash) }.getOrElse { overComposite(backdrop, wash) }
        }
        backdrop.recycle()
        wash.recycle()
        return result
    }

    /**
     * The wet backdrop: [base] with the stroke region diluted and pushed, but
     * without the new pigment yet. Ports `applyWetInteraction` — the blurs run on
     * the GPU ([GpuRender.blur]); the dstOut/dstIn/alpha compositing is software.
     */
    private fun wetBackdrop(base: Bitmap, stroke: Stroke): Bitmap {
        val region = strokeRegionPath(stroke)
        val avgW = avgWidth(stroke) * SUPER_SAMPLE
        val soften = max(2f, avgW * 0.14f)
        val clearFeather = max(2f, avgW * 0.10f)
        val spread = max(4f, avgW * 0.30f)

        val backdrop = base.copy(Bitmap.Config.ARGB_8888, true)

        // 1. Dilute: clear the paint inside the stroke (tight feathered edge) so
        //    the centre fades toward the surface where the water floods it.
        val clearMask = softRegion(region, clearFeather)
        Canvas(backdrop).drawBitmap(clearMask, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT })
        clearMask.recycle()

        // 2. Push: a blurred, faded copy of the paint, masked to a WIDER region so
        //    displaced pigment blooms past the stroke edges instead of staying put.
        val push = GpuRender.blur(base, soften)
        val spreadMask = softRegion(region, spread)
        Canvas(push).drawBitmap(spreadMask, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_IN })
        spreadMask.recycle()
        Canvas(backdrop).drawBitmap(push, 0f, 0f, Paint().apply { alpha = WET_DILUTE_ALPHA })
        push.recycle()

        return backdrop
    }

    /** A soft-edged (blurred) white mask of [region] — feathers the wet effect. */
    private fun softRegion(region: Path, feather: Float): Bitmap {
        val tmp = createBitmap(bufW, bufH)
        Canvas(tmp).apply {
            scale(SUPER_SAMPLE, SUPER_SAMPLE)
            drawPath(region, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        val blurred = GpuRender.blur(tmp, feather)
        tmp.recycle()
        return blurred
    }

    private fun avgWidth(stroke: Stroke): Float {
        var sum = 0f
        for (p in stroke.points) sum += p.width
        return sum / stroke.points.size
    }

    /** Plain over-composite of [wash] onto [base] (the no-shader fallback). */
    private fun overComposite(base: Bitmap, wash: Bitmap): Bitmap {
        val out = createBitmap(bufW, bufH)
        Canvas(out).apply {
            drawBitmap(base, 0f, 0f, null)
            drawBitmap(wash, 0f, 0f, null)
        }
        return out
    }

    /** Clears painted strokes, keeping the surface. */
    fun clear() {
        val old = paintBmp ?: return
        unbaked.clear()
        active = null
        paintBmp = createBitmap(bufW, bufH)
        old.recycle()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
        surfaceBmp?.recycle()
        paintBmp?.recycle()
        surfaceBmp = null
        paintBmp = null
    }

    private companion object {
        const val INVALID_POINTER = -1

        // Fraction of the softened underlying paint kept in the wet "push" step
        // (WetConfig.dilute = 0.5 in the Flutter reference), as an 8-bit alpha.
        const val WET_DILUTE_ALPHA = 128

        // Backing buffer resolution over the view. The view already reports
        // physical pixels (unlike Flutter's logical px, which drove its 2x
        // supersample), so 1.0 renders at native resolution and keeps buffer
        // memory minimal. Bump if edge AA proves insufficient on-device.
        const val SUPER_SAMPLE = 1.0f

        fun isStylus(toolType: Int): Boolean =
            toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                toolType == MotionEvent.TOOL_TYPE_ERASER
    }
}
