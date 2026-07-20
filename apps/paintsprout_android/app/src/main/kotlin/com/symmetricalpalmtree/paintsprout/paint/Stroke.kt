package com.symmetricalpalmtree.paintsprout.paint

import androidx.annotation.ColorInt

/** [StrokePoint.color] sentinel: take the colour from the stroke instead. */
const val INHERIT_COLOR = 0

/**
 * One captured sample along a stroke. Width and density are resolved at capture
 * time from the tool profile + stylus pressure/tilt (see [resolveWidth] /
 * [resolveDensity]), so rendering never needs to look at pressure again.
 *
 * [color] and [load] are resolved at capture time for the same reason: they are
 * what the brush happened to be carrying here. Baking them into the point (rather
 * than re-deriving them at paint time) keeps replay honest — undo/redo refolds the
 * op history without having to re-simulate the brush and arrive at the same
 * answer.
 *
 * @param density per-point opacity/darkness in [0, 1]. Pencil maps pressure to
 *   this so lighter pressure = fainter marks; other tools leave it at 1.
 * @param color ARGB the brush carried at this point, or [INHERIT_COLOR] to use
 *   the stroke's own colour. Only wet media (which pick pigment up as they go)
 *   vary this along a stroke. Must be opaque: the renderer draws segments
 *   inside an isolated layer and relies on opaque overlaps not accumulating.
 * @param load how full the brush was here, in [0, 1]. 1 is a fully charged
 *   brush; as it drains the mark fades out. Non-wet tools leave it at 1.
 */
data class StrokePoint(
    val position: Vec2,
    val width: Float,
    val density: Float = 1.0f,
    @param:ColorInt val color: Int = INHERIT_COLOR,
    val load: Float = 1.0f,
)

/**
 * A single continuous stroke (pointer-down to pointer-up). Ported from `Stroke`
 * in the Flutter `stroke.dart`.
 *
 * @param color the color the stroke was drawn with (ARGB). Eraser strokes use
 *   the paper color.
 * @param seed stable per-stroke seed for any randomized texture (brush
 *   bristles), so the live preview and the baked result look identical.
 */
class Stroke(
    val tool: Tool,
    @param:ColorInt val color: Int,
    val seed: Int = 0,
    /**
     * The tool's nominal (unpressed) width in buffer px at capture time, or 0
     * when unknown. The bristle renderer sizes its layout from this — a
     * brush's bristle count is a property of the brush, not of how hard one
     * stroke pressed — and replay re-renders identically because it's stored.
     * 0 falls back to the observed stroke width (legacy strokes, tests).
     */
    val baseWidth: Float = 0f,
    /**
     * A clean-water stroke (watercolor's water mode): deposits no pigment,
     * only re-wets — the paint underneath is diluted and pushed, harder than
     * a pigmented wash does it. Captured at pen-down so undo/redo replays
     * the stroke as what it was.
     */
    val water: Boolean = false,
) {
    val points: MutableList<StrokePoint> = mutableListOf()

    /**
     * The wet simulation's recorded tick schedule (watercolor only; empty for
     * every other tool and for the ribbon fallback). Live ticks run on the wall
     * clock — the wash keeps moving while the pen pauses, and for a while after
     * pen-up — but each tick RECORDS how many points had been stamped before
     * it. Replaying stamps and ticks from this list re-runs exactly the
     * simulation the live preview showed, which is what makes undo/redo honest
     * for a wall-clock-driven effect.
     */
    val wetSchedule: MutableList<Int> = mutableListOf()

    /**
     * The buffer crop the live wet sim ended on (watercolor only). The live
     * crop grows as the stroke wanders; the bake replays over this final crop
     * from the start so both see the same field extent.
     */
    var wetCrop: android.graphics.Rect? = null

    /**
     * Pen only: how long the nib rested at pen-down before travelling, and
     * at the stroke's end before lifting, in ms. Ink pools while a nib
     * rests; the dwell is captured here so the bake and any replay pool
     * exactly as the screen showed.
     */
    var startDwellMs = 0L
    var endDwellMs = 0L

    /**
     * Watercolor only: the per-point drying progress this stroke was FROZEN at
     * when something cut its drying short (a new stroke, undo, a surface
     * change). Null — the normal case — means it dried fully. The bake renders
     * whatever is recorded here, so an interrupted wash commits exactly as the
     * screen showed it (soft rim and all) instead of snapping crisp — and undo
     * replays the same frozen state.
     */
    var dryFreeze: FloatArray? = null

    fun add(p: StrokePoint) {
        points.add(p)
    }

    val isEmpty: Boolean get() = points.isEmpty()

    private var arcCache = FloatArray(0)
    private var arcCount = 0

    /**
     * Cumulative arc length at each point, in buffer px — entry i is valid for
     * i < [points]`.size`. The bristle renderer parameterizes its along-stroke
     * texture by this, so the same canvas distance always gets the same streak
     * regardless of how the points were captured or replayed.
     *
     * Incremental: interior entries never change (points only append), but the
     * final entry is re-derived on every call because the live preview
     * temporarily appends a predicted point and then removes it — the cached
     * value at that index may belong to a point that no longer exists.
     */
    fun arcLengths(): FloatArray {
        val n = points.size
        if (n == 0) return arcCache
        if (arcCache.size < n) arcCache = arcCache.copyOf(maxOf(n, arcCache.size * 2, 64))
        arcCache[0] = 0f
        if (n > 1) {
            // The last cached entry may have belonged to a removed transient
            // point, so re-derive from there; everything before it is real.
            var i = (arcCount - 1).coerceIn(1, n - 1)
            while (i < n) {
                arcCache[i] = arcCache[i - 1] + (points[i].position - points[i - 1].position).distance
                i++
            }
        }
        arcCount = n
        return arcCache
    }
}

