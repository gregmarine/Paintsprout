package com.symmetricalpalmtree.paintsprout

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap
import com.symmetricalpalmtree.paintsprout.paint.EraseOp
import com.symmetricalpalmtree.paintsprout.paint.FillOp
import com.symmetricalpalmtree.paintsprout.paint.GalleryExport
import com.symmetricalpalmtree.paintsprout.paint.GpuRender
import com.symmetricalpalmtree.paintsprout.paint.MoveOp
import com.symmetricalpalmtree.paintsprout.paint.PaintOp
import com.symmetricalpalmtree.paintsprout.paint.PigmentMixer
import com.symmetricalpalmtree.paintsprout.paint.SelectionRender
import com.symmetricalpalmtree.paintsprout.paint.Stroke
import com.symmetricalpalmtree.paintsprout.paint.StrokeOp
import com.symmetricalpalmtree.paintsprout.paint.StrokePoint
import com.symmetricalpalmtree.paintsprout.paint.SurfaceOp
import com.symmetricalpalmtree.paintsprout.paint.StrokeRenderer
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.Tool
import com.symmetricalpalmtree.paintsprout.paint.ToothCache
import com.symmetricalpalmtree.paintsprout.paint.Vec2
import com.symmetricalpalmtree.paintsprout.paint.WandFloodFill
import com.symmetricalpalmtree.paintsprout.paint.CanvasParams
import com.symmetricalpalmtree.paintsprout.paint.ChalkboardParams
import com.symmetricalpalmtree.paintsprout.paint.ConcreteParams
import com.symmetricalpalmtree.paintsprout.paint.MetalParams
import com.symmetricalpalmtree.paintsprout.paint.StoneParams
import com.symmetricalpalmtree.paintsprout.paint.WatercolorParams
import com.symmetricalpalmtree.paintsprout.paint.WoodParams
import com.symmetricalpalmtree.paintsprout.paint.buildSurfaceVisual
import com.symmetricalpalmtree.paintsprout.paint.resolveDensity
import com.symmetricalpalmtree.paintsprout.paint.resolveWidth
import com.symmetricalpalmtree.paintsprout.paint.strokeRegionPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The drawing surface — the native counterpart of Flutter's `DrawingCanvas`.
 *
 * Two [Bitmap]s at buffer resolution: [surfaceBmp] (the base you paint on) and
 * [paintBmp] (committed strokes, transparent where unpainted so the surface
 * shows through). The stroke under the pointer is previewed on top in [onDraw]
 * until pointer-up, then baked into [paintBmp] off-thread. Only stylus input
 * draws; touch is ignored.
 *
 * The magic wand (Tool.WAND) selects a contiguous painted region, then fills /
 * erases inside it, constrains further painting to it (a frisket), or lifts it
 * to move / scale / rotate. Selection edits join [committed] as [FillOp] /
 * [EraseOp] / [MoveOp] so they undo/redo like strokes.
 */
class PaintCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * The active drawing tool. Leaving the wand while a move is floating bakes it;
     * leaving the line tool while a line is still being edited bakes that line.
     */
    var tool: Tool = Tool.PEN
        set(value) {
            val old = field
            field = value
            if (old != value) {
                if (old == Tool.WAND && isFloating) scope.launch { commitFloating() }
                if (old == Tool.LINE && hasPendingLine) commitPendingLine()
                if (old == Tool.ARC && hasPendingArc) commitPendingArc()
            }
        }

    /** Deposit color (ARGB). Ignored by the eraser. */
    @ColorInt
    var strokeColor: Int = Color.BLACK

    /** Base size override; null uses the tool's default. */
    var baseSize: Float? = null

    /** The surface being painted on. User changes go through [commitSurfaceChange]. */
    var surface: SurfaceKind = SurfaceKind.PAPER
        private set

    /** Background color for the Plain surface (ignored by textured surfaces). */
    @ColorInt
    var plainColor: Int = Color.WHITE
        private set

    /** Customisation for the Canvas surface (ignored by other surfaces). */
    var canvasParams: CanvasParams = CanvasParams()
        private set

    /** Customisation for the Watercolor surface (ignored by other surfaces). */
    var watercolorParams: WatercolorParams = WatercolorParams()
        private set

    /** Customisation for the Wood surface (ignored by other surfaces). */
    var woodParams: WoodParams = WoodParams()
        private set

    /** Customisation for the Stone surface (ignored by other surfaces). */
    var stoneParams: StoneParams = StoneParams()
        private set

    /** Customisation for the Concrete surface (ignored by other surfaces). */
    var concreteParams: ConcreteParams = ConcreteParams()
        private set

    /** Customisation for the Metal surface (ignored by other surfaces). */
    var metalParams: MetalParams = MetalParams()
        private set

    /** Customisation for the Chalkboard surface (ignored by other surfaces). */
    var chalkboardParams: ChalkboardParams = ChalkboardParams()
        private set

    /**
     * Random seed for organic (non-tiled) surfaces like Watercolor — the paper's
     * unique "batch". Generated once per artwork so the texture is stable across
     * surface switches, undo/redo and reloads, and re-generated on [clear] (a new
     * piece). Persist this with the document when save/load lands.
     */
    var surfaceSeed: Long = java.util.Random().nextLong()
        private set

    /**
     * The surface/background at the base of the undo timeline — the state restored
     * when every [SurfaceOp] has been undone. Re-based on [clear].
     */
    private var initialSurface: SurfaceKind = SurfaceKind.PAPER
    @ColorInt private var initialPlainColor: Int = Color.WHITE
    private var initialCanvasParams: CanvasParams = CanvasParams()
    private var initialWatercolorParams: WatercolorParams = WatercolorParams()
    private var initialWoodParams: WoodParams = WoodParams()
    private var initialStoneParams: StoneParams = StoneParams()
    private var initialConcreteParams: ConcreteParams = ConcreteParams()
    private var initialMetalParams: MetalParams = MetalParams()
    private var initialChalkboardParams: ChalkboardParams = ChalkboardParams()

    // Magic-wand tuning (Flutter reference defaults).
    var wandTolerance: Float = 0.15f
    var wandEdgeSensitivity: Float = 0.5f
    var wandGap: Int = 3

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
    private var activeClip: Bitmap? = null
    private var activePointerId = INVALID_POINTER
    private val unbaked = mutableListOf<Stroke>()
    private val unbakedClips = mutableListOf<Bitmap?>()
    private var pressureMax = 1.0f

    // --- Touch history gestures (finger, not stylus) ------------------------
    // A 2-finger double-tap undoes; a 3-finger double-tap redoes. Drawing is
    // stylus-only, so finger touches are free for these. Ported from Flutter.
    private val touchStart = HashMap<Int, PointF>()
    private var touchSessionStart = 0L
    private var touchMaxCount = 0        // most fingers down at once this session
    private var touchMoved = false       // any finger dragged past the tap slop
    private var pendingTapCount = 0      // finger-count of a first tap awaiting its pair
    private var pendingTapTime = 0L

    /**
     * Scratch bitmap the live watercolor render draws the wash into each frame so
     * it can feed the pigment shader as a texture. The wet look is drawn straight
     * on the hardware View canvas (the spectral mixer runs as a Paint shader) with
     * no GPU→CPU readback — the readback is what made an off-thread spectral
     * preview lag. The full wet interaction (dilute/bloom) still runs at bake.
     */
    private val washScratch = arrayOfNulls<Bitmap>(2)
    private var washFlip = 0

    // Half-resolution wet backdrop for the LIVE preview: the dilute+bloom is blurry,
    // so computing it at half res keeps the per-frame GPU read-back (needed to feed
    // the diluted backdrop to the spectral mixer as a texture) cheap. Double-buffered
    // for the same reason the wash is — HWUI samples it a frame later.
    private val wetLiveScratch = arrayOfNulls<Bitmap>(2)
    private var wetLiveFlip = 0

    /** Frees both wash scratch buffers (double-buffered — see [drawWetLive]). */
    private fun recycleWashScratch() {
        washScratch[0]?.recycle(); washScratch[0] = null
        washScratch[1]?.recycle(); washScratch[1] = null
        wetLiveScratch[0]?.recycle(); wetLiveScratch[0] = null
        wetLiveScratch[1]?.recycle(); wetLiveScratch[1] = null
    }

    /**
     * Incremental accumulation buffer for the active spray stroke. Spray redraws
     * as many blurred segments as the stroke is long, so redrawing the whole
     * stroke every frame is O(n²) and janks on long strokes; instead we append
     * only the new segments here each frame and blit this once. [accumDrawn] is
     * how many of the stroke's points are already in it.
     */
    private var activeAccum: Bitmap? = null
    private var accumDrawn = 0

    // --- History ------------------------------------------------------------
    private val committed = mutableListOf<PaintOp>()
    private val redoStack = mutableListOf<PaintOp>()
    var onHistoryChanged: (() -> Unit)? = null
    val canUndo: Boolean get() = committed.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Paint-layer snapshots keyed by op count (state after that many committed
     * ops), taken every [CHECKPOINT_STRIDE] ops. An undo/redo rebuild starts from
     * the nearest checkpoint ≤ the target and replays only the remainder, so it
     * never re-runs the whole (watercolor-heavy) history. Bounded to
     * [MAX_CHECKPOINTS] most-recent — a rebuild before the earliest just replays
     * from blank.
     */
    private val checkpoints = java.util.TreeMap<Int, Bitmap>()

    // --- Magic-wand selection -----------------------------------------------
    /** White where selected (half-buffer resolution), or null when unselected. */
    private var selectionMask: Bitmap? = null

    /** Selection bounds in VIEW (= buffer) pixels. */
    private val selRect = RectF()

    /** Fired when a selection appears (true) or is cleared (false). */
    var onSelectionChanged: ((Boolean) -> Unit)? = null
    val hasSelection: Boolean get() = selectionMask != null || isFloating

    // Floating move/transform.
    private var floating: Bitmap? = null       // lifted paint (buffer res)
    private var paintHole: Bitmap? = null      // committed paint with the region removed
    private var floatSourceMask: Bitmap? = null
    private val floatTranslate = PointF(0f, 0f)
    private var floatScale = 1f
    private var floatRotation = 0f // radians
    private var lifting = false
    private val isFloating: Boolean get() = floating != null

    // Wand-gesture tracking (single stylus pointer).
    private var wandPointerId = INVALID_POINTER
    private var wandDownPos: PointF? = null
    private var wandMoved = false
    private var movePointerId = INVALID_POINTER
    private var xformMode: Xform? = null
    private val moveLastPos = PointF()
    private var gestureStartScale = 1f
    private var gestureStartDist = 1f
    private var gestureStartRotation = 0f
    private var gestureStartAngle = 0f

    private enum class Xform { TRANSLATE, SCALE, ROTATE }

    // --- Line tool ----------------------------------------------------------
    // A line is drawn tap-and-drag, then stays editable: its two endpoints, its
    // body, and a rotate handle off the centre are all grabbable. It bakes into
    // the paint layer as an ordinary two-point [StrokeOp] on commit (start a new
    // line, switch tools, tap Done, or save), so it undoes / re-tooths like any
    // stroke. Endpoints are in view (= buffer) pixels.
    private var lineA: PointF? = null
    private var lineB: PointF? = null
    private var lineMode = LineDrag.NONE
    private var linePointerId = INVALID_POINTER
    private val lineDragLast = PointF()
    // Rotate gesture: endpoints + centre captured at grab, plus the start angle.
    private val lineRotA0 = PointF()
    private val lineRotB0 = PointF()
    private val lineRotCenter = PointF()
    private var lineRotStartAngle = 0f

    private enum class LineDrag { NONE, DRAW, ENDPOINT_A, ENDPOINT_B, BODY, ROTATE }

    /** Whether an editable line is placed (or being drawn) but not yet baked. */
    val hasPendingLine: Boolean get() = lineA != null && lineB != null

    /** Fired when the editable line appears (true) or is committed/cleared (false). */
    var onLineChanged: ((Boolean) -> Unit)? = null

    // --- Arc tool -----------------------------------------------------------
    // Drawn like the line (tap-drag lays a straight chord), then a third handle
    // [arcM] — a point that rides ON the curve — can be pulled to bend it into a
    // (possibly lopsided) arc. Three shape handles (two ends + middle) plus body
    // move and rotate. The curve is a quadratic that passes through A, M (t=0.5)
    // and B; on commit it's densely sampled into an ordinary [StrokeOp], so it
    // undoes / re-tooths / saves like any stroke. Points are in view (= buffer) px.
    private var arcA: PointF? = null
    private var arcB: PointF? = null
    private var arcM: PointF? = null
    private var arcMode = ArcDrag.NONE
    private var arcPointerId = INVALID_POINTER
    private val arcDragLast = PointF()
    private val arcRotA0 = PointF()
    private val arcRotB0 = PointF()
    private val arcRotM0 = PointF()
    private val arcRotCenter = PointF()
    private var arcRotStartAngle = 0f

    private enum class ArcDrag { NONE, DRAW, ENDPOINT_A, ENDPOINT_B, MIDDLE, BODY, ROTATE }

    /** Whether an editable arc is placed (or being drawn) but not yet baked. */
    val hasPendingArc: Boolean get() = arcA != null && arcB != null && arcM != null

    /** Fired when the editable arc appears (true) or is committed/cleared (false). */
    var onArcChanged: ((Boolean) -> Unit)? = null

    /** Whether any editable shape (line or arc) is pending — for the Done button. */
    val hasPendingShape: Boolean get() = hasPendingLine || hasPendingArc

    /** Bakes whichever editable shape is pending (only ever one at a time). */
    fun commitPendingShape() {
        if (hasPendingLine) commitPendingLine()
        if (hasPendingArc) commitPendingArc()
    }

    // Marching-ants animation.
    private var antsPhase = 0f
    private val antsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700L
        repeatCount = ValueAnimator.INFINITE
        interpolator = null
        addUpdateListener {
            antsPhase = it.animatedValue as Float
            invalidate()
        }
    }

    // --- Baking -------------------------------------------------------------
    private var baking = false
    private var rebuilding = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val pigmentAgsl: String? by lazy {
        runCatching {
            resources.openRawResource(R.raw.pigment_mix).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /** The pigment mixer as a live shader (for the on-canvas watercolor preview). */
    private val pigmentShader: RuntimeShader? by lazy {
        pigmentAgsl?.let { runCatching { RuntimeShader(it) }.getOrNull() }
    }

    /** Marching-ants overlay shader (res/raw/selection_overlay.agsl). */
    private val antsShader: RuntimeShader? by lazy {
        runCatching {
            val src = resources.openRawResource(R.raw.selection_overlay)
                .bufferedReader().use { it.readText() }
            RuntimeShader(src)
        }.getOrNull()
    }

    private val hqPaint = Paint().apply { isFilterBitmap = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        bufW = (w * SUPER_SAMPLE).roundToInt()
        bufH = (h * SUPER_SAMPLE).roundToInt()
        srcRect.set(0, 0, bufW, bufH)
        dstRect.set(0, 0, w, h)

        val placeholder = createBitmap(bufW, bufH).apply { Canvas(this).drawColor(plainColor) }
        val newPaint = createBitmap(bufW, bufH)

        surfaceBmp?.recycle()
        paintBmp?.recycle()
        surfaceBmp = placeholder
        paintBmp = newPaint
        unbaked.clear()
        unbakedClips.clear()
        active = null
        endActiveExtras()
        recycleWashScratch()
        recycleCheckpoints()
        clearSelectionState()
        invalidate()
        regenerateSurface()
    }

    /**
     * Sets the starting surface/background — the base of the undo timeline — without
     * recording history. For initial setup (e.g. restoring saved prefs), not user edits.
     */
    fun setInitialSurface(
        kind: SurfaceKind,
        @ColorInt bgColor: Int,
        canvas: CanvasParams = CanvasParams(),
        watercolor: WatercolorParams = WatercolorParams(),
        wood: WoodParams = WoodParams(),
        stone: StoneParams = StoneParams(),
        concrete: ConcreteParams = ConcreteParams(),
        metal: MetalParams = MetalParams(),
        chalkboard: ChalkboardParams = ChalkboardParams(),
    ) {
        surface = kind
        plainColor = bgColor
        canvasParams = canvas
        watercolorParams = watercolor
        woodParams = wood
        stoneParams = stone
        concreteParams = concrete
        metalParams = metal
        chalkboardParams = chalkboard
        initialSurface = kind
        initialPlainColor = bgColor
        initialCanvasParams = canvas
        initialWatercolorParams = watercolor
        initialWoodParams = wood
        initialStoneParams = stone
        initialConcreteParams = concrete
        initialMetalParams = metal
        initialChalkboardParams = chalkboard
        regenerateSurface()
    }

    /**
     * A user-initiated surface / background-colour change, recorded on the undo
     * timeline as a [SurfaceOp] so it can be undone/redone like a stroke. No-op if
     * nothing actually changes (e.g. re-picking the current surface).
     *
     * The surface is a document property: every committed stroke re-tooths to the
     * new material (grain follows the substrate), so the paint layer is rebuilt.
     */
    fun commitSurfaceChange(
        kind: SurfaceKind,
        @ColorInt bgColor: Int,
        canvas: CanvasParams = CanvasParams(),
        watercolor: WatercolorParams = WatercolorParams(),
        wood: WoodParams = WoodParams(),
        stone: StoneParams = StoneParams(),
        concrete: ConcreteParams = ConcreteParams(),
        metal: MetalParams = MetalParams(),
        chalkboard: ChalkboardParams = ChalkboardParams(),
    ) {
        if (kind == surface && bgColor == plainColor &&
            canvas == canvasParams && watercolor == watercolorParams && wood == woodParams &&
            stone == stoneParams && concrete == concreteParams && metal == metalParams &&
            chalkboard == chalkboardParams
        ) {
            return
        }
        clearRedo()
        surface = kind
        plainColor = bgColor
        canvasParams = canvas
        watercolorParams = watercolor
        woodParams = wood
        stoneParams = stone
        concreteParams = concrete
        metalParams = metal
        chalkboardParams = chalkboard
        committed.add(SurfaceOp(kind, bgColor, canvas, watercolor, wood, stone, concrete, metal, chalkboard))
        regenerateSurface()
        // Checkpoints hold paint toothed for the OLD surface, so drop them and
        // rebuild from blank — foldOps re-tooths each stroke with the new surface.
        recycleCheckpoints()
        if (!baking && !rebuilding && bufW > 0) rebuild() else onHistoryChanged?.invoke()
    }

    /**
     * Restores the surface/background to whatever the last [SurfaceOp] on the
     * current timeline dictates (or the initial state if none), after an undo/redo
     * moved the boundary. Regenerates the base layer only when it actually changed;
     * also drops now-stale (wrong-tooth) checkpoints so the following rebuild
     * re-tooths every stroke to the restored surface.
     */
    private fun syncSurfaceToHistory() {
        var kind = initialSurface
        var bg = initialPlainColor
        var cp = initialCanvasParams
        var wp = initialWatercolorParams
        var wd = initialWoodParams
        var st = initialStoneParams
        var cc = initialConcreteParams
        var mt = initialMetalParams
        var cb = initialChalkboardParams
        for (op in committed) if (op is SurfaceOp) {
            kind = op.kind; bg = op.plainColor; cp = op.canvas; wp = op.watercolor; wd = op.wood
            st = op.stone; cc = op.concrete; mt = op.metal; cb = op.chalkboard
        }
        if (kind != surface || bg != plainColor || cp != canvasParams ||
            wp != watercolorParams || wd != woodParams || st != stoneParams || cc != concreteParams ||
            mt != metalParams || cb != chalkboardParams
        ) {
            surface = kind
            plainColor = bg
            canvasParams = cp
            watercolorParams = wp
            woodParams = wd
            stoneParams = st
            concreteParams = cc
            metalParams = mt
            chalkboardParams = cb
            regenerateSurface()
            recycleCheckpoints()
        }
    }

    private fun regenerateSurface() {
        val w = bufW
        val h = bufH
        if (w <= 0 || h <= 0) return
        val kind = surface
        val pc = plainColor
        val cp = canvasParams
        val sd = surfaceSeed
        val wp = watercolorParams
        val wd = woodParams
        val st = stoneParams
        val cc = concreteParams
        val mt = metalParams
        val cb = chalkboardParams
        scope.launch {
            val bmp = withContext(Dispatchers.Default) {
                ToothCache.init()
                buildSurfaceVisual(kind, w, h, pc, cp, sd, wp, wd, st, cc, mt, cb)
            }
            if (bufW != w || bufH != h) {
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
        canvas.drawBitmap(surfaceLayer, srcRect, dstRect, null)

        // Moving a selection: the committed paint with a hole where the region was
        // lifted, then the floating paint on top under its live transform.
        val fl = floating
        val hole = paintHole
        if (fl != null && hole != null) {
            canvas.drawBitmap(hole, srcRect, dstRect, hqPaint)
            canvas.save()
            canvas.concat(floatMatrix(1f))
            canvas.drawBitmap(fl, srcRect, dstRect, hqPaint)
            canvas.restore()
            drawSelectionOverlay(canvas)
            return
        }

        val paintLayer = paintBmp ?: return
        val liveClip = activeClip ?: unbakedClips.lastOrNull { it != null }
        val hasEdits = unbaked.isNotEmpty() || active != null

        // Draws the edited paint: the committed layer plus the pending strokes. A
        // live watercolor stroke re-wets the paint under it directly on this
        // hardware canvas (dilute + push + multiply the wash), so the wet bleed
        // shows under the pen with no off-thread readback.
        fun drawEdited() {
            val a = active
            if (a != null && a.tool == Tool.WATERCOLOR) {
                drawWetLive(canvas, paintLayer, a)
                return
            }
            canvas.drawBitmap(paintLayer, srcRect, dstRect, null)
            for (s in unbaked) drawLiveStroke(canvas, s, isActive = false)
            a?.let { drawLiveStroke(canvas, it, isActive = true) }
        }

        // Isolated layer so an eraser stroke (CLEAR) punches through to the surface
        // rather than the window behind the view.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        if (liveClip == null || !hasEdits) {
            drawEdited()
        } else {
            // Frisket: committed paint outside the selection, the edited result
            // inside — `committed*(1-m) + edited*m` — so live strokes (and the
            // eraser) can't spill past the boundary.
            val mSrc = Rect(0, 0, liveClip.width, liveClip.height)
            val punch = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawBitmap(paintLayer, srcRect, dstRect, null)
            canvas.drawBitmap(liveClip, mSrc, dstRect, dstOutPaint)
            canvas.restoreToCount(punch)
            val inside = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            drawEdited()
            canvas.drawBitmap(liveClip, mSrc, dstRect, dstInPaint)
            canvas.restoreToCount(inside)
        }
        canvas.restoreToCount(layer)

        drawPendingLine(canvas)
        drawPendingArc(canvas)
        drawSelectionOverlay(canvas)
    }

    /** Previews the editable line (as it will bake) plus its grab handles. */
    private fun drawPendingLine(canvas: Canvas) {
        val a = lineA ?: return
        val b = lineB ?: return
        StrokeRenderer.paintStroke(canvas, buildLineStroke(a, b), surface)
        // While first dragging out the line, show just the rubber band; once placed,
        // show the endpoint handles and the rotate stalk.
        if (lineMode != LineDrag.DRAW) drawLineHandles(canvas, a, b)
    }

    /** Square endpoint handles + a rotate handle on a stalk off the line's centre. */
    private fun drawLineHandles(canvas: Canvas, a: PointF, b: PointF) {
        val cx = (a.x + b.x) / 2f
        val cy = (a.y + b.y) / 2f
        val rot = lineRotateHandle(a, b)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.6f
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDD000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.4f
        }
        // Rotate stalk (white underlay for contrast on dark surfaces, dark on top).
        canvas.drawLine(cx, cy, rot.x, rot.y, white)
        canvas.drawLine(cx, cy, rot.x, rot.y, black)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        for (c in listOf(a, b)) {
            canvas.drawRect(c.x - 6, c.y - 6, c.x + 6, c.y + 6, fill)
            canvas.drawRect(c.x - 6, c.y - 6, c.x + 6, c.y + 6, black)
        }
        canvas.drawCircle(rot.x, rot.y, 7f, fill)
        canvas.drawCircle(rot.x, rot.y, 7f, black)
    }

    /** Previews the editable arc (as it will bake) plus its grab handles. */
    private fun drawPendingArc(canvas: Canvas) {
        val a = arcA ?: return
        val b = arcB ?: return
        val m = arcM ?: return
        StrokeRenderer.paintStroke(canvas, buildArcStroke(a, b, m), surface)
        if (arcMode != ArcDrag.DRAW) drawArcHandles(canvas, a, b, m)
    }

    /** Square endpoint handles, a diamond bend handle at [m], and a rotate handle. */
    private fun drawArcHandles(canvas: Canvas, a: PointF, b: PointF, m: PointF) {
        val cx = (a.x + b.x) / 2f
        val cy = (a.y + b.y) / 2f
        val rot = arcRotateHandle(a, b, m)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.6f
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDD000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.4f
        }
        // Rotate stalk from the chord centre (white underlay, dark on top).
        canvas.drawLine(cx, cy, rot.x, rot.y, white)
        canvas.drawLine(cx, cy, rot.x, rot.y, black)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        for (c in listOf(a, b)) {
            canvas.drawRect(c.x - 6, c.y - 6, c.x + 6, c.y + 6, fill)
            canvas.drawRect(c.x - 6, c.y - 6, c.x + 6, c.y + 6, black)
        }
        // Bend handle: a diamond, so it reads differently from the square ends.
        val diamond = Path().apply {
            moveTo(m.x, m.y - 8f); lineTo(m.x + 8f, m.y)
            lineTo(m.x, m.y + 8f); lineTo(m.x - 8f, m.y); close()
        }
        canvas.drawPath(diamond, fill)
        canvas.drawPath(diamond, black)
        canvas.drawCircle(rot.x, rot.y, 7f, fill)
        canvas.drawCircle(rot.x, rot.y, 7f, black)
    }

    /**
     * Draws one pending stroke in the live preview. The active spray stroke uses
     * the incremental [activeAccum] (append-only) to stay fast on long strokes;
     * everything else renders straight.
     */
    private fun drawLiveStroke(canvas: Canvas, s: Stroke, isActive: Boolean) {
        if (isActive && s.tool == Tool.SPRAY && activeAccum != null) {
            ensureSprayAccum(s)
            blitSprayAccum(canvas)
        } else {
            StrokeRenderer.paintStroke(canvas, s, surface)
        }
    }

    /** Appends any not-yet-drawn spray segments to [activeAccum]. */
    private fun ensureSprayAccum(stroke: Stroke) {
        val accum = activeAccum ?: return
        if (stroke.points.size <= accumDrawn && accumDrawn > 0) return
        StrokeRenderer.appendSoftSegments(Canvas(accum), stroke, accumDrawn)
        accumDrawn = stroke.points.size
    }

    /** Blits the spray accumulation with the tool's opacity + surface tooth. */
    private fun blitSprayAccum(canvas: Canvas) {
        val accum = activeAccum ?: return
        val opacity = com.symmetricalpalmtree.paintsprout.paint.ToolProfile.of(Tool.SPRAY).opacity
        val tooth = ToothCache.toothFor(surface, Tool.SPRAY)
        val lp = Paint().apply { alpha = (opacity * 255f).roundToInt().coerceIn(0, 255) }
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), lp)
        canvas.drawBitmap(accum, 0f, 0f, null)
        if (tooth != null) {
            com.symmetricalpalmtree.paintsprout.paint.applyTooth(
                canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()), tooth, surface.toothScale,
            )
        }
        canvas.restoreToCount(layer)
    }

    /**
     * Live watercolor, rendered to MATCH the baked result so lifting the pen causes
     * no visible change. Builds the same wet backdrop as the bake (paint diluted +
     * bloomed under the stroke), then runs the spectral pigment mixer over it so the
     * wash mixes like real pigment (blue under yellow reads green). The wet backdrop
     * needs a GPU read-back (the mixer reads it as a texture), so it's computed at
     * HALF resolution — it's blurry anyway — to keep that read-back cheap. Both the
     * wash and the wet backdrop are double-buffered (HWUI samples them a frame late).
     * Falls back to a plain colour mix (or a raw draw) if the GPU path is unavailable.
     */
    private fun drawWetLive(canvas: Canvas, paintLayer: Bitmap, stroke: Stroke) {
        val mixer = pigmentShader
        if (mixer == null || !canvas.isHardwareAccelerated) {
            canvas.drawBitmap(paintLayer, srcRect, dstRect, null)
            StrokeRenderer.paintStroke(canvas, stroke, surface)
            return
        }
        washFlip = washFlip xor 1
        val wash = (washScratch[washFlip] ?: createBitmap(bufW, bufH).also { washScratch[washFlip] = it })
        Canvas(wash).apply {
            drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            save()
            scale(SUPER_SAMPLE, SUPER_SAMPLE)
            StrokeRenderer.paintStroke(this, stroke, surface)
            restore()
        }

        val region = strokeRegionPath(stroke)
        val avgW = avgWidth(stroke) * SUPER_SAMPLE
        val soften = max(2f, avgW * 0.14f)
        val clearFeather = max(2f, avgW * 0.10f)
        val spread = max(4f, avgW * 0.30f)

        // Backdrop for the mixer: the wet-interacted paint at half res, or — if the
        // GPU read-back fails — the raw paint layer (still correct colour, no bleed).
        wetLiveFlip = wetLiveFlip xor 1
        val hw = bufW / 2
        val hh = bufH / 2
        val wetHalf = (wetLiveScratch[wetLiveFlip] ?: createBitmap(hw, hh).also { wetLiveScratch[wetLiveFlip] = it })
        val backdropShader = runCatching {
            GpuRender.renderInto(wetHalf) { rc ->
                recordWetBackdrop(
                    rc as android.graphics.RecordingCanvas, paintLayer, region, hw, hh,
                    0.5f, clearFeather * 0.5f, spread * 0.5f, soften * 0.5f,
                )
            }
            BitmapShader(wetHalf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(2f, 2f) })
            }
        }.getOrElse {
            BitmapShader(paintLayer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        val b = RectF()
        region.computeBounds(b, true)
        // Pad past the stroke to include the bloom that spreads beyond the ribbon,
        // else the SRC rect below clips the soft bleed into a hard edge.
        b.inset(-(avgW + spread + soften + 4f), -(avgW + spread + soften + 4f))

        canvas.drawBitmap(paintLayer, srcRect, dstRect, null)
        mixer.setInputShader("uBackdrop", backdropShader)
        mixer.setInputShader("uWash", BitmapShader(wash, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        mixer.setFloatUniform("uWashGain", 1f)
        mixer.setFloatUniform("uDarkHold", 0f)
        // The mixer emits the FULL composited result over the bounds (backdrop where
        // there's no wash, mixed where there is), so it must REPLACE those pixels, not
        // over-composite them onto the paint layer already drawn above — SRC_OVER would
        // double the backdrop's alpha and leave a visible rectangle under the pen.
        canvas.drawRect(b.left, b.top, b.right, b.bottom, Paint().apply {
            shader = mixer
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
        })
    }

    /** Marching ants over the selection, or the transform frame while floating. */
    private fun drawSelectionOverlay(canvas: Canvas) {
        if (isFloating) {
            drawFloatFrame(canvas)
            return
        }
        val mask = selectionMask ?: return
        val shader = antsShader
        if (shader != null) {
            val bmpShader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(width.toFloat() / mask.width, height.toFloat() / mask.height)
                })
            }
            shader.setInputShader("uMask", bmpShader)
            shader.setFloatUniform("uTime", antsPhase)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.shader = shader })
            return
        }
        // Fallback: dim outside + faint tint.
        val mSrc = Rect(0, 0, mask.width, mask.height)
        val dim = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawColor(0x47000000)
        canvas.drawBitmap(mask, mSrc, dstRect, dstOutPaint)
        canvas.restoreToCount(dim)
    }

    /** Dashed transform frame with corner scale handles and a rotate stalk. */
    private fun drawFloatFrame(canvas: Canvas) {
        val quad = floatCorners()
        val rotate = rotateHandle()
        val outline = Path().apply {
            moveTo(quad[0].x, quad[0].y)
            for (i in 1 until quad.size) lineTo(quad[i].x, quad[i].y)
            close()
            val topMid = PointF((quad[0].x + quad[1].x) / 2, (quad[0].y + quad[1].y) / 2)
            moveTo(topMid.x, topMid.y)
            lineTo(rotate.x, rotate.y)
        }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.6f
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.6f
        }
        val dash = 9f
        val phase = antsPhase * dash * 2
        val measure = PathMeasure(outline, false)
        do {
            val len = measure.length
            var d = -phase
            var i = 0
            val seg = Path()
            while (d < len) {
                val start = if (d < 0f) 0f else d
                val end = (d + dash).coerceIn(0f, len)
                if (end > start) {
                    seg.reset()
                    measure.getSegment(start, end, seg, true)
                    canvas.drawPath(seg, if (i % 2 == 0) white else black)
                }
                d += dash
                i++
            }
        } while (measure.nextContour())

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDD000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.4f
        }
        for (c in quad) {
            canvas.drawRect(c.x - 6, c.y - 6, c.x + 6, c.y + 6, fill)
            canvas.drawRect(c.x - 6, c.y - 6, c.x + 6, c.y + 6, border)
        }
        canvas.drawCircle(rotate.x, rotate.y, 7f, fill)
        canvas.drawCircle(rotate.x, rotate.y, 7f, border)
    }

    // --- Input --------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        if (!isStylus(event.getToolType(actionIndex))) {
            return handleTouchGesture(event) // finger: history gestures, not drawing.
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (tool == Tool.WAND) {
                    handleWandDown(event, actionIndex)
                    return true
                }
                if (tool == Tool.LINE) {
                    handleLineDown(event, actionIndex)
                    return true
                }
                if (tool == Tool.ARC) {
                    handleArcDown(event, actionIndex)
                    return true
                }
                if (active != null) return true
                activePointerId = event.getPointerId(actionIndex)
                pressureMax = event.device
                    ?.getMotionRange(MotionEvent.AXIS_PRESSURE)
                    ?.max
                    ?.takeIf { it > 0f } ?: 1.0f
                val color = if (tool == Tool.ERASER) Color.WHITE else strokeColor
                // Capture the frisket this stroke is drawn under, if any.
                activeClip = selectionMask?.copy(Bitmap.Config.ARGB_8888, false)
                active = Stroke(tool, color, seed = Random.nextInt()).apply {
                    add(sample(event, actionIndex))
                }
                beginActiveExtras()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (tool == Tool.WAND) {
                    handleWandMove(event)
                    return true
                }
                if (tool == Tool.LINE) {
                    handleLineMove(event)
                    return true
                }
                if (tool == Tool.ARC) {
                    handleArcMove(event)
                    return true
                }
                val stroke = active ?: return true
                val pi = event.findPointerIndex(activePointerId)
                if (pi < 0) return true
                for (h in 0 until event.historySize) {
                    stroke.add(sampleHistorical(event, pi, h))
                }
                stroke.add(sample(event, pi))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tool == Tool.WAND) {
                    handleWandUp(event, actionIndex)
                    return true
                }
                if (tool == Tool.LINE) {
                    handleLineUp(event, actionIndex)
                    return true
                }
                if (tool == Tool.ARC) {
                    handleArcUp(event, actionIndex)
                    return true
                }
                val stroke = active
                if (stroke != null && event.getPointerId(actionIndex) == activePointerId) {
                    unbaked.add(stroke)
                    unbakedClips.add(activeClip)
                    active = null
                    activeClip = null
                    activePointerId = INVALID_POINTER
                    endActiveExtras()
                    kickBake()
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    /**
     * Finger-touch history gestures: a two-finger double-tap undoes, a
     * three-finger double-tap redoes. A "tap" = all fingers down and up within
     * ~400 ms with no finger dragged past the slop; the tap's count is the most
     * fingers down at once. Two matching taps within [DOUBLE_TAP_WINDOW_MS] make
     * the double-tap. Mirrors Flutter's `_onTouch*` handlers. Always consumes.
     */
    private fun handleTouchGesture(event: MotionEvent): Boolean {
        val slop = TOUCH_TAP_SLOP_DP * resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (touchStart.isEmpty()) {
                    touchSessionStart = event.eventTime
                    touchMaxCount = 0
                    touchMoved = false
                }
                val ai = event.actionIndex
                touchStart[event.getPointerId(ai)] = PointF(event.getX(ai), event.getY(ai))
                if (touchStart.size > touchMaxCount) touchMaxCount = touchStart.size
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val start = touchStart[event.getPointerId(i)] ?: continue
                    if (hypot(event.getX(i) - start.x, event.getY(i) - start.y) > slop) {
                        touchMoved = true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (touchStart.remove(event.getPointerId(event.actionIndex)) == null) return true
                if (touchStart.isNotEmpty()) return true // still fingers down; wait
                val tapped = !touchMoved && event.eventTime - touchSessionStart < TAP_MAX_MS
                val count = touchMaxCount
                touchMaxCount = 0
                touchMoved = false
                if (!tapped || (count != 2 && count != 3)) {
                    pendingTapCount = 0
                    return true
                }
                val now = event.eventTime
                if (pendingTapCount == count && now - pendingTapTime < DOUBLE_TAP_WINDOW_MS) {
                    pendingTapCount = 0
                    pendingTapTime = 0L
                    if (count == 2) undo() else redo()
                } else {
                    pendingTapCount = count // first tap; wait for its pair
                    pendingTapTime = now
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                touchStart.clear()
                touchMaxCount = 0
                touchMoved = false
                pendingTapCount = 0
            }
        }
        return true
    }

    // --- Wand gestures ------------------------------------------------------

    private fun handleWandDown(e: MotionEvent, ai: Int) {
        val p = PointF(e.getX(ai), e.getY(ai))
        // A corner scales, the top handle rotates, dragging inside moves.
        val mode = hitTransform(p)
        if (mode != null) {
            movePointerId = e.getPointerId(ai)
            moveLastPos.set(p)
            xformMode = mode
            if (!isFloating && !lifting) scope.launch { liftSelection() }
            return
        }
        // Otherwise a stylus tap selects; a drag is ignored (no stroke is created).
        wandPointerId = e.getPointerId(ai)
        wandDownPos = PointF(p.x, p.y)
        wandMoved = false
    }

    private fun handleWandMove(e: MotionEvent) {
        if (movePointerId != INVALID_POINTER) {
            val pi = e.findPointerIndex(movePointerId)
            if (pi < 0) return
            val p = PointF(e.getX(pi), e.getY(pi))
            if (!isFloating) { // lift still in flight — just track position.
                moveLastPos.set(p)
                return
            }
            when (xformMode) {
                Xform.TRANSLATE -> {
                    floatTranslate.offset(p.x - moveLastPos.x, p.y - moveLastPos.y)
                    moveLastPos.set(p)
                    invalidate()
                }
                Xform.SCALE -> {
                    val piv = visiblePivot()
                    val d = hypot(p.x - piv.x, p.y - piv.y)
                    floatScale = (gestureStartScale * d / gestureStartDist).coerceIn(0.05f, 20f)
                    invalidate()
                }
                Xform.ROTATE -> {
                    val piv = visiblePivot()
                    val ang = atan2(p.y - piv.y, p.x - piv.x)
                    floatRotation = gestureStartRotation + (ang - gestureStartAngle)
                    invalidate()
                }
                null -> {}
            }
            return
        }
        if (wandPointerId != INVALID_POINTER) {
            val pi = e.findPointerIndex(wandPointerId)
            val down = wandDownPos
            if (pi >= 0 && down != null && hypot(e.getX(pi) - down.x, e.getY(pi) - down.y) > 8f) {
                wandMoved = true
            }
        }
    }

    private fun handleWandUp(e: MotionEvent, ai: Int) {
        val pid = e.getPointerId(ai)
        if (pid == movePointerId) {
            // Drag ended; the selection stays floating so it can be transformed
            // again. It bakes on deselect, tool switch, or a new selection.
            movePointerId = INVALID_POINTER
            xformMode = null
            return
        }
        if (pid == wandPointerId) {
            val pos = wandDownPos
            val moved = wandMoved
            wandPointerId = INVALID_POINTER
            wandDownPos = null
            wandMoved = false
            if (!moved && pos != null) scope.launch { runWandSelection(pos) }
        }
    }

    // --- Line-tool gestures -------------------------------------------------

    private fun handleLineDown(e: MotionEvent, ai: Int) {
        val p = PointF(e.getX(ai), e.getY(ai))
        if (hasPendingLine) {
            val mode = hitLine(p)
            if (mode != null) {
                linePointerId = e.getPointerId(ai)
                lineMode = mode
                lineDragLast.set(p)
                if (mode == LineDrag.ROTATE) {
                    val a = lineA!!
                    val b = lineB!!
                    lineRotCenter.set((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    lineRotA0.set(a)
                    lineRotB0.set(b)
                    lineRotStartAngle = atan2(p.y - lineRotCenter.y, p.x - lineRotCenter.x)
                }
                return
            }
            // Tapped empty space: bake the current line, then begin a new one here.
            commitPendingLine()
        }
        val had = hasPendingLine
        lineA = PointF(p.x, p.y)
        lineB = PointF(p.x, p.y)
        lineMode = LineDrag.DRAW
        linePointerId = e.getPointerId(ai)
        lineDragLast.set(p)
        if (!had) onLineChanged?.invoke(true)
        invalidate()
    }

    private fun handleLineMove(e: MotionEvent) {
        if (linePointerId == INVALID_POINTER) return
        val pi = e.findPointerIndex(linePointerId)
        if (pi < 0) return
        val p = PointF(e.getX(pi), e.getY(pi))
        when (lineMode) {
            LineDrag.DRAW, LineDrag.ENDPOINT_B -> {
                lineB?.set(p.x, p.y)
                invalidate()
            }
            LineDrag.ENDPOINT_A -> {
                lineA?.set(p.x, p.y)
                invalidate()
            }
            LineDrag.BODY -> {
                val dx = p.x - lineDragLast.x
                val dy = p.y - lineDragLast.y
                lineA?.offset(dx, dy)
                lineB?.offset(dx, dy)
                lineDragLast.set(p)
                invalidate()
            }
            LineDrag.ROTATE -> {
                val ang = atan2(p.y - lineRotCenter.y, p.x - lineRotCenter.x)
                val d = ang - lineRotStartAngle
                lineA?.set(rotatePoint(lineRotA0, lineRotCenter, d))
                lineB?.set(rotatePoint(lineRotB0, lineRotCenter, d))
                invalidate()
            }
            LineDrag.NONE -> {}
        }
    }

    private fun handleLineUp(e: MotionEvent, ai: Int) {
        if (e.getPointerId(ai) != linePointerId) return
        linePointerId = INVALID_POINTER
        // A tap with no real drag makes no line — drop it and stay ready.
        if (lineMode == LineDrag.DRAW) {
            val a = lineA
            val b = lineB
            if (a == null || b == null || hypot(b.x - a.x, b.y - a.y) < MIN_LINE_LEN) {
                discardPendingLine()
                return
            }
        }
        lineMode = LineDrag.NONE
        invalidate()
    }

    /** Which part of the pending line [p] grabs, or null if it misses everything. */
    private fun hitLine(p: PointF): LineDrag? {
        val a = lineA ?: return null
        val b = lineB ?: return null
        val rot = lineRotateHandle(a, b)
        if (hypot(p.x - rot.x, p.y - rot.y) <= HANDLE_HIT) return LineDrag.ROTATE
        if (hypot(p.x - a.x, p.y - a.y) <= HANDLE_HIT) return LineDrag.ENDPOINT_A
        if (hypot(p.x - b.x, p.y - b.y) <= HANDLE_HIT) return LineDrag.ENDPOINT_B
        val bodyHit = max(BODY_HIT, sizeFor(Tool.LINE) / 2f + 8f)
        if (distToSegment(p, a, b) <= bodyHit) return LineDrag.BODY
        return null
    }

    /** The rotate handle: off the line's centre, perpendicular to it. */
    private fun lineRotateHandle(a: PointF, b: PointF): PointF {
        val cx = (a.x + b.x) / 2f
        val cy = (a.y + b.y) / 2f
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        val ux = if (len < 1e-3f) 0f else dx / len
        val uy = if (len < 1e-3f) -1f else dy / len
        // Perpendicular (-uy, ux).
        return PointF(cx - uy * LINE_ROT_OFFSET, cy + ux * LINE_ROT_OFFSET)
    }

    private fun rotatePoint(src: PointF, center: PointF, radians: Float): PointF {
        val c = cos(radians)
        val s = sin(radians)
        val vx = src.x - center.x
        val vy = src.y - center.y
        return PointF(center.x + vx * c - vy * s, center.y + vx * s + vy * c)
    }

    private fun distToSegment(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** A two-point straight [Stroke] for the current line endpoints. */
    private fun buildLineStroke(a: PointF, b: PointF): Stroke {
        val width = resolveWidth(Tool.LINE, sizeFor(Tool.LINE), 1f, 0f)
        return Stroke(Tool.LINE, strokeColor, seed = Random.nextInt()).apply {
            add(StrokePoint(Vec2(a.x, a.y), width))
            add(StrokePoint(Vec2(b.x, b.y), width))
        }
    }

    /** Bakes the pending line as a [StrokeOp] (or drops it if it's degenerate). */
    fun commitPendingLine() {
        val a = lineA
        val b = lineB
        lineMode = LineDrag.NONE
        linePointerId = INVALID_POINTER
        if (a == null || b == null || hypot(b.x - a.x, b.y - a.y) < MIN_LINE_LEN) {
            discardPendingLine()
            return
        }
        val stroke = buildLineStroke(a, b)
        lineA = null
        lineB = null
        onLineChanged?.invoke(false)
        invalidate()
        scope.launch { applyCommittedOp(StrokeOp(stroke)) }
    }

    private fun discardPendingLine() {
        val had = hasPendingLine
        lineA = null
        lineB = null
        lineMode = LineDrag.NONE
        linePointerId = INVALID_POINTER
        if (had) onLineChanged?.invoke(false)
        invalidate()
    }

    // --- Arc-tool gestures --------------------------------------------------

    private fun handleArcDown(e: MotionEvent, ai: Int) {
        val p = PointF(e.getX(ai), e.getY(ai))
        if (hasPendingArc) {
            val mode = hitArc(p)
            if (mode != null) {
                arcPointerId = e.getPointerId(ai)
                arcMode = mode
                arcDragLast.set(p)
                if (mode == ArcDrag.ROTATE) {
                    val a = arcA!!
                    val b = arcB!!
                    val m = arcM!!
                    arcRotCenter.set((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    arcRotA0.set(a)
                    arcRotB0.set(b)
                    arcRotM0.set(m)
                    arcRotStartAngle = atan2(p.y - arcRotCenter.y, p.x - arcRotCenter.x)
                }
                return
            }
            // Tapped empty space: bake the current arc, then begin a new one here.
            commitPendingArc()
        }
        val had = hasPendingArc
        arcA = PointF(p.x, p.y)
        arcB = PointF(p.x, p.y)
        arcM = PointF(p.x, p.y)
        arcMode = ArcDrag.DRAW
        arcPointerId = e.getPointerId(ai)
        arcDragLast.set(p)
        if (!had) onArcChanged?.invoke(true)
        invalidate()
    }

    private fun handleArcMove(e: MotionEvent) {
        if (arcPointerId == INVALID_POINTER) return
        val pi = e.findPointerIndex(arcPointerId)
        if (pi < 0) return
        val p = PointF(e.getX(pi), e.getY(pi))
        when (arcMode) {
            ArcDrag.DRAW -> {
                arcB?.set(p.x, p.y)
                val a = arcA
                val b = arcB
                if (a != null && b != null) arcM?.set((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                invalidate()
            }
            ArcDrag.ENDPOINT_A -> { arcA?.set(p.x, p.y); invalidate() }
            ArcDrag.ENDPOINT_B -> { arcB?.set(p.x, p.y); invalidate() }
            ArcDrag.MIDDLE -> { arcM?.set(p.x, p.y); invalidate() }
            ArcDrag.BODY -> {
                val dx = p.x - arcDragLast.x
                val dy = p.y - arcDragLast.y
                arcA?.offset(dx, dy)
                arcB?.offset(dx, dy)
                arcM?.offset(dx, dy)
                arcDragLast.set(p)
                invalidate()
            }
            ArcDrag.ROTATE -> {
                val ang = atan2(p.y - arcRotCenter.y, p.x - arcRotCenter.x)
                val d = ang - arcRotStartAngle
                arcA?.set(rotatePoint(arcRotA0, arcRotCenter, d))
                arcB?.set(rotatePoint(arcRotB0, arcRotCenter, d))
                arcM?.set(rotatePoint(arcRotM0, arcRotCenter, d))
                invalidate()
            }
            ArcDrag.NONE -> {}
        }
    }

    private fun handleArcUp(e: MotionEvent, ai: Int) {
        if (e.getPointerId(ai) != arcPointerId) return
        arcPointerId = INVALID_POINTER
        if (arcMode == ArcDrag.DRAW) {
            val a = arcA
            val b = arcB
            if (a == null || b == null || hypot(b.x - a.x, b.y - a.y) < MIN_LINE_LEN) {
                discardPendingArc()
                return
            }
        }
        arcMode = ArcDrag.NONE
        invalidate()
    }

    /** Which part of the pending arc [p] grabs, or null if it misses everything. */
    private fun hitArc(p: PointF): ArcDrag? {
        val a = arcA ?: return null
        val b = arcB ?: return null
        val m = arcM ?: return null
        val rot = arcRotateHandle(a, b, m)
        if (hypot(p.x - rot.x, p.y - rot.y) <= HANDLE_HIT) return ArcDrag.ROTATE
        if (hypot(p.x - a.x, p.y - a.y) <= HANDLE_HIT) return ArcDrag.ENDPOINT_A
        if (hypot(p.x - b.x, p.y - b.y) <= HANDLE_HIT) return ArcDrag.ENDPOINT_B
        if (hypot(p.x - m.x, p.y - m.y) <= HANDLE_HIT) return ArcDrag.MIDDLE
        val bodyHit = max(BODY_HIT, sizeFor(Tool.ARC) / 2f + 8f)
        val pts = arcPoints(a, b, m)
        for (i in 1 until pts.size) {
            if (distToSegment(p, pts[i - 1], pts[i]) <= bodyHit) return ArcDrag.BODY
        }
        return null
    }

    /** Rotate handle: off the chord centre, perpendicular, on the side away from the bulge. */
    private fun arcRotateHandle(a: PointF, b: PointF, m: PointF): PointF {
        val cx = (a.x + b.x) / 2f
        val cy = (a.y + b.y) / 2f
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        val ux = if (len < 1e-3f) 0f else dx / len
        val uy = if (len < 1e-3f) -1f else dy / len
        var px = -uy
        var py = ux
        // Keep the rotate handle opposite the arc's bulge so it never sits on [arcM].
        if ((m.x - cx) * px + (m.y - cy) * py > 0f) {
            px = -px; py = -py
        }
        return PointF(cx + px * LINE_ROT_OFFSET, cy + py * LINE_ROT_OFFSET)
    }

    /** The quadratic control point placing the curve through [m] at t = 0.5. */
    private fun arcControl(a: PointF, b: PointF, m: PointF): PointF =
        PointF(2f * m.x - 0.5f * (a.x + b.x), 2f * m.y - 0.5f * (a.y + b.y))

    /** Sampled points along the arc's quadratic curve (through A, M, B). */
    private fun arcPoints(a: PointF, b: PointF, m: PointF): List<PointF> {
        val c = arcControl(a, b, m)
        val chord = hypot(b.x - a.x, b.y - a.y)
        val bulge = hypot(m.x - (a.x + b.x) / 2f, m.y - (a.y + b.y) / 2f)
        val n = ((chord + bulge * 2f) / 6f).roundToInt().coerceIn(16, 96)
        val out = ArrayList<PointF>(n + 1)
        for (i in 0..n) {
            val t = i.toFloat() / n
            val mt = 1f - t
            val x = mt * mt * a.x + 2f * mt * t * c.x + t * t * b.x
            val y = mt * mt * a.y + 2f * mt * t * c.y + t * t * b.y
            out.add(PointF(x, y))
        }
        return out
    }

    /** A densely-sampled straight-or-curved [Stroke] for the current arc handles. */
    private fun buildArcStroke(a: PointF, b: PointF, m: PointF): Stroke {
        val width = resolveWidth(Tool.ARC, sizeFor(Tool.ARC), 1f, 0f)
        return Stroke(Tool.ARC, strokeColor, seed = Random.nextInt()).apply {
            for (p in arcPoints(a, b, m)) add(StrokePoint(Vec2(p.x, p.y), width))
        }
    }

    /** Bakes the pending arc as a [StrokeOp] (or drops it if it's degenerate). */
    fun commitPendingArc() {
        val a = arcA
        val b = arcB
        val m = arcM
        arcMode = ArcDrag.NONE
        arcPointerId = INVALID_POINTER
        if (a == null || b == null || m == null || hypot(b.x - a.x, b.y - a.y) < MIN_LINE_LEN) {
            discardPendingArc()
            return
        }
        val stroke = buildArcStroke(a, b, m)
        arcA = null
        arcB = null
        arcM = null
        onArcChanged?.invoke(false)
        invalidate()
        scope.launch { applyCommittedOp(StrokeOp(stroke)) }
    }

    private fun discardPendingArc() {
        val had = hasPendingArc
        arcA = null
        arcB = null
        arcM = null
        arcMode = ArcDrag.NONE
        arcPointerId = INVALID_POINTER
        if (had) onArcChanged?.invoke(false)
        invalidate()
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

    /** Sets up per-stroke live-preview scratch (spray accumulation / wet backdrop). */
    private fun beginActiveExtras() {
        activeAccum?.recycle()
        activeAccum = null
        accumDrawn = 0
        if (active?.tool == Tool.SPRAY && width > 0 && height > 0) {
            activeAccum = createBitmap(width, height)
        }
    }

    /** Tears down per-stroke live-preview scratch on pointer-up. */
    private fun endActiveExtras() {
        activeAccum?.recycle()
        activeAccum = null
        accumDrawn = 0
    }

    // --- Baking -------------------------------------------------------------

    private fun kickBake() {
        if (baking || rebuilding || unbaked.isEmpty()) return
        val base = paintBmp ?: return
        baking = true
        val batch = unbaked.toList()
        val clips = unbakedClips.toList()
        val ops = batch.indices.map { StrokeOp(batch[it], clips[it]) }
        scope.launch {
            val next = withContext(Dispatchers.Default) {
                foldOps(base.copy(Bitmap.Config.ARGB_8888, true), ops)
            }
            val old = paintBmp
            paintBmp = next
            unbaked.subList(0, batch.size).clear()
            unbakedClips.subList(0, batch.size).clear()
            committed.addAll(ops)
            clearRedo()
            storeCheckpoint(committed.size, next)
            old?.recycle()
            baking = false
            invalidate()
            onHistoryChanged?.invoke()
            if (unbaked.isNotEmpty()) kickBake()
        }
    }

    /**
     * Folds [ops] onto [start] (which it takes ownership of), returning the
     * result. Ordinary strokes deposit directly; watercolor mixes spectrally; the
     * wand ops fill / erase / move within their region. Bitmaps the ops own (clip
     * / mask / sourceMask) are read but never recycled here — they belong to the op.
     */
    private fun foldOps(start: Bitmap, ops: List<PaintOp>): Bitmap {
        var cur = start
        for (op in ops) {
            when (op) {
                is StrokeOp -> {
                    val s = op.stroke
                    val clip = op.clip
                    if (clip == null) {
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
                    } else {
                        val full = if (s.tool == Tool.WATERCOLOR) {
                            compositeWatercolor(cur, s)
                        } else {
                            cur.copy(Bitmap.Config.ARGB_8888, true).also {
                                Canvas(it).apply {
                                    save()
                                    scale(SUPER_SAMPLE, SUPER_SAMPLE)
                                    StrokeRenderer.paintStroke(this, s, surface)
                                    restore()
                                }
                            }
                        }
                        val out = blendMasked(cur, full, clip)
                        if (full !== cur) full.recycle()
                        cur.recycle()
                        cur = out
                    }
                }
                is FillOp -> {
                    Canvas(cur).let {
                        SelectionRender.paintToothedFill(
                            it, op.mask, RectF(0f, 0f, bufW.toFloat(), bufH.toFloat()),
                            op.color, surface, surface.toothScale * SUPER_SAMPLE,
                        )
                    }
                }
                is EraseOp -> {
                    SelectionRender.paintMaskedErase(
                        Canvas(cur), op.mask, RectF(0f, 0f, bufW.toFloat(), bufH.toFloat()),
                    )
                }
                is MoveOp -> {
                    val out = compositeMove(cur, op)
                    cur.recycle()
                    cur = out
                }
                // Surface changes don't touch the paint layer; the surface state is
                // resolved separately (see syncSurfaceToHistory).
                is SurfaceOp -> {}
            }
        }
        return cur
    }

    fun undo() {
        if (baking || rebuilding || committed.isEmpty()) return
        redoStack.add(committed.removeAt(committed.lastIndex))
        syncSurfaceToHistory()
        rebuild()
    }

    fun redo() {
        if (baking || rebuilding || redoStack.isEmpty()) return
        committed.add(redoStack.removeAt(redoStack.lastIndex))
        syncSurfaceToHistory()
        rebuild()
    }

    private fun rebuild() {
        rebuilding = true
        val target = committed.size
        val startIdx = checkpoints.floorKey(target) ?: 0
        val startBmp = if (startIdx == 0) null else checkpoints[startIdx]
        val ops = committed.subList(startIdx, target).toList()
        scope.launch {
            val next = withContext(Dispatchers.Default) {
                val base = startBmp?.copy(Bitmap.Config.ARGB_8888, true) ?: createBitmap(bufW, bufH)
                foldOps(base, ops)
            }
            val old = paintBmp
            paintBmp = next
            old?.recycle()
            storeCheckpoint(target, next)
            rebuilding = false
            invalidate()
            onHistoryChanged?.invoke()
        }
    }

    /** Drops (and recycles) the redo history — called when a new op is committed. */
    private fun clearRedo() {
        for (op in redoStack) op.recycle()
        redoStack.clear()
        dropCheckpointsAfter(committed.size)
    }

    /** Snapshots the paint at [index] ops (a copy of [source]) if it's on-stride. */
    private fun storeCheckpoint(index: Int, source: Bitmap) {
        if (index <= 0 || index % CHECKPOINT_STRIDE != 0 || checkpoints.containsKey(index)) return
        checkpoints[index] = source.copy(Bitmap.Config.ARGB_8888, false)
        while (checkpoints.size > MAX_CHECKPOINTS) {
            checkpoints.remove(checkpoints.firstKey())?.recycle()
        }
    }

    /** Invalidates checkpoints past [index] (history diverged there). */
    private fun dropCheckpointsAfter(index: Int) {
        for (k in checkpoints.tailMap(index + 1).keys.toList()) checkpoints.remove(k)?.recycle()
    }

    private fun recycleCheckpoints() {
        for (b in checkpoints.values) b.recycle()
        checkpoints.clear()
    }

    // --- Selection ops ------------------------------------------------------

    /** Flood-fills a contiguous painted region at [logical] (view px). */
    private suspend fun runWandSelection(logical: PointF) {
        commitFloating()
        val paint = paintBmp ?: return
        val seedX = logical.x.roundToInt().coerceIn(0, bufW - 1)
        val seedY = logical.y.roundToInt().coerceIn(0, bufH - 1)
        val w = bufW
        val h = bufH
        val result = withContext(Dispatchers.Default) {
            val pixels = IntArray(w * h)
            paint.getPixels(pixels, 0, w, 0, 0, w, h)
            WandFloodFill.run(
                WandFloodFill.Request(
                    pixels, w, h, seedX, seedY,
                    wandTolerance, wandEdgeSensitivity, wandGap,
                ),
            )
        }
        if (result.isEmpty) {
            clearSelection()
            return
        }
        val maskBmp = createBitmap(result.width, result.height).apply {
            setPixels(result.mask, 0, result.width, 0, 0, result.width, result.height)
        }
        val ds = WandFloodFill.DOWNSAMPLE.toFloat()
        val old = selectionMask
        selectionMask = maskBmp
        selRect.set(
            result.bounds.left * ds, result.bounds.top * ds,
            result.bounds.right * ds, result.bounds.bottom * ds,
        )
        floatTranslate.set(0f, 0f)
        floatScale = 1f
        floatRotation = 0f
        old?.recycle()
        startAnts()
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    /** Fills the current selection with [color] (toothed), as one undoable op. */
    fun fillSelection(@ColorInt color: Int) {
        scope.launch {
            if (selectionMask == null) return@launch
            commitFloating()
            flushPending()
            val mask = selectionMask ?: return@launch
            applyCommittedOp(FillOp(mask.copy(Bitmap.Config.ARGB_8888, false), color))
        }
    }

    /** Erases paint within the current selection, as one undoable op. */
    fun deleteSelection() {
        scope.launch {
            if (selectionMask == null) return@launch
            commitFloating()
            flushPending()
            val mask = selectionMask ?: return@launch
            applyCommittedOp(EraseOp(mask.copy(Bitmap.Config.ARGB_8888, false)))
        }
    }

    /** Clears the current selection (baking any floating move first). */
    fun clearSelection() {
        scope.launch {
            if (selectionMask == null && !isFloating) return@launch
            commitFloating()
            val old = selectionMask
            selectionMask = null
            old?.recycle()
            selRect.setEmpty()
            stopAnts()
            onSelectionChanged?.invoke(false)
            invalidate()
        }
    }

    /** Bakes [op] onto the paint layer and records it as a committed step. */
    private suspend fun applyCommittedOp(op: PaintOp) {
        val base = paintBmp ?: return
        val next = withContext(Dispatchers.Default) {
            foldOps(base.copy(Bitmap.Config.ARGB_8888, true), listOf(op))
        }
        val old = paintBmp
        paintBmp = next
        committed.add(op)
        clearRedo()
        storeCheckpoint(committed.size, next)
        old?.recycle()
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** Waits for pending strokes to bake so a following op composites on top. */
    private suspend fun flushPending() {
        while (unbaked.isNotEmpty() || baking) {
            if (!baking && unbaked.isNotEmpty()) kickBake()
            delay(8)
        }
    }

    // --- Floating move/transform --------------------------------------------

    private fun visiblePivot(): PointF =
        PointF(selRect.centerX() + floatTranslate.x, selRect.centerY() + floatTranslate.y)

    /**
     * The current float transform (scale + rotate about the selection centre, then
     * translate) as an affine [Matrix]. [sampleScale] scales the pivot/translation
     * into the target space: 1 for buffer/view coords, 1/DOWNSAMPLE for the mask.
     */
    private fun floatMatrix(sampleScale: Float): Matrix {
        val px = selRect.centerX() * sampleScale
        val py = selRect.centerY() * sampleScale
        val tx = floatTranslate.x * sampleScale
        val ty = floatTranslate.y * sampleScale
        return Matrix().apply {
            postTranslate(-px, -py)
            postScale(floatScale, floatScale)
            postRotate(Math.toDegrees(floatRotation.toDouble()).toFloat())
            postTranslate(px + tx, py + ty)
        }
    }

    private fun floatCorners(): List<PointF> {
        val pts = floatArrayOf(
            selRect.left, selRect.top,
            selRect.right, selRect.top,
            selRect.right, selRect.bottom,
            selRect.left, selRect.bottom,
        )
        floatMatrix(1f).mapPoints(pts)
        return listOf(
            PointF(pts[0], pts[1]), PointF(pts[2], pts[3]),
            PointF(pts[4], pts[5]), PointF(pts[6], pts[7]),
        )
    }

    private fun rotateHandle(): PointF {
        val pts = floatArrayOf(
            selRect.centerX(), selRect.top,
            selRect.centerX(), selRect.centerY(),
        )
        floatMatrix(1f).mapPoints(pts)
        val topMid = PointF(pts[0], pts[1])
        val pivot = PointF(pts[2], pts[3])
        val dx = topMid.x - pivot.x
        val dy = topMid.y - pivot.y
        val len = hypot(dx, dy)
        val nx = if (len == 0f) 0f else dx / len
        val ny = if (len == 0f) -1f else dy / len
        return PointF(topMid.x + nx * 30f, topMid.y + ny * 30f)
    }

    private fun hitTransform(p: PointF): Xform? {
        if (selectionMask == null && !isFloating) return null
        if (selRect.isEmpty) return null
        val hit = 26f
        val pivot = visiblePivot()
        val rot = rotateHandle()
        if (hypot(p.x - rot.x, p.y - rot.y) <= hit) {
            gestureStartRotation = floatRotation
            gestureStartAngle = atan2(p.y - pivot.y, p.x - pivot.x)
            return Xform.ROTATE
        }
        val corners = floatCorners()
        for (c in corners) {
            if (hypot(p.x - c.x, p.y - c.y) <= hit) {
                gestureStartScale = floatScale
                gestureStartDist = max(1f, hypot(p.x - pivot.x, p.y - pivot.y))
                return Xform.SCALE
            }
        }
        if (pointInQuad(p, corners)) return Xform.TRANSLATE
        return null
    }

    private fun pointInQuad(p: PointF, q: List<PointF>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    /** Lifts the selected paint into a floating layer, clearing its original spot. */
    private suspend fun liftSelection() {
        val base = paintBmp ?: return
        val mask = selectionMask ?: return
        if (isFloating || lifting) return
        lifting = true
        try {
            val (fl, ho) = withContext(Dispatchers.Default) {
                maskedCopy(base, mask, keepInside = true) to maskedCopy(base, mask, keepInside = false)
            }
            floating = fl
            paintHole = ho
            floatSourceMask = mask.copy(Bitmap.Config.ARGB_8888, false)
            floatTranslate.set(0f, 0f)
            floatScale = 1f
            floatRotation = 0f
            invalidate()
        } finally {
            lifting = false
        }
    }

    /** Bakes the floating move into the paint as a [MoveOp] and re-lands the mask. */
    private suspend fun commitFloating() {
        val sourceMask = floatSourceMask
        val base = paintBmp
        if (floating == null || sourceMask == null || base == null) {
            discardFloating()
            return
        }
        if (floatTranslate.x == 0f && floatTranslate.y == 0f && floatScale == 1f && floatRotation == 0f) {
            discardFloating()
            return
        }
        val bufMatrix = floatMatrix(1f)
        val maskMatrix = floatMatrix(1f / WandFloodFill.DOWNSAMPLE)
        val newRect = boundsOf(floatCorners())
        val op = MoveOp(sourceMask.copy(Bitmap.Config.ARGB_8888, false), Matrix(bufMatrix))
        val liveMask = selectionMask
        val (newPaint, movedMask) = withContext(Dispatchers.Default) {
            compositeMove(base, op) to (liveMask?.let { transformMask(it, maskMatrix) })
        }
        val oldPaint = paintBmp
        val oldMask = selectionMask
        val f = floating
        val hh = paintHole
        val m = floatSourceMask
        paintBmp = newPaint
        committed.add(op)
        clearRedo()
        storeCheckpoint(committed.size, newPaint)
        if (movedMask != null) selectionMask = movedMask
        selRect.set(newRect)
        floating = null
        paintHole = null
        floatSourceMask = null
        floatTranslate.set(0f, 0f)
        floatScale = 1f
        floatRotation = 0f
        oldPaint?.recycle()
        if (movedMask != null) oldMask?.recycle()
        f?.recycle()
        hh?.recycle()
        m?.recycle()
        invalidate()
        onHistoryChanged?.invoke()
    }

    private fun discardFloating() {
        if (floating == null && paintHole == null && floatSourceMask == null) return
        floating?.recycle()
        paintHole?.recycle()
        floatSourceMask?.recycle()
        floating = null
        paintHole = null
        floatSourceMask = null
        floatTranslate.set(0f, 0f)
        floatScale = 1f
        floatRotation = 0f
        invalidate()
    }

    private fun boundsOf(pts: List<PointF>): RectF {
        var l = pts.first().x
        var t = pts.first().y
        var r = l
        var b = t
        for (p in pts) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        return RectF(l, t, r, b)
    }

    /** Copies [src], keeping only the paint inside (or outside) [mask]. Buffer res. */
    private fun maskedCopy(src: Bitmap, mask: Bitmap, keepInside: Boolean): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint().apply { blendMode = if (keepInside) BlendMode.DST_IN else BlendMode.DST_OUT }
        Canvas(out).drawBitmap(mask, Rect(0, 0, mask.width, mask.height), Rect(0, 0, bufW, bufH), paint)
        return out
    }

    /** Returns a copy of [mask] under [matrix] (mask-resolution coordinates). */
    private fun transformMask(mask: Bitmap, matrix: Matrix): Bitmap {
        val out = createBitmap(mask.width, mask.height)
        Canvas(out).apply {
            concat(matrix)
            drawBitmap(mask, 0f, 0f, null)
        }
        return out
    }

    /** Bakes a [MoveOp]: lift under the source mask, clear the spot, re-lay transformed. */
    private fun compositeMove(base: Bitmap, op: MoveOp): Bitmap {
        val fl = maskedCopy(base, op.sourceMask, keepInside = true)
        val ho = maskedCopy(base, op.sourceMask, keepInside = false)
        val out = createBitmap(bufW, bufH)
        Canvas(out).apply {
            drawBitmap(ho, 0f, 0f, null)
            save()
            concat(op.transform)
            drawBitmap(fl, 0f, 0f, null)
            restore()
        }
        fl.recycle()
        ho.recycle()
        return out
    }

    /**
     * Per-pixel select between [base] (outside [mask]) and [full] (inside it):
     * `out = base*(1-m) + full*m`. Constrains an edit to the selection uniformly.
     */
    private fun blendMasked(base: Bitmap, full: Bitmap, mask: Bitmap): Bitmap {
        val out = createBitmap(bufW, bufH)
        val c = Canvas(out)
        val mSrc = Rect(0, 0, mask.width, mask.height)
        val bDst = Rect(0, 0, bufW, bufH)
        c.drawBitmap(base, 0f, 0f, null)
        val l1 = c.saveLayer(0f, 0f, bufW.toFloat(), bufH.toFloat(), dstOutPaint)
        c.drawBitmap(mask, mSrc, bDst, null)
        c.restoreToCount(l1)
        val l2 = c.saveLayer(0f, 0f, bufW.toFloat(), bufH.toFloat(), null)
        c.drawBitmap(full, 0f, 0f, null)
        c.drawBitmap(mask, mSrc, bDst, dstInPaint)
        c.restoreToCount(l2)
        return out
    }

    // --- Marching-ants animation --------------------------------------------

    private fun startAnts() {
        if (!antsAnimator.isStarted) antsAnimator.start()
    }

    private fun stopAnts() {
        if (!hasSelection) antsAnimator.cancel()
    }

    private fun clearSelectionState() {
        selectionMask?.recycle()
        selectionMask = null
        discardFloating()
        discardPendingLine()
        discardPendingArc()
        selRect.setEmpty()
        antsAnimator.cancel()
    }

    // --- Watercolor (from Stage 4b) -----------------------------------------

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
     * The wet backdrop: [base] with the stroke region diluted (feathered clear)
     * and pushed (a blurred, faded copy bloomed into a wider region), done in a
     * SINGLE GPU pass. The three blurs are child [RenderNode]s carrying a blur
     * [RenderEffect], composited on the recording canvas with `saveLayer` blend
     * modes — so there's one GPU readback instead of the four the old
     * blur-per-round-trip version needed. Never recycles [base].
     */
    private fun wetBackdrop(base: Bitmap, stroke: Stroke): Bitmap {
        val region = strokeRegionPath(stroke)
        val avgW = avgWidth(stroke) * SUPER_SAMPLE
        val soften = max(2f, avgW * 0.14f)
        val clearFeather = max(2f, avgW * 0.10f)
        val spread = max(4f, avgW * 0.30f)
        return GpuRender.renderToBitmap(bufW, bufH) { canvas ->
            recordWetBackdrop(
                canvas as android.graphics.RecordingCanvas, base, region, bufW, bufH,
                SUPER_SAMPLE, clearFeather, spread, soften,
            )
        }
    }

    /**
     * Records the wet backdrop composite onto [rc] (a hardware [RecordingCanvas],
     * either GpuRender's full-res bake surface or the half-res live one): [base]
     * with the stroke [region] diluted (feathered clear) and pushed (a blurred,
     * faded copy bloomed into a wider region). Geometry and blur radii are scaled by
     * [sc] so the same recipe renders at either resolution. Everything is done with
     * blur-[RenderEffect] child [RenderNode]s in a single pass (no round-trips).
     */
    private fun recordWetBackdrop(
        rc: android.graphics.RecordingCanvas, base: Bitmap, region: android.graphics.Path,
        w: Int, h: Int, sc: Float, clearFeather: Float, spread: Float, soften: Float,
    ) {
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val fw = w.toFloat()
        val fh = h.toFloat()
        fun blurNode(radius: Float, record: (Canvas) -> Unit) = RenderNode("wet").apply {
            setPosition(0, 0, w, h)
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            val c = beginRecording(w, h)
            record(c)
            endRecording()
        }
        // White stroke region, feathered two ways (tight clear + wide bloom).
        val clearNode = blurNode(clearFeather) { it.scale(sc, sc); it.drawPath(region, white) }
        val spreadNode = blurNode(spread) { it.scale(sc, sc); it.drawPath(region, white) }
        val baseBlurNode = blurNode(soften) { it.scale(sc, sc); it.drawBitmap(base, 0f, 0f, null) }
        try {
            // 1. base
            rc.save(); rc.scale(sc, sc); rc.drawBitmap(base, 0f, 0f, null); rc.restore()
            // 2. dilute: punch a feathered hole where the stroke floods with water
            val l1 = rc.saveLayer(0f, 0f, fw, fh, Paint().apply { blendMode = BlendMode.DST_OUT })
            rc.drawRenderNode(clearNode)
            rc.restoreToCount(l1)
            // 3. push: blurred base kept only inside the wider spread region, laid
            //    back on at reduced alpha so displaced pigment blooms past the edge
            val l2 = rc.saveLayer(0f, 0f, fw, fh, Paint().apply { alpha = WET_DILUTE_ALPHA })
            rc.drawRenderNode(baseBlurNode)
            val l3 = rc.saveLayer(0f, 0f, fw, fh, Paint().apply { blendMode = BlendMode.DST_IN })
            rc.drawRenderNode(spreadNode)
            rc.restoreToCount(l3)
            rc.restoreToCount(l2)
        } finally {
            clearNode.discardDisplayList()
            spreadNode.discardDisplayList()
            baseBlurNode.discardDisplayList()
        }
    }

    private fun avgWidth(stroke: Stroke): Float {
        var sum = 0f
        for (p in stroke.points) sum += p.width
        return sum / stroke.points.size
    }

    private fun overComposite(base: Bitmap, wash: Bitmap): Bitmap {
        val out = createBitmap(bufW, bufH)
        Canvas(out).apply {
            drawBitmap(base, 0f, 0f, null)
            drawBitmap(wash, 0f, 0f, null)
        }
        return out
    }

    /** Clears painted strokes, history, and any selection, keeping the surface. */
    fun clear() {
        val old = paintBmp ?: return
        unbaked.clear()
        unbakedClips.forEach { it?.recycle() }
        unbakedClips.clear()
        active = null
        activeClip?.recycle()
        activeClip = null
        endActiveExtras()
        for (op in committed) op.recycle()
        committed.clear()
        clearRedo()
        recycleCheckpoints()
        clearSelectionState()
        onSelectionChanged?.invoke(false)
        // The current surface becomes the new timeline base — undo can't cross clear.
        initialSurface = surface
        initialPlainColor = plainColor
        initialCanvasParams = canvasParams
        initialWatercolorParams = watercolorParams
        initialWoodParams = woodParams
        initialStoneParams = stoneParams
        initialConcreteParams = concreteParams
        initialMetalParams = metalParams
        initialChalkboardParams = chalkboardParams
        // A cleared canvas is a new piece of art: fresh paper for organic surfaces.
        surfaceSeed = java.util.Random().nextLong()
        regenerateSurface()
        paintBmp = createBitmap(bufW, bufH)
        old.recycle()
        invalidate()
        onHistoryChanged?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        antsAnimator.cancel()
        scope.cancel()
        recycleCheckpoints()
        activeAccum?.recycle()
        activeAccum = null
        recycleWashScratch()
        surfaceBmp?.recycle()
        paintBmp?.recycle()
        surfaceBmp = null
        paintBmp = null
        GpuRender.release()
    }

    /**
     * Flattens surface + paint and saves it as a PNG in the gallery, off-thread.
     * [onResult] is delivered on the main thread.
     */
    fun savePng(onResult: (Result<String>) -> Unit) {
        val surface = surfaceBmp
        val paint = paintBmp
        if (surface == null || paint == null) {
            onResult(Result.failure(IllegalStateException("Nothing to save yet")))
            return
        }
        val surfaceCopy = surface.copy(Bitmap.Config.ARGB_8888, false)
        val paintCopy = paint.copy(Bitmap.Config.ARGB_8888, true)
        // Fold in a placed-but-uncommitted line so the export matches the screen,
        // without adding it to history (it stays editable after saving).
        val la = lineA
        val lb = lineB
        if (la != null && lb != null) {
            Canvas(paintCopy).apply {
                save()
                scale(SUPER_SAMPLE, SUPER_SAMPLE)
                StrokeRenderer.paintStroke(this, buildLineStroke(la, lb), surface = this@PaintCanvasView.surface)
                restore()
            }
        }
        val aa = arcA
        val ab = arcB
        val am = arcM
        if (aa != null && ab != null && am != null) {
            Canvas(paintCopy).apply {
                save()
                scale(SUPER_SAMPLE, SUPER_SAMPLE)
                StrokeRenderer.paintStroke(this, buildArcStroke(aa, ab, am), surface = this@PaintCanvasView.surface)
                restore()
            }
        }
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val flat = createBitmap(bufW, bufH)
                    Canvas(flat).apply {
                        drawBitmap(surfaceCopy, 0f, 0f, null)
                        drawBitmap(paintCopy, 0f, 0f, null)
                    }
                    val name = "paintsprout_${System.currentTimeMillis()}"
                    val where = GalleryExport.savePng(context, flat, name)
                    flat.recycle()
                    where
                }
            }
            surfaceCopy.recycle()
            paintCopy.recycle()
            onResult(result)
        }
    }

    private companion object {
        const val INVALID_POINTER = -1
        const val WET_DILUTE_ALPHA = 128
        const val SUPER_SAMPLE = 1.0f

        // Undo/redo: keep a paint snapshot every STRIDE ops, at most MAX of them
        // (most-recent), so an undo replays at most STRIDE ops from a checkpoint.
        const val CHECKPOINT_STRIDE = 6
        const val MAX_CHECKPOINTS = 6

        // Line tool: min drag to count as a line, handle hit radii (px), and how
        // far the rotate handle sits off the line's centre.
        const val MIN_LINE_LEN = 6f
        const val HANDLE_HIT = 26f
        const val BODY_HIT = 16f
        const val LINE_ROT_OFFSET = 34f

        // Finger history gestures (undo/redo double-tap).
        const val TOUCH_TAP_SLOP_DP = 18f
        const val TAP_MAX_MS = 400L
        const val DOUBLE_TAP_WINDOW_MS = 450L

        fun isStylus(toolType: Int): Boolean =
            toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                toolType == MotionEvent.TOOL_TYPE_ERASER
    }

    // Reusable mask-compositing paints.
    private val dstOutPaint = Paint().apply { blendMode = BlendMode.DST_OUT }
    private val dstInPaint = Paint().apply { blendMode = BlendMode.DST_IN }
}
