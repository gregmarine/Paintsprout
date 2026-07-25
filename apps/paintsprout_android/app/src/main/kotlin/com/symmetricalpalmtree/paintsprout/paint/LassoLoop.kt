package com.symmetricalpalmtree.paintsprout.paint

import kotlin.math.abs

/**
 * Whether a freehand drag enclosed anything, and what it enclosed.
 *
 * Kept apart from the view — and expressed over a flat `[x0, y0, x1, y1, …]`
 * array rather than `PointF`s — so the question can be answered without a canvas.
 * It is a question with a wrong answer: a lasso closes itself, so *every* drag
 * encloses some region, including the one where the pen skidded 200 px across the
 * page. Selecting a sliver the user did not draw is worse than selecting nothing,
 * because the next stroke they paint is then silently clipped to it.
 *
 * The test is enclosed **area**, by the shoelace formula, not path length: a long
 * scribble back and forth over its own tracks covers a lot of ground and encloses
 * almost none, which is exactly the case a length threshold would wave through.
 */
object LassoLoop {

    /**
     * The smallest loop that counts, in square logical pixels — about a 5 mm box
     * on the target device. Below it the drag reads as a slip of the hand.
     */
    const val MIN_AREA = 900f

    /** Two points enclose nothing at all; a triangle is the smallest real loop. */
    const val MIN_POINTS = 3

    /**
     * The area enclosed by the closed polygon through [xy], always positive.
     *
     * The polygon closes itself: the last point joins the first, which is what a
     * lasso is. Self-intersecting loops — a figure eight — get the signed sum,
     * where the two halves partly cancel; that is the honest answer for a shape
     * whose inside is genuinely ambiguous, and it errs towards "not a selection".
     */
    fun area(xy: FloatArray): Float {
        val n = xy.size / 2
        if (n < MIN_POINTS) return 0f
        var sum = 0f
        var j = n - 1
        for (i in 0 until n) {
            sum += (xy[2 * j] + xy[2 * i]) * (xy[2 * j + 1] - xy[2 * i + 1])
            j = i
        }
        return abs(sum) / 2f
    }

    /** Whether this drag is a loop worth turning into a selection. */
    fun encloses(xy: FloatArray, minArea: Float = MIN_AREA): Boolean =
        xy.size / 2 >= MIN_POINTS && area(xy) >= minArea

    /**
     * The loop's bounding box as `[left, top, right, bottom]`, or null for a path
     * with no points. The caller scales it into whatever space it needs; this
     * knows only about the coordinates it was handed.
     */
    fun bounds(xy: FloatArray): FloatArray? {
        val n = xy.size / 2
        if (n == 0) return null
        var l = xy[0]
        var t = xy[1]
        var r = xy[0]
        var b = xy[1]
        for (i in 1 until n) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return floatArrayOf(l, t, r, b)
    }
}
