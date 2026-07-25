package com.symmetricalpalmtree.paintsprout.data.soil

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.symmetricalpalmtree.paintsprout.data.soil.codec.MaskCodec

/**
 * Selection masks, between the bitmap the renderer wants and the bytes the file
 * keeps.
 *
 * A wand or lasso mask is opaque white where selected and nothing elsewhere, so
 * the alpha channel *is* the mask and the other three bytes per pixel are pure
 * waste. It also spans the whole canvas while covering a fraction of it, so it is
 * cropped to what it actually contains: the row keeps the crop's origin, the full
 * size it belongs in, and the downsample factor, which is everything needed to
 * put it back exactly where it was.
 *
 * Between dropping three bytes in four and cropping, a typical selection stores
 * in a fraction of a percent of its in-memory size — before zlib, which is very
 * good at large flat regions.
 */
object MaskBitmaps {

    /** A mask, and where in the full field it sits. */
    class Cropped(
        val mask: MaskCodec.Mask,
        val left: Int,
        val top: Int,
        val fullWidth: Int,
        val fullHeight: Int,
    )

    /** Null when the mask is entirely empty — there is nothing to select. */
    fun encode(bitmap: Bitmap): Cropped? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return crop(alphaOf(pixels), w, h)
    }

    fun decode(cropped: Cropped): Bitmap {
        val alpha = expand(cropped)
        val full = IntArray(cropped.fullWidth * cropped.fullHeight)
        for (i in full.indices) {
            val a = alpha[i].toInt() and 0xFF
            // Opaque white at full coverage, which is what the renderer scales and
            // blends; anything less carries its own alpha for a soft edge.
            full[i] = (a shl 24) or 0x00FFFFFF
        }
        return createBitmap(cropped.fullWidth, cropped.fullHeight).apply {
            setPixels(full, 0, cropped.fullWidth, 0, 0, cropped.fullWidth, cropped.fullHeight)
        }
    }

    // --- The pure core, so all of this is testable off-device ----------------

    internal fun alphaOf(pixels: IntArray): ByteArray =
        ByteArray(pixels.size) { ((pixels[it] ushr 24) and 0xFF).toByte() }

    /** Crops to the covered region, or null when nothing is covered. */
    internal fun crop(alpha: ByteArray, width: Int, height: Int): Cropped? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (alpha[row + x].toInt() == 0) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null

        val cw = right - left + 1
        val ch = bottom - top + 1
        val out = ByteArray(cw * ch)
        for (y in 0 until ch) {
            System.arraycopy(alpha, (top + y) * width + left, out, y * cw, cw)
        }
        return Cropped(MaskCodec.Mask(cw, ch, out), left, top, width, height)
    }

    /** Puts a cropped mask back into its full field. */
    internal fun expand(cropped: Cropped): ByteArray {
        val full = ByteArray(cropped.fullWidth * cropped.fullHeight)
        val m = cropped.mask
        for (y in 0 until m.height) {
            val destY = cropped.top + y
            if (destY < 0 || destY >= cropped.fullHeight) continue
            val destX = cropped.left
            if (destX < 0 || destX + m.width > cropped.fullWidth) continue
            System.arraycopy(m.alpha, y * m.width, full, destY * cropped.fullWidth + destX, m.width)
        }
        return full
    }
}
