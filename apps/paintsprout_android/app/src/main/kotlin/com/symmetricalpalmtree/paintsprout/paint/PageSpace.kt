package com.symmetricalpalmtree.paintsprout.paint

import kotlin.math.min

/**
 * The map from the space a page's marks were recorded in to the space it is being
 * drawn in now — a uniform scale and a centring offset, nothing else.
 *
 * A page is recorded in the view pixels of the tablet it was drawn on, and those
 * are not the same on two tablets: a 5 × 7 sheet is 1148 × 1608 px at one screen's
 * calibrated PPI and 1213 × 1700 at another's. Replaying the first on the second
 * without this puts the drawing in the top-left 95% of the paper with every mark
 * a twentieth too thin.
 *
 * **Uniform, and centred.** Scaling the axes independently would stretch the
 * artwork, so the sheet is fitted — the larger of the two scales is the one that
 * would overflow, so the smaller wins — and what is left over is split evenly.
 * For a print or a frame the two axes agree exactly and the offsets are zero; it
 * is the full-screen canvas, whose shape is the panel's and therefore differs
 * between tablets, where the fit does real work and leaves a margin. That margin
 * is honest: a drawing made edge to edge on a 3:2 panel cannot also reach the
 * edges of a 16:10 one, and the alternative to a margin is a crop.
 *
 * Every field is in the *destination* space. [inverse] goes back, which is what a
 * save uses: the file keeps the space it was created in for life, so nothing has
 * to be rewritten in bulk and no half-finished conversion can leave a page whose
 * marks and whose stamp disagree.
 */
data class PageSpace(val scale: Float, val dx: Float, val dy: Float) {

    val isIdentity: Boolean get() = scale == 1f && dx == 0f && dy == 0f

    /** p = (p' − d) / s, as a [PageSpace] in its own right. */
    fun inverse(): PageSpace =
        if (isIdentity) IDENTITY else PageSpace(1f / scale, -dx / scale, -dy / scale)

    fun x(v: Float): Float = v * scale + dx

    fun y(v: Float): Float = v * scale + dy

    /** A width, a radius, a distance — scaled but never offset. */
    fun length(v: Float): Float = v * scale

    fun point(p: Vec2): Vec2 = Vec2(x(p.x), y(p.y))

    /**
     * A 3×3 affine (Android's `Matrix.getValues` order) conjugated into this
     * space: `T · M · T⁻¹`.
     *
     * Only the translation column moves. A rotation is the same rotation and a
     * scale the same scale whichever space they are read in — what changes is
     * where the thing being rotated sits, and that is entirely in the translation.
     */
    fun matrix(m: FloatArray): FloatArray {
        if (m.size < 9) return m
        val (a, b, c) = Triple(m[0], m[1], m[2])
        val (d, e, f) = Triple(m[3], m[4], m[5])
        return floatArrayOf(
            a, b, scale * c + dx - a * dx - b * dy,
            d, e, scale * f + dy - d * dx - e * dy,
            m[6], m[7], m[8],
        )
    }

    companion object {

        val IDENTITY = PageSpace(1f, 0f, 0f)

        /**
         * The map that puts a [fromW] × [fromH] sheet inside a [toW] × [toH] one,
         * as large as it will go without distortion, centred.
         *
         * A dimension that is missing or nonsense yields [IDENTITY] rather than a
         * guess — that is the case of a page written before pages recorded their
         * size, and the right thing to do with it is to open it exactly as this
         * device would have opened it before, which is what identity means.
         */
        fun fit(fromW: Float?, fromH: Float?, toW: Float, toH: Float): PageSpace {
            if (fromW == null || fromH == null) return IDENTITY
            if (fromW <= 0f || fromH <= 0f || toW <= 0f || toH <= 0f) return IDENTITY
            val s = min(toW / fromW, toH / fromH)
            if (!s.isFinite() || s <= 0f) return IDENTITY
            return PageSpace(s, (toW - fromW * s) / 2f, (toH - fromH * s) / 2f)
        }
    }
}
