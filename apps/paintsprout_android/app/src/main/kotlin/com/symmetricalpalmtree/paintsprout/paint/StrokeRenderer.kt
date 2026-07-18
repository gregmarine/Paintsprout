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
 * Wash and solid soften their edges with a per-shape [BlurMaskFilter], rather
 * than Flutter's whole-layer `ImageFilter.blur`, because our software Bitmap
 * bakes can't host a `RenderEffect`. Grain and bristle instead build their marks
 * as meshes with per-vertex colour, which is both cheaper and the only way they
 * can carry a colour and a strength that vary along the stroke — see
 * [drawBristle].
 */
object StrokeRenderer {

    fun paintStroke(canvas: Canvas, stroke: Stroke, surface: SurfaceKind = SurfaceKind.PAPER) {
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
                paintWash(canvas, stroke, rgb, tooth, toothScale, maxWidth, blurSigma, minX, minY, maxX, maxY)
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
    ) {
        val halo = bleed * 3f + 2f
        val pad = maxWidth / 2f + halo
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val layer = canvas.saveLayer(bounds, Paint().apply { alpha = alpha255(ToolProfile.of(stroke.tool).opacity) })
        val blur = if (bleed > 0.3f) BlurMaskFilter(bleed, BlurMaskFilter.Blur.NORMAL) else null
        val pts = stroke.points
        if (pts.size == 1) {
            val p = pts.first()
            val r = max(1f, p.width / 2f)
            val c = colorAt(p, rgb)
            val fade = loadAlpha(p.load)
            canvas.drawCircle(p.position.x, p.position.y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(c, 0.6f * fade); maskFilter = blur
            })
            canvas.drawCircle(p.position.x, p.position.y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = max(1.5f, r * 0.4f)
                color = withAlpha(c, 0.95f * fade); maskFilter = blur
            })
        } else {
            val normals = strokeNormals(pts)
            // Fill: ONE mesh with per-vertex colour and alpha, so a draining or
            // contaminated wash fades smoothly instead of stepping span by span
            // — per-span blurred fills used to overlap at every load boundary
            // and print a dark crossband there: the too-regular "ladder" on a
            // fading wash. The soft bleed edge is geometry, not a mask blur.
            drawWashFillMesh(canvas, pts, normals, rgb, bleed)
            // Rim: pooled pigment along the outer edges (per-span colour; its
            // steps run along the edge, never across the stroke).
            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = max(1.5f, maxWidth * 0.16f)
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; maskFilter = blur
            }
            val lastIdx = pts.size - 1
            for (run in spansOf(stroke, rgb)) {
                // A dry span deposits nothing; skip it rather than blur-render
                // an invisible path (a long drained tail is pure waste).
                if (run.alpha <= 0.004f) continue
                val subPts = pts.subList(run.from, run.to + 1)
                val subNorms = normals.subList(run.from, run.to + 1)
                canvas.drawPath(
                    rimEdgesPath(subPts, subNorms, capStart = run.from == 0, capEnd = run.to == lastIdx),
                    rimPaint.apply { color = withAlpha(run.color, 0.95f * run.alpha) },
                )
            }
        }
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
    }

    /**
     * The wash fill as three triangle strips (left soft band, solid core, right
     * soft band) sharing exact vertex lines, tapered to nothing past both ends.
     * Per-vertex colour carries the load fade and any picked-up pigment
     * continuously along the stroke; alpha ramps from the solid core to zero
     * across the bleed width, which is what the old per-shape blur provided —
     * without a mask filter, and without span quantization.
     */
    private fun drawWashFillMesh(
        canvas: Canvas,
        pts: List<StrokePoint>,
        normals: List<Vec2>,
        rgb: Int,
        bleed: Float,
    ) {
        val n = pts.size
        val soft = max(1f, bleed)
        val offCore = FloatArray(n)
        val offOuter = FloatArray(n)
        val coreCol = IntArray(n)
        val edgeCol = IntArray(n)
        for (i in 0 until n) {
            val p = pts[i]
            val hw = max(0.5f, p.width / 2f)
            offCore[i] = max(0.1f, hw - soft)
            offOuter[i] = hw + soft
            coreCol[i] = withAlpha(colorAt(p, rgb), 0.6f * loadAlpha(p.load))
            edgeCol[i] = coreCol[i] and 0x00FFFFFF
        }
        // End caps collapse to a point just past each end — kept short (the
        // bleed plus a little body) so the cap reads as a soft blunt end, not a
        // pointed beak sticking past the rim.
        val head = pts[0].position - tangentOf(normals[0]) * (soft + offCore[0] * 0.35f)
        val tail = pts[n - 1].position + tangentOf(normals[n - 1]) * (soft + offCore[n - 1] * 0.35f)
        val verts = FloatArray((n + 2) * 4)
        val colors = IntArray((n + 2) * 2)
        val paint = Paint()

        fun strip(sideA: Float, offA: FloatArray, colA: IntArray, sideB: Float, offB: FloatArray, colB: IntArray) {
            var slot = 0
            fun put(ax: Float, ay: Float, bx: Float, by: Float, ca: Int, cb: Int) {
                verts[slot * 4] = ax; verts[slot * 4 + 1] = ay
                verts[slot * 4 + 2] = bx; verts[slot * 4 + 3] = by
                colors[slot * 2] = ca; colors[slot * 2 + 1] = cb
                slot++
            }
            put(head.x, head.y, head.x, head.y, edgeCol[0], edgeCol[0])
            for (i in 0 until n) {
                val p = pts[i].position
                val nm = normals[i]
                put(
                    p.x + nm.x * sideA * offA[i], p.y + nm.y * sideA * offA[i],
                    p.x + nm.x * sideB * offB[i], p.y + nm.y * sideB * offB[i],
                    colA[i], colB[i],
                )
            }
            put(tail.x, tail.y, tail.x, tail.y, edgeCol[n - 1], edgeCol[n - 1])
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLE_STRIP, slot * 4, verts, 0,
                null, 0, colors, 0, null, 0, 0, paint,
            )
        }

        strip(1f, offOuter, edgeCol, 1f, offCore, coreCol) // left bleed band
        strip(1f, offCore, coreCol, -1f, offCore, coreCol) // solid core
        strip(-1f, offCore, coreCol, -1f, offOuter, edgeCol) // right bleed band
    }

    /**
     * The pooled-rim path for one span of a wash: the ribbon's two long edges,
     * plus the end caps where the whole STROKE starts and stops — but never the
     * closing cross-lines a full ribbon outline would add at the span's own
     * boundaries. A draining stroke is split into spans at fixed load steps, so
     * rimming each span's closed outline painted a dark bar across the stroke's
     * interior at every split — the too-regular "glops" along a fading wash.
     * Real pigment pools only at the wash's outer boundary.
     */
    private fun rimEdgesPath(
        pts: List<StrokePoint>,
        normals: List<Vec2>,
        capStart: Boolean,
        capEnd: Boolean,
    ): android.graphics.Path {
        val n = pts.size
        fun edge(i: Int, side: Float): Vec2 {
            val hw = max(0.5f, pts[i].width / 2f)
            return pts[i].position + normals[i] * (side * hw)
        }

        val path = android.graphics.Path()
        fun move(v: Vec2) = path.moveTo(v.x, v.y)
        fun line(v: Vec2) = path.lineTo(v.x, v.y)
        when {
            capStart && capEnd -> { // single span: the full outline, as before
                move(edge(0, 1f)); for (i in 1 until n) line(edge(i, 1f))
                for (i in n - 1 downTo 0) line(edge(i, -1f))
                path.close()
            }
            capEnd -> { // left edge, around the stroke's end, back up the right
                move(edge(0, 1f)); for (i in 1 until n) line(edge(i, 1f))
                for (i in n - 1 downTo 0) line(edge(i, -1f))
            }
            capStart -> { // down the left edge, around the stroke's start, out the right
                move(edge(n - 1, 1f)); for (i in n - 2 downTo 0) line(edge(i, 1f))
                for (i in 0 until n) line(edge(i, -1f))
            }
            else -> { // interior span: two open edges, nothing across the stroke
                move(edge(0, 1f)); for (i in 1 until n) line(edge(i, 1f))
                move(edge(0, -1f)); for (i in 1 until n) line(edge(i, -1f))
            }
        }
        return path
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

    /** The live tail: the last segment plus the tapered end, redrawn per frame. */
    fun drawBristleLiveTail(canvas: Canvas, stroke: Stroke, layout: BristleLayout) {
        val pts = stroke.points
        val n = pts.size
        if (n == 1) {
            drawBristleDab(canvas, pts.first(), stroke.color or OPAQUE_ALPHA, layout)
            return
        }
        val from = n - 2
        val rgb = stroke.color or OPAQUE_ALPHA
        val normals = strokeNormalsRange(pts, from, n - 1)
        val ink = IntArray(2) { k ->
            val p = pts[from + k]
            withAlpha(colorAt(p, rgb), loadAlpha(p.load))
        }
        val verts = FloatArray(4 * 4)
        val colors = IntArray(4 * 2)
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

    /**
     * The stroke as spans of near-constant colour and strength — one span for an
     * ordinary stroke, more as the brush fades or picks pigment up. See
     * [strokeRuns] for why this is not done with a mask.
     */
    private fun spansOf(stroke: Stroke, rgb: Int): List<StrokeRun> =
        strokeRuns(stroke, rgb) { loadAlpha(it) }

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
