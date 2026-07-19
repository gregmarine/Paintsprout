package com.symmetricalpalmtree.paintsprout

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import com.symmetricalpalmtree.paintsprout.paint.BrushLoad
import com.symmetricalpalmtree.paintsprout.paint.Calibration
import com.symmetricalpalmtree.paintsprout.paint.CanvasSize
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
import com.symmetricalpalmtree.paintsprout.paint.WetSim
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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
                if (old == Tool.POLYLINE && hasPendingPolyline) commitPendingPolyline()
                if (old == Tool.POLYARC && hasPendingPolyarc) commitPendingPolyarc()
            }
        }

    /**
     * Watercolor's water mode: strokes carry clean water instead of pigment —
     * they deposit nothing, only re-wet and wash out the paint they cross.
     * Captured into each stroke at pen-down (see [Stroke.water]).
     */
    var waterMode = false

    /**
     * Deposit color (ARGB). Ignored by the eraser.
     *
     * Setting it recharges the brush with that colour, so picking a colour off
     * the wheel behaves the way it always has — you don't have to visit the tray
     * to paint. Mixing on the tray goes through [loadBrush] instead.
     */
    @ColorInt
    private var _strokeColor: Int = Color.BLACK

    var strokeColor: Int
        @ColorInt get() = _strokeColor
        set(@ColorInt value) {
            _strokeColor = value
            brushLoad = BrushLoad.of(value)
            onBrushLoadChanged?.invoke(brushLoad)
        }

    /**
     * What the brush is currently carrying: a mixture and how much of it is
     * left. Wet media (see [Tool.usesLoad]) deposit from it and run dry; every
     * other tool ignores it.
     */
    var brushLoad: BrushLoad = BrushLoad.of(Color.BLACK)
        private set

    /** Fired when the load changes — drained by painting, or recharged. */
    var onBrushLoadChanged: ((BrushLoad) -> Unit)? = null

    /**
     * Charges the brush from the tray's well, keeping the mixture intact.
     *
     * Deliberately not routed through [strokeColor]: that setter recharges with
     * a single flat pigment, which would throw away the recipe you just mixed
     * and leave a brush that can't be contaminated meaningfully.
     */
    fun loadBrush(load: BrushLoad) {
        brushLoad = load
        _strokeColor = load.color
        onBrushLoadChanged?.invoke(load)
    }

    /**
     * Pixels per real millimetre at the calibrated PPI. Paint is spent per unit
     * of surface actually covered, so this keeps a brushful lasting the same
     * physical distance instead of draining faster on a denser screen.
     */
    var pxPerMm: Float = 1f

    /** Where the tip last laid paint, for measuring how much the next step spends. */
    private var lastDepositPos: Vec2? = null

    /**
     * The active wet stroke's own trail, so a dirty brush can pick up paint it
     * laid moments ago (crossing back over itself), not just committed paint.
     * Committed paint is read straight from [paintBmp]; this holds only the live
     * stroke. Cleared at the start of each wet stroke.
     */
    private var pickupBuf: Bitmap? = null
    private var pickupCanvas: Canvas? = null
    private val pickupStampPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

    // The drawing surface, sized to [canvasSize] and centred in the view. When it's
    // smaller than the panel, it sits inside a mat (bevel). logicalW/H are its size
    // in view px; canvasLeft/Top centre it. For FullScreen these equal the view.
    private var logicalW = 0
    private var logicalH = 0
    private var canvasLeft = 0
    private var canvasTop = 0

    /** Physical size of the drawing surface. [CanvasSize.FullScreen] fills the panel. */
    var canvasSize: CanvasSize = CanvasSize.FullScreen
        private set

    private val matPaint = Paint().apply { color = 0xFF23282B.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000 }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x40000000
    }

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
    // so computing it at half res keeps the GPU read-back (needed to feed the
    // diluted backdrop to the spectral mixer as a texture) cheap. Double-buffered
    // for the same reason the wash is — HWUI samples it a frame later.
    private val wetLiveScratch = arrayOfNulls<Bitmap>(2)
    private var wetLiveFlip = 0

    // Cached live backdrop shader between rebuilds (see drawWetLive). Reset per stroke.
    private var wetBackdropShader: Shader? = null
    private var wetBackdropAtPoints = 0
    private var wetBackdropAtMs = 0L
    private var wetBackdropKey = 0L

    /**
     * The crop-sized scratch at [idx], reallocated only when the crop's
     * quantized size actually changes ([watercolorCrop] keeps that rare).
     * Recycling the same-index buffer is safe: it was last shown two frames
     * ago, and HWUI holds only the previous frame's (other-index) buffer.
     */
    private fun scratchFor(arr: Array<Bitmap?>, idx: Int, w: Int, h: Int): Bitmap {
        val cur = arr[idx]
        if (cur != null && cur.width == w && cur.height == h) return cur
        cur?.recycle()
        return createBitmap(w, h).also { arr[idx] = it }
    }

    /** Frees both wash scratch buffers (double-buffered — see [drawWetLive]). */
    private fun recycleWashScratch() {
        washScratch[0]?.recycle(); washScratch[0] = null
        washScratch[1]?.recycle(); washScratch[1] = null
        wetLiveScratch[0]?.recycle(); wetLiveScratch[0] = null
        wetLiveScratch[1]?.recycle(); wetLiveScratch[1] = null
    }

    /**
     * Incremental accumulation buffer for the active spray OR brush stroke.
     * Both redraw work proportional to the stroke's length, so redrawing the
     * whole stroke every frame is O(n²) and janks on long strokes; instead only
     * the new, finalized geometry is appended here each frame (raw — no
     * opacity/tooth; the blit applies those over [activeBounds]) and the still-
     * moving tail is drawn fresh on top. [accumDrawn] is how many of the
     * stroke's points are already in it. The bitmap is kept across strokes and
     * erased, not reallocated.
     */
    private var activeAccum: Bitmap? = null
    private var accumDrawn = 0

    /** The active stroke's bristle layout (brush only; frozen per stroke). */
    private var bristleLayout: com.symmetricalpalmtree.paintsprout.paint.BristleLayout? = null

    /**
     * Kalman-predicted pen position: the live TAIL is drawn where the pen will
     * be when this frame reaches the glass, cutting perceived ink lag. Strictly
     * cosmetic — predicted points never enter the stroke, the accumulators, or
     * the load accounting; each real frame re-derives the tail from real data,
     * so a wrong prediction can never persist.
     */
    private val motionPredictor by lazy {
        androidx.input.motionprediction.MotionEventPredictor.newInstance(this)
    }

    /** Builds the transient predicted tail point (no side effects, no spend). */
    private fun predictedTailPoint(): StrokePoint? {
        if (active == null) return null
        val e = motionPredictor.predict() ?: return null
        try {
            if (!insideCanvas(e.x, e.y)) return null
            val pressure = normPressure(e.pressure)
            val tilt = e.getAxisValue(MotionEvent.AXIS_TILT)
            val width = resolveWidth(tool, sizeFor(tool), pressure, tilt)
            val density = resolveDensity(tool, pressure)
            return if (tool.usesLoad) {
                StrokePoint(Vec2(e.x, e.y), width, density, color = brushLoad.color, load = brushLoad.fill)
            } else {
                StrokePoint(Vec2(e.x, e.y), width, density)
            }
        } finally {
            e.recycle()
        }
    }

    /** Runs [draw] with [p] temporarily appended to [stroke] (preview only). */
    private inline fun withTransientPoint(stroke: Stroke, p: StrokePoint?, draw: () -> Unit) {
        if (p == null) {
            draw()
            return
        }
        stroke.points.add(p)
        try {
            draw()
        } finally {
            stroke.points.removeAt(stroke.points.size - 1)
        }
    }

    /** Padded bounds of the active stroke, for bounded blit layers. */
    private val activeBounds = RectF()
    private var activeMaxWidth = 0f

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

    // --- Polyline tool ------------------------------------------------------
    // Built by tapping: each tap plants an anchor and a straight segment joins it
    // to the previous one. A stylus double-tap finishes; if that final tap lands
    // on the first anchor the run closes into a loop. Once finished the anchors
    // stay editable (drag any anchor, drag the body to move, rotate off the top),
    // exactly like the line/arc. On commit the whole run is densely sampled along
    // each segment into one ordinary [StrokeOp] (dense samples keep the corners
    // crisp through [smoothPath]). Points are in view (= buffer) pixels.
    private val polyPts = ArrayList<PointF>()
    private var polyClosed = false
    // false while still tapping out anchors; true once double-tapped into editing.
    private var polyFinished = false
    private var polyMode = PolyDrag.NONE
    private var polyDragIndex = -1
    private var polyPointerId = INVALID_POINTER
    private val polyDragLast = PointF()
    // Rotate gesture: anchors + centre captured at grab, plus the start angle.
    private val polyRot0 = ArrayList<PointF>()
    private val polyRotCenter = PointF()
    private var polyRotStartAngle = 0f
    // Tap tracking for the finishing double-tap (stylus DOWN-to-DOWN).
    private var polyLastTapTime = 0L
    private val polyLastTapPos = PointF()
    // Rubber-band preview: the hovering pen's position while tapping out anchors.
    private var polyHoverPt: PointF? = null

    private enum class PolyDrag { NONE, ANCHOR, BODY, ROTATE }

    /** Whether an editable polyline is placed (being tapped out or edited) but unbaked. */
    val hasPendingPolyline: Boolean get() = polyPts.isNotEmpty()

    /** Fired when the editable polyline appears (true) or is committed/cleared (false). */
    var onPolylineChanged: ((Boolean) -> Unit)? = null

    // --- Polyarc tool -------------------------------------------------------
    // A polyline whose every segment is a pullable quadratic arc (see [[arc]] and
    // [[polyline]]). Built like the polyline — tap out anchors — but each segment
    // carries an on-curve middle handle ([paMids]) that can be dragged (even while
    // still building) to bend that segment into a lopsided arc. A stylus double-tap
    // finishes; a final tap on the first anchor closes the loop (the closing
    // segment gets its own middle handle). Once finished the anchors, the middles,
    // the body and a rotate handle are all grabbable. On commit the whole run is
    // densely sampled along each arc into one ordinary [StrokeOp]. View (= buffer) px.
    //
    // Invariant: while open, paMids.size == max(0, paAnchors.size - 1); when closed,
    // paMids.size == paAnchors.size (the last mid is the closing segment). paMids[i]
    // is the on-curve middle of the segment from paAnchors[i] to paAnchors[i+1]
    // (wrapping to paAnchors[0] for the closing segment).
    private val paAnchors = ArrayList<PointF>()
    private val paMids = ArrayList<PointF>()
    private var paClosed = false
    private var paFinished = false
    private var paMode = PaDrag.NONE
    private var paDragIndex = -1
    private var paPointerId = INVALID_POINTER
    private val paDragLast = PointF()
    private val paRotAnchors0 = ArrayList<PointF>()
    private val paRotMids0 = ArrayList<PointF>()
    private val paRotCenter = PointF()
    private var paRotStartAngle = 0f
    private var paLastTapTime = 0L
    private val paLastTapPos = PointF()
    private var paHoverPt: PointF? = null

    private enum class PaDrag { NONE, ANCHOR, MID, BODY, ROTATE }

    /** Whether an editable polyarc is placed (being built or edited) but unbaked. */
    val hasPendingPolyarc: Boolean get() = paAnchors.isNotEmpty()

    /** Fired when the editable polyarc appears (true) or is committed/cleared (false). */
    var onPolyarcChanged: ((Boolean) -> Unit)? = null

    /** Whether any editable shape (line, arc, polyline, polyarc) is pending — for the Done button. */
    val hasPendingShape: Boolean
        get() = hasPendingLine || hasPendingArc || hasPendingPolyline || hasPendingPolyarc

    /** Bakes whichever editable shape is pending (only ever one at a time). */
    fun commitPendingShape() {
        if (hasPendingLine) commitPendingLine()
        if (hasPendingArc) commitPendingArc()
        if (hasPendingPolyline) commitPendingPolyline()
        if (hasPendingPolyarc) commitPendingPolyarc()
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

    // --- Wet simulation (Phase 3) -------------------------------------------

    /** The wet sim's shader trio (water tick, pigment tick, present). */
    private val wetAgsl: Array<String>? by lazy {
        runCatching {
            arrayOf(R.raw.wet_water, R.raw.wet_pigment, R.raw.wet_present).map { res ->
                resources.openRawResource(res).bufferedReader().use { it.readText() }
            }.toTypedArray()
        }.getOrNull()
    }

    /** The LIVE wet sim (UI thread only). Kept across strokes; fields realloc in begin(). */
    private var wetSim: WetSim? = null

    /**
     * The stroke the live sim is simulating: the active watercolor stroke, or —
     * after pen-up, while [wetSettling] — the wash still spreading and drying.
     * Null when the ribbon fallback is in effect (no mixer / no shaders).
     */
    private var wetStroke: Stroke? = null
    private var wetStamped = 0
    private var wetSettling = false
    private var wetClip: Bitmap? = null

    /**
     * Finalized wet strokes waiting for their bake: drawn from a snapshot of the
     * composited crop exactly as the sim last showed it, so the wash neither
     * vanishes nor changes while the bake replays it into the paint layer.
     */
    private class WetPending(val stroke: Stroke, val snapshot: Bitmap, val crop: Rect)
    private val wetPending = mutableListOf<WetPending>()
    private val wetPendingPaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
    }

    /** The bake-side sim: one instance, lazily made and reused — bakes are serialized. */
    private var bakeWetSim: WetSim? = null

    /**
     * The live wall-clock tick: advance the fields one fixed step, RECORD the
     * stamp count into the stroke's schedule (that record is what the bake
     * replays), and keep going until a settling wash calms, then commit it.
     */
    private val wetTicker = object : Runnable {
        override fun run() {
            val sim = wetSim ?: return
            val stroke = wetStroke ?: return
            // Idle while there is nothing to move: before the first stamp, and
            // once the wash has calmed under a resting pen — otherwise a pen
            // held still would grow the schedule (and every later replay of it)
            // by thirty no-op ticks a second. Stamping resumes the ticking.
            if (wetStamped > 0 && !sim.isCalm) {
                sim.tick(ToothCache.rawFieldFor(surface), surface.toothScale * SUPER_SAMPLE)
                stroke.wetSchedule.add(wetStamped)
                invalidate()
            }
            if (wetSettling && sim.isCalm) finalizeWet() else postDelayed(this, WetSim.TICK_MS)
        }
    }

    // --- Live drying (the wash ages in place; dry == the baked look) --------

    /**
     * The watercolor stroke whose wash is still drying: each accepted point
     * records its birth time, and the live render maps age to a drying
     * progression (spread creeps outward, the rim deepens, the fill lightens
     * — see StrokeRenderer's dryness curves). The stroke's beginning is
     * already drying while the tail is still being laid; after pen-up it
     * stays live until the tail catches up, then bakes. Purely cosmetic and
     * live-only: the bake always renders fully dry, so the animation
     * converges to exactly what bakes — no recording, no replay.
     */
    private var dryingStroke: Stroke? = null
    private val dryingBorn = mutableListOf<Long>()
    private var dryingSettling = false

    /** Redraw pulse while any of the wash is wet; commits once all is dry. */
    private val dryingPulse = object : Runnable {
        override fun run() {
            if (dryingStroke == null) return
            invalidate()
            if (dryingSettling && dryingAllDry()) finalizeDrying() else postDelayed(this, DRY_PULSE_MS)
        }
    }

    private fun startDrying(stroke: Stroke) {
        dryingStroke = stroke
        dryingBorn.clear()
        dryingSettling = false
        removeCallbacks(dryingPulse)
        postDelayed(dryingPulse, DRY_PULSE_MS)
    }

    /** Points age in laid order, so the whole wash is dry when the youngest is. */
    private fun dryingAllDry(): Boolean {
        val last = dryingBorn.lastOrNull() ?: return true
        return android.os.SystemClock.uptimeMillis() - last >= DRY_MS
    }

    /**
     * Per-point drying progress for the live render, or null when [stroke]
     * isn't the drying one (everything else draws fully dry).
     */
    private fun liveWetness(stroke: Stroke): FloatArray? {
        if (stroke !== dryingStroke || dryingBorn.isEmpty()) return null
        val now = android.os.SystemClock.uptimeMillis()
        return FloatArray(stroke.points.size) { i ->
            if (i < dryingBorn.size) {
                ((now - dryingBorn[i]).toFloat() / DRY_MS).coerceIn(0f, 1f)
            } else {
                0f // transient predicted tail: just laid
            }
        }
    }

    /** Commits the dried (or interrupted — snaps dry) wash to the bake. */
    private fun finalizeDrying() {
        removeCallbacks(dryingPulse)
        val stroke = dryingStroke ?: return
        val clip = wetClip
        dryingStroke = null
        dryingSettling = false
        dryingBorn.clear()
        wetClip = null
        // A water stroke draws nothing itself, so between this commit and the
        // bake landing its whole effect would vanish for a few frames — the
        // end-of-drying flicker. Bridge the gap with a snapshot of what the
        // bake will produce (same recipe, same inputs), pruned once it lands.
        if (stroke.water) {
            runCatching { renderWaterSnapshot(stroke) }.getOrNull()?.let {
                wetPending.add(WetPending(stroke, it.second, it.first))
            }
        }
        unbaked.add(stroke)
        unbakedClips.add(clip)
        kickBake()
        invalidate()
    }

    /**
     * The fully-developed water effect over its crop — pixel-for-pixel what
     * [compositeWatercolorInto]'s water branch will bake, rendered against the
     * same committed paint (bakes are serialized, so the base cannot move
     * between this snapshot and that bake).
     */
    private fun renderWaterSnapshot(stroke: Stroke): Pair<Rect, Bitmap> {
        val paintLayer = paintBmp ?: error("no paint layer")
        val crop = watercolorCrop(stroke)
        val cw = crop.width()
        val ch = crop.height()
        val l = crop.left.toFloat()
        val t = crop.top.toFloat()
        val avgW = avgWidth(stroke) * SUPER_SAMPLE
        val soften = max(2f, avgW * 0.14f)
        val clearFeather = max(2f, avgW * 0.10f)
        val spread = max(4f, avgW * 0.30f) * WATER_SPREAD_SCALE
        val nodes = mutableListOf<RenderNode>()
        val bmp = GpuRender.renderToBitmap(cw, ch) { c ->
            nodes += recordWetBackdrop(
                c as android.graphics.RecordingCanvas, paintLayer,
                { m -> StrokeRenderer.paintWetMask(m, stroke, null) },
                cw, ch, SUPER_SAMPLE, clearFeather, spread, soften, l, t, WATER_DILUTE_ALPHA,
            )
        }
        nodes.forEach { it.discardDisplayList() }
        return crop to bmp
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
        reconfigureBuffers(w, h)
    }

    /**
     * (Re)creates the canvas buffers for a view of [w]×[h]. The buffer is sized to
     * the selected [canvasSize] (centred, at true physical size), not the whole view.
     * Resets the in-progress paint like a fresh sheet; the committed op history is
     * left intact ([applyCanvasSize] clears it separately when the size changes).
     */
    private fun reconfigureBuffers(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val (lw, lh) = canvasLogicalPx(w, h)
        logicalW = lw
        logicalH = lh
        canvasLeft = (w - lw) / 2
        canvasTop = (h - lh) / 2
        bufW = (lw * SUPER_SAMPLE).roundToInt()
        bufH = (lh * SUPER_SAMPLE).roundToInt()
        srcRect.set(0, 0, bufW, bufH)
        dstRect.set(0, 0, lw, lh)

        // The pickup trail is buffer-sized; drop it so it's reallocated to match.
        pickupBuf?.recycle()
        pickupBuf = null
        pickupCanvas = null

        val placeholder = createBitmap(bufW, bufH).apply { Canvas(this).drawColor(plainColor) }
        val newPaint = createBitmap(bufW, bufH)

        surfaceBmp?.recycle()
        paintBmp?.recycle()
        surfaceBmp = placeholder
        paintBmp = newPaint
        abortWet()
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

    /** Whether a canvas-local point falls on the sheet (not the surrounding mat). */
    private fun insideCanvas(x: Float, y: Float): Boolean =
        x >= 0f && y >= 0f && x <= logicalW.toFloat() && y <= logicalH.toFloat()

    /** Canvas size in view px for the current [canvasSize], clamped to the view. */
    private fun canvasLogicalPx(w: Int, h: Int): Pair<Int, Int> = when (val s = canvasSize) {
        is CanvasSize.FullScreen -> w to h
        is CanvasSize.Print -> {
            val ppi = Calibration.effectivePpi(context)
            val pw = Calibration.inToPx(s.wIn, ppi).roundToInt().coerceIn(1, w)
            val ph = Calibration.inToPx(s.hIn, ppi).roundToInt().coerceIn(1, h)
            pw to ph
        }
    }

    /**
     * Switches to [size] and starts a fresh document at the new dimensions. A
     * different-sized sheet can't share the old buffers, so the paint and the undo
     * history are cleared (fresh paper, new surface seed).
     */
    fun applyCanvasSize(size: CanvasSize) {
        canvasSize = size
        if (width <= 0 || height <= 0) return // onSizeChanged will apply it once laid out
        for (op in committed) op.recycle()
        committed.clear()
        clearRedo()
        surfaceSeed = java.util.Random().nextLong()
        reconfigureBuffers(width, height)
        onHistoryChanged?.invoke()
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
        // A settling wash commits under the old surface first; like every other
        // op it re-tooths to the new material when the history rebuilds.
        interruptWet()
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
        if (surfaceBmp == null) return
        val matted = canvasLeft > 0 || canvasTop > 0
        if (matted) drawMat(canvas)
        val sc = canvas.save()
        canvas.translate(canvasLeft.toFloat(), canvasTop.toFloat())
        canvas.clipRect(0f, 0f, logicalW.toFloat(), logicalH.toFloat())
        drawDocument(canvas)
        canvas.restoreToCount(sc)
        if (matted) drawCanvasFrame(canvas)
    }

    /** Fills the surround with the mat colour and drops a soft shadow under the sheet. */
    private fun drawMat(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), matPaint)
        val l = canvasLeft.toFloat()
        val t = canvasTop.toFloat()
        val r = (canvasLeft + logicalW).toFloat()
        val b = (canvasTop + logicalH).toFloat()
        // Cheap soft shadow: overlapping translucent frames, darkest against the sheet.
        for (i in 6 downTo 1) {
            val g = i * 2f
            canvas.drawRect(l - g, t - g, r + g, b + g, shadowPaint)
        }
    }

    /** A hairline border so a near-white sheet still reads against the mat. */
    private fun drawCanvasFrame(canvas: Canvas) {
        canvas.drawRect(
            canvasLeft + 0.5f, canvasTop + 0.5f,
            canvasLeft + logicalW - 0.5f, canvasTop + logicalH - 0.5f, framePaint,
        )
    }

    private fun drawDocument(canvas: Canvas) {
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
        val liveClip = activeClip ?: wetClip ?: unbakedClips.lastOrNull { it != null }
        val hasEdits = unbaked.isNotEmpty() || active != null || wetSettling || dryingSettling

        // Draws the edited paint: the committed layer plus the pending strokes. A
        // live watercolor stroke re-wets the paint under it directly on this
        // hardware canvas (dilute + push + multiply the wash), so the wet bleed
        // shows under the pen with no off-thread readback. A settling wash (pen
        // lifted, still spreading) renders exactly the same way until it calms.
        fun drawEdited() {
            val a = active
            if (a != null && a.tool == Tool.WATERCOLOR) {
                withTransientPoint(a, predictedTailPoint()) { drawWetLive(canvas, paintLayer, a) }
                return
            }
            val settling = when {
                wetSettling -> wetStroke
                dryingSettling -> dryingStroke
                else -> null
            }
            if (a == null && settling != null) {
                drawWetLive(canvas, paintLayer, settling)
                return
            }
            canvas.drawBitmap(paintLayer, srcRect, dstRect, null)
            drawWetPending(canvas)
            for (s in unbaked) {
                if (wetPending.none { it.stroke === s }) drawLiveStroke(canvas, s, isActive = false)
            }
            a?.let { drawLiveStroke(canvas, it, isActive = true) }
        }

        // Isolated layer so an eraser stroke (CLEAR) punches through to the surface
        // rather than the window behind the view.
        val layer = canvas.saveLayer(0f, 0f, logicalW.toFloat(), logicalH.toFloat(), null)
        if (liveClip == null || !hasEdits) {
            drawEdited()
        } else {
            // Frisket: committed paint outside the selection, the edited result
            // inside — `committed*(1-m) + edited*m` — so live strokes (and the
            // eraser) can't spill past the boundary.
            val mSrc = Rect(0, 0, liveClip.width, liveClip.height)
            val punch = canvas.saveLayer(0f, 0f, logicalW.toFloat(), logicalH.toFloat(), null)
            canvas.drawBitmap(paintLayer, srcRect, dstRect, null)
            canvas.drawBitmap(liveClip, mSrc, dstRect, dstOutPaint)
            canvas.restoreToCount(punch)
            val inside = canvas.saveLayer(0f, 0f, logicalW.toFloat(), logicalH.toFloat(), null)
            drawEdited()
            canvas.drawBitmap(liveClip, mSrc, dstRect, dstInPaint)
            canvas.restoreToCount(inside)
        }
        canvas.restoreToCount(layer)

        drawPendingLine(canvas)
        drawPendingArc(canvas)
        drawPendingPolyline(canvas)
        drawPendingPolyarc(canvas)
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

    /** Previews the editable polyline (as it will bake) plus its handles. */
    private fun drawPendingPolyline(canvas: Canvas) {
        if (polyPts.isEmpty()) return
        StrokeRenderer.paintStroke(canvas, buildPolyStroke(), surface)
        // While still tapping out anchors, rubber-band the next segment to the pen.
        if (!polyFinished) polyHoverPt?.let { drawPolyRubberBand(canvas, polyPts.last(), it) }
        drawPolyHandles(canvas)
    }

    /** Dashed preview of the next segment; rings the first anchor if it'd close. */
    private fun drawPolyRubberBand(canvas: Canvas, from: PointF, to: PointF) {
        val dash = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.6f; pathEffect = dash
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.4f; pathEffect = dash
        }
        canvas.drawLine(from.x, from.y, to.x, to.y, white)
        canvas.drawLine(from.x, from.y, to.x, to.y, black)
        if (polyPts.size >= 3) {
            val first = polyPts.first()
            if (hypot(to.x - first.x, to.y - first.y) <= POLY_CLOSE_DIST) {
                canvas.drawCircle(first.x, first.y, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF3B82F6.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.4f
                })
            }
        }
    }

    /** Square handles at every anchor; a rotate stalk off the top once finished. */
    private fun drawPolyHandles(canvas: Canvas) {
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.6f
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDD000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.4f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (polyFinished) {
            val c = polyCenter()
            val rot = polyRotateHandle()
            canvas.drawLine(c.x, c.y, rot.x, rot.y, white)
            canvas.drawLine(c.x, c.y, rot.x, rot.y, black)
            canvas.drawCircle(rot.x, rot.y, 7f, fill)
            canvas.drawCircle(rot.x, rot.y, 7f, black)
        }
        for (a in polyPts) {
            canvas.drawRect(a.x - 6, a.y - 6, a.x + 6, a.y + 6, fill)
            canvas.drawRect(a.x - 6, a.y - 6, a.x + 6, a.y + 6, black)
        }
    }

    /** Previews the editable polyarc (as it will bake) plus its handles. */
    private fun drawPendingPolyarc(canvas: Canvas) {
        if (paAnchors.isEmpty()) return
        StrokeRenderer.paintStroke(canvas, buildPolyarcStroke(), surface)
        if (!paFinished) paHoverPt?.let { drawPolyarcRubberBand(canvas, paAnchors.last(), it) }
        drawPolyarcHandles(canvas)
    }

    /** Dashed preview of the next segment; rings the first anchor if it'd close. */
    private fun drawPolyarcRubberBand(canvas: Canvas, from: PointF, to: PointF) {
        val dash = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.6f; pathEffect = dash
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.4f; pathEffect = dash
        }
        canvas.drawLine(from.x, from.y, to.x, to.y, white)
        canvas.drawLine(from.x, from.y, to.x, to.y, black)
        if (paAnchors.size >= 3) {
            val first = paAnchors.first()
            if (hypot(to.x - first.x, to.y - first.y) <= POLY_CLOSE_DIST) {
                canvas.drawCircle(first.x, first.y, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF3B82F6.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.4f
                })
            }
        }
    }

    /** Square handles at anchors, diamond handles at the arc middles, rotate stalk when finished. */
    private fun drawPolyarcHandles(canvas: Canvas) {
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.6f
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDD000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.4f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (paFinished) {
            val c = polyarcCenter()
            val rot = polyarcRotateHandle()
            canvas.drawLine(c.x, c.y, rot.x, rot.y, white)
            canvas.drawLine(c.x, c.y, rot.x, rot.y, black)
            canvas.drawCircle(rot.x, rot.y, 7f, fill)
            canvas.drawCircle(rot.x, rot.y, 7f, black)
        }
        // Diamonds first so the square vertex handles sit on top where they meet.
        for (m in paMids) {
            val diamond = Path().apply {
                moveTo(m.x, m.y - 7f); lineTo(m.x + 7f, m.y)
                lineTo(m.x, m.y + 7f); lineTo(m.x - 7f, m.y); close()
            }
            canvas.drawPath(diamond, fill)
            canvas.drawPath(diamond, black)
        }
        for (a in paAnchors) {
            canvas.drawRect(a.x - 6, a.y - 6, a.x + 6, a.y + 6, fill)
            canvas.drawRect(a.x - 6, a.y - 6, a.x + 6, a.y + 6, black)
        }
    }

    /**
     * Draws one pending stroke in the live preview. The active spray and brush
     * strokes use the incremental [activeAccum] (append-only, live tail drawn
     * fresh) to stay fast on long strokes; everything else renders straight.
     */
    private fun drawLiveStroke(canvas: Canvas, s: Stroke, isActive: Boolean) {
        if (isActive && activeAccum != null) {
            // Accumulate REAL points first; the predicted point only ever rides
            // the tail drawn fresh this frame, never the accumulator.
            when (s.tool) {
                Tool.SPRAY -> {
                    ensureSprayAccum(s)
                    withTransientPoint(s, predictedTailPoint()) {
                        blitAccum(canvas, s) { c ->
                            if (s.points.size > accumDrawn) {
                                StrokeRenderer.appendSoftSegments(c, s, accumDrawn)
                            }
                        }
                    }
                    return
                }
                Tool.BRUSH -> {
                    val layout = bristleLayout
                        ?: com.symmetricalpalmtree.paintsprout.paint.BristleLayout(s, activeMaxWidth)
                            .also { bristleLayout = it }
                    activeAccum?.let { accum ->
                        accumDrawn = StrokeRenderer.appendBristleSegments(Canvas(accum), s, layout, accumDrawn)
                    }
                    val predicted = predictedTailPoint()
                    withTransientPoint(s, predicted) {
                        blitAccum(canvas, s) {
                            StrokeRenderer.drawBristleLiveTail(it, s, layout, tailPoints = if (predicted != null) 3 else 2)
                        }
                    }
                    return
                }
                else -> {}
            }
        }
        if (isActive) {
            withTransientPoint(s, predictedTailPoint()) { StrokeRenderer.paintStroke(canvas, s, surface) }
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

    /**
     * Blits the accumulated stroke (plus its live tail, if any) with the tool's
     * opacity and the surface tooth — inside a layer clipped to the stroke's
     * padded bounds, not the whole canvas, so the per-frame cost follows the
     * stroke's size.
     */
    private fun blitAccum(canvas: Canvas, stroke: Stroke, liveTail: ((Canvas) -> Unit)?) {
        val accum = activeAccum ?: return
        val profile = com.symmetricalpalmtree.paintsprout.paint.ToolProfile.of(stroke.tool)
        val tooth = ToothCache.toothFor(surface, stroke.tool)
        val pad = activeMaxWidth / 2f + activeMaxWidth * profile.blurFactor * 3f + 8f
        val bounds = RectF(activeBounds).apply {
            inset(-pad, -pad)
            intersect(0f, 0f, logicalW.toFloat(), logicalH.toFloat())
        }
        val lp = Paint().apply { alpha = (profile.opacity * 255f).roundToInt().coerceIn(0, 255) }
        val layer = canvas.saveLayer(bounds, lp)
        canvas.drawBitmap(accum, 0f, 0f, null)
        liveTail?.invoke(canvas)
        if (tooth != null) {
            com.symmetricalpalmtree.paintsprout.paint.applyTooth(canvas, bounds, tooth, surface.toothScale)
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
            StrokeRenderer.paintStroke(canvas, stroke, surface, liveWetness(stroke))
            return
        }
        // Everything below happens in a crop around the stroke, not the buffer.
        // With the wet sim running, the wash IS the simulated field and the crop
        // is the sim's; the ribbon wash only remains as the no-GPU fallback.
        val sim = wetSim
        val simLive = sim != null && stroke === wetStroke && wetStamped > 0
        val crop = if (simLive) sim!!.crop else watercolorCrop(stroke)
        val cw = crop.width()
        val ch = crop.height()
        if (cw <= 0 || ch <= 0) {
            canvas.drawBitmap(paintLayer, srcRect, dstRect, null)
            return
        }
        val l = crop.left.toFloat()
        val t = crop.top.toFloat()

        val washShader: Shader?
        val b: RectF
        if (simLive) {
            washShader = sim!!.presentShader()
            // The wash can have bloomed anywhere the field covers.
            b = RectF(crop)
        } else {
            washShader = if (stroke.water) {
                null // clean water lays no wash; the backdrop IS the effect
            } else {
                washFlip = washFlip xor 1
                val wash = scratchFor(washScratch, washFlip, cw, ch)
                Canvas(wash).apply {
                    drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    save()
                    translate(-l, -t)
                    scale(SUPER_SAMPLE, SUPER_SAMPLE)
                    StrokeRenderer.paintStroke(this, stroke, surface, liveWetness(stroke))
                    restore()
                }
                BitmapShader(wash, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setTranslate(l, t) })
                }
            }
            val avgW = avgWidth(stroke) * SUPER_SAMPLE
            val soften = max(2f, avgW * 0.14f)
            val spread = max(4f, avgW * 0.30f) * if (stroke.water) WATER_SPREAD_SCALE else 1f
            b = RectF()
            strokeRegionPath(stroke).computeBounds(b, true)
            // Pad past the stroke to include the bloom that spreads beyond the
            // ribbon, else the SRC rect below clips the soft bleed into a hard
            // edge — but stay inside the crop, where the clamped shaders above
            // are actually valid.
            b.inset(-(avgW + spread + soften + 4f), -(avgW + spread + soften + 4f))
            b.intersect(RectF(crop))
        }

        val backdropShader = wetBackdropShaderFor(paintLayer, stroke, crop)

        canvas.drawBitmap(paintLayer, srcRect, dstRect, null)
        drawWetPending(canvas)

        if (washShader == null) {
            // Water mode: the diluted-and-pushed paint replaces the crop region
            // directly — the stroke draws nothing of its own (eraser-like); its
            // age-weighted mask inside the backdrop grows toward what bakes.
            canvas.drawRect(b.left, b.top, b.right, b.bottom, Paint().apply {
                shader = backdropShader
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
            })
            return
        }

        mixer.setInputShader("uBackdrop", backdropShader)
        mixer.setInputShader("uWash", washShader)
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

    /**
     * Backdrop for the mixer: the wet-interacted paint at half res, or — if the
     * GPU read-back fails — the raw paint layer (still correct colour, no bleed).
     * The readback is the live path's single most expensive step, and the
     * backdrop is blurred context under the wash — so it's rebuilt only every
     * few points (or when the crop moves) and the shader reused in between.
     */
    private fun wetBackdropShaderFor(paintLayer: Bitmap, stroke: Stroke, crop: Rect): Shader {
        val cw = crop.width()
        val ch = crop.height()
        val cropKey = (crop.left.toLong() shl 42) xor (crop.top.toLong() shl 21) xor
            (cw.toLong() shl 11) xor ch.toLong()
        val cached = wetBackdropShader
        // Pigmented washes rebuild on a point stride (blurred context, brief
        // staleness invisible). Water strokes ARE the backdrop, and their mask
        // ages continuously — rebuild on a finer point stride and on TIME, so
        // the wash-out grows smoothly instead of popping in steps.
        val now = android.os.SystemClock.uptimeMillis()
        val fresh = if (stroke.water) {
            stroke.points.size - wetBackdropAtPoints < WATER_BACKDROP_STRIDE &&
                now - wetBackdropAtMs < WATER_BACKDROP_STRIDE_MS
        } else {
            stroke.points.size - wetBackdropAtPoints < WET_BACKDROP_STRIDE
        }
        if (cached != null && cropKey == wetBackdropKey && fresh) {
            return cached
        }
        val l = crop.left.toFloat()
        val t = crop.top.toFloat()
        val avgW = avgWidth(stroke) * SUPER_SAMPLE
        val soften = max(2f, avgW * 0.14f)
        val clearFeather = max(2f, avgW * 0.10f)
        // Clean water re-wets harder than a pigmented wash: it pushes the paint
        // further and leaves less of it standing where it flooded.
        val spread = max(4f, avgW * 0.30f) * if (stroke.water) WATER_SPREAD_SCALE else 1f
        val dilute = if (stroke.water) WATER_DILUTE_ALPHA else WET_DILUTE_ALPHA
        val drawMask: (Canvas) -> Unit = if (stroke.water) {
            val wetness = liveWetness(stroke)
            ;
            { c -> StrokeRenderer.paintWetMask(c, stroke, wetness) }
        } else {
            val region = strokeRegionPath(stroke)
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            ;
            { c -> c.drawPath(region, white) }
        }
        val hw = cw / 2
        val hh = ch / 2
        wetLiveFlip = wetLiveFlip xor 1
        val wetHalf = scratchFor(wetLiveScratch, wetLiveFlip, hw, hh)
        val nodes = mutableListOf<RenderNode>()
        val shader = runCatching {
            GpuRender.renderInto(wetHalf) { rc ->
                nodes += recordWetBackdrop(
                    rc as android.graphics.RecordingCanvas, paintLayer, drawMask, hw, hh,
                    0.5f, clearFeather * 0.5f, spread * 0.5f, soften * 0.5f, l, t, dilute,
                )
            }
            BitmapShader(wetHalf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(2f, 2f); postTranslate(l, t) })
            }
        }.getOrElse {
            BitmapShader(paintLayer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        nodes.forEach { it.discardDisplayList() }
        wetBackdropShader = shader
        wetBackdropAtPoints = stroke.points.size
        wetBackdropAtMs = now
        wetBackdropKey = cropKey
        return shader
    }

    /**
     * Draws the finalized-but-unbaked washes from their snapshots. SRC, because
     * a snapshot is the full composited crop (backdrop where there is no wash) —
     * over-compositing would double the backdrop. Where a LIVE wash's mixer rect
     * later overlaps one of these crops the pending wash briefly disappears
     * under it (the live backdrop reads only the committed paint); the bake
     * restores the true stacking within a frame or two.
     */
    private fun drawWetPending(canvas: Canvas) {
        for (p in wetPending) {
            val dst = RectF(
                p.crop.left / SUPER_SAMPLE, p.crop.top / SUPER_SAMPLE,
                p.crop.right / SUPER_SAMPLE, p.crop.bottom / SUPER_SAMPLE,
            )
            canvas.drawBitmap(p.snapshot, null, dst, wetPendingPaint)
        }
    }

    /** Puts the live sim on [stroke]; fields start at the first accepted stamp. */
    private fun startWetSim(stroke: Stroke) {
        // User verdict on the Movink (2026-07-18): this first wiring reads as
        // airbrush fog, not watercolor, and the per-tick GPU readbacks lag the
        // UI thread badly (worse with brush size). Gated OFF — the tool uses
        // the ribbon path — until the sim is GPU-resident, ticked off the UI
        // thread, and deposits a flat surface-tension wash, tuned against the
        // ribbon look as the baseline.
        if (!WET_SIM_ENABLED) return
        val agsl = wetAgsl ?: return
        if (pigmentShader == null) return // no mixer — ribbon fallback end to end
        if (wetSim == null) wetSim = WetSim(agsl[0], agsl[1], agsl[2])
        wetStroke = stroke
        wetStamped = 0
        wetSettling = false
        removeCallbacks(wetTicker)
        postDelayed(wetTicker, WetSim.TICK_MS)
    }

    /** The live sim crop: [activeBounds] padded like [watercolorCrop], quantized. */
    private fun liveWetCrop(): Rect {
        val pad = (activeMaxWidth / 2f + activeMaxWidth * 1.5f + 16f) * SUPER_SAMPLE
        return quantizedCrop(
            activeBounds.left * SUPER_SAMPLE - pad, activeBounds.top * SUPER_SAMPLE - pad,
            activeBounds.right * SUPER_SAMPLE + pad, activeBounds.bottom * SUPER_SAMPLE + pad,
        )
    }

    /**
     * Commits the settled (or interrupted) wash: freeze the recorded schedule
     * and final crop into the stroke, snapshot the composited crop exactly as
     * shown (drawn until the bake lands), and hand the stroke to the bake.
     */
    private fun finalizeWet() {
        removeCallbacks(wetTicker)
        val sim = wetSim
        val stroke = wetStroke ?: return
        val clip = wetClip
        wetStroke = null
        wetSettling = false
        wetClip = null
        if (sim != null && wetStamped > 0) {
            stroke.wetCrop = Rect(sim.crop)
            runCatching { renderWetSnapshot(sim, stroke) }.getOrNull()?.let {
                wetPending.add(WetPending(stroke, it, Rect(sim.crop)))
            }
        }
        wetStamped = 0
        unbaked.add(stroke)
        unbakedClips.add(clip)
        kickBake()
        invalidate()
    }

    /**
     * Cuts a still-settling wash short so something else (a new stroke, undo, a
     * surface change) can proceed against a committed history. The wash stops
     * spreading right where the screen showed it — the schedule already recorded
     * is exactly what bakes, so nothing visibly changes.
     */
    private fun interruptWet() {
        if (wetSettling) finalizeWet()
        if (dryingSettling) finalizeDrying()
    }

    /** Discards the live sim AND the pending snapshots (canvas is being reset). */
    private fun abortWet() {
        removeCallbacks(wetTicker)
        removeCallbacks(dryingPulse)
        wetStroke = null
        wetSettling = false
        wetStamped = 0
        dryingStroke = null
        dryingSettling = false
        dryingBorn.clear()
        wetClip?.recycle()
        wetClip = null
        for (p in wetPending) p.snapshot.recycle()
        wetPending.clear()
    }

    /** Drops pending snapshots whose strokes have been baked into the paint. */
    private fun pruneWetPending() {
        if (wetPending.isEmpty()) return
        val it = wetPending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (unbaked.none { u -> u === p.stroke }) {
                p.snapshot.recycle()
                it.remove()
            }
        }
    }

    /** The composited crop (backdrop + presented wash) as the screen shows it. */
    private fun renderWetSnapshot(sim: WetSim, stroke: Stroke): Bitmap {
        val paintLayer = paintBmp ?: error("no paint layer")
        val mixer = pigmentShader ?: error("no mixer")
        val crop = sim.crop
        mixer.setInputShader("uBackdrop", wetBackdropShaderFor(paintLayer, stroke, crop))
        mixer.setInputShader("uWash", sim.presentShader())
        mixer.setFloatUniform("uWashGain", 1f)
        mixer.setFloatUniform("uDarkHold", 0f)
        val paint = Paint().apply { shader = mixer }
        val l = crop.left.toFloat()
        val t = crop.top.toFloat()
        return GpuRender.renderToBitmap(crop.width(), crop.height()) { c ->
            c.translate(-l, -t)
            c.drawRect(l, t, crop.right.toFloat(), crop.bottom.toFloat(), paint)
        }
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
                    setScale(logicalW.toFloat() / mask.width, logicalH.toFloat() / mask.height)
                })
            }
            shader.setInputShader("uMask", bmpShader)
            shader.setFloatUniform("uTime", antsPhase)
            canvas.drawRect(0f, 0f, logicalW.toFloat(), logicalH.toFloat(), Paint().apply { this.shader = shader })
            return
        }
        // Fallback: dim outside + faint tint.
        val mSrc = Rect(0, 0, mask.width, mask.height)
        val dim = canvas.saveLayer(0f, 0f, logicalW.toFloat(), logicalH.toFloat(), null)
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
        // Work in canvas-local coordinates: (0,0) is the sheet's top-left, so every
        // handler below is oblivious to the mat/centring offset.
        event.offsetLocation(-canvasLeft.toFloat(), -canvasTop.toFloat())
        // Feed the predictor canvas-local events; predictions come back in kind.
        motionPredictor.record(event)
        // Stylus events arrive batched to vsync by default; ask for immediate
        // delivery while drawing — up to a frame less input latency.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Ignore presses that land in the mat around the sheet.
                if (!insideCanvas(event.getX(actionIndex), event.getY(actionIndex))) return true
                // A new mark cuts a still-settling wash short (committed as shown).
                interruptWet()
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
                if (tool == Tool.POLYLINE) {
                    handlePolyDown(event, actionIndex)
                    return true
                }
                if (tool == Tool.POLYARC) {
                    handlePolyarcDown(event, actionIndex)
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
                // Paint is spent over distance travelled; a new stroke starts
                // from its own first point, not wherever the last one ended.
                lastDepositPos = null
                beginConditioning(
                    event.getPressure(actionIndex),
                    event.getAxisValue(MotionEvent.AXIS_TILT, actionIndex),
                )
                val stroke = Stroke(
                    tool, color, seed = Random.nextInt(), baseWidth = sizeFor(tool),
                    water = tool == Tool.WATERCOLOR && waterMode,
                )
                active = stroke
                if (tool == Tool.WATERCOLOR) {
                    startWetSim(stroke)
                    startDrying(stroke)
                }
                // Extras first: the first sample stamps the pickup trail, which
                // beginActiveExtras would otherwise immediately erase.
                beginActiveExtras()
                addSample(
                    stroke, event.getX(actionIndex), event.getY(actionIndex),
                    event.getPressure(actionIndex), event.getAxisValue(MotionEvent.AXIS_TILT, actionIndex),
                )
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
                if (tool == Tool.POLYLINE) {
                    handlePolyMove(event)
                    return true
                }
                if (tool == Tool.POLYARC) {
                    handlePolyarcMove(event)
                    return true
                }
                val stroke = active ?: return true
                val pi = event.findPointerIndex(activePointerId)
                if (pi < 0) return true
                for (h in 0 until event.historySize) {
                    addSample(
                        stroke, event.getHistoricalX(pi, h), event.getHistoricalY(pi, h),
                        event.getHistoricalPressure(pi, h),
                        event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pi, h),
                    )
                }
                addSample(
                    stroke, event.getX(pi), event.getY(pi),
                    event.getPressure(pi), event.getAxisValue(MotionEvent.AXIS_TILT, pi),
                )
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
                if (tool == Tool.POLYLINE) {
                    handlePolyUp(event, actionIndex)
                    return true
                }
                if (tool == Tool.POLYARC) {
                    handlePolyarcUp(event, actionIndex)
                    return true
                }
                val stroke = active
                if (stroke != null && event.getPointerId(actionIndex) == activePointerId) {
                    if (stroke === wetStroke) {
                        // The wash stays live past pen-up: it keeps spreading and
                        // drying under the ticker, and commits once it calms (or
                        // is interrupted). The clip rides along until then.
                        wetClip = activeClip
                        wetSettling = true
                    } else if (stroke === dryingStroke) {
                        // The wash stays live until its tail finishes drying,
                        // then commits — the dried render IS the baked render.
                        wetClip = activeClip
                        dryingSettling = true
                    } else {
                        unbaked.add(stroke)
                        unbakedClips.add(activeClip)
                        kickBake()
                    }
                    active = null
                    activeClip = null
                    activePointerId = INVALID_POINTER
                    endActiveExtras()
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    /**
     * Stylus hover: while tapping out a polyline / polyarc, track the hovering pen
     * so the next segment can be previewed as a rubber band from the last anchor.
     * Only meaningful for an unfinished run of the active tool; ignored otherwise.
     */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val building = when (tool) {
            Tool.POLYLINE -> !polyFinished && polyPts.isNotEmpty()
            Tool.POLYARC -> !paFinished && paAnchors.isNotEmpty()
            else -> false
        }
        if (!building || !isStylus(event.getToolType(event.actionIndex))) {
            if (polyHoverPt != null) { polyHoverPt = null; invalidate() }
            if (paHoverPt != null) { paHoverPt = null; invalidate() }
            return super.onHoverEvent(event)
        }
        event.offsetLocation(-canvasLeft.toFloat(), -canvasTop.toFloat()) // canvas-local
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (tool == Tool.POLYLINE) polyHoverPt = PointF(event.x, event.y)
                else paHoverPt = PointF(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                polyHoverPt = null
                paHoverPt = null
                invalidate()
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

    // --- Polyline-tool gestures ---------------------------------------------

    private fun handlePolyDown(e: MotionEvent, ai: Int) {
        val p = PointF(e.getX(ai), e.getY(ai))
        val now = e.eventTime
        if (polyFinished) {
            // Editing a finished run: grab a handle, else bake it and start anew.
            val mode = hitPolyline(p)
            if (mode != null) {
                polyPointerId = e.getPointerId(ai)
                polyMode = mode
                polyDragLast.set(p)
                if (mode == PolyDrag.ROTATE) beginPolyRotate(p)
                return
            }
            commitPendingPolyline() // clears state; fall through to a fresh run.
        }
        if (polyPts.isEmpty()) {
            plantPolyAnchor(p, e.getPointerId(ai), now, first = true)
            return
        }
        // Second tap of a finishing double-tap? (near the last anchor, in time.)
        val dt = now - polyLastTapTime
        if (dt in 1..POLY_DOUBLE_MS &&
            hypot(p.x - polyLastTapPos.x, p.y - polyLastTapPos.y) <= POLY_TAP_SLOP
        ) {
            finishPolyline()
            return
        }
        plantPolyAnchor(p, e.getPointerId(ai), now, first = false)
    }

    /** Adds an anchor at [p] and grabs it so a same-gesture drag can nudge it. */
    private fun plantPolyAnchor(p: PointF, pointerId: Int, now: Long, first: Boolean) {
        if (first) {
            polyFinished = false
            polyClosed = false
            onPolylineChanged?.invoke(true)
        }
        polyPts.add(PointF(p.x, p.y))
        polyPointerId = pointerId
        polyMode = PolyDrag.ANCHOR
        polyDragIndex = polyPts.size - 1
        polyDragLast.set(p)
        polyLastTapTime = now
        polyLastTapPos.set(p)
        polyHoverPt = null
        invalidate()
    }

    private fun handlePolyMove(e: MotionEvent) {
        if (polyPointerId == INVALID_POINTER) return
        val pi = e.findPointerIndex(polyPointerId)
        if (pi < 0) return
        val p = PointF(e.getX(pi), e.getY(pi))
        when (polyMode) {
            PolyDrag.ANCHOR -> {
                polyPts.getOrNull(polyDragIndex)?.set(p.x, p.y)
                invalidate()
            }
            PolyDrag.BODY -> {
                val dx = p.x - polyDragLast.x
                val dy = p.y - polyDragLast.y
                for (a in polyPts) a.offset(dx, dy)
                polyDragLast.set(p)
                invalidate()
            }
            PolyDrag.ROTATE -> {
                val ang = atan2(p.y - polyRotCenter.y, p.x - polyRotCenter.x)
                val d = ang - polyRotStartAngle
                for (i in polyPts.indices) {
                    polyPts[i].set(rotatePoint(polyRot0[i], polyRotCenter, d))
                }
                invalidate()
            }
            PolyDrag.NONE -> {}
        }
    }

    private fun handlePolyUp(e: MotionEvent, ai: Int) {
        if (e.getPointerId(ai) != polyPointerId) return
        polyPointerId = INVALID_POINTER
        // While tapping out anchors, remember where the newest one settled so the
        // finishing double-tap is measured against its final (possibly nudged) pos.
        if (!polyFinished && polyMode == PolyDrag.ANCHOR) {
            polyPts.lastOrNull()?.let { polyLastTapPos.set(it.x, it.y) }
        }
        polyMode = PolyDrag.NONE
        polyDragIndex = -1
        invalidate()
    }

    /** Captures the anchors + centre for a rotate drag. */
    private fun beginPolyRotate(p: PointF) {
        val c = polyCenter()
        polyRotCenter.set(c)
        polyRot0.clear()
        for (a in polyPts) polyRot0.add(PointF(a.x, a.y))
        polyRotStartAngle = atan2(p.y - c.y, p.x - c.x)
    }

    /**
     * Ends anchor placement and enters edit mode. Closes the loop if the final
     * anchor landed on the first; drops a degenerate (< 2-point) run.
     */
    private fun finishPolyline() {
        polyPointerId = INVALID_POINTER
        polyMode = PolyDrag.NONE
        polyHoverPt = null
        if (polyPts.size >= 3) {
            val first = polyPts.first()
            val last = polyPts.last()
            if (hypot(last.x - first.x, last.y - first.y) <= POLY_CLOSE_DIST) {
                polyPts.removeAt(polyPts.size - 1) // drop the near-duplicate close tap
                if (polyPts.size >= 3) polyClosed = true
            }
        }
        if (polyPts.size < 2) {
            discardPendingPolyline()
            return
        }
        polyFinished = true
        invalidate()
    }

    /** Which part of the finished polyline [p] grabs, or null if it misses. */
    private fun hitPolyline(p: PointF): PolyDrag? {
        if (polyPts.isEmpty()) return null
        val rot = polyRotateHandle()
        if (hypot(p.x - rot.x, p.y - rot.y) <= HANDLE_HIT) return PolyDrag.ROTATE
        for (i in polyPts.indices) {
            val a = polyPts[i]
            if (hypot(p.x - a.x, p.y - a.y) <= HANDLE_HIT) {
                polyDragIndex = i
                return PolyDrag.ANCHOR
            }
        }
        val bodyHit = max(BODY_HIT, sizeFor(Tool.POLYLINE) / 2f + 8f)
        val edges = polyEdges()
        for (i in 1 until edges.size) {
            if (distToSegment(p, edges[i - 1], edges[i]) <= bodyHit) return PolyDrag.BODY
        }
        return null
    }

    /** The anchors, with the first appended when the run is closed (for edges). */
    private fun polyEdges(): List<PointF> =
        if (polyClosed && polyPts.size >= 2) polyPts + polyPts.first() else polyPts

    private fun polyBounds(): RectF {
        val f = polyPts.first()
        val r = RectF(f.x, f.y, f.x, f.y)
        for (a in polyPts) {
            r.left = min(r.left, a.x); r.top = min(r.top, a.y)
            r.right = max(r.right, a.x); r.bottom = max(r.bottom, a.y)
        }
        return r
    }

    private fun polyCenter(): PointF {
        val b = polyBounds()
        return PointF((b.left + b.right) / 2f, (b.top + b.bottom) / 2f)
    }

    /** Rotate handle: centred above the run's bounding box. */
    private fun polyRotateHandle(): PointF {
        val b = polyBounds()
        return PointF((b.left + b.right) / 2f, b.top - LINE_ROT_OFFSET)
    }

    /**
     * Densely samples every segment (plus the closing one when looped) so the
     * baked stroke's corners stay crisp through [smoothPath]'s midpoint rounding.
     */
    private fun polySamples(): List<PointF> {
        val edges = polyEdges()
        if (edges.size < 2) return edges.map { PointF(it.x, it.y) }
        val out = ArrayList<PointF>()
        out.add(PointF(edges[0].x, edges[0].y))
        for (i in 1 until edges.size) {
            val a = edges[i - 1]
            val b = edges[i]
            val len = hypot(b.x - a.x, b.y - a.y)
            val n = max(1, ceil(len / POLY_SAMPLE).toInt())
            for (k in 1..n) {
                val t = k.toFloat() / n
                out.add(PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        return out
    }

    /** A densely-sampled [Stroke] for the current polyline anchors. */
    private fun buildPolyStroke(): Stroke {
        val width = resolveWidth(Tool.POLYLINE, sizeFor(Tool.POLYLINE), 1f, 0f)
        return Stroke(Tool.POLYLINE, strokeColor, seed = Random.nextInt()).apply {
            for (p in polySamples()) add(StrokePoint(Vec2(p.x, p.y), width))
        }
    }

    /** Bakes the pending polyline as a [StrokeOp] (or drops it if degenerate). */
    fun commitPendingPolyline() {
        if (polyPts.size < 2) {
            discardPendingPolyline()
            return
        }
        val stroke = buildPolyStroke()
        resetPolyState()
        onPolylineChanged?.invoke(false)
        invalidate()
        scope.launch { applyCommittedOp(StrokeOp(stroke)) }
    }

    private fun discardPendingPolyline() {
        val had = polyPts.isNotEmpty()
        resetPolyState()
        if (had) onPolylineChanged?.invoke(false)
        invalidate()
    }

    private fun resetPolyState() {
        polyPts.clear()
        polyRot0.clear()
        polyClosed = false
        polyFinished = false
        polyMode = PolyDrag.NONE
        polyDragIndex = -1
        polyPointerId = INVALID_POINTER
        polyHoverPt = null
        polyLastTapTime = 0L
    }

    // --- Polyarc-tool gestures ----------------------------------------------

    private fun handlePolyarcDown(e: MotionEvent, ai: Int) {
        val p = PointF(e.getX(ai), e.getY(ai))
        val now = e.eventTime
        if (paFinished) {
            // Editing a finished run: grab a handle, else bake it and start anew.
            val mode = hitPolyarc(p)
            if (mode != null) {
                paPointerId = e.getPointerId(ai)
                paMode = mode
                paDragLast.set(p)
                if (mode == PaDrag.ROTATE) beginPolyarcRotate(p)
                return
            }
            commitPendingPolyarc() // clears state; fall through to a fresh run.
        }
        if (paAnchors.isEmpty()) {
            plantPolyarcAnchor(p, e.getPointerId(ai), now, first = true)
            return
        }
        // Second tap of a finishing double-tap? (near the last anchor, in time.)
        val dt = now - paLastTapTime
        if (dt in 1..POLY_DOUBLE_MS &&
            hypot(p.x - paLastTapPos.x, p.y - paLastTapPos.y) <= POLY_TAP_SLOP
        ) {
            finishPolyarc()
            return
        }
        // Grab a segment's middle handle to bend it live (before placing the next).
        val mid = hitPolyarcMid(p)
        if (mid >= 0) {
            paPointerId = e.getPointerId(ai)
            paMode = PaDrag.MID
            paDragIndex = mid
            paDragLast.set(p)
            return
        }
        plantPolyarcAnchor(p, e.getPointerId(ai), now, first = false)
    }

    /** Adds an anchor at [p] plus its straight incoming segment's middle handle. */
    private fun plantPolyarcAnchor(p: PointF, pointerId: Int, now: Long, first: Boolean) {
        if (first) {
            paFinished = false
            paClosed = false
            onPolyarcChanged?.invoke(true)
        }
        paAnchors.add(PointF(p.x, p.y))
        if (paAnchors.size >= 2) {
            val a = paAnchors[paAnchors.size - 2]
            val b = paAnchors[paAnchors.size - 1]
            paMids.add(PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)) // straight to start
        }
        paPointerId = pointerId
        paMode = PaDrag.ANCHOR
        paDragIndex = paAnchors.size - 1
        paDragLast.set(p)
        paLastTapTime = now
        paLastTapPos.set(p)
        paHoverPt = null
        invalidate()
    }

    private fun handlePolyarcMove(e: MotionEvent) {
        if (paPointerId == INVALID_POINTER) return
        val pi = e.findPointerIndex(paPointerId)
        if (pi < 0) return
        val p = PointF(e.getX(pi), e.getY(pi))
        when (paMode) {
            PaDrag.ANCHOR -> {
                val idx = paDragIndex
                val a = paAnchors.getOrNull(idx) ?: return
                a.set(p.x, p.y)
                // While still placing the newest anchor, keep its straight incoming
                // segment's middle pinned to the chord midpoint (bend it afterward).
                if (!paFinished && idx == paAnchors.size - 1 && idx >= 1 && paMids.size >= idx) {
                    val prev = paAnchors[idx - 1]
                    paMids[idx - 1].set((prev.x + a.x) / 2f, (prev.y + a.y) / 2f)
                }
                invalidate()
            }
            PaDrag.MID -> {
                paMids.getOrNull(paDragIndex)?.set(p.x, p.y)
                invalidate()
            }
            PaDrag.BODY -> {
                val dx = p.x - paDragLast.x
                val dy = p.y - paDragLast.y
                for (a in paAnchors) a.offset(dx, dy)
                for (m in paMids) m.offset(dx, dy)
                paDragLast.set(p)
                invalidate()
            }
            PaDrag.ROTATE -> {
                val ang = atan2(p.y - paRotCenter.y, p.x - paRotCenter.x)
                val d = ang - paRotStartAngle
                for (i in paAnchors.indices) paAnchors[i].set(rotatePoint(paRotAnchors0[i], paRotCenter, d))
                for (i in paMids.indices) paMids[i].set(rotatePoint(paRotMids0[i], paRotCenter, d))
                invalidate()
            }
            PaDrag.NONE -> {}
        }
    }

    private fun handlePolyarcUp(e: MotionEvent, ai: Int) {
        if (e.getPointerId(ai) != paPointerId) return
        paPointerId = INVALID_POINTER
        // While building, remember where the newest anchor settled so the finishing
        // double-tap is measured against its final position.
        if (!paFinished && paMode == PaDrag.ANCHOR) {
            paAnchors.lastOrNull()?.let { paLastTapPos.set(it.x, it.y) }
        }
        paMode = PaDrag.NONE
        paDragIndex = -1
        invalidate()
    }

    /** Captures the anchors + middles + centre for a rotate drag. */
    private fun beginPolyarcRotate(p: PointF) {
        val c = polyarcCenter()
        paRotCenter.set(c)
        paRotAnchors0.clear()
        for (a in paAnchors) paRotAnchors0.add(PointF(a.x, a.y))
        paRotMids0.clear()
        for (m in paMids) paRotMids0.add(PointF(m.x, m.y))
        paRotStartAngle = atan2(p.y - c.y, p.x - c.x)
    }

    /**
     * Ends anchor placement and enters edit mode. Closes the loop if the final
     * anchor landed on the first (adding a straight closing segment); drops a
     * degenerate (< 2-anchor) run.
     */
    private fun finishPolyarc() {
        paPointerId = INVALID_POINTER
        paMode = PaDrag.NONE
        paHoverPt = null
        if (paAnchors.size >= 3) {
            val first = paAnchors.first()
            val last = paAnchors.last()
            if (hypot(last.x - first.x, last.y - first.y) <= POLY_CLOSE_DIST) {
                paAnchors.removeAt(paAnchors.size - 1)                    // drop close tap
                if (paMids.isNotEmpty()) paMids.removeAt(paMids.size - 1) // and its segment
                if (paAnchors.size >= 3) {
                    paClosed = true
                    val a = paAnchors.last()
                    val b = paAnchors.first()
                    paMids.add(PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)) // straight closing seg
                }
            }
        }
        if (paAnchors.size < 2) {
            discardPendingPolyarc()
            return
        }
        paFinished = true
        invalidate()
    }

    /** Which part of the finished polyarc [p] grabs, or null if it misses. */
    private fun hitPolyarc(p: PointF): PaDrag? {
        if (paAnchors.isEmpty()) return null
        val rot = polyarcRotateHandle()
        if (hypot(p.x - rot.x, p.y - rot.y) <= HANDLE_HIT) return PaDrag.ROTATE
        for (i in paAnchors.indices) {
            val a = paAnchors[i]
            if (hypot(p.x - a.x, p.y - a.y) <= HANDLE_HIT) {
                paDragIndex = i
                return PaDrag.ANCHOR
            }
        }
        if (hitPolyarcMid(p) >= 0) {
            paDragIndex = hitPolyarcMid(p)
            return PaDrag.MID
        }
        val bodyHit = max(BODY_HIT, sizeFor(Tool.POLYARC) / 2f + 8f)
        val n = paAnchors.size
        val segCount = if (paClosed) n else n - 1
        for (s in 0 until segCount) {
            val a = paAnchors[s]
            val b = paAnchors[(s + 1) % n]
            val m = paMids.getOrNull(s) ?: continue
            val pts = arcPoints(a, b, m)
            for (i in 1 until pts.size) {
                if (distToSegment(p, pts[i - 1], pts[i]) <= bodyHit) return PaDrag.BODY
            }
        }
        return null
    }

    /** Index of the middle handle [p] grabs (building or editing), or -1. */
    private fun hitPolyarcMid(p: PointF): Int {
        for (i in paMids.indices) {
            val m = paMids[i]
            if (hypot(p.x - m.x, p.y - m.y) <= HANDLE_HIT) return i
        }
        return -1
    }

    /** Bounding box over the anchors and the (possibly bent-out) middles. */
    private fun polyarcBounds(): RectF {
        val f = paAnchors.first()
        val r = RectF(f.x, f.y, f.x, f.y)
        for (a in paAnchors) {
            r.left = min(r.left, a.x); r.top = min(r.top, a.y)
            r.right = max(r.right, a.x); r.bottom = max(r.bottom, a.y)
        }
        for (m in paMids) {
            r.left = min(r.left, m.x); r.top = min(r.top, m.y)
            r.right = max(r.right, m.x); r.bottom = max(r.bottom, m.y)
        }
        return r
    }

    private fun polyarcCenter(): PointF {
        val b = polyarcBounds()
        return PointF((b.left + b.right) / 2f, (b.top + b.bottom) / 2f)
    }

    /** Rotate handle: centred above the run's bounding box. */
    private fun polyarcRotateHandle(): PointF {
        val b = polyarcBounds()
        return PointF((b.left + b.right) / 2f, b.top - LINE_ROT_OFFSET)
    }

    /** Dense samples along every arc segment (plus the closing one), for baking/preview. */
    private fun polyarcSamples(): List<PointF> {
        val n = paAnchors.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(PointF(paAnchors[0].x, paAnchors[0].y))
        val out = ArrayList<PointF>()
        out.add(PointF(paAnchors[0].x, paAnchors[0].y))
        val segCount = if (paClosed) n else n - 1
        for (s in 0 until segCount) {
            val a = paAnchors[s]
            val b = paAnchors[(s + 1) % n]
            val m = paMids.getOrNull(s) ?: PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
            val pts = arcPoints(a, b, m)
            for (i in 1 until pts.size) out.add(pts[i]) // skip the shared start point
        }
        return out
    }

    /** A densely-sampled [Stroke] tracing the current polyarc. */
    private fun buildPolyarcStroke(): Stroke {
        val width = resolveWidth(Tool.POLYARC, sizeFor(Tool.POLYARC), 1f, 0f)
        return Stroke(Tool.POLYARC, strokeColor, seed = Random.nextInt()).apply {
            for (p in polyarcSamples()) add(StrokePoint(Vec2(p.x, p.y), width))
        }
    }

    /** Bakes the pending polyarc as a [StrokeOp] (or drops it if degenerate). */
    fun commitPendingPolyarc() {
        if (paAnchors.size < 2) {
            discardPendingPolyarc()
            return
        }
        val stroke = buildPolyarcStroke()
        resetPolyarcState()
        onPolyarcChanged?.invoke(false)
        invalidate()
        scope.launch { applyCommittedOp(StrokeOp(stroke)) }
    }

    private fun discardPendingPolyarc() {
        val had = paAnchors.isNotEmpty()
        resetPolyarcState()
        if (had) onPolyarcChanged?.invoke(false)
        invalidate()
    }

    private fun resetPolyarcState() {
        paAnchors.clear()
        paMids.clear()
        paRotAnchors0.clear()
        paRotMids0.clear()
        paClosed = false
        paFinished = false
        paMode = PaDrag.NONE
        paDragIndex = -1
        paPointerId = INVALID_POINTER
        paHoverPt = null
        paLastTapTime = 0L
    }

    // --- Input conditioning -------------------------------------------------
    // The stylus reports ~240 samples/sec with a noisy pressure sensor. Raw
    // samples flicker the stroke width and pile up thousands of sub-pixel
    // points on slow strokes, so each raw sample first updates a light
    // exponential smoothing of pressure/tilt (sensor-noise cleanup — the line
    // still lands exactly where the pen went), and only samples that moved a
    // visible distance (or meaningfully changed pressure, e.g. bearing down in
    // place) become stroke points.
    private var smoothPressure = 1f
    private var smoothTilt = 0f
    private var lastAcceptPos: Vec2? = null
    private var lastAcceptPressure = 1f

    private fun normPressure(raw: Float): Float =
        if (pressureMax > 0f) (raw / pressureMax).coerceIn(0f, 1f) else 1f

    /** Resets the conditioning state at pointer-down (no carry across strokes). */
    private fun beginConditioning(rawPressure: Float, tilt: Float) {
        smoothPressure = normPressure(rawPressure)
        // Seed with the REAL initial tilt: seeding zero made every stroke start
        // as if the pen were vertical and "catch up" over the first samples — a
        // funnel-shaped start, worst on the tilt-heavy marker.
        smoothTilt = tilt
        lastAcceptPos = null
        lastAcceptPressure = smoothPressure
    }

    /**
     * Feeds one raw stylus sample through the conditioning: the smoothing sees
     * every sample (so it tracks time, not acceptance), the stroke only gains a
     * point when it would visibly matter.
     */
    private fun addSample(stroke: Stroke, x: Float, y: Float, rawPressure: Float, tilt: Float) {
        smoothPressure += SMOOTH_ALPHA * (normPressure(rawPressure) - smoothPressure)
        smoothTilt += SMOOTH_ALPHA * (tilt - smoothTilt)
        val pos = Vec2(x, y)
        val last = lastAcceptPos
        if (last != null &&
            (pos - last).distance < MIN_SAMPLE_PX &&
            kotlin.math.abs(smoothPressure - lastAcceptPressure) < PRESSURE_EPS
        ) {
            return
        }
        lastAcceptPos = pos
        lastAcceptPressure = smoothPressure
        val point = buildPoint(pos, smoothPressure, smoothTilt)
        stroke.add(point)
        // Grow the live-blit bounds (padded when used; see blit helpers).
        activeMaxWidth = max(activeMaxWidth, point.width)
        if (stroke.points.size == 1) {
            activeBounds.set(pos.x, pos.y, pos.x, pos.y)
        } else {
            activeBounds.union(pos.x, pos.y)
        }
        // Each accepted wash point is born now; its age drives the live drying.
        if (stroke === dryingStroke) dryingBorn.add(android.os.SystemClock.uptimeMillis())
        // Deposit into the wet sim: every ACCEPTED point stamps, so the stroke's
        // point list is exactly the deposition record the bake replays.
        val sim = wetSim
        if (sim != null && stroke === wetStroke) {
            val needed = liveWetCrop()
            if (wetStamped == 0) {
                sim.begin(needed)
            } else if (!sim.crop.contains(needed)) {
                val u = Rect(needed)
                u.union(sim.crop)
                sim.grow(quantizedCrop(u.left.toFloat(), u.top.toFloat(), u.right.toFloat(), u.bottom.toFloat()))
            }
            sim.stampPoint(point, stroke.color, SUPER_SAMPLE)
            wetStamped = stroke.points.size
        }
    }

    /**
     * Builds the next sample — and, for wet media, spends the paint it took to
     * get here. Every live point funnels through this, so it's the one place the
     * brush drains.
     */
    private fun buildPoint(pos: Vec2, pressure: Float, tilt: Float): StrokePoint {
        val width = resolveWidth(tool, sizeFor(tool), pressure, tilt)
        val density = resolveDensity(tool, pressure)

        if (!tool.usesLoad) return StrokePoint(pos, width, density)
        // Clean water: an infinite, pigmentless charge — nothing spends, nothing
        // contaminates, and the stroke lays no trail a later pass could pick up.
        if (active?.water == true) return StrokePoint(pos, width, density)

        val last = lastDepositPos
        lastDepositPos = pos
        if (last != null) {
            val mm = pxPerMm.coerceAtLeast(0.0001f)
            val distMm = (pos - last).distance / mm
            // Paint is spent per unit of surface covered: how far the tip
            // travelled, times how wide a track it left.
            val areaMm2 = distMm * (width / mm)
            if (areaMm2 > 0f) brushLoad = brushLoad.deposit(areaMm2 / BrushLoad.COVERAGE_MM2)

            // A dirty brush takes on the paint it drags through. Swap a little of
            // the load for whatever colour is under the tip, scaled by how far it
            // moved and how much paint is actually there — so a brush dragged over
            // bare paper picks up nothing.
            val picked = pickupColorAt(pos)
            if (picked != 0) {
                val coverage = (picked ushr 24) / 255f
                val fraction = (distMm * PICKUP_PER_MM * coverage).coerceIn(0f, PICKUP_MAX_FRACTION)
                brushLoad = brushLoad.contaminate(picked or 0xFF000000.toInt(), fraction)
            }
            onBrushLoadChanged?.invoke(brushLoad)
        }

        val sp = StrokePoint(pos, width, density, color = brushLoad.color, load = brushLoad.fill)
        // Record what we just laid so a later part of the same stroke can pick it
        // up. Done after sampling, so a point never picks up its own deposit.
        stampPickupTrail(pos, width, brushLoad.color)
        return sp
    }

    /**
     * The colour on the canvas under [pos] — the committed paint, else the
     * brush's own live trail. Returns 0 (nothing to pick up) over bare surface.
     * Only the paint layer is sampled, never the surface, so dragging over
     * unpainted paper contaminates nothing — the same rule the magic wand
     * follows.
     *
     * Committed paint is consulted first, and this matters: consecutive points
     * overlap by roughly the brush radius, so the tip is nearly always sitting
     * on the trail it just laid. If the trail won, it would shadow the paint
     * underneath and the brush could never pick anything up. So the trail only
     * speaks where there is no committed paint — a later part of the stroke
     * crossing back over an earlier part on bare ground.
     */
    private fun pickupColorAt(pos: Vec2): Int {
        val bx = (pos.x * SUPER_SAMPLE).toInt()
        val by = (pos.y * SUPER_SAMPLE).toInt()
        if (bx < 0 || by < 0 || bx >= bufW || by >= bufH) return 0

        paintBmp?.getPixel(bx, by)?.let { if ((it ushr 24) > PICKUP_ALPHA_FLOOR) return it }
        pickupBuf?.getPixel(bx, by)?.let { if ((it ushr 24) > PICKUP_ALPHA_FLOOR) return it }
        return 0
    }

    /** Stamps the colour deposited at [pos] into the live trail for later pickup. */
    private fun stampPickupTrail(pos: Vec2, width: Float, @ColorInt color: Int) {
        val c = pickupCanvas ?: return
        pickupStampPaint.color = color or 0xFF000000.toInt()
        c.drawCircle(pos.x * SUPER_SAMPLE, pos.y * SUPER_SAMPLE, (width * SUPER_SAMPLE) / 2f, pickupStampPaint)
    }

    /** Sets up per-stroke live-preview scratch (accumulator / wet backdrop). */
    private fun beginActiveExtras() {
        accumDrawn = 0
        bristleLayout = null
        val wantsAccum = active?.tool == Tool.SPRAY || active?.tool == Tool.BRUSH
        if (wantsAccum && logicalW > 0 && logicalH > 0) {
            val cur = activeAccum
            if (cur != null && cur.width == logicalW && cur.height == logicalH) {
                cur.eraseColor(Color.TRANSPARENT)
            } else {
                cur?.recycle()
                activeAccum = createBitmap(logicalW, logicalH)
            }
        } else {
            activeAccum?.recycle()
            activeAccum = null
        }
        // The dirty brush's own-trail buffer: reused across strokes, cleared here.
        if (active?.tool?.usesLoad == true && bufW > 0 && bufH > 0) {
            val buf = pickupBuf ?: createBitmap(bufW, bufH).also {
                pickupBuf = it
                pickupCanvas = Canvas(it)
            }
            buf.eraseColor(Color.TRANSPARENT)
        }
    }

    /** Tears down per-stroke live-preview scratch on pointer-up. */
    private fun endActiveExtras() {
        // Keep the accumulator allocated for the next stroke; just reset use.
        accumDrawn = 0
        bristleLayout = null
        wetBackdropShader = null
        wetBackdropAtPoints = 0
        // Keep pickupBuf allocated for reuse; just drop its contents.
        pickupBuf?.eraseColor(Color.TRANSPARENT)
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
            pruneWetPending()
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
                            compositeWatercolorInto(cur, s)
                        } else {
                            Canvas(cur).apply {
                                save()
                                scale(SUPER_SAMPLE, SUPER_SAMPLE)
                                StrokeRenderer.paintStroke(this, s, surface)
                                restore()
                            }
                        }
                    } else {
                        val full = cur.copy(Bitmap.Config.ARGB_8888, true).also {
                            if (s.tool == Tool.WATERCOLOR) {
                                compositeWatercolorInto(it, s)
                            } else {
                                Canvas(it).apply {
                                    save()
                                    scale(SUPER_SAMPLE, SUPER_SAMPLE)
                                    StrokeRenderer.paintStroke(this, s, surface)
                                    restore()
                                }
                            }
                        }
                        val out = blendMasked(cur, full, clip)
                        full.recycle()
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
        // A settling wash commits first (it becomes the newest op — so the FIRST
        // undo after it lands removes the wash; this call itself starts its bake,
        // and the guard below makes this undo a no-op while that bake runs).
        interruptWet()
        if (baking || rebuilding || committed.isEmpty()) return
        redoStack.add(committed.removeAt(committed.lastIndex))
        syncSurfaceToHistory()
        rebuild()
    }

    fun redo() {
        interruptWet()
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
        discardPendingPolyline()
        discardPendingPolyarc()
        selRect.setEmpty()
        antsAnimator.cancel()
    }

    // --- Watercolor (from Stage 4b) -----------------------------------------

    /**
     * Bakes a watercolor stroke into [base] IN PLACE: the paint under the
     * stroke is wetted (diluted + pushed) and the wash mixed on spectrally —
     * all confined to a crop around the stroke ([watercolorCrop]) rather than
     * the full buffer, so a small wash on a big canvas costs what the wash
     * covers, not what the canvas measures. Falls back to a plain
     * over-composite if the pigment shader is unavailable.
     */
    private fun compositeWatercolorInto(base: Bitmap, stroke: Stroke) {
        if (stroke.isEmpty) return
        // A stroke the wet sim simulated live bakes by REPLAYING that simulation
        // (its recorded schedule and final crop); the ribbon path below remains
        // for the no-GPU fallback — and as the last resort if the replay fails.
        if (stroke.wetCrop != null) {
            val ok = runCatching { compositeWetSimInto(base, stroke) }.isSuccess
            if (ok) return
        }
        val crop = watercolorCrop(stroke)
        val cw = crop.width()
        val ch = crop.height()
        if (cw <= 0 || ch <= 0) return
        val l = crop.left.toFloat()
        val t = crop.top.toFloat()
        val avgW = avgWidth(stroke) * SUPER_SAMPLE
        val soften = max(2f, avgW * 0.14f)
        val clearFeather = max(2f, avgW * 0.10f)
        val spread = max(4f, avgW * 0.30f) * if (stroke.water) WATER_SPREAD_SCALE else 1f
        val dilute = if (stroke.water) WATER_DILUTE_ALPHA else WET_DILUTE_ALPHA
        val drawMask: (Canvas) -> Unit = if (stroke.water) {
            // Fully developed (null dryness): what the live growth converged to.
            { c -> StrokeRenderer.paintWetMask(c, stroke, null) }
        } else {
            val region = strokeRegionPath(stroke)
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            ;
            { c -> c.drawPath(region, white) }
        }
        val nodes = mutableListOf<RenderNode>()
        val backdrop = GpuRender.renderToBitmap(cw, ch) { canvas ->
            nodes += recordWetBackdrop(
                canvas as android.graphics.RecordingCanvas, base, drawMask, cw, ch,
                SUPER_SAMPLE, clearFeather, spread, soften, l, t, dilute,
            )
        }
        nodes.forEach { it.discardDisplayList() }
        if (stroke.water) {
            // Clean water bakes as its lasting effect alone: the paint under
            // the stroke diluted and pushed. No wash, nothing to mix.
            Canvas(base).drawBitmap(backdrop, l, t, srcPaint)
            backdrop.recycle()
            return
        }
        val wash = createBitmap(cw, ch)
        Canvas(wash).apply {
            translate(-l, -t)
            scale(SUPER_SAMPLE, SUPER_SAMPLE)
            StrokeRenderer.paintStroke(this, stroke, surface)
        }
        val agsl = pigmentAgsl
        val mixed = if (agsl == null) {
            null
        } else {
            runCatching { PigmentMixer.mix(agsl, backdrop, wash) }.getOrNull()
        }
        Canvas(base).apply {
            if (mixed != null) {
                // The mixed crop is the full replacement for its region
                // (backdrop where there is no wash), so it overwrites.
                drawBitmap(mixed, l, t, srcPaint)
            } else {
                drawBitmap(backdrop, l, t, srcPaint)
                drawBitmap(wash, l, t, null)
            }
        }
        backdrop.recycle()
        wash.recycle()
        mixed?.recycle()
    }

    /**
     * Bakes a wet-sim watercolor stroke by re-running the simulation the live
     * preview showed: same stamps, same recorded tick schedule, same
     * canvas-anchored tooth — over the stroke's final crop from the start (the
     * live crop grew along the way, so live == bake is near-exact at crop
     * edges, not bit-exact). The presented wash then composites through the
     * same backdrop + spectral mix as the ribbon bake. Runs on the bake
     * dispatcher; [GpuRender] serializes GPU access with the live sim.
     */
    private fun compositeWetSimInto(base: Bitmap, stroke: Stroke) {
        val agsl = wetAgsl ?: error("wet shaders unavailable")
        val crop = stroke.wetCrop ?: error("not a wet-sim stroke")
        val cw = crop.width()
        val ch = crop.height()
        if (cw <= 0 || ch <= 0) return
        val sim = bakeWetSim ?: WetSim(agsl[0], agsl[1], agsl[2]).also { bakeWetSim = it }
        sim.begin(crop)
        val tooth = ToothCache.rawFieldFor(surface)
        val toothScale = surface.toothScale * SUPER_SAMPLE
        var cursor = 0
        for (n in stroke.wetSchedule) {
            while (cursor < n && cursor < stroke.points.size) {
                sim.stampPoint(stroke.points[cursor], stroke.color, SUPER_SAMPLE)
                cursor++
            }
            sim.tick(tooth, toothScale)
        }
        // Points laid after the last live tick were stamped but never advanced.
        while (cursor < stroke.points.size) {
            sim.stampPoint(stroke.points[cursor], stroke.color, SUPER_SAMPLE)
            cursor++
        }
        val wash = sim.presentToBitmap()

        val l = crop.left.toFloat()
        val t = crop.top.toFloat()
        val region = strokeRegionPath(stroke)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val avgW = avgWidth(stroke) * SUPER_SAMPLE
        val soften = max(2f, avgW * 0.14f)
        val clearFeather = max(2f, avgW * 0.10f)
        val spread = max(4f, avgW * 0.30f)
        val nodes = mutableListOf<RenderNode>()
        val backdrop = GpuRender.renderToBitmap(cw, ch) { canvas ->
            nodes += recordWetBackdrop(
                canvas as android.graphics.RecordingCanvas, base, { c -> c.drawPath(region, white) },
                cw, ch, SUPER_SAMPLE, clearFeather, spread, soften, l, t,
            )
        }
        nodes.forEach { it.discardDisplayList() }
        val mixAgsl = pigmentAgsl
        val mixed = mixAgsl?.let { runCatching { PigmentMixer.mix(it, backdrop, wash) }.getOrNull() }
        Canvas(base).apply {
            if (mixed != null) {
                // The mixed crop is the full replacement for its region
                // (backdrop where there is no wash), so it overwrites.
                drawBitmap(mixed, l, t, srcPaint)
            } else {
                drawBitmap(backdrop, l, t, srcPaint)
                drawBitmap(wash, l, t, null)
            }
        }
        backdrop.recycle()
        wash.recycle()
        mixed?.recycle()
    }

    /**
     * The buffer rect the watercolor pipeline must touch for [stroke]: its
     * bounds padded for the widest mark, the bloom spread and the bleed halo,
     * clamped to the buffer — with the SIZE quantized up to [CROP_QUANTUM]
     * multiples so the GPU pool sees a handful of distinct sizes instead of a
     * new one every frame as the stroke grows.
     */
    private fun watercolorCrop(stroke: Stroke): Rect {
        val pts = stroke.points
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxW = 0f
        var sumW = 0f
        for (p in pts) {
            minX = min(minX, p.position.x); maxX = max(maxX, p.position.x)
            minY = min(minY, p.position.y); maxY = max(maxY, p.position.y)
            maxW = max(maxW, p.width); sumW += p.width
        }
        val avgW = sumW / pts.size
        // Must cover everything the pipeline draws: the ribbon (maxW/2), the
        // bloom + bleed reach (~1.44*avgW, see drawWetLive's mixer rect), with
        // margin so the crop-clamped shaders never show their edge.
        val pad = (maxW / 2f + avgW * 1.5f + 16f) * SUPER_SAMPLE
        return quantizedCrop(
            minX * SUPER_SAMPLE - pad, minY * SUPER_SAMPLE - pad,
            maxX * SUPER_SAMPLE + pad, maxY * SUPER_SAMPLE + pad,
        )
    }

    /** Clamps float buffer bounds and quantizes the SIZE up to [CROP_QUANTUM]. */
    private fun quantizedCrop(lf: Float, tf: Float, rf: Float, bf: Float): Rect {
        val l0 = kotlin.math.floor(lf).toInt().coerceIn(0, bufW)
        val t0 = kotlin.math.floor(tf).toInt().coerceIn(0, bufH)
        val r0 = ceil(rf).toInt().coerceIn(0, bufW)
        val b0 = ceil(bf).toInt().coerceIn(0, bufH)
        val qw = min(bufW, (((r0 - l0).coerceAtLeast(1) + CROP_QUANTUM - 1) / CROP_QUANTUM) * CROP_QUANTUM)
        val qh = min(bufH, (((b0 - t0).coerceAtLeast(1) + CROP_QUANTUM - 1) / CROP_QUANTUM) * CROP_QUANTUM)
        val l = l0.coerceAtMost(bufW - qw).coerceAtLeast(0)
        val t = t0.coerceAtMost(bufH - qh).coerceAtLeast(0)
        return Rect(l, t, l + qw, t + qh)
    }

    /**
     * Records the wet backdrop composite onto [rc] (a hardware [RecordingCanvas],
     * either GpuRender's full-res bake surface or the half-res live one): [base]
     * with the stroke [region] diluted (feathered clear) and pushed (a blurred,
     * faded copy bloomed into a wider region). Geometry and blur radii are scaled
     * by [sc], and everything is shifted by (-[ox], -[oy]) buffer px first, so the
     * same recipe renders any crop of the buffer at either resolution. Everything
     * is done with blur-[RenderEffect] child [RenderNode]s in a single pass.
     */
    private fun recordWetBackdrop(
        rc: android.graphics.RecordingCanvas, base: Bitmap, drawMask: (Canvas) -> Unit,
        w: Int, h: Int, sc: Float, clearFeather: Float, spread: Float, soften: Float,
        ox: Float, oy: Float, diluteAlpha: Int = WET_DILUTE_ALPHA,
    ): List<RenderNode> {
        val fw = w.toFloat()
        val fh = h.toFloat()
        fun blurNode(radius: Float, record: (Canvas) -> Unit) = RenderNode("wet").apply {
            setPosition(0, 0, w, h)
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            val c = beginRecording(w, h)
            c.scale(sc, sc)
            c.translate(-ox, -oy)
            record(c)
            endRecording()
        }
        // The white wet mask (a path for pigmented washes; an age-weighted mesh
        // for water strokes), feathered two ways (tight clear + wide bloom).
        val clearNode = blurNode(clearFeather) { drawMask(it) }
        val spreadNode = blurNode(spread) { drawMask(it) }
        val baseBlurNode = blurNode(soften) { it.drawBitmap(base, 0f, 0f, null) }
        // 1. base
        rc.save(); rc.scale(sc, sc); rc.translate(-ox, -oy); rc.drawBitmap(base, 0f, 0f, null); rc.restore()
        // 2. dilute: punch a feathered hole where the stroke floods with water
        val l1 = rc.saveLayer(0f, 0f, fw, fh, Paint().apply { blendMode = BlendMode.DST_OUT })
        rc.drawRenderNode(clearNode)
        rc.restoreToCount(l1)
        // 3. push: blurred base kept only inside the wider spread region, laid
        //    back on at reduced alpha so displaced pigment blooms past the edge
        val l2 = rc.saveLayer(0f, 0f, fw, fh, Paint().apply { alpha = diluteAlpha })
        rc.drawRenderNode(baseBlurNode)
        val l3 = rc.saveLayer(0f, 0f, fw, fh, Paint().apply { blendMode = BlendMode.DST_IN })
        rc.drawRenderNode(spreadNode)
        rc.restoreToCount(l3)
        rc.restoreToCount(l2)
        // The recording holds REFERENCES to these nodes; HWUI resolves them at
        // sync time, after this function returns. Discarding their display
        // lists here (as this code once did in a finally) hands the renderer
        // three EMPTY nodes — the dilute and push silently vanish and the
        // backdrop degenerates to the untouched base. The caller discards
        // them once the GPU render has actually completed.
        return listOf(clearNode, spreadNode, baseBlurNode)
    }

    private fun avgWidth(stroke: Stroke): Float {
        var sum = 0f
        for (p in stroke.points) sum += p.width
        return sum / stroke.points.size
    }

    /** Clears painted strokes, history, and any selection, keeping the surface. */
    fun clear() {
        val old = paintBmp ?: return
        abortWet()
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
        abortWet()
        wetSim?.release()
        wetSim = null
        bakeWetSim?.release()
        recycleCheckpoints()
        activeAccum?.recycle()
        activeAccum = null
        recycleWashScratch()
        pickupBuf?.recycle()
        pickupBuf = null
        pickupCanvas = null
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
        if (polyPts.size >= 2) {
            Canvas(paintCopy).apply {
                save()
                scale(SUPER_SAMPLE, SUPER_SAMPLE)
                StrokeRenderer.paintStroke(this, buildPolyStroke(), surface = this@PaintCanvasView.surface)
                restore()
            }
        }
        if (paAnchors.size >= 2) {
            Canvas(paintCopy).apply {
                save()
                scale(SUPER_SAMPLE, SUPER_SAMPLE)
                StrokeRenderer.paintStroke(this, buildPolyarcStroke(), surface = this@PaintCanvasView.surface)
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
                    // Stamp the file with the physical resolution so it prints 1:1
                    // with the screen: the buffer is SUPER_SAMPLE× the view pixels.
                    val dpi = Calibration.effectivePpi(context) * SUPER_SAMPLE
                    val where = GalleryExport.savePng(context, flat, name, dpi)
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

        // Water mode: clean water washes existing paint out harder than a
        // pigmented stroke — less of the paint re-laid, pushed further.
        const val WATER_DILUTE_ALPHA = 72
        const val WATER_SPREAD_SCALE = 1.35f

        // Water's backdrop rebuild cadence: finer than the pigmented stride
        // (the backdrop IS the stroke's whole appearance) plus a time stride,
        // so the mask keeps growing under a resting or lifted pen.
        const val WATER_BACKDROP_STRIDE = 4
        const val WATER_BACKDROP_STRIDE_MS = 100L
        const val SUPER_SAMPLE = 1.0f

        // Input conditioning: EMA weight per raw sample (~10 ms time constant at
        // 240 Hz), minimum travel before a sample becomes a point (~0.14 mm on
        // the Movink), and the smoothed-pressure change that forces one through
        // anyway (bearing down in place must still grow the dab).
        const val SMOOTH_ALPHA = 0.35f
        const val MIN_SAMPLE_PX = 1.25f
        const val PRESSURE_EPS = 0.015f

        // Dirty brush: how fast it takes on the paint it drags through. PER_MM is
        // the share of the load swapped for the picked colour per mm travelled
        // (over fully-covered paint); MAX_FRACTION caps a single step so a fast
        // stroke can't swap the whole load at once; ALPHA_FLOOR ignores near-bare
        // pixels so faint edges don't contaminate. Tuned by eye on-device.
        const val PICKUP_PER_MM = 0.05f
        const val PICKUP_MAX_FRACTION = 0.35f
        const val PICKUP_ALPHA_FLOOR = 12

        // Undo/redo: keep a paint snapshot every STRIDE ops, at most MAX of them
        // (most-recent), so an undo replays at most STRIDE ops from a checkpoint.
        const val CHECKPOINT_STRIDE = 6
        const val MAX_CHECKPOINTS = 6

        // Watercolor crop sizes round up to this so the GPU pool and the live
        // scratch bitmaps see a handful of sizes, not one per frame.
        const val CROP_QUANTUM = 512

        // Rebuild the live wet backdrop after this many new points (not every
        // frame): it's blurred context under the wash, so brief staleness is
        // invisible, and slow strokes — where it changes slowest — rebuild least.
        const val WET_BACKDROP_STRIDE = 8

        // The live wet simulation, gated off after its first on-device round
        // failed on look (airbrush fog) and frame cost (per-tick readbacks on
        // the UI thread). See startWetSim for what re-enabling requires.
        const val WET_SIM_ENABLED = false

        // Live drying: how long a laid wash section takes to fully dry, and
        // how often the wet render refreshes while anything is still wet.
        const val DRY_MS = 3500L
        const val DRY_PULSE_MS = 33L

        // Line tool: min drag to count as a line, handle hit radii (px), and how
        // far the rotate handle sits off the line's centre.
        const val MIN_LINE_LEN = 6f
        const val HANDLE_HIT = 26f
        const val BODY_HIT = 16f
        const val LINE_ROT_OFFSET = 34f

        // Polyline: sample spacing (px) for crisp corners, the finishing double-tap
        // window + slop, and how near the first anchor a final tap must land to close.
        const val POLY_SAMPLE = 4f
        const val POLY_DOUBLE_MS = 350L
        const val POLY_TAP_SLOP = 26f
        const val POLY_CLOSE_DIST = 24f

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
    private val srcPaint = Paint().apply { blendMode = BlendMode.SRC }
}
