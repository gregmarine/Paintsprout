package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders a [Stroke] onto an `android.graphics.Canvas`. Shared by the live
 * preview and the bake, so what you see while drawing is exactly what commits —
 * the same contract as `paintStroke` in the Flutter `stroke.dart`.
 *
 * Ports every render branch: grain (pencil/marker), wash (watercolor), bristle
 * (brush), and the shared solid/soft path (pen, spray, eraser), plus the surface
 * [ToothCache] break-up.
 *
 * Grain, bristle and the multi-point wash build their marks as meshes with
 * per-vertex colour — cheaper than mask filters (a [BlurMaskFilter] on a
 * software canvas was, twice over, the single largest per-frame cost) and the
 * only way a mark can carry colour and strength that vary continuously along
 * the stroke. Softness is geometry: a band fading to zero alpha across its
 * width just is a soft edge. Blur survives only in single-dab marks and the
 * solid/soft ribbon path.
 */
object StrokeRenderer {

    /**
     * @param dryness live-only, wash strokes: per-point drying progress in
     *   [0,1] (1 = fully dried). Null — the default, and always the bake —
     *   renders fully dry, so the drying animation CONVERGES to the baked
     *   result rather than being a separate look. Indices past the array
     *   (the transient predicted tail) count as freshly laid (0).
     */
    fun paintStroke(
        canvas: Canvas,
        stroke: Stroke,
        surface: SurfaceKind = SurfaceKind.PAPER,
        dryness: FloatArray? = null,
    ) {
        if (stroke.isEmpty) return

        val profile = ToolProfile.of(stroke.tool)
        val rgb = stroke.color or OPAQUE_ALPHA
        val tooth = ToothCache.toothFor(surface, stroke.tool)
        val toothScale = surface.toothScale
        val pts = stroke.points

        // Representative width (for blur) and bounds (to keep layers tight).
        var maxWidth = 0f
        var sumWidth = 0f
        var minX = pts.first().position.x
        var maxX = minX
        var minY = pts.first().position.y
        var maxY = minY
        for (p in pts) {
            maxWidth = max(maxWidth, p.width)
            sumWidth += p.width
            minX = min(minX, p.position.x); maxX = max(maxX, p.position.x)
            minY = min(minY, p.position.y); maxY = max(maxY, p.position.y)
        }
        val avgWidth = sumWidth / pts.size
        val blurSigma = if (profile.blurFactor > 0f) profile.blurFactor * avgWidth else 0f

        when (profile.renderStyle) {
            RenderStyle.GRAIN ->
                paintGrain(canvas, stroke, rgb, tooth, toothScale, maxWidth, minX, minY, maxX, maxY)
            RenderStyle.WASH ->
                paintWash(canvas, stroke, rgb, tooth, toothScale, maxWidth, blurSigma, minX, minY, maxX, maxY, dryness)
            RenderStyle.BRISTLE ->
                paintBristle(canvas, stroke, rgb, tooth, toothScale, maxWidth, avgWidth, minX, minY, maxX, maxY)
            RenderStyle.DROPLET ->
                paintDroplets(canvas, stroke, profile, tooth, toothScale, maxWidth, minX, minY, maxX, maxY)
            RenderStyle.SOLID, RenderStyle.SOFT ->
                paintSolid(canvas, stroke, profile, rgb, tooth, toothScale, maxWidth, blurSigma, minX, minY, maxX, maxY)
        }
    }

    // --- Grain (pencil / marker) --------------------------------------------

