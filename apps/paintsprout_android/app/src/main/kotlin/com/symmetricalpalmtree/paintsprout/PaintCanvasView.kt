package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                val out = createBitmap(bufW, bufH)
                Canvas(out).apply {
                    drawBitmap(base, 0f, 0f, null)
                    scale(SUPER_SAMPLE, SUPER_SAMPLE)
                    for (s in batch) StrokeRenderer.paintStroke(this, s, surface)
                }
                out
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