/**
 * A contiguous span of a stroke the brush painted with effectively one colour at
 * one strength — `[from, to]` inclusive, sharing its end points with its
 * neighbours so the spans join up.
 */
class StrokeRun(val from: Int, val to: Int, @param:ColorInt val color: Int, val alpha: Float)

/**
 * Splits a stroke into spans of near-constant colour and strength.
 *
 * This is how a fading or contaminated stroke gets drawn: one path per span,
 * composited normally, instead of a mask over the whole thing or a draw call per
 * segment.
 *
 * A mask is the tempting shortcut and it is wrong — masking multiplies coverage,
 * so where a stroke crosses itself (an enso, where a spent tail sweeps back over
 * the wet opening) the tail's near-zero mask scrubs out the fresh paint beneath.
 * Spans composite with SRC_OVER, which unions: the strong deposit wins, which is
 * what two coats of paint on one spot actually do.
 *
 * Spans are contiguous because load only ever drains, so the count is bounded by
 * [ALPHA_STEP] — and a stroke that barely fades is a span or two, no dearer than
 * drawing it flat. Splitting per segment instead costs ~150x the draw calls.
 *
 * @param baseColor the colour to use where a point carries none. Already opaque;
 *   spans inherit that, which the bristle path relies on.
 * @param alphaOf maps a point's [StrokePoint.load] to the strength it deposits.
 */
fun strokeRuns(
    stroke: Stroke,
    @ColorInt baseColor: Int,
    alphaOf: (Float) -> Float,
): List<StrokeRun> {
    val pts = stroke.points
    if (pts.size < 2) return emptyList()

    fun colorAt(i: Int): Int {
        val c = pts[i].color
        return if (c == INHERIT_COLOR) baseColor else c or OPAQUE
    }

    val runs = ArrayList<StrokeRun>()
    var from = 0
    var runColor = colorAt(0)
    var runAlpha = alphaOf(pts[0].load)

    for (i in 1 until pts.size) {
        val c = colorAt(i)
        val a = alphaOf(pts[i].load)
        if (c != runColor || Math.abs(a - runAlpha) >= ALPHA_STEP) {
            // The span ends *at* i, so the next one starts there too and they meet.
            runs.add(StrokeRun(from, i, runColor, runAlpha))
            from = i
            runColor = c
            runAlpha = a
        }
    }
    if (from < pts.size - 1) runs.add(StrokeRun(from, pts.size - 1, runColor, runAlpha))
    return runs
}

/** How far strength may drift inside one span. Bounds the span count to ~1/this. */
const val ALPHA_STEP = 1f / 10f

private const val OPAQUE = 0xFF shl 24
