package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.ColorInt

/**
 * Rendering for the magic-wand region ops (fill / erase). Ports the
 * `paintToothedFill` / `paintMaskedErase` helpers from the Flutter `stroke.dart`.
 * The mask is white where selected; [dst] maps it into the current canvas
 * (buffer) coordinates.
 */
object SelectionRender {

    /**
     * Fills the region marked by [mask] (alpha = coverage) with [color], broken
     * up by the surface tooth so it reads like laid-down paint rather than a flat
     * digital bucket-fill. Uses the marker's response to the surface (soft, opaque
     * paint). [toothTexelScale] is buffer px per tooth texel
     * (`surface.toothScale` × the supersample). On a tooth-less surface (Plain)
     * the fill is simply flat.
     */
    fun paintToothedFill(
        canvas: Canvas,
        mask: Bitmap,
        dst: RectF,
        @ColorInt color: Int,
        surface: SurfaceKind,
        toothTexelScale: Float,
    ) {
        val tooth = ToothCache.toothFor(surface, Tool.MARKER)
        val src = Rect(0, 0, mask.width, mask.height)
        val dstI = Rect(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt())
        val layer = canvas.saveLayer(dst, null)
        // Lay the opaque colour everywhere the mask covers (tinting the mask alpha).
        val opaque = color or (0xFF shl 24)
        val paint = Paint().apply {
            colorFilter = PorterDuffColorFilter(opaque, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(mask, src, dstI, paint)
        if (tooth != null) applyTooth(canvas, dst, tooth, toothTexelScale)
        canvas.restoreToCount(layer)
    }

    /**
     * Erases paint within the region marked by [mask] (revealing the surface),
     * leaving other paint untouched. [dst] maps the mask into buffer coordinates.
     */
    fun paintMaskedErase(canvas: Canvas, mask: Bitmap, dst: RectF) {
        val src = Rect(0, 0, mask.width, mask.height)
        val dstI = Rect(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt())
        val paint = Paint().apply { blendMode = BlendMode.DST_OUT }
        canvas.drawBitmap(mask, src, dstI, paint)
    }
}
