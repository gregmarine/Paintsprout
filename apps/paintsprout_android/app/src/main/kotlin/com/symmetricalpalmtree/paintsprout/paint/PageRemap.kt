package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Matrix
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Moves a freshly-loaded page's ops out of the space they were recorded in and
 * into the one this device is drawing in. See [PageSpace] for why they differ.
 *
 * **Mutates in place**, which is safe exactly here and nowhere else: these ops
 * have just been built from rows and nothing else holds them yet. Copying them
 * would mean duplicating or sharing the bitmaps a fill, an erase and a frisket
 * own, and a shared bitmap with two owners is a recycle waiting to crash.
 *
 * Two spaces go in because a page has two. Marks are recorded in **view** px, the
 * space a finger arrives in; masks and wet crops are **buffer** px. They were the
 * same number until a [CanvasSize.Frame] could set the canvas's super-sample to
 * something other than one, and a frame's buffer is its own fixed grid — the same
 * on every tablet — while its on-screen size is not.
 */
object PageRemap {

    /** Rewrites [ops] and everything nested under them. */
    fun apply(ops: List<PaintOp>, view: PageSpace, buffer: PageSpace) {
        if (view.isIdentity && buffer.isIdentity) return
        for (op in ops) one(op, view, buffer)
    }

    private fun one(op: PaintOp, view: PageSpace, buffer: PageSpace) {
        when (op) {
            is StrokeOp -> stroke(op.stroke, view, buffer)
            is MoveOp -> matrix(op.transform, buffer)
            is PasteOp -> apply(op.ops, view, buffer)

            // A fill, an erase and a frisket carry a mask that is stretched to the
            // whole buffer when it replays, so it is already proportional and there
            // is nothing here to move. Everything else — surface changes, layer and
            // folder steps, renames — has no geometry at all.
            else -> Unit
        }
    }

    private fun stroke(s: Stroke, view: PageSpace, buffer: PageSpace) {
        s.baseWidth = view.length(s.baseWidth)
        for (i in s.points.indices) {
            val p = s.points[i]
            s.points[i] = p.copy(position = view.point(p.position), width = view.length(p.width))
        }
        s.wetCrop = s.wetCrop?.let { r ->
            Rect(
                buffer.x(r.left.toFloat()).roundToInt(),
                buffer.y(r.top.toFloat()).roundToInt(),
                buffer.x(r.right.toFloat()).roundToInt(),
                buffer.y(r.bottom.toFloat()).roundToInt(),
            )
        }
        // The dry freeze is one progress value per point, not a position — it
        // travels unchanged however the stroke is rescaled.
    }

    private fun matrix(m: Matrix, buffer: PageSpace) {
        if (buffer.isIdentity) return
        val values = FloatArray(9).also(m::getValues)
        m.setValues(buffer.matrix(values))
    }
}
