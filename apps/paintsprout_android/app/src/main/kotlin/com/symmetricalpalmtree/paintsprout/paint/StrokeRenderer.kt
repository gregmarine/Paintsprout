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
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = blur }
            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = max(1.5f, maxWidth * 0.16f)
                strokeJoin = Paint.Join.ROUND; maskFilter = blur
            }
            for (run in spansOf(stroke, rgb)) {
                val ribbon = ribbonPath(
                    ribbonOutline(
                        pts.subList(run.from, run.to + 1),
                        normals.subList(run.from, run.to + 1),
                    ),
                )
                canvas.drawPath(ribbon, fillPaint.apply { color = withAlpha(run.color, 0.6f * run.alpha) })
                canvas.drawPath(ribbon, rimPaint.apply { color = withAlpha(run.color, 0.95f * run.alpha) })
            }
        }
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
    }

    // --- Bristle (brush) ----------------------------------------------------

    private fun paintBristle(
        canvas: Canvas, stroke: Stroke, rgb: Int, tooth: android.graphics.Bitmap?,
        toothScale: Float, maxWidth: Float, avgWidth: Float,
        minX: Float, minY: Float, maxX: Float, maxY: Float,
    ) {
        val profile = ToolProfile.of(stroke.tool)
        val smear = profile.blurFactor * avgWidth
        val pad = maxWidth / 2f + smear * 3f + 2f
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val layer = canvas.saveLayer(bounds, Paint().apply { alpha = alpha255(profile.opacity) })
        val blur = if (smear > 0.3f) BlurMaskFilter(smear, BlurMaskFilter.Blur.NORMAL) else null
        val pts = stroke.points
        if (pts.size == 1) {
            val p = pts.first()
            canvas.drawCircle(p.position.x, p.position.y, p.width / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(colorAt(p, rgb), loadAlpha(p.load)); maskFilter = blur
            })
        } else {
            val normals = strokeNormals(pts)
            val rnd = Random(stroke.seed.toLong())
            val bristleCount = (maxWidth / 2.5f).roundToInt().coerceIn(8, 22)
            val centerSpacing = maxWidth / bristleCount
            val meshPaint = Paint()

            // What each point deposits: the colour the brush carried there, at
            // the strength its remaining load allows. Resolved once and shared
            // by every bristle.
            val ink = IntArray(pts.size) { i ->
                withAlpha(colorAt(pts[i], rgb), loadAlpha(pts[i].load))
            }
            // Reused across bristles — this runs every frame of the live preview.
            // Two slots spare for the tapered ends.
            val verts = FloatArray((pts.size + 2) * 4)
            val colors = IntArray((pts.size + 2) * 2)

            for (b in 0 until bristleCount) {
                if (rnd.nextDouble() < 0.1) continue // dry-brush gap
                val base = (b + 0.5f) / bristleCount * 2f - 1f
                val frac = (base + (rnd.nextDouble().toFloat() - 0.5f) * (2f / bristleCount) * 0.8f)
                    .coerceIn(-1f, 1f)
                val bw = max(0.6f, centerSpacing * (0.9f + rnd.nextDouble().toFloat() * 0.9f))
                val half = bw / 2f + smear * 2f
                drawBristle(canvas, pts, normals, ink, frac, half, -1f, verts, colors, meshPaint)
                drawBristle(canvas, pts, normals, ink, frac, half, 1f, verts, colors, meshPaint)
            }
        }
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
    }

    /**
     * Half of one bristle: a mesh running from its transparent edge to its solid
     * core, on the [side] given (-1 left, +1 right). Two halves make a bristle
     * that fades out at both edges.
     *
     * Bristles are meshes rather than blurred strokes because a mask filter
     * cannot ride on [Canvas.drawVertices], and the mesh is what buys everything
     * else: it carries a colour and a strength at *every* point, so a fading or
     * contaminated bristle costs no mask and no extra draw call — and it
     * composites normally, so a stroke crossing itself unions instead of erasing
     * its own opening. The softness the blur used to provide is built into the
     * geometry: a thin bar blurred is a soft-edged bar, so the mesh just is one.
     *
     * [verts] and [colors] are scratch owned by the caller; this runs 22 times a
     * frame and must not allocate.
     */
    private fun drawBristle(
        canvas: Canvas,
        pts: List<StrokePoint>,
        normals: List<Vec2>,
        ink: IntArray,
        frac: Float,
        halfWidth: Float,
        side: Float,
        verts: FloatArray,
        colors: IntArray,
        paint: Paint,
    ) {
        val n = pts.size

        fun spineAt(i: Int) = pts[i].position + normals[i] * (frac * pts[i].width / 2f)
        fun put(slot: Int, spine: Vec2, normal: Vec2, ink: Int) {
            val edge = spine + normal * (side * halfWidth)
            verts[slot * 4] = edge.x; verts[slot * 4 + 1] = edge.y
            verts[slot * 4 + 2] = spine.x; verts[slot * 4 + 3] = spine.y
            // Same hue at the edge, no coverage — so it fades out rather than
            // fringing toward another colour.
            colors[slot * 2] = ink and 0x00FFFFFF
            colors[slot * 2 + 1] = ink
        }

        // A stroked path had round caps, and the blur softened them further; a
        // bare mesh ends flat, which lands as a straight edge across the mark —
        // glaring at the start of an enso, where the faded tail sweeps in behind
        // it. Taper the ends to nothing instead.
        val headDir = tangentOf(normals[0])
        val tailDir = tangentOf(normals[n - 1])
        put(0, spineAt(0) - headDir * halfWidth, normals[0], ink[0] and 0x00FFFFFF)
        for (i in 0 until n) put(i + 1, spineAt(i), normals[i], ink[i])
        put(n + 1, spineAt(n - 1) + tailDir * halfWidth, normals[n - 1], ink[n - 1] and 0x00FFFFFF)

        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLE_STRIP, verts.size, verts, 0,
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
