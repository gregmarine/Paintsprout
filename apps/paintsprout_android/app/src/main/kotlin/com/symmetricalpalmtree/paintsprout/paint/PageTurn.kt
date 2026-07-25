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
         */
        fun of(dx: Float, dy: Float, minimum: Float): PageTurn? {
            if (abs(dx) < minimum) return null
            if (abs(dx) < abs(dy) * 2f) return null
            return if (dx < 0f) FORWARD else BACK
        }
    }
}