    private fun paintGrain(
        canvas: Canvas, stroke: Stroke, rgb: Int, tooth: android.graphics.Bitmap?,
        toothScale: Float, maxWidth: Float, minX: Float, minY: Float, maxX: Float, maxY: Float,
    ) {
        val profile = ToolProfile.of(stroke.tool)
        val pad = maxWidth / 2f + 1f
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val layer = canvas.saveLayer(bounds, null)
        val pts = stroke.points
        val structured = profile.grainFalloff > 0f || profile.grainStreak > 0f ||
            profile.grainChunkPx > 0f
        if (pts.size == 1) {
            val p = pts.first()
            val side = if (structured) grainSide(stroke, p, profile) else 0f
            canvas.drawCircle(
                p.position.x, p.position.y, p.width / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(
                        colorAt(p, rgb),
                        p.density * loadAlpha(p.load) * (1f - GRAIN_SIDE_DENSITY_DROP * side),
                    )
                },
            )
        } else if (structured) {
            paintGrainStructured(canvas, stroke, rgb, profile)
        } else {
            val normals = strokeNormals(pts)
            val verts = FloatArray(pts.size * 4) // 2 vertices/point, 2 floats each
            val colors = IntArray(pts.size * 2)
            for (i in pts.indices) {
                val p = pts[i]
                val hw = max(0.25f, p.width / 2f)
                val left = p.position + normals[i] * hw
                val right = p.position - normals[i] * hw
                // One mesh, so per-vertex alpha can't overlap-accumulate — the
                // grain path folds load straight into the vertex colour and
                // needs no separate mask.
                val col = withAlpha(colorAt(p, rgb), p.density * loadAlpha(p.load))
                verts[i * 4] = left.x; verts[i * 4 + 1] = left.y
                verts[i * 4 + 2] = right.x; verts[i * 4 + 3] = right.y
                colors[i * 2] = col; colors[i * 2 + 1] = col
            }
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLE_STRIP, verts.size, verts, 0,
                null, 0, colors, 0, null, 0, 0,
                Paint().apply { color = Color.WHITE },
            )
        }
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
    }

    // --- Structured grain (graphite) ----------------------------------------

    /** Cross-section lane positions for the structured grain mesh. */
    private val GRAIN_FRACS = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

    /** Along-stroke micro-streak wavelength, buffer px. */
    private const val GRAIN_STREAK_LEN_PX = 7f

    /** Side-of-lead: how much lighter the laid-over lead deposits per area,
     *  and how much it deepens falloff and streaking. */
    private const val GRAIN_SIDE_DENSITY_DROP = 0.45f
    private const val GRAIN_SIDE_FALLOFF_BOOST = 0.6f
    private const val GRAIN_SIDE_STREAK_BOOST = 1.4f

    /** Streak depth a fully dry tip adds (marker felt saturation; load-driven). */
    private const val GRAIN_DRY_STREAK = 0.65f

    /** Turn angle below which the anti-fold bound is skipped (degenerate-math
     *  guard only — the bound itself self-limits at small angles). */
    private const val GRAIN_FOLD_MIN_TURN = 0.02f

    /**
     * How far into the side-of-lead regime this point is, in [0,1]. Derived
     * from the stored width against the stroke's nominal base — for the
     * pencil, width is (almost) purely tilt, so the ratio recovers tilt
     * without storing it per point. 0 when the stroke has no base width.
     */
    private fun grainSide(stroke: Stroke, p: StrokePoint, profile: ToolProfile): Float {
        if (!profile.grainSideRegime || profile.tiltGain <= 0f || stroke.baseWidth <= 0f) return 0f
        val t = ((p.width / stroke.baseWidth - 1f) / profile.tiltGain).coerceIn(0f, 1f)
        return smooth01((t - 0.1f) / 0.6f)
    }

    /**
     * The graphite mesh: [GRAIN_FRACS] lanes across the width instead of a
     * flat two-vertex ribbon, per-vertex —
     *
     *  - CORE-TO-EDGE FALLOFF: deposit thins toward the mark's edge (the
     *    lead bears hardest under its core), deepening as the pencil lays
     *    over onto its side;
     *  - MICRO-STREAKS: seeded value noise along arc length, per lane, the
     *    fine lines a dragging lead leaves; deeper on the side of the lead;
     *  - SIDE-OF-LEAD: the wide tilted mark deposits lighter per area.
     *
     * Emitted in chunks of [ToolProfile.grainChunkPx] of arc length that
     * composite SRC_OVER: a stroke crossing itself darkens there, like
     * layered graphite. Arc length, not points — point spacing varies with
     * pen speed, and a fast loop must build up the same as a slow one.
     * Adjacent chunks share their boundary cross-section exactly (same
     * positions, same colours — pure functions of stored data), so the
     * seams have zero area. Within one chunk overlaps still union; only
     * loops longer than a chunk build up, which is every real crossing.
     * (Note: buildup saturates at high density — two near-black layers
     * barely darken, which is also what graphite does.)
     */
    private fun paintGrainStructured(canvas: Canvas, stroke: Stroke, rgb: Int, profile: ToolProfile) {
        val pts = stroke.points
        val n = pts.size
        val arcs = stroke.arcLengths()
        // Windowed, not central-difference: landing clusters are pure sensor
        // noise at sample spacing, and the dense wet-edge rim lanes make
        // every normal swing visible at the stroke start.
        val normals = windowedNormals(pts, arcs)

        // Anti-fold (the wash's per-sample bound): on a tight inner curve a
        // lane offset past the local centre of curvature crosses its
        // neighbour's cross-section — the fold glob. Bound each point's
        // half-width by d/(2·sin(φ/2)) against both neighbours; the tiny-φ
        // guard only skips degenerate math, the formula self-limits.
        val hwClamp = FloatArray(n) { max(0.25f, pts[it].width / 2f) }
        for (i in 1 until n) {
            val d = (pts[i].position - pts[i - 1].position).distance
            val dot = (normals[i - 1].x * normals[i].x + normals[i - 1].y * normals[i].y)
                .coerceIn(-1f, 1f)
            val phi = kotlin.math.acos(dot)
            if (phi > GRAIN_FOLD_MIN_TURN) {
                val bound = d / (2f * sin(phi / 2f)) * 0.9f
                if (bound < hwClamp[i - 1]) hwClamp[i - 1] = bound
                if (bound < hwClamp[i]) hwClamp[i] = bound
            }
        }
        val chunkPx = if (profile.grainChunkPx > 0f) profile.grainChunkPx else Float.MAX_VALUE
        val verts = FloatArray(n * 4)
        val colors = IntArray(n * 2)
        val paint = Paint()

        // Every (point, lane) colour exactly once — interior lanes feed two
        // strips, and the side/noise math is the live path's real cost (the
        // whole mesh re-renders per frame).
        val lanes = GRAIN_FRACS.size
        val laneCols = IntArray(n * lanes)
        for (i in 0 until n) {
            val p = pts[i]
            val side = grainSide(stroke, p, profile)
            val densBase = p.density * loadAlpha(p.load) * (1f - GRAIN_SIDE_DENSITY_DROP * side)
            // Where the anti-fold clamp bites, the cross-lane structure fades
            // toward the core: the inside of a tight turn is interior ink,
            // not an edge — a pinched wet-edge lane must not read as a rim.
            val clampRatio = (hwClamp[i] / max(0.25f, p.width / 2f)).coerceIn(0f, 1f)
            // A drying felt tip goes streaky before it goes faint, and its
            // pooled wet edge thins away — no ink to pool. Load is 1 for
            // dry media (pencil), so neither term moves there.
            val fall = clampRatio * if (profile.grainFalloff < 0f) {
                profile.grainFalloff * p.load
            } else {
                (profile.grainFalloff * (1f + GRAIN_SIDE_FALLOFF_BOOST * side)).coerceAtMost(0.85f)
            }
            val depth = profile.grainStreak * (0.6f + GRAIN_SIDE_STREAK_BOOST * side) +
                GRAIN_DRY_STREAK * (1f - p.load)
            val base = colorAt(p, rgb)
            for (lane in 0 until lanes) {
                val frac = GRAIN_FRACS[lane]
                val nz = streakNoise(arcs[i] / GRAIN_STREAK_LEN_PX, stroke.seed xor (lane * 0x3779))
                val a = densBase * (1f - fall * frac * frac) * (1f - depth * nz)
                laneCols[i * lanes + lane] = withAlpha(base, a.coerceIn(0f, 1f))
            }
        }
        fun laneColor(i: Int, lane: Int): Int = laneCols[i * lanes + lane]

        var start = 0
        while (start < n - 1) {
            var end = start + 1
            while (end < n - 1 && arcs[end] - arcs[start] < chunkPx) end++
            for (lane in 0 until GRAIN_FRACS.size - 1) {
                var slot = 0
                for (i in start..end) {
                    val p = pts[i]
                    val hw = hwClamp[i]
                    val a = p.position + normals[i] * (GRAIN_FRACS[lane] * hw)
                    val b = p.position + normals[i] * (GRAIN_FRACS[lane + 1] * hw)
                    verts[slot * 4] = a.x; verts[slot * 4 + 1] = a.y
                    verts[slot * 4 + 2] = b.x; verts[slot * 4 + 3] = b.y
                    colors[slot * 2] = laneColor(i, lane)
                    colors[slot * 2 + 1] = laneColor(i, lane + 1)
                    slot++
                }
                canvas.drawVertices(
                    Canvas.VertexMode.TRIANGLE_STRIP, slot * 4, verts, 0,
                    null, 0, colors, 0, null, 0, 0, paint,
                )
            }
            start = end
        }
    }

    // --- Wash (watercolor look; wet/spectral is Stage 4) --------------------

    private fun paintWash(
        canvas: Canvas, stroke: Stroke, rgb: Int, tooth: android.graphics.Bitmap?,
        toothScale: Float, maxWidth: Float, bleed: Float,
        minX: Float, minY: Float, maxX: Float, maxY: Float,
        dryness: FloatArray? = null,
    ) {
        // A clean-water stroke paints nothing of its own — like the eraser, it
        // only acts on what is already there. Its dilute-and-push effect is
        // composited by the canvas view through [paintWetMask].
        if (stroke.water) return
        val halo = bleed * 3f + 2f
        val pad = maxWidth / 2f + halo
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val layer = canvas.saveLayer(bounds, Paint().apply { alpha = alpha255(ToolProfile.of(stroke.tool).opacity) })
        val blur = if (bleed > 0.3f) BlurMaskFilter(bleed, BlurMaskFilter.Blur.NORMAL) else null
        val pts = stroke.points
        if (pts.size == 1) {
            val p = pts.first()
            val d = drynessAt(dryness, 0)
            val r = max(1f, p.width / 2f) * spreadOf(d)
            val c = colorAt(p, rgb)
            val fade = loadAlpha(p.load)
            canvas.drawCircle(p.position.x, p.position.y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(c, (0.6f * fade * wetDarkenOf(d)).coerceAtMost(1f)); maskFilter = blur
            })
            canvas.drawCircle(p.position.x, p.position.y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = max(1.5f, r * 0.4f)
                color = withAlpha(c, 0.95f * fade * rimOf(d)); maskFilter = blur
            })
        } else {
            // The whole wash — fill AND pooled rim — as meshes with per-vertex
            // colour/alpha: the load fades continuously (no span quantization,
            // no crossband "ladder"), the soft edges are geometry rather than a
            // mask blur, and each side's offsets are clamped to the local
            // radius of curvature so cross-sections never fold over themselves
            // on tight curves (the starburst artifact blur used to smear).
            drawWashMesh(canvas, pts, rgb, bleed, maxWidth, dryness)
        }
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
    }

    /**
     * The wet MASK of a watercolor stroke, for the backdrop's dilute and push
     * nodes: the stroke's fill mesh in white, each cross-section's alpha the
     * WETNESS the brush had there — the load it carried (a soaked brush
     * re-wets hard, a spent one is barely damp and barely disturbs anything;
     * clean water never drains), times how far a water stroke's wash-out has
     * DEVELOPED under the drying progression. Null [dryness] (the bake, and
     * pigmented strokes' static interaction) is fully developed.
     */
    fun paintWetMask(canvas: Canvas, stroke: Stroke, dryness: FloatArray?) {
        val pts = stroke.points
        if (pts.isEmpty()) return
        var maxWidth = 0f
        var sumWidth = 0f
        for (p in pts) {
            maxWidth = max(maxWidth, p.width)
            sumWidth += p.width
        }
        val bleed = ToolProfile.of(stroke.tool).blurFactor * (sumWidth / pts.size)
        if (pts.size == 1) {
            val d = drynessAt(dryness, 0)
            canvas.drawCircle(
                pts[0].position.x, pts[0].position.y, max(1f, pts[0].width / 2f) * spreadOf(d),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(Color.WHITE, maskGrowthOf(d) * loadAlpha(pts[0].load))
                },
            )
        } else {
            drawWashMesh(canvas, pts, Color.WHITE, bleed, maxWidth, dryness, mask = true)
        }
    }

    // --- The drying progression (live-only; dry == the baked look) ----------
    //
    // A freshly laid section of wash sits tighter than its final footprint,
    // reads a touch darker (wet pigment is denser than dried), and has almost
    // no rim. As it dries the boundary creeps outward to the final width, the
    // fill lightens to its final strength, and pigment gathers at the edge —
    // the rim develops late, the way a real wash's edge darkens as the last
    // water leaves. All three curves reach exactly 1 at dryness 1, which is
    // what the null-dryness (bake) path renders.

    private fun drynessAt(dryness: FloatArray?, i: Int): Float =
        when {
            dryness == null -> 1f
            i >= dryness.size -> 0f // transient predicted tail: just laid
            else -> dryness[i]
        }

    /** Boundary creep: starts at [WET_SPREAD] of the final width, eases out. */
    private fun spreadOf(d: Float): Float {
        val ease = 1f - (1f - d) * (1f - d)
        return WET_SPREAD + (1f - WET_SPREAD) * ease
    }

    /** Rim development: faint while wet, deepening late in the dry. */
    private fun rimOf(d: Float): Float =
        WET_RIM + (1f - WET_RIM) * d * d * (3f - 2f * d)

    /** Wet fill reads slightly denser; dries lighter to the final strength. */
    private fun wetDarkenOf(d: Float): Float = 1f + WET_DARKEN * (1f - d)

    /** How far a water section's wash-out has developed: gentle at first touch. */
    private fun maskGrowthOf(d: Float): Float {
        val ease = 1f - (1f - d) * (1f - d)
        return MASK_FLOOR + (1f - MASK_FLOOR) * ease
    }

    private const val WET_SPREAD = 0.86f
    private const val WET_RIM = 0.18f
    private const val WET_DARKEN = 0.12f

    /** A just-laid water section already acts at this fraction of full effect. */
    private const val MASK_FLOOR = 0.30f

    // Wash mesh geometry: how sharp a windowed turn must be before the whole
    // cross-section starts necking (surface tension at a reversal), and how
    // many swept segments approximate each round end cap.
    private const val PINCH_START = 1.75f // ~100°
    private const val FOLD_MIN_TURN = 0.02f // atan2 stability guard only
    private const val RIM_FADE_FLOOR = 0.45f // rim is gone once clamped below this
    private const val MIN_EMIT_PX = 1.5f // one cross-section per this much arc
    private const val PI_F = Math.PI.toFloat()
    private const val HALF_PI = (Math.PI / 2).toFloat()
    private const val CAP_SEGS = 5

    /**
     * The whole wash as triangle strips sharing exact vertex lines: a solid
     * core flanked by soft bleed bands (the fill), and a thin peaked band
     * riding each edge (the pooled rim), all tapered to a blunt point past the
     * stroke's ends. Per-vertex colour carries the load fade and any picked-up
     * pigment continuously — no spans, no mask filters.
     *
     * All geometry — tangents, the width envelope, and curvature — is measured
     * over an arc-length WINDOW comparable to the stroke's width, never between
     * adjacent samples: at real pen density (~1.3 px steps) per-sample angles
     * are sensor noise, which grooved the edges and made the curvature clamp
     * flicker. Physically this is surface tension: a wet boundary cannot hold
     * wiggles smaller than the wash is wide.
     *
     * Fold-proofing: on the inner side of a turn, any offset beyond the local
     * radius of curvature makes consecutive cross-sections cross, stacking the
     * translucent mesh into dark radial streaks. Each side's offsets are
     * clamped to that (windowed) radius, so a genuinely tight curve pinches —
     * as real paint does — instead of bursting.
     */
    private fun drawWashMesh(
        canvas: Canvas,
        pts: List<StrokePoint>,
        rgb: Int,
        bleed: Float,
        maxWidth: Float,
        dryness: FloatArray? = null,
        mask: Boolean = false,
    ) {
        val n = pts.size
        val soft = max(1f, bleed)
        val rimHalf = max(0.75f, maxWidth * 0.08f) + soft * 0.5f
        val win = max(3f, maxWidth * 0.45f)

        // Cumulative arc length + width prefix sums for O(n) windowing.
        val arc = FloatArray(n)
        val prefW = FloatArray(n + 1)
        for (i in 0 until n) {
            if (i > 0) arc[i] = arc[i - 1] + (pts[i].position - pts[i - 1].position).distance
            prefW[i + 1] = prefW[i] + pts[i].width
        }

        val nx = FloatArray(n); val ny = FloatArray(n) // windowed normals
        val coreL = FloatArray(n); val coreR = FloatArray(n)
        val outerL = FloatArray(n); val outerR = FloatArray(n)
        val rimPkL = FloatArray(n); val rimPkR = FloatArray(n)
        val rimOutL = FloatArray(n); val rimOutR = FloatArray(n)
        val rimInL = FloatArray(n); val rimInR = FloatArray(n)
        val coreCol = IntArray(n); val edgeCol = IntArray(n)
        val rimColL = IntArray(n); val rimColR = IntArray(n); val rimEdgeCol = IntArray(n)

        val hwA = FloatArray(n)
        val limL = FloatArray(n) { Float.MAX_VALUE }
        val limR = FloatArray(n) { Float.MAX_VALUE }
        val pinchA = FloatArray(n) { 1f }

        // Pass 1 — windowed geometry: normals, width envelope, curvature
        // limits, and the reversal pinch.
        var j = 0 // window start: farthest point within win behind i
        var k = 0 // window end: farthest point within win ahead of i
        for (i in 0 until n) {
            while (j < i && arc[i] - arc[j] > win) j++
            if (k < i) k = i
            while (k < n - 1 && arc[k + 1] - arc[i] <= win) k++

            val p = pts[i].position
            // Windowed tangent. When the window DEGENERATES (its ends nearly
            // meet — a slow cluster of samples at pen-down, or a true hairpin
            // tip), do NOT fall back to an adjacent-sample difference: at that
            // scale direction is sensor noise (the Phase-1 lesson), and one
            // noisy normal cascades through the anti-fold clamps as a phantom
            // reversal — the start slash, the jagged inner edges. Carry the
            // previous direction instead; at the first point, look further
            // ahead until the chord is meaningful.
            var dx = pts[k].position.x - pts[j].position.x
            var dy = pts[k].position.y - pts[j].position.y
            var len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len < win * 0.25f && i > 0) {
                nx[i] = nx[i - 1]
                ny[i] = ny[i - 1]
            } else {
                if (len < win * 0.25f) {
                    var kk = k
                    while (kk < n - 1 && len < win * 0.5f) {
                        kk++
                        dx = pts[kk].position.x - p.x
                        dy = pts[kk].position.y - p.y
                        len = kotlin.math.sqrt(dx * dx + dy * dy)
                    }
                }
                if (len < 1e-4f) { dx = 1f; dy = 0f; len = 1f }
                nx[i] = -dy / len
                ny[i] = dx / len
            }

            // Windowed width envelope (surface tension smooths the boundary),
            // scaled by the drying creep: a young section sits tighter than
            // its final footprint. Dryness varies smoothly along the stroke
            // (points age in laid order), so the boundary stays smooth.
            hwA[i] = max(0.5f, (prefW[k + 1] - prefW[j]) / (k - j + 1) / 2f) *
                spreadOf(drynessAt(dryness, i))

            // Windowed curvature -> radius; only the turn's inner side clamps.
            // BOTH chords must be long enough to be geometry: inside a slow
            // cluster of samples (a pen landing's pressure ramp, a lingering
            // turn) a chord is a few px of position noise, and the radius it
            // yields collapses one side of the mesh to the centerline for a
            // sample or two — the hairline slash at stroke starts and the
            // sharp-edged glops in tight turns. Real folds that a skipped
            // clamp would have caught are handled by the per-sample anti-fold
            // bound below, which works from noise-smoothed normals.
            if (j < i && k > i) {
                val v1x = p.x - pts[j].position.x; val v1y = p.y - pts[j].position.y
                val v2x = pts[k].position.x - p.x; val v2y = pts[k].position.y - p.y
                val l1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y)
                val l2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y)
                if (l1 > win * 0.35f && l2 > win * 0.35f) {
                    val cross = v1x * v2y - v1y * v2x
                    val dot = v1x * v2x + v1y * v2y
                    val theta = kotlin.math.atan2(kotlin.math.abs(cross), dot)
                    if (theta > 1e-3f) {
                        // Chords underestimate the radius ~2x over this window.
                        val radius = 2f * min(l1, l2) / theta
                        if (cross > 0f) limL[i] = radius else limR[i] = radius
                    }
                    // Past a sharp windowed turn the inner-side clamp isn't
                    // enough: at a hairpin BOTH sides fold. Surface tension
                    // necks the whole cross-section toward the tip instead.
                    if (theta > PINCH_START) {
                        pinchA[i] = ((PI_F - theta) / (PI_F - PINCH_START)).coerceIn(0.03f, 1f)
                    }
                }
            }
        }

        // Pass 2 — normal-hemisphere continuity: at a reversal the windowed
        // tangent can flip sign between neighbours, swapping which geometric
        // side "left" lands on; cross-sections then cross into a bowtie that
        // stacks the translucent mesh dark. Keep each normal in its
        // predecessor's hemisphere (with its side limits). Ordinary curves
        // never rotate 90°+ between adjacent samples, so this only acts there.
        for (i in 1 until n) {
            if (nx[i] * nx[i - 1] + ny[i] * ny[i - 1] < 0f) {
                nx[i] = -nx[i]; ny[i] = -ny[i]
                val t = limL[i]; limL[i] = limR[i]; limR[i] = t
            }
        }

        // Pass 3 — per-sample anti-fold bound: whatever the smoothed window
        // estimates, an inner offset larger than THIS pair's turning radius
        // makes these two cross-sections' edges cross — at slow, closely
        // spaced samples even a few degrees of rotation crosses a wide
        // section (rotation x half-width vs spacing), which is the hairline
        // sliver at stroke starts and the needles in slow tight turns. The
        // bound is self-limiting — tiny rotations yield huge, inactive
        // bounds — so it applies at (almost) every angle; the normals are
        // window-smoothed, so sensor noise never reaches it.
        for (i in 0 until n - 1) {
            val cross = nx[i] * ny[i + 1] - ny[i] * nx[i + 1]
            val dot = nx[i] * nx[i + 1] + ny[i] * ny[i + 1]
            val phi = kotlin.math.atan2(kotlin.math.abs(cross), dot)
            if (phi > FOLD_MIN_TURN) {
                val d = (pts[i + 1].position - pts[i].position).distance
                val bound = max(0.5f, d / (2f * kotlin.math.sin(phi / 2f)) * 0.9f)
                if (cross > 0f) {
                    limL[i] = min(limL[i], bound); limL[i + 1] = min(limL[i + 1], bound)
                } else {
                    limR[i] = min(limR[i], bound); limR[i + 1] = min(limR[i + 1], bound)
                }
            }
        }

        // Pass 4 — offsets and colours from the final limits.
        for (i in 0 until n) {
            val dry = drynessAt(dryness, i)
            val hw = hwA[i]
            val limitL = limL[i]
            val limitR = limR[i]
            val pinch = pinchA[i]
            val pkL = min(hw, limitL * 0.85f)
            val pkR = min(hw, limitR * 0.85f)
            coreL[i] = min(max(0.1f, hw - soft), limitL * 0.75f) * pinch
            coreR[i] = min(max(0.1f, hw - soft), limitR * 0.75f) * pinch
            outerL[i] = min(hw + soft, limitL * 0.95f) * pinch
            outerR[i] = min(hw + soft, limitR * 0.95f) * pinch
            rimPkL[i] = pkL * pinch
            rimPkR[i] = pkR * pinch
            rimOutL[i] = min(pkL + rimHalf, limitL * 0.98f) * pinch
            rimOutR[i] = min(pkR + rimHalf, limitR * 0.98f) * pinch
            rimInL[i] = max(0.05f, pkL - rimHalf) * pinch
            rimInR[i] = max(0.05f, pkR - rimHalf) * pinch

            if (mask) {
                // The dilute mask: the punch is only as strong as the brush was
                // wet here (load), deepening as a water section dries.
                coreCol[i] = withAlpha(rgb, maskGrowthOf(dry) * loadAlpha(pts[i].load))
                edgeCol[i] = coreCol[i] and 0x00FFFFFF
                rimColL[i] = 0
                rimColR[i] = 0
                rimEdgeCol[i] = 0
            } else {
                val a = loadAlpha(pts[i].load)
                val c = colorAt(pts[i], rgb)
                coreCol[i] = withAlpha(c, (0.6f * a * wetDarkenOf(dry)).coerceAtMost(1f))
                edgeCol[i] = coreCol[i] and 0x00FFFFFF
                // A pooled rim only exists at an OUTER boundary of the wet
                // region. Where a side is curvature-clamped (the inside of a
                // tight turn), that edge is interior wash — a rim riding a
                // clamped edge fans into sharp dark needles. Fade it out
                // entirely once the clamp bites deep.
                val rimA = 0.95f * a * rimOf(dry)
                val fadeL = ((pkL / hw - RIM_FADE_FLOOR) / (1f - RIM_FADE_FLOOR)).coerceIn(0f, 1f)
                val fadeR = ((pkR / hw - RIM_FADE_FLOOR) / (1f - RIM_FADE_FLOOR)).coerceIn(0f, 1f)
                rimColL[i] = withAlpha(c, rimA * fadeL * fadeL)
                rimColR[i] = withAlpha(c, rimA * fadeR * fadeR)
                rimEdgeCol[i] = rimColL[i] and 0x00FFFFFF
            }
        }

        // A cross-section is only emitted once the pen has ADVANCED past the
        // previous one along its own tangent. A landing (or a lingering turn)
        // zigzags near-stationary samples back and forth — the input
        // decimator accepts them whenever pressure changes — and sections
        // that step backward lay their quads over the forward ones: a stacked
        // dark bar across the stroke. Arc length can't see the difference
        // (it always accumulates); forward advance can. Widths and colours
        // interpolate across the skipped samples.
        val emit = BooleanArray(n)
        var lastEmitX = 0f
        var lastEmitY = 0f
        for (i in 0 until n) {
            if (i == 0 || i == n - 1) {
                emit[i] = true
                lastEmitX = pts[i].position.x
                lastEmitY = pts[i].position.y
                continue
            }
            val advance = (pts[i].position.x - lastEmitX) * ny[i] -
                (pts[i].position.y - lastEmitY) * nx[i] // projection on tangent
            if (advance >= MIN_EMIT_PX) {
                emit[i] = true
                lastEmitX = pts[i].position.x
                lastEmitY = pts[i].position.y
            }
        }

        // Both ends close with a ROUND cap: the last cross-section swept
        // around the endpoint toward the outward tangent, so every band —
        // soft bleed, fill, and the pooled rim — wraps the tip the way a wet
        // stroke's end actually pools, instead of collapsing to a beak.
        val verts = FloatArray((n + 2 * CAP_SEGS) * 4)
        val colors = IntArray((n + 2 * CAP_SEGS) * 2)
        val paint = Paint()

        fun strip(
            sideA: Float, offA: FloatArray, colA: IntArray,
            sideB: Float, offB: FloatArray, colB: IntArray,
        ) {
            var slot = 0
            fun put(ax: Float, ay: Float, bx: Float, by: Float, ca: Int, cb: Int) {
                verts[slot * 4] = ax; verts[slot * 4 + 1] = ay
                verts[slot * 4 + 2] = bx; verts[slot * 4 + 3] = by
                colors[slot * 2] = ca; colors[slot * 2 + 1] = cb
                slot++
            }
            // The cross-section at [i] rotated by [swing] radians from the
            // normal toward the outward tangent direction ([back] = -1 at the
            // head, +1 at the tail).
            fun putSwung(i: Int, back: Float, swing: Float) {
                val p = pts[i].position
                val tx = ny[i] * back
                val ty = -nx[i] * back
                val ca = kotlin.math.cos(swing)
                val sa = kotlin.math.sin(swing)
                put(
                    p.x + (nx[i] * sideA * ca + tx * sa) * offA[i],
                    p.y + (ny[i] * sideA * ca + ty * sa) * offA[i],
                    p.x + (nx[i] * sideB * ca + tx * sa) * offB[i],
                    p.y + (ny[i] * sideB * ca + ty * sa) * offB[i],
                    colA[i], colB[i],
                )
            }
            for (s in CAP_SEGS downTo 1) putSwung(0, -1f, s * HALF_PI / CAP_SEGS)
            for (i in 0 until n) if (emit[i]) putSwung(i, 1f, 0f)
            for (s in 1..CAP_SEGS) putSwung(n - 1, 1f, s * HALF_PI / CAP_SEGS)
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLE_STRIP, slot * 4, verts, 0,
                null, 0, colors, 0, null, 0, 0, paint,
            )
        }

        // Fill: bleed band, core half, core half, bleed band. The core is
        // SPLIT AT THE SPINE so no strip crosses the stroke's centerline:
        // an across-center strip swept around an end cap rotates full-width
        // bars through the pivot, and every swept quad self-intersects there
        // — a double-covered lens that reads as a dark bar across the start.
        // One-sided strips sweep as clean fans instead.
        val spine = FloatArray(n)
        strip(1f, outerL, edgeCol, 1f, coreL, coreCol)
        strip(1f, coreL, coreCol, 1f, spine, coreCol)
        strip(-1f, spine, coreCol, -1f, coreR, coreCol)
        strip(-1f, coreR, coreCol, -1f, outerR, edgeCol)
        if (mask) return // the mask is fill only — water pools no pigment rim
        // Rim: a soft peak riding each edge, over the fill.
        strip(1f, rimOutL, rimEdgeCol, 1f, rimPkL, rimColL)
        strip(1f, rimPkL, rimColL, 1f, rimInL, rimEdgeCol)
        strip(-1f, rimInR, rimEdgeCol, -1f, rimPkR, rimColR)
        strip(-1f, rimPkR, rimColR, -1f, rimOutR, rimEdgeCol)
    }

    // --- Bristle (brush) ----------------------------------------------------

    private fun paintBristle(
        canvas: Canvas, stroke: Stroke, rgb: Int, tooth: android.graphics.Bitmap?,
        toothScale: Float, maxWidth: Float, avgWidth: Float,
        minX: Float, minY: Float, maxX: Float, maxY: Float,
    ) {
        val profile = ToolProfile.of(stroke.tool)
        val layout = BristleLayout(stroke, maxWidth)
        val pad = maxWidth / 2f + layout.smear * 3f + 2f
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val layer = canvas.saveLayer(bounds, Paint().apply { alpha = alpha255(profile.opacity) })
        val pts = stroke.points
        if (pts.size == 1) {
            drawBristleDab(canvas, stroke, pts.first(), rgb, layout)
        } else {
            // The landing pool: pressed at pen-down, before travel — and it
            // seats the fan's birth, which has no direction to speak of yet.
            drawBristleDab(canvas, stroke, pts.first(), rgb, layout, withCrown = false)
            val normals = strokeNormals(pts).toMutableList()
            windowedTailNormal(pts, bristleTailWindow(layout, pts))?.let {
                normals[normals.size - 1] = it
            }
            val arcs = stroke.arcLengths()
            val ink = IntArray(pts.size) { i ->
                withAlpha(colorAt(pts[i], rgb), brushLoadAlpha(pts[i].load))
            }
            // Reused across bristles; two slots spare for the tapered ends.
            val verts = FloatArray((pts.size + 2) * 4)
            val colors = IntArray((pts.size + 2) * 2)
            val alphaS = FloatArray(pts.size)
            val widthS = FloatArray(pts.size)
            val meshPaint = Paint()
            for (b in 0 until layout.count) {
                fillBristleScales(layout, b, pts, arcs, 0, pts.size - 1, alphaS, widthS)
                bristleSpan(
                    canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], -1f,
                    0, pts.size - 1, withHead = true, withTail = true, verts, colors, meshPaint,
                    alphaScale = alphaS, widthScale = widthS,
                )
                bristleSpan(
                    canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], 1f,
                    0, pts.size - 1, withHead = true, withTail = true, verts, colors, meshPaint,
                    alphaScale = alphaS, widthScale = widthS,
                )
            }
        }
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
    }

    /**
     * A single pressed dab: a brush pushed straight down leaves a pooled
     * core with a radial crown — the hairs splay outward in every direction,
     * not along some travel line. Each crown mark keeps its bristle's
     * identity (thickness, strength, supply, engagement), so a light touch
     * is a soft pool with a few hairs and a spent brush leaves almost
     * nothing. Deterministic from the stroke seed, so the live preview and
     * the bake agree. Also drawn — [withCrown] false, core only — under
     * every stroke's first point (see [paintBristle] /
     * [appendBristleSegments]): the landing pool is real, and it seats the
     * fan while the stroke is still too short to know its direction. The
     * crown stays off there because travel drags those hairs into the
     * stroke; left on, it reads as a sunburst poking out of the start.
     */
    private fun drawBristleDab(
        canvas: Canvas, stroke: Stroke, p: StrokePoint, rgb: Int, layout: BristleLayout,
        withCrown: Boolean = true,
    ) {
        val ink = withAlpha(colorAt(p, rgb), brushLoadAlpha(p.load))
        val blur = if (layout.smear > 0.3f) BlurMaskFilter(layout.smear, BlurMaskFilter.Blur.NORMAL) else null
        canvas.drawCircle(
            p.position.x, p.position.y, p.width * DAB_CORE_RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = scaleAlpha(ink, DAB_CORE_ALPHA); maskFilter = blur
            },
        )
        if (!withCrown) return
        val theta = bristleHash(0, stroke.seed) * (2.0 * PI).toFloat()
        val arcs = stroke.arcLengths()
        val alphaS = FloatArray(1)
        val widthS = FloatArray(1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND; maskFilter = blur
        }
        for (b in 0 until layout.count) {
            fillBristleScales(layout, b, stroke.points, arcs, 0, 0, alphaS, widthS)
            if (alphaS[0] <= 0.02f) continue
            // The fan's cross positions unroll around the full circle, plus a
            // seeded wobble — a crown, not a comb.
            val ang = theta + layout.fracs[b] * PI.toFloat() +
                (bristleHash(b + 1, layout.salts[b]) - 0.5f) * 0.5f
            val bx = cos(ang)
            val by = sin(ang)
            val r0 = p.width * DAB_CROWN_START
            val r1 = r0 + p.width *
                (DAB_LEN_LO + (DAB_LEN_HI - DAB_LEN_LO) * bristleHash(b + 77, layout.salts[b]))
            paint.color = scaleAlpha(ink, alphaS[0] * DAB_CROWN_ALPHA)
            paint.strokeWidth = (layout.halves[b] * 2f * widthS[0] * 0.8f).coerceAtLeast(0.6f)
            canvas.drawLine(
                p.position.x + bx * r0, p.position.y + by * r0,
                p.position.x + bx * r1, p.position.y + by * r1, paint,
            )
        }
    }

    /**
     * Appends the bristle geometry for the active stroke's points beyond
     * [accumPts] (how many leading points are already appended) onto [canvas] —
     * RAW, with no opacity layer and no tooth; the caller's blit applies those.
     * Only points whose geometry is final are appended (a point's normal moves
     * until its successor exists), so the last point stays out. Returns the new
     * accumPts. [drawBristleLiveTail] draws that provisional tail each frame.
     */
    fun appendBristleSegments(canvas: Canvas, stroke: Stroke, layout: BristleLayout, accumPts: Int): Int {
        val pts = stroke.points
        val n = pts.size
        val target = n - 1 // leave the live tail point out
        if (n < 2 || accumPts >= target) return accumPts
        val from = max(0, accumPts - 1) // share the vertex line with what's drawn
        val to = target - 1
        val rgb = stroke.color or OPAQUE_ALPHA
        // First append: lay the landing pool under the fan, exactly as the
        // bake will (drawn once — the accumulator persists it).
        if (accumPts == 0) {
            drawBristleDab(canvas, stroke, pts.first(), rgb, layout, withCrown = false)
        }
        // Range-local normals: all of [from..to] have both neighbours, so they
        // are final and identical to a whole-stroke computation.
        val normals = strokeNormalsRange(pts, from, to)
        val arcs = stroke.arcLengths()
        val ink = IntArray(to - from + 1) { k ->
            val p = pts[from + k]
            withAlpha(colorAt(p, rgb), brushLoadAlpha(p.load))
        }
        val verts = FloatArray((to - from + 3) * 4)
        val colors = IntArray((to - from + 3) * 2)
        val alphaS = FloatArray(to - from + 1)
        val widthS = FloatArray(to - from + 1)
        val paint = Paint()
        for (b in 0 until layout.count) {
            fillBristleScales(layout, b, pts, arcs, from, to, alphaS, widthS)
            bristleSpan(
                canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], -1f,
                from, to, withHead = from == 0, withTail = false, verts, colors, paint, base = from,
                alphaScale = alphaS, widthScale = widthS,
            )
            bristleSpan(
                canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], 1f,
                from, to, withHead = from == 0, withTail = false, verts, colors, paint, base = from,
                alphaScale = alphaS, widthScale = widthS,
            )
        }
        return target
    }

    /**
     * The live tail: the last [tailPoints] points plus the tapered end, redrawn
     * per frame (3 when a predicted point rides beyond the last real one).
     */
    fun drawBristleLiveTail(canvas: Canvas, stroke: Stroke, layout: BristleLayout, tailPoints: Int = 2) {
        val pts = stroke.points
        val n = pts.size
        if (n == 1) {
            drawBristleDab(canvas, stroke, pts.first(), stroke.color or OPAQUE_ALPHA, layout)
            return
        }
        val from = max(0, n - tailPoints)
        val count = n - from
        val rgb = stroke.color or OPAQUE_ALPHA
        val normals = strokeNormalsRange(pts, from, n - 1).toMutableList()
        // The raw tail normal swings with every sensor wobble and pivots the
        // whole fan around the newest point; measure it over a brush-width
        // window instead. The bake does the same, so pen-up changes nothing.
        windowedTailNormal(pts, bristleTailWindow(layout, pts))?.let {
            normals[normals.size - 1] = it
        }
        val arcs = stroke.arcLengths()
        val ink = IntArray(count) { k ->
            val p = pts[from + k]
            withAlpha(colorAt(p, rgb), brushLoadAlpha(p.load))
        }
        val verts = FloatArray((count + 2) * 4)
        val colors = IntArray((count + 2) * 2)
        val alphaS = FloatArray(count)
        val widthS = FloatArray(count)
        val paint = Paint()
        for (b in 0 until layout.count) {
            fillBristleScales(layout, b, pts, arcs, from, n - 1, alphaS, widthS)
            bristleSpan(
                canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], -1f,
                from, n - 1, withHead = false, withTail = true, verts, colors, paint, base = from,
                alphaScale = alphaS, widthScale = widthS,
            )
            bristleSpan(
                canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], 1f,
                from, n - 1, withHead = false, withTail = true, verts, colors, paint, base = from,
                alphaScale = alphaS, widthScale = widthS,
            )
        }
    }

    /**
     * Half of one bristle over points [from..to]: a mesh running from its
     * transparent edge to its solid core, on the [side] given (-1 left, +1
     * right). Two halves make a bristle that fades out at both edges.
     *
     * Bristles are meshes rather than blurred strokes because a mask filter
     * cannot ride on [Canvas.drawVertices], and the mesh is what buys everything
     * else: it carries a colour and a strength at *every* point, so a fading or
     * contaminated bristle costs no mask and no extra draw call — and it
     * composites normally, so a stroke crossing itself unions instead of erasing
     * its own opening. The softness the blur used to provide is built into the
     * geometry: a thin bar blurred is a soft-edged bar, so the mesh just is one.
     *
     * [withHead]/[withTail] add the tapered end caps — only the stroke's true
     * ends get them; incremental appends join flush at shared points instead.
     * [base] is the point index that [normals], [ink], [alphaScale] and
     * [widthScale] entry 0 correspond to (range-local arrays). [verts] and
     * [colors] are scratch owned by the caller; this runs dozens of times a
     * frame and must not allocate.
     *
     * [alphaScale]/[widthScale] are this bristle's per-point supply and edge
     * modulation (see [fillBristleScales]) — pure functions of stored stroke
     * data, so an incremental append and the bake agree at shared vertices.
     */
    private fun bristleSpan(
        canvas: Canvas,
        pts: List<StrokePoint>,
        normals: List<Vec2>,
        ink: IntArray,
        frac: Float,
        halfWidth: Float,
        side: Float,
        from: Int,
        to: Int,
        withHead: Boolean,
        withTail: Boolean,
        verts: FloatArray,
        colors: IntArray,
        paint: Paint,
        base: Int = 0,
        alphaScale: FloatArray? = null,
        widthScale: FloatArray? = null,
    ) {
        fun normalAt(i: Int) = normals[i - base]
        fun spineAt(i: Int) = pts[i].position + normalAt(i) * (frac * pts[i].width / 2f)
        fun halfAt(i: Int) = halfWidth * (widthScale?.get(i - base) ?: 1f)
        fun inkAt(i: Int): Int {
            val c = ink[i - base]
            val s = alphaScale?.get(i - base) ?: 1f
            return if (s >= 1f) c else scaleAlpha(c, s)
        }

        var slot = 0
        fun put(spine: Vec2, normal: Vec2, inkColor: Int, hw: Float) {
            val edge = spine + normal * (side * hw)
            verts[slot * 4] = edge.x; verts[slot * 4 + 1] = edge.y
            verts[slot * 4 + 2] = spine.x; verts[slot * 4 + 3] = spine.y
            // Same hue at the edge, no coverage — so it fades out rather than
            // fringing toward another colour.
            colors[slot * 2] = inkColor and 0x00FFFFFF
            colors[slot * 2 + 1] = inkColor
            slot++
        }

        // A stroked path had round caps, and the blur softened them further; a
        // bare mesh ends flat, which lands as a straight edge across the mark —
        // glaring at the start of an enso, where the faded tail sweeps in behind
        // it. Taper the true ends to nothing instead.
        if (withHead) {
            val headDir = tangentOf(normalAt(from))
            put(spineAt(from) - headDir * halfAt(from), normalAt(from), ink[from - base] and 0x00FFFFFF, halfAt(from))
        }
        for (i in from..to) put(spineAt(i), normalAt(i), inkAt(i), halfAt(i))
        if (withTail) {
            val tailDir = tangentOf(normalAt(to))
            put(spineAt(to) + tailDir * halfAt(to), normalAt(to), ink[to - base] and 0x00FFFFFF, halfAt(to))
        }

        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLE_STRIP, slot * 4, verts, 0,
            null, 0, colors, 0, null, 0, 0, paint,
        )
    }

    /** The direction of travel that goes with a stroke normal. */
    private fun tangentOf(normal: Vec2) = Vec2(normal.y, -normal.x)

    // --- Shared solid / soft path (pen, spray, eraser) ----------------------

    private fun paintSolid(
        canvas: Canvas, stroke: Stroke, profile: ToolProfile, rgb: Int,
        tooth: android.graphics.Bitmap?, toothScale: Float, maxWidth: Float, blurSigma: Float,
        minX: Float, minY: Float, maxX: Float, maxY: Float,
    ) {
        val isEraser = stroke.tool == Tool.ERASER
        // The eraser always works through a DST_OUT layer so its per-point
        // density can LIFT partially — a light touch thins the paint, a hard
        // press removes it (with tooth, residue survives in the valleys).
        val needsLayer = isEraser || profile.opacity < 1f || blurSigma > 0f || tooth != null
        val pad = maxWidth / 2f + blurSigma * 3f + 1f
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)

        var layer = -1
        if (needsLayer) {
            val lp = Paint()
            if (profile.opacity < 1f) lp.alpha = alpha255(profile.opacity)
            if (isEraser) lp.blendMode = BlendMode.DST_OUT
            layer = canvas.saveLayer(bounds, lp)
        }

        val drawColor = if (isEraser) Color.WHITE else rgb
        val blur = if (blurSigma > 0f) BlurMaskFilter(blurSigma, BlurMaskFilter.Blur.NORMAL) else null
        val pts = stroke.points

        fun basePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = drawColor
            maskFilter = blur
        }

        // Constant-width, constant-density strokes keep the fitted smooth
        // path; anything that varies (pen speed thinning, eraser pressure)
        // renders per span. Data-driven, so tools that don't vary lose nothing.
        var varies = false
        for (p in pts) {
            if (kotlin.math.abs(p.width - pts[0].width) > 0.01f ||
                kotlin.math.abs(p.density - pts[0].density) > 0.01f
            ) {
                varies = true
                break
            }
        }

        when {
            pts.size == 1 -> {
                val p = pts.first()
                canvas.drawCircle(
                    p.position.x, p.position.y, p.width / 2f,
                    basePaint().apply { alpha = (alpha * p.density).toInt() },
                )
            }
            !varies -> {
                canvas.drawPath(smoothPath(pts), basePaint().apply {
                    style = Paint.Style.STROKE; strokeWidth = pts.first().width
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                    alpha = (alpha * pts.first().density).toInt()
                })
            }
            else -> {
                // Spans of near-constant density, one stroked path each —
                // per-segment caps under translucent lift would dot every
                // joint; spans overlap caps only where the density steps.
                val paint = basePaint().apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                }
                var from = 0
                while (from < pts.size - 1) {
                    val runDensity = pts[from].density
                    var to = from + 1
                    while (to < pts.size - 1 &&
                        kotlin.math.abs(pts[to].density - runDensity) < ALPHA_STEP
                    ) {
                        to++
                    }
                    paint.alpha = alpha255(runDensity)
                    for (i in from + 1..to) {
                        val a = pts[i - 1]; val b = pts[i]
                        paint.strokeWidth = (a.width + b.width) / 2f
                        canvas.drawLine(a.position.x, a.position.y, b.position.x, b.position.y, paint)
                    }
                    from = to
                }
            }
        }

        // Pen ink pooling (dwell-grown end/mid-stroke discs) was built here
        // and REMOVED at the user's verdict — the onset/feel never sat
        // right. Pinned to docs/tool-ideas.md; implementation in history at
        // 944c81a (diffusion curve, PenPool records, movement-based dwell).

        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        if (needsLayer) canvas.restoreToCount(layer)
    }

    // --- Droplet field (spray) ----------------------------------------------

    /**
     * Droplets emitted per accepted point. Decimation spaces points by
     * travel, so a slow pass lays more points per mm of path — dwell density
     * for free, deterministic from stored points.
     */
    private const val DROPS_PER_POINT = 40

    /** Scatter radius as a fraction of the half-width — the cone overshoots
     *  the nominal width a touch, as a real can does. */
    const val DROP_SCATTER = 1.15f

    /** r = R·u^pow: density thins toward the rim — the soft edge is
     *  statistics, not blur. */
    private const val DROP_EDGE_POW = 0.7f

    /** Fine-droplet diameter range (px), and the rare fat spatter. */
    private const val DROP_SIZE_LO = 0.8f
    private const val DROP_SIZE_HI = 1.9f
    private const val SPATTER_CHANCE = 0.055f
    private const val SPATTER_SIZE_LO = 2.6f
    private const val SPATTER_SIZE_HI = 5.2f

    /** Droplets are batched into one drawPoints per size bucket. */
    private const val DROP_BUCKETS = 8

    // Drips (runs shed by heavy buildup) were built here and REMOVED at the
    // user's verdict — the tool settled as a stipple/spray-can field. If a
    // wet "spray paint" tool is ever built, the lookback-pool approach is
    // in history at 632046f (pool radius must be small ABSOLUTE px, or
    // ordinary travel self-qualifies as buildup).

    /**
     * The bake: the whole droplet field inside one opacity layer, tooth on
     * top — identical droplets to what the live accumulator laid, because
     * both run [appendDropletSegments] over the same seeded hashes. That
     * identity is what retired the old soft path's pen-up size snap.
     */
    private fun paintDroplets(
        canvas: Canvas, stroke: Stroke, profile: ToolProfile, tooth: android.graphics.Bitmap?,
        toothScale: Float, maxWidth: Float, minX: Float, minY: Float, maxX: Float, maxY: Float,
    ) {
        val pad = maxWidth / 2f * DROP_SCATTER + SPATTER_SIZE_HI + 1f
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val layer = canvas.saveLayer(bounds, Paint().apply { alpha = alpha255(profile.opacity) })
        appendDropletSegments(canvas, stroke, 0)
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
    }

    /**
     * Appends the droplets for points [fromIndex] onward, RAW (no opacity
     * layer, no tooth — the caller's blit or bake layer applies those once).
     * Every droplet is a pure function of (stroke seed, point index, droplet
     * index), so the live accumulator, the transient predicted tail and the
     * bake all lay exactly the same field.
     */
    fun appendDropletSegments(canvas: Canvas, stroke: Stroke, fromIndex: Int) {
        val pts = stroke.points
        if (pts.isEmpty() || fromIndex >= pts.size) return
        val rgb = stroke.color or OPAQUE_ALPHA
        val seed = stroke.seed

        // Two passes: count per size bucket, then fill — one allocation and
        // one drawPoints per bucket.
        val counts = IntArray(DROP_BUCKETS)
        forEachDroplet(pts, fromIndex, seed) { _, _, bucket -> counts[bucket]++ }
        val buckets = Array(DROP_BUCKETS) { FloatArray(counts[it] * 2) }
        val fill = IntArray(DROP_BUCKETS)
        forEachDroplet(pts, fromIndex, seed) { x, y, bucket ->
            val k = fill[bucket]
            buckets[bucket][k] = x
            buckets[bucket][k + 1] = y
            fill[bucket] = k + 2
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (b in 0 until DROP_BUCKETS) {
            if (counts[b] == 0) continue
            paint.color = rgb
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = bucketSize(b)
            canvas.drawPoints(buckets[b], 0, counts[b] * 2, paint)
        }
    }

    /** The droplet diameter bucket [b] renders at (bucket midpoints). */
    private fun bucketSize(b: Int): Float {
        val t = (b + 0.5f) / DROP_BUCKETS
        return DROP_SIZE_LO + (SPATTER_SIZE_HI - DROP_SIZE_LO) * t
    }

    private inline fun forEachDroplet(
        pts: List<StrokePoint>,
        fromIndex: Int,
        seed: Int,
        emit: (x: Float, y: Float, bucket: Int) -> Unit,
    ) {
        for (i in fromIndex until pts.size) {
            val p = pts[i]
            val r0 = p.width / 2f * DROP_SCATTER
            for (d in 0 until DROPS_PER_POINT) {
                val cell = i * DROPS_PER_POINT + d
                val ang = bristleHash(cell, seed) * (2.0 * PI).toFloat()
                val ru = bristleHash(cell, seed xor 0x51ed270b)
                val su = bristleHash(cell, seed xor 0x2c9277b5)
                val r = r0 * ru.pow(DROP_EDGE_POW)
                val size = if (su < SPATTER_CHANCE) {
                    SPATTER_SIZE_LO + (SPATTER_SIZE_HI - SPATTER_SIZE_LO) * (su / SPATTER_CHANCE)
                } else {
                    DROP_SIZE_LO + (DROP_SIZE_HI - DROP_SIZE_LO) *
                        ((su - SPATTER_CHANCE) / (1f - SPATTER_CHANCE))
                }
                val t = ((size - DROP_SIZE_LO) / (SPATTER_SIZE_HI - DROP_SIZE_LO))
                    .coerceIn(0f, 0.999f)
                val bucket = (t * DROP_BUCKETS).toInt()
                emit(
                    p.position.x + cos(ang) * r,
                    p.position.y + sin(ang) * r,
                    bucket,
                )
            }
        }
    }

    // --- Brush load ---------------------------------------------------------

    /**
     * How a draining brush fades. Below 1 the mark holds its strength through
     * most of the load and then drops away near the end, rather than dimming
     * linearly from the first stroke.
     */
    private const val LOAD_FADE_EXP = 0.7f

    /** Alpha the mark carries at a given remaining [load]. */
    private fun loadAlpha(load: Float): Float =
        if (load >= 1f) 1f else Math.pow(load.coerceAtLeast(0f).toDouble(), LOAD_FADE_EXP.toDouble()).toFloat()

    // --- Bristle drying and texture -----------------------------------------
    //
    // A draining BRUSH does not dim like an airbrush: individual bristles run
    // out, outer ones first, and the mark thins into streaks while what still
    // deposits stays strong. So the bristle path keeps only a gentle global
    // fade and lets per-bristle dropout carry the drain. Everything below is a
    // pure function of stored stroke data (load, width, positions) plus the
    // seeded layout, so live preview, incremental appends and the bake agree.

    /** The bristle path's own, much gentler global fade (dropout does the rest). */
    private const val BRUSH_LOAD_FADE_EXP = 0.3f

    /** Load span over which one bristle fades from full supply to nothing. */
    private const val DRY_BAND = 0.15f

    /** Along-stroke texture wavelength, buffer px. */
    private const val STREAK_LEN_PX = 14f

    /** Texture depth on a loaded bristle vs one approaching dry. */
    private const val STREAK_WET = 0.10f
    private const val STREAK_DRY = 0.55f

    /** How raggedy a drying bristle's edge gets (fraction of its thickness). */
    private const val WIDTH_RAG = 0.45f

    /** A fast drag starves transfer: load knocked off, ramping over spacing. */
    private const val SPEED_DRY_SPAN = 0.22f
    private const val SPEED_LO_PX = 8f
    private const val SPEED_HI_PX = 40f

    /**
     * Pressure engagement: the width ratio (point width / full spread) at
     * which every bristle is on the paper, and the softness of each
     * bristle's own onset below that.
     */
    private const val ENGAGE_FULL = 0.62f
    private const val ENGAGE_SOFT = 0.3f

    /** Pressed-dab look: pooled core, crown hairs just breaking its rim. */
    private const val DAB_CORE_RADIUS = 0.45f
    private const val DAB_CORE_ALPHA = 0.8f
    private const val DAB_CROWN_START = 0.34f
    private const val DAB_CROWN_ALPHA = 0.6f
    private const val DAB_LEN_LO = 0.05f
    private const val DAB_LEN_HI = 0.16f

    private fun brushLoadAlpha(load: Float): Float =
        if (load >= 1f) 1f else load.coerceAtLeast(0f).pow(BRUSH_LOAD_FADE_EXP)

    private fun smooth01(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Deterministic per-cell hash in [0,1) — murmur-style finalizer. */
    private fun bristleHash(cell: Int, salt: Int): Float {
        var h = cell * -0x61c88647 xor salt
        h = h xor (h ushr 16); h *= -0x7a143595
        h = h xor (h ushr 13); h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF) * (1f / 0x80000000L.toFloat())
    }

    /** Smooth 1-D value noise in [0,1), parameterized by arc length. */
    private fun streakNoise(x: Float, salt: Int): Float {
        val c = floor(x)
        val t = x - c
        val cell = c.toInt()
        val a = bristleHash(cell, salt)
        val b = bristleHash(cell + 1, salt)
        return a + (b - a) * smooth01(t)
    }

    /** Arc-length window for the steadied tail normal: about a brush width. */
    private fun bristleTailWindow(layout: BristleLayout, pts: List<StrokePoint>): Float =
        max(pts.last().width, layout.spread * 0.5f)

    /**
     * Per-point alpha and edge-width multipliers for bristle [b] over points
     * [from..to], written range-local into [alphaOut]/[widthOut] (entry 0 =
     * point [from]). This is where the brush's physics reads:
     *
     *  - SUPPLY: the bristle holds full strength while the (speed-starved)
     *    load is above its own seeded threshold, then fades out over
     *    [DRY_BAND] — outer bristles carry higher thresholds, so they dry
     *    first and the stroke thins from its edges inward.
     *  - TEXTURE: seeded value noise along arc length breaks the deposit into
     *    streaks, shallow on a wet bristle, deep as it approaches dry; a
     *    second phase ruffles the edge width the same way.
     *  - ENGAGEMENT: at light pressure only the inner bristles touch; an
     *    outer bristle settles on as the stroke's width ratio spreads past
     *    its position in the fan.
     */
    private fun fillBristleScales(
        layout: BristleLayout,
        b: Int,
        pts: List<StrokePoint>,
        arcs: FloatArray,
        from: Int,
        to: Int,
        alphaOut: FloatArray,
        widthOut: FloatArray,
    ) {
        val dryAt = layout.dryAt[b]
        val salt = layout.salts[b]
        val edge = abs(layout.fracs[b])
        for (i in from..to) {
            val k = i - from
            val p = pts[i]
            val spacing = if (i == 0) 0f else arcs[i] - arcs[i - 1]
            val eff = p.load -
                SPEED_DRY_SPAN * smooth01((spacing - SPEED_LO_PX) / (SPEED_HI_PX - SPEED_LO_PX))
            val wet = smooth01((eff - (dryAt - DRY_BAND)) / DRY_BAND)
            // Texture starts building before the bristle actually dies.
            val drought = smooth01((dryAt + 2f * DRY_BAND - eff) / (3f * DRY_BAND))
            val depth = STREAK_WET + (STREAK_DRY - STREAK_WET) * drought
            val n1 = streakNoise(arcs[i] / STREAK_LEN_PX, salt)
            val n2 = streakNoise(arcs[i] / (STREAK_LEN_PX * 1.7f) + 11.3f, salt xor 0x2f77)
            val engage = smooth01((p.width / layout.spread / ENGAGE_FULL - edge) / ENGAGE_SOFT + 1f)
            alphaOut[k] = layout.strength[b] * wet * engage * (1f - depth * n1)
            widthOut[k] = (1f - WIDTH_RAG * depth * n2).coerceAtLeast(0.15f)
        }
    }

    /** The colour to deposit at [p] — what the brush carried, else the stroke's. */
    private fun colorAt(p: StrokePoint, rgb: Int): Int =
        if (p.color != INHERIT_COLOR) p.color or OPAQUE_ALPHA else rgb

    // --- Helpers ------------------------------------------------------------

    private const val OPAQUE_ALPHA = 0xFF000000.toInt()

    private fun alpha255(a: Float): Int = (a * 255f).roundToInt().coerceIn(0, 255)

    private fun withAlpha(rgb: Int, a: Float): Int =
        (alpha255(a) shl 24) or (rgb and 0x00FFFFFF)

    /** [color] with its alpha multiplied by [s] (hue untouched). */
    private fun scaleAlpha(color: Int, s: Float): Int {
        val a = ((color ushr 24) * s.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}

/**
 * The per-stroke bristle layout: which bristles paint (dry-brush gaps survive as
 * missing entries), where each rides across the brush ([fracs], -1..1) and its
 * half-thickness ([halves]). Deterministic from the stroke's seed and nominal
 * width, so the live preview (which appends geometry incrementally and must
 * never re-lay old bristles) and the bake (which re-renders from scratch) put
 * down identical marks.
 *
 * Sized from [Stroke.baseWidth] at full spread — a brush's bristle count is a
 * property of the brush the artist picked, not of how hard one stroke pressed.
 * Light pressure bunches those same bristles into a narrow track, exactly like
 * real hair. [maxWidthFallback] (the observed width) serves strokes captured
 * without a baseWidth.
 */
class BristleLayout(stroke: Stroke, maxWidthFallback: Float) {
    @JvmField val fracs: FloatArray
    @JvmField val halves: FloatArray

    /**
     * Per-bristle load threshold: the supply level at which this bristle
     * starts to fade out. Outer bristles carry higher thresholds — they dry
     * first, so a draining stroke thins from its edges inward instead of
     * dimming uniformly.
     */
    @JvmField val dryAt: FloatArray

    /** Per-bristle noise identity for the along-stroke streak texture. */
    @JvmField val salts: IntArray

    /**
     * Per-bristle deposit strength. Real hairs don't carry equal paint — the
     * variation is what makes the fan read as bristles instead of a solid
     * band — and the edge hairs of the bundle carry the least.
     */
    @JvmField val strength: FloatArray

    @JvmField val smear: Float

    /** The brush's full-pressure footprint width — what [fracs] span. */
    @JvmField val spread: Float

    val count: Int get() = fracs.size

    init {
        val profile = ToolProfile.of(stroke.tool)
        spread =
            if (stroke.baseWidth > 0f) stroke.baseWidth * profile.maxPressureFactor else maxWidthFallback
        smear = profile.blurFactor * spread * 0.55f
        val n = (spread / 2.5f).roundToInt().coerceIn(8, 22)
        val spacing = spread / n
        val rnd = Random(stroke.seed.toLong())
        val fr = FloatArray(n)
        val hv = FloatArray(n)
        val dr = FloatArray(n)
        val st = IntArray(n)
        val sg = FloatArray(n)
        var kept = 0
        for (b in 0 until n) {
            // Every bristle draws all its randoms so one bristle's fate never
            // shifts another's identity.
            val gap = rnd.nextDouble() < 0.1 // dry-brush gap
            val jitter = rnd.nextDouble().toFloat() - 0.5f
            val widthRnd = rnd.nextDouble().toFloat()
            val dryRnd = rnd.nextDouble().toFloat()
            val strengthRnd = rnd.nextDouble().toFloat()
            val salt = rnd.nextInt()
            if (gap) continue
            val base = (b + 0.5f) / n * 2f - 1f
            fr[kept] = (base + jitter * (2f / n) * 0.8f).coerceIn(-1f, 1f)
            val bw = max(0.6f, spacing * (0.9f + widthRnd * 0.9f))
            // One smear margin, not two: bristles must not fuse into a band —
            // the gaps that open as pressure spreads the fan are the look.
            hv[kept] = bw / 2f + smear
            val edgeness = abs(base).pow(1.2f)
            dr[kept] = ((DRY_EDGE_LO + (DRY_EDGE_HI - DRY_EDGE_LO) * edgeness) *
                (0.7f + 0.6f * dryRnd)).coerceAtMost(0.9f)
            st[kept] = salt
            sg[kept] = (STRENGTH_LO + (1f - STRENGTH_LO) * strengthRnd) *
                (1f - EDGE_HAIR_FADE * abs(base))
            kept++
        }
        fracs = fr.copyOf(kept)
        halves = hv.copyOf(kept)
        dryAt = dr.copyOf(kept)
        salts = st.copyOf(kept)
        strength = sg.copyOf(kept)
    }

    private companion object {
        /** Dry-out threshold range from the fan's center to its edge. */
        const val DRY_EDGE_LO = 0.08f
        const val DRY_EDGE_HI = 0.55f

        /** Weakest per-bristle deposit strength (strongest is 1). */
        const val STRENGTH_LO = 0.55f

        /** How much lighter the bundle's outermost hairs deposit. */
        const val EDGE_HAIR_FADE = 0.25f
    }
}
