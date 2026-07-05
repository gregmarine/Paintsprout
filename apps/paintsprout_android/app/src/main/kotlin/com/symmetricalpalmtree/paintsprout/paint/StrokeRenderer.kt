package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Renders a [Stroke] onto an `android.graphics.Canvas`. Shared by the live
 * preview (drawn on the view canvas) and the bake (drawn into the paint buffer),
 * so what you see while drawing is exactly what commits — the same contract as
 * `paintStroke` in the Flutter `stroke.dart`.
 *
 * Stage 2 ports only the **solid** branch (pen + eraser): crisp constant-width
 * ribbons, and the eraser as a `CLEAR` blend that reveals the surface. The
 * grain / wash / bristle / soft branches, the surface tooth, and the layer blur
 * arrive in Stage 3.
 */
object StrokeRenderer {

    fun paintStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.isEmpty) return

        val isEraser = stroke.tool == Tool.ERASER
        val pts = stroke.points

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // The eraser removes paint (revealing the surface) via a CLEAR blend; its
        // color is irrelevant. Deposit tools draw their opaque color.
        paint.color = if (isEraser) Color.WHITE else (stroke.color or OPAQUE_ALPHA)
        if (isEraser) paint.blendMode = BlendMode.CLEAR

        when {
            pts.size == 1 -> {
                val p = pts.first()
                paint.style = Paint.Style.FILL
                canvas.drawCircle(p.position.x, p.position.y, p.width / 2f, paint)
            }

            !stroke.tool.isDynamic -> {
                // Constant width (pen, eraser): one smooth bézier path for clean,
                // curved edges instead of angular straight segments.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = pts.first().width
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(smoothPath(pts), paint)
            }

            else -> {
                // Variable width: per-segment so the width can track pressure.
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    paint.strokeWidth = (a.width + b.width) / 2f
                    canvas.drawLine(a.position.x, a.position.y, b.position.x, b.position.y, paint)
                }
            }
        }
    }

    private const val OPAQUE_ALPHA = 0xFF000000.toInt()
}
