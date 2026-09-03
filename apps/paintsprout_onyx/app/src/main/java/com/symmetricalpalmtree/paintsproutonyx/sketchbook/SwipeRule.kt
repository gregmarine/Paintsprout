package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import kotlin.math.abs

/**
 * Was that a page turn, or was it a hand moving across the paper?
 *
 * The whole judgement, pulled out of the touch plumbing so it can be argued about and tested
 * without a panel. Everything the decision needs arrives as five numbers; nothing here knows about
 * MotionEvents, pointers or timing.
 *
 * The failure this exists to avoid is not a swipe that does not work. It is a swipe that works when
 * it was not meant to: the artist rests a hand, shifts it an inch, and the page they were drawing
 * on goes away. On a panel where a page turn costs a full refresh and the marks are still being
 * written to the file, an accidental flip reads as lost work. So the rule is deliberately harder to
 * satisfy than a scroll and asks for a gesture that could not be anything else.
 */
object SwipeRule {

    /** Which way the book went, or that it did not. */
    enum class Flip { NEXT, PREVIOUS, NONE }

    /**
     * The floor. Under this the finger has not crossed enough of the page for anyone to have meant
     * it — a fifteenth of the panel is inside the distance a resting hand shifts on its own — and
     * every gesture below it is refused whatever its speed.
     */
    const val MIN_DISTANCE_FRAC = 0.15f

    /**
     * A drag this far across the page is a page turn on its own, however slowly it was made. It is
     * the deliberate, unhurried version of the gesture: a hand that has travelled nearly half the
     * panel in one direction was not resting.
     */
    const val LONG_DISTANCE_FRAC = 0.40f

    /**
     * How much faster than the platform's own fling floor a short swipe has to be. The floor is
     * tuned for a list that scrolls under a fingertip, and on this panel it is well inside the
     * speed of a palm sliding on the glass. Doubling it asks for a flick.
     */
    const val MIN_VELOCITY_MULT = 2f

    /**
     * [dx]/[dy] are the finger's whole displacement, [vx] its horizontal velocity at the lift, and
     * [widthPx] the panel's width; [minFlingVelocity] is the platform's own fling floor.
     *
     * Three things have to hold: the movement is **horizontal-dominant**, so a hand travelling
     * mostly down the page is never a page turn; it covers at least [MIN_DISTANCE_FRAC] of the
     * width; and it is either fast or long — a flick, or a deliberate haul.
     *
     * **The direction comes from the displacement and never from the velocity.** A finger that
     * decelerates into its lift can report a velocity with the opposite sign to the journey it
     * actually made, and the reader of that number would turn the page backwards at the end of a
     * forward swipe. Where the finger *ended up* cannot lie about which way it went.
     */
    fun evaluate(
        dx: Float,
        dy: Float,
        vx: Float,
        widthPx: Float,
        minFlingVelocity: Float,
    ): Flip {
        val travelled = abs(dx)
        if (travelled <= abs(dy)) return Flip.NONE
        if (travelled < MIN_DISTANCE_FRAC * widthPx) return Flip.NONE
        val fast = abs(vx) >= MIN_VELOCITY_MULT * minFlingVelocity
        val long = travelled >= LONG_DISTANCE_FRAC * widthPx
        if (!fast && !long) return Flip.NONE
        return if (dx < 0f) Flip.NEXT else Flip.PREVIOUS
    }
}
