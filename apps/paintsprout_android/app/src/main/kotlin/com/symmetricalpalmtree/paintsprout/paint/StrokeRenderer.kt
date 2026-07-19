package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
            RenderStyle.SOLID, RenderStyle.SOFT ->
                paintSolid(canvas, stroke, profile, rgb, tooth, toothScale, maxWidth, blurSigma, minX, minY, maxX, maxY)
        }
    }

    // --- Grain (pencil / marker) --------------------------------------------

    private fun paintGrain(
        canvas: Canvas, stroke: Stroke, rgb: Int, tooth: android.graphics.Bitmap?,
        toothScale: Float, maxWidth: Float, minX: Float, minY: Float, maxX: Float, maxY: Float,
    ) {
        val pad = maxWidth / 2f + 1f
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val layer = canvas.saveLayer(bounds, null)
        val pts = stroke.points
        if (pts.size == 1) {
            val p = pts.first()
            canvas.drawCircle(
                p.position.x, p.position.y, p.width / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(colorAt(p, rgb), p.density * loadAlpha(p.load))
                },
            )
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

    // --- Wash (watercolor look; wet/spectral is Stage 4) --------------------

    private fun paintWash(
        canvas: Canvas, stroke: Stroke, rgb: Int, tooth: android.graphics.Bitmap?,
        toothScale: Float, maxWidth: Float, bleed: Float,
        minX: Float, minY: Float, maxX: Float, maxY: Float,
        dryness: FloatArray? = null,
    ) {
        // A clean-water stroke leaves no pigment of its own: its lasting effect
        // (diluting and pushing the paint underneath) is composited by the
        // canvas view, not painted here. All this path draws is the transient
        // wet-paper sheen while the stroke is drying — and nothing at all for
        // the bake (null dryness), which is exactly what the sheen dries to.
        if (stroke.water) {
            if (dryness != null) {
                paintWaterSheen(canvas, stroke, tooth, toothScale, maxWidth, bleed, minX, minY, maxX, maxY, dryness)
            }
            return
        }
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
     * The wet-paper sheen of a clean-water stroke: paper reads slightly darker
     * where it is wet, fading out as each section dries — the only visible
     * trace the water itself leaves. Same fill mesh as the wash, no rim.
     */
    private fun paintWaterSheen(
        canvas: Canvas, stroke: Stroke, tooth: android.graphics.Bitmap?,
        toothScale: Float, maxWidth: Float, bleed: Float,
        minX: Float, minY: Float, maxX: Float, maxY: Float,
        dryness: FloatArray,
    ) {
        val pad = maxWidth / 2f + bleed * 3f + 2f
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val layer = canvas.saveLayer(bounds, null)
        val pts = stroke.points
        if (pts.size == 1) {
            val d = drynessAt(dryness, 0)
            canvas.drawCircle(
                pts[0].position.x, pts[0].position.y, max(1f, pts[0].width / 2f) * spreadOf(d),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(SHEEN_RGB, WATER_SHEEN * (1f - d))
                    if (bleed > 0.3f) maskFilter = BlurMaskFilter(bleed, BlurMaskFilter.Blur.NORMAL)
                },
            )
        } else {
            drawWashMesh(canvas, pts, SHEEN_RGB, bleed, maxWidth, dryness, water = true)
        }
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
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

    private const val WET_SPREAD = 0.86f
    private const val WET_RIM = 0.18f
    private const val WET_DARKEN = 0.12f

    /** Wet-paper sheen: peak alpha of a just-laid water stroke, and its tint. */
    private const val WATER_SHEEN = 0.10f
    private const val SHEEN_RGB = 0xFF453F38.toInt()

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
        water: Boolean = false,
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
        val rimCol = IntArray(n); val rimEdgeCol = IntArray(n)

        var j = 0 // window start: farthest point within win behind i
        var k = 0 // window end: farthest point within win ahead of i
        for (i in 0 until n) {
            while (j < i && arc[i] - arc[j] > win) j++
            if (k < i) k = i
            while (k < n - 1 && arc[k + 1] - arc[i] <= win) k++

            val p = pts[i].position
            // Windowed tangent; per-sample fallback at a true hairpin, where
            // the window's ends meet and the difference degenerates.
            var dx = pts[k].position.x - pts[j].position.x
            var dy = pts[k].position.y - pts[j].position.y
            var len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len < win * 0.25f) {
                val a = if (i > 0) pts[i - 1].position else p
                val b = if (i < n - 1) pts[i + 1].position else p
                dx = b.x - a.x; dy = b.y - a.y
                len = kotlin.math.sqrt(dx * dx + dy * dy)
            }
            if (len < 1e-4f) { dx = 1f; dy = 0f; len = 1f }
            nx[i] = -dy / len
            ny[i] = dx / len

            // Windowed width envelope (surface tension smooths the boundary),
            // scaled by the drying creep: a young section sits tighter than
            // its final footprint. Dryness varies smoothly along the stroke
            // (points age in laid order), so the boundary stays smooth.
            val dry = drynessAt(dryness, i)
            val hw = max(0.5f, (prefW[k + 1] - prefW[j]) / (k - j + 1) / 2f) * spreadOf(dry)

            // Windowed curvature -> radius; only the turn's inner side clamps.
            var limitL = Float.MAX_VALUE
            var limitR = Float.MAX_VALUE
            if (j < i && k > i) {
                val v1x = p.x - pts[j].position.x; val v1y = p.y - pts[j].position.y
                val v2x = pts[k].position.x - p.x; val v2y = pts[k].position.y - p.y
                val l1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y)
                val l2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y)
                if (l1 > 1e-4f && l2 > 1e-4f) {
                    val cross = v1x * v2y - v1y * v2x
                    val dot = v1x * v2x + v1y * v2y
                    val theta = kotlin.math.atan2(kotlin.math.abs(cross), dot)
                    if (theta > 1e-3f) {
                        // Chords underestimate the radius ~2x over this window.
                        val radius = 2f * min(l1, l2) / theta
                        if (cross > 0f) limitL = radius else limitR = radius
                    }
                }
            }
            coreL[i] = min(max(0.1f, hw - soft), limitL * 0.75f)
            coreR[i] = min(max(0.1f, hw - soft), limitR * 0.75f)
            outerL[i] = min(hw + soft, limitL * 0.95f)
            outerR[i] = min(hw + soft, limitR * 0.95f)
            rimPkL[i] = min(hw, limitL * 0.85f)
            rimPkR[i] = min(hw, limitR * 0.85f)
            rimOutL[i] = min(rimPkL[i] + rimHalf, limitL * 0.98f)
            rimOutR[i] = min(rimPkR[i] + rimHalf, limitR * 0.98f)
            rimInL[i] = max(0.05f, rimPkL[i] - rimHalf)
            rimInR[i] = max(0.05f, rimPkR[i] - rimHalf)

            if (water) {
                // Wet-paper sheen: fades out entirely as this section dries.
                coreCol[i] = withAlpha(rgb, WATER_SHEEN * (1f - dry))
                edgeCol[i] = coreCol[i] and 0x00FFFFFF
                rimCol[i] = 0
                rimEdgeCol[i] = 0
            } else {
                val a = loadAlpha(pts[i].load)
                val c = colorAt(pts[i], rgb)
                coreCol[i] = withAlpha(c, (0.6f * a * wetDarkenOf(dry)).coerceAtMost(1f))
                edgeCol[i] = coreCol[i] and 0x00FFFFFF
                rimCol[i] = withAlpha(c, 0.95f * a * rimOf(dry))
                rimEdgeCol[i] = rimCol[i] and 0x00FFFFFF
            }
        }

        // End caps collapse to a point just past each end — short, so the cap
        // reads as a soft blunt end rather than a pointed beak past the rim.
        val headT = Vec2(ny[0], -nx[0]) // tangent = perp of normal
        val tailT = Vec2(ny[n - 1], -nx[n - 1])
        val head = pts[0].position - headT * (soft + coreL[0] * 0.35f)
        val tail = pts[n - 1].position + tailT * (soft + coreL[n - 1] * 0.35f)
        val verts = FloatArray((n + 2) * 4)
        val colors = IntArray((n + 2) * 2)
        val paint = Paint()

        fun strip(
            sideA: Float, offA: FloatArray, colA: IntArray,
            sideB: Float, offB: FloatArray, colB: IntArray,
            capCol: IntArray,
        ) {
            var slot = 0
            fun put(ax: Float, ay: Float, bx: Float, by: Float, ca: Int, cb: Int) {
                verts[slot * 4] = ax; verts[slot * 4 + 1] = ay
                verts[slot * 4 + 2] = bx; verts[slot * 4 + 3] = by
                colors[slot * 2] = ca; colors[slot * 2 + 1] = cb
                slot++
            }
            put(head.x, head.y, head.x, head.y, capCol[0], capCol[0])
            for (i in 0 until n) {
                val p = pts[i].position
                put(
                    p.x + nx[i] * sideA * offA[i], p.y + ny[i] * sideA * offA[i],
                    p.x + nx[i] * sideB * offB[i], p.y + ny[i] * sideB * offB[i],
                    colA[i], colB[i],
                )
            }
            put(tail.x, tail.y, tail.x, tail.y, capCol[n - 1], capCol[n - 1])
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLE_STRIP, slot * 4, verts, 0,
                null, 0, colors, 0, null, 0, 0, paint,
            )
        }

        // Fill: left bleed band, solid core, right bleed band.
        strip(1f, outerL, edgeCol, 1f, coreL, coreCol, edgeCol)
        strip(1f, coreL, coreCol, -1f, coreR, coreCol, edgeCol)
        strip(-1f, coreR, coreCol, -1f, outerR, edgeCol, edgeCol)
        if (water) return // sheen only — water pools no pigment rim
        // Rim: a soft peak riding each edge, over the fill.
        strip(1f, rimOutL, rimEdgeCol, 1f, rimPkL, rimCol, rimEdgeCol)
        strip(1f, rimPkL, rimCol, 1f, rimInL, rimEdgeCol, rimEdgeCol)
        strip(-1f, rimInR, rimEdgeCol, -1f, rimPkR, rimCol, rimEdgeCol)
        strip(-1f, rimPkR, rimCol, -1f, rimOutR, rimEdgeCol, rimEdgeCol)
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
            drawBristleDab(canvas, pts.first(), rgb, layout)
        } else {
            val normals = strokeNormals(pts)
            val ink = IntArray(pts.size) { i ->
                withAlpha(colorAt(pts[i], rgb), loadAlpha(pts[i].load))
            }
            // Reused across bristles; two slots spare for the tapered ends.
            val verts = FloatArray((pts.size + 2) * 4)
            val colors = IntArray((pts.size + 2) * 2)
            val meshPaint = Paint()
            for (b in 0 until layout.count) {
                bristleSpan(
                    canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], -1f,
                    0, pts.size - 1, withHead = true, withTail = true, verts, colors, meshPaint,
                )
                bristleSpan(
                    canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], 1f,
                    0, pts.size - 1, withHead = true, withTail = true, verts, colors, meshPaint,
                )
            }
        }
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
    }

    /** A single pressed dab: a soft disc in the ink the brush carried. */
    private fun drawBristleDab(canvas: Canvas, p: StrokePoint, rgb: Int, layout: BristleLayout) {
        val blur = if (layout.smear > 0.3f) BlurMaskFilter(layout.smear, BlurMaskFilter.Blur.NORMAL) else null
        canvas.drawCircle(
            p.position.x, p.position.y, p.width / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(colorAt(p, rgb), loadAlpha(p.load)); maskFilter = blur
            },
        )
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
        // Range-local normals: all of [from..to] have both neighbours, so they
        // are final and identical to a whole-stroke computation.
        val normals = strokeNormalsRange(pts, from, to)
        val ink = IntArray(to - from + 1) { k ->
            val p = pts[from + k]
            withAlpha(colorAt(p, rgb), loadAlpha(p.load))
        }
        val verts = FloatArray((to - from + 3) * 4)
        val colors = IntArray((to - from + 3) * 2)
        val paint = Paint()
        for (b in 0 until layout.count) {
            bristleSpan(
                canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], -1f,
                from, to, withHead = from == 0, withTail = false, verts, colors, paint, base = from,
            )
            bristleSpan(
                canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], 1f,
                from, to, withHead = from == 0, withTail = false, verts, colors, paint, base = from,
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
            drawBristleDab(canvas, pts.first(), stroke.color or OPAQUE_ALPHA, layout)
            return
        }
        val from = max(0, n - tailPoints)
        val count = n - from
        val rgb = stroke.color or OPAQUE_ALPHA
        val normals = strokeNormalsRange(pts, from, n - 1)
        val ink = IntArray(count) { k ->
            val p = pts[from + k]
            withAlpha(colorAt(p, rgb), loadAlpha(p.load))
        }
        val verts = FloatArray((count + 2) * 4)
        val colors = IntArray((count + 2) * 2)
        val paint = Paint()
        for (b in 0 until layout.count) {
            bristleSpan(
                canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], -1f,
                from, n - 1, withHead = false, withTail = true, verts, colors, paint, base = from,
            )
            bristleSpan(
                canvas, pts, normals, ink, layout.fracs[b], layout.halves[b], 1f,
                from, n - 1, withHead = false, withTail = true, verts, colors, paint, base = from,
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
     * [base] is the point index that [normals] and [ink] entry 0 correspond to
     * (range-local arrays). [verts] and [colors] are scratch owned by the
     * caller; this runs dozens of times a frame and must not allocate.
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
    ) {
        fun normalAt(i: Int) = normals[i - base]
        fun spineAt(i: Int) = pts[i].position + normalAt(i) * (frac * pts[i].width / 2f)

        var slot = 0
        fun put(spine: Vec2, normal: Vec2, inkColor: Int) {
            val edge = spine + normal * (side * halfWidth)
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
            put(spineAt(from) - headDir * halfWidth, normalAt(from), ink[from - base] and 0x00FFFFFF)
        }
        for (i in from..to) put(spineAt(i), normalAt(i), ink[i - base])
        if (withTail) {
            val tailDir = tangentOf(normalAt(to))
            put(spineAt(to) + tailDir * halfWidth, normalAt(to), ink[to - base] and 0x00FFFFFF)
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
        val needsLayer = profile.opacity < 1f || blurSigma > 0f || tooth != null
        val pad = maxWidth / 2f + blurSigma * 3f + 1f
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)

        var layer = -1
        if (needsLayer) {
            val lp = Paint()
            if (profile.opacity < 1f) lp.alpha = alpha255(profile.opacity)
            // Eraser with tooth: subtract a tooth-masked stamp (dstOut) on restore,
            // so it erases on the crests but leaves residue in the valleys.
            if (isEraser && tooth != null) lp.blendMode = BlendMode.DST_OUT
            layer = canvas.saveLayer(bounds, lp)
        }

        val useClear = isEraser && tooth == null
        val drawColor = if (isEraser) Color.WHITE else rgb
        val blur = if (blurSigma > 0f) BlurMaskFilter(blurSigma, BlurMaskFilter.Blur.NORMAL) else null
        val pts = stroke.points

        fun basePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = drawColor
            if (useClear) blendMode = BlendMode.CLEAR
            maskFilter = blur
        }

        when {
            pts.size == 1 -> {
                val p = pts.first()
                canvas.drawCircle(p.position.x, p.position.y, p.width / 2f, basePaint())
            }
            !stroke.tool.isDynamic -> {
                canvas.drawPath(smoothPath(pts), basePaint().apply {
                    style = Paint.Style.STROKE; strokeWidth = pts.first().width
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                })
            }
            else -> {
                val paint = basePaint().apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                }
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]
                    paint.strokeWidth = (a.width + b.width) / 2f
                    canvas.drawLine(a.position.x, a.position.y, b.position.x, b.position.y, paint)
                }
            }
        }

        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        if (needsLayer) canvas.restoreToCount(layer)
    }

    /**
     * Appends the soft (spray) stroke's segments from point [fromIndex] onward to
     * [canvas], WITHOUT the outer opacity/tooth layer (the caller applies those
     * once when blitting). Lets the live preview accumulate a long spray stroke
     * incrementally instead of re-rendering the whole thing every frame. Blur is
     * per-segment (local width) rather than the whole-stroke average [paintSolid]
     * uses — imperceptible for spray's soft, noisy look, and the bake stays the
     * source of truth.
     */
    fun appendSoftSegments(canvas: Canvas, stroke: Stroke, fromIndex: Int) {
        val profile = ToolProfile.of(stroke.tool)
        val rgb = stroke.color or OPAQUE_ALPHA
        val pts = stroke.points
        if (pts.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = rgb
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun blurFor(w: Float): BlurMaskFilter? {
            val sigma = profile.blurFactor * w
            return if (sigma > 0.3f) BlurMaskFilter(sigma, BlurMaskFilter.Blur.NORMAL) else null
        }
        if (fromIndex == 0) {
            val p = pts.first()
            paint.maskFilter = blurFor(p.width)
            canvas.drawPoint(p.position.x, p.position.y, paint.apply {
                strokeCap = Paint.Cap.ROUND; strokeWidth = p.width
            })
        }
        var i = max(1, fromIndex)
        while (i < pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val w = (a.width + b.width) / 2f
            paint.strokeWidth = w
            paint.maskFilter = blurFor(w)
            canvas.drawLine(a.position.x, a.position.y, b.position.x, b.position.y, paint)
            i++
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

    /** The colour to deposit at [p] — what the brush carried, else the stroke's. */
    private fun colorAt(p: StrokePoint, rgb: Int): Int =
        if (p.color != INHERIT_COLOR) p.color or OPAQUE_ALPHA else rgb

    // --- Helpers ------------------------------------------------------------

    private const val OPAQUE_ALPHA = 0xFF000000.toInt()

    private fun alpha255(a: Float): Int = (a * 255f).roundToInt().coerceIn(0, 255)

    private fun withAlpha(rgb: Int, a: Float): Int =
        (alpha255(a) shl 24) or (rgb and 0x00FFFFFF)
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
    @JvmField val smear: Float

    val count: Int get() = fracs.size

    init {
        val profile = ToolProfile.of(stroke.tool)
        val spread =
            if (stroke.baseWidth > 0f) stroke.baseWidth * profile.maxPressureFactor else maxWidthFallback
        smear = profile.blurFactor * spread * 0.55f
        val n = (spread / 2.5f).roundToInt().coerceIn(8, 22)
        val spacing = spread / n
        val rnd = Random(stroke.seed.toLong())
        val fr = FloatArray(n)
        val hv = FloatArray(n)
        var kept = 0
        for (b in 0 until n) {
            if (rnd.nextDouble() < 0.1) continue // dry-brush gap
            val base = (b + 0.5f) / n * 2f - 1f
            fr[kept] = (base + (rnd.nextDouble().toFloat() - 0.5f) * (2f / n) * 0.8f)
                .coerceIn(-1f, 1f)
            val bw = max(0.6f, spacing * (0.9f + rnd.nextDouble().toFloat() * 0.9f))
            hv[kept] = bw / 2f + smear * 2f
            kept++
        }
        fracs = fr.copyOf(kept)
        halves = hv.copyOf(kept)
    }
}
