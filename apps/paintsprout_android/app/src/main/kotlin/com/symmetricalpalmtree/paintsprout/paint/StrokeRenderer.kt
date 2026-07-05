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
 * Ports every render branch: grain (pencil/marker), wash (watercolor's look —
 * the wet/spectral interaction is Stage 4), bristle (brush), and the shared
 * solid/soft path (pen, spray, eraser), plus the surface [ToothCache] break-up.
 *
 * The soft-edge blur uses [BlurMaskFilter] (per-shape) rather than Flutter's
 * whole-layer `ImageFilter.blur`, because our software Bitmap bakes can't host a
 * `RenderEffect`. Stage 4 introduces a GPU-offscreen path (for AGSL) where a
 * true layer blur can be adopted if this approximation falls short.
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
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(rgb, p.density) },
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
                val col = withAlpha(rgb, p.density)
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
        val pad = maxWidth / 2f + bleed * 3f + 2f
        val bounds = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val layer = canvas.saveLayer(bounds, Paint().apply { alpha = alpha255(ToolProfile.of(stroke.tool).opacity) })
        val blur = if (bleed > 0.3f) BlurMaskFilter(bleed, BlurMaskFilter.Blur.NORMAL) else null
        val pts = stroke.points
        if (pts.size == 1) {
            val p = pts.first()
            val r = max(1f, p.width / 2f)
            canvas.drawCircle(p.position.x, p.position.y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(rgb, 0.6f); maskFilter = blur
            })
            canvas.drawCircle(p.position.x, p.position.y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = max(1.5f, r * 0.4f)
                color = withAlpha(rgb, 0.95f); maskFilter = blur
            })
        } else {
            val ribbon = ribbonPath(ribbonOutline(pts, strokeNormals(pts)))
            canvas.drawPath(ribbon, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(rgb, 0.6f); maskFilter = blur
            })
            canvas.drawPath(ribbon, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = max(1.5f, maxWidth * 0.16f)
                strokeJoin = Paint.Join.ROUND; color = withAlpha(rgb, 0.95f); maskFilter = blur
            })
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
                color = rgb; maskFilter = blur
            })
        } else {
            val normals = strokeNormals(pts)
            val rnd = Random(stroke.seed.toLong())
            val bristleCount = (maxWidth / 2.5f).roundToInt().coerceIn(8, 22)
            val centerSpacing = maxWidth / bristleCount
            for (b in 0 until bristleCount) {
                if (rnd.nextDouble() < 0.1) continue // dry-brush gap
                val base = (b + 0.5f) / bristleCount * 2f - 1f
                val frac = (base + (rnd.nextDouble().toFloat() - 0.5f) * (2f / bristleCount) * 0.8f)
                    .coerceIn(-1f, 1f)
                val bw = max(0.6f, centerSpacing * (0.9f + rnd.nextDouble().toFloat() * 0.9f))
                val path = android.graphics.Path()
                for (j in pts.indices) {
                    val p = pts[j]
                    val off = p.position + normals[j] * (frac * p.width / 2f)
                    if (j == 0) path.moveTo(off.x, off.y) else path.lineTo(off.x, off.y)
                }
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = bw
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                    color = rgb; maskFilter = blur
                })
            }
        }
        if (tooth != null) applyTooth(canvas, bounds, tooth, toothScale)
        canvas.restoreToCount(layer)
    }

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

    // --- Helpers ------------------------------------------------------------

    private const val OPAQUE_ALPHA = 0xFF000000.toInt()

    private fun alpha255(a: Float): Int = (a * 255f).roundToInt().coerceIn(0, 255)

    private fun withAlpha(rgb: Int, a: Float): Int =
        (alpha255(a) shl 24) or (rgb and 0x00FFFFFF)
}
