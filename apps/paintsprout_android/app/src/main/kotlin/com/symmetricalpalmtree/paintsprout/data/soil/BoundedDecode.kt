package com.symmetricalpalmtree.paintsprout.data.soil

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decoding pixels that came out of a file, with a ceiling.
 *
 * Three places decode a stored image — a library card's cover, a page's raster
 * cache, a page-strip thumbnail — and all three used to *say* they were bounded
 * while doing nothing of the kind. That was survivable while every byte had been
 * written by this app. Import ended that: a `.soil` is a file we did not write,
 * its cache blob is a PNG, and a PNG header can claim any size at all. Decoding
 * one is a multiplication away from an out-of-memory kill on the page-open path.
 *
 * So the bound is real, it is checked from the **header** before any pixels are
 * allocated, and going over it returns null rather than a smaller picture: the
 * callers all have a correct answer for "no image" — replay the ops, draw a blank
 * card — and none of them has one for "a different image than the file holds".
 */
object BoundedDecode {

    /**
     * The ceiling, in pixels. Roughly 8000 × 5000 — far past any buffer a device
     * this targets can produce, and about 160 MB decoded, which is the number
     * that matters: it is the allocation being refused.
     */
    const val MAX_PIXELS = 40_000_000L

    /** Width and height from the header alone, without decoding anything. */
    fun sizeOf(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outWidth to bounds.outHeight
    }

    /** Whether [bytes] is small enough to decode at full size. */
    fun withinBounds(bytes: ByteArray, maxPixels: Long = MAX_PIXELS): Boolean {
        val (w, h) = sizeOf(bytes) ?: return false
        return w.toLong() * h.toLong() <= maxPixels
    }

    /**
     * The image at full size, or null — unreadable, or bigger than [maxPixels].
     *
     * [mutable] for the raster cache, which is drawn into after it is read.
     */
    fun full(bytes: ByteArray, mutable: Boolean = false, maxPixels: Long = MAX_PIXELS): Bitmap? {
        if (!withinBounds(bytes, maxPixels)) return null
        return runCatching {
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inMutable = mutable },
            )
        }.getOrNull()
    }

    /**
     * The image no larger than [maxEdge], subsampled during the decode.
     *
     * For a picture that is only ever *shown* small — a library card. Sampling is
     * the right trade there and the wrong one for anything that gets composited,
     * which is why [full] does not offer it. (See `Compactor`'s neighbour
     * `ThumbnailPlan` for what sampling does to a hairline.)
     */
    fun sampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val (w, h) = sizeOf(bytes) ?: return null
        var sample = 1
        while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2
        // Even sampled, refuse a header claiming something absurd: `inSampleSize`
        // is a hint the decoder may round down, so the allocation is not capped by
        // arithmetic alone.
        if (!withinBounds(bytes, MAX_PIXELS)) return null
        return runCatching {
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }
}
