package com.symmetricalpalmtree.paintsprout.data.soil

import kotlin.math.roundToInt

/**
 * How a full-size page is reduced to a thumbnail: in halves, never in one jump.
 *
 * This exists as its own object because the *schedule* is the part that was
 * wrong, and the part that cannot be tested on a device without looking at
 * pictures. The first page strip decoded each cached page straight down with
 * `inSampleSize`, which subsamples — it keeps one row in every *n* and throws
 * the rest away. A pen line one pixel wide then survives only when it happens to
 * land on a kept row, and on device alternate pages of identical line art came
 * back as blank paper.
 *
 * The rule that prevents it: **no step may discard more than three quarters of
 * the pixels.** A halving averages four pixels into one, so a hairline arrives
 * faint rather than missing, and repeated halvings get anywhere.
 */
object ThumbnailPlan {

    /**
     * The sizes to scale through, in order, ending at one that fits [maxEdge].
     * Empty when the source already fits — the caller keeps what it decoded.
     */
    fun steps(width: Int, height: Int, maxEdge: Int): List<Size> {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return emptyList()
        val out = mutableListOf<Size>()
        var w = width
        var h = height
        // Down to within one step of the target, halving each time.
        while (maxOf(w, h) > maxEdge * 2) {
            w = (w / 2).coerceAtLeast(1)
            h = (h / 2).coerceAtLeast(1)
            out += Size(w, h)
        }
        // The last step is a fraction rather than a half, so the thumbnail lands
        // on the size asked for instead of somewhere between it and double it.
        val scale = minOf(maxEdge.toFloat() / w, maxEdge.toFloat() / h)
        if (scale < 1f) {
            out += Size((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }
        return out
    }

    data class Size(val width: Int, val height: Int)
}
