package com.symmetricalpalmtree.paintsprout.paint

import kotlin.math.abs

/**
 * Which way a finger swept across the sheet.
 *
 * The decision lives here, away from the touch handler, because it is the part
 * with a wrong answer: a swipe that is mostly vertical, or barely a swipe at all,
 * must turn nothing. Losing the page you were painting on because your palm slid
 * is not a recoverable annoyance — the page is still there, but you are not on
 * it, and you find out by looking away from your work.
 */
enum class PageTurn {
    /** Swept right to left — the way you push a finished sheet aside. */
    FORWARD,

    /** Swept left to right — back towards the pages already done. */
    BACK,

    ;

    companion object {

        /**
         * The turn a drag of ([dx], [dy]) means, or null for no turn.
         *
         * Two conditions, both necessary: it travelled at least [minimum]
         * horizontally, and it is genuinely sideways rather than a diagonal that
         * happened to go far enough — hence the comparison against [dy] rather
         * than an angle, which is the same test and cheaper.
         *
         * [quarter] is how far the sheet has been turned away from the person
         * looking at it — the same count the rail's glyphs turn back by. Sideways
         * means sideways *to them*: the drag arrives in the sheet's coordinates,
         * which stay pinned to the glass, so at a quarter turn a sweep across the
         * page is a sweep down the sheet and would otherwise turn nothing.
         */
        fun of(dx: Float, dy: Float, minimum: Float, quarter: Int = 0): PageTurn? {
            val (sx, sy) = asSeen(dx, dy, quarter)
            if (abs(sx) < minimum) return null
            if (abs(sx) < abs(sy) * 2f) return null
            return if (sx < 0f) FORWARD else BACK
        }

        /**
         * A drag in the sheet's coordinates, re-expressed in the viewer's.
         *
         * The frame holding the sheet is rotated by `-quarter * 90°` to keep the
         * paper square on the glass, so undoing that turn is what carries a
         * direction back into the space the hand moved in.
         */
        private fun asSeen(dx: Float, dy: Float, quarter: Int): Pair<Float, Float> =
            when (((quarter % 4) + 4) % 4) {
                0 -> dx to dy
                1 -> dy to -dx
                2 -> -dx to -dy
                else -> -dy to dx
            }
    }
}
