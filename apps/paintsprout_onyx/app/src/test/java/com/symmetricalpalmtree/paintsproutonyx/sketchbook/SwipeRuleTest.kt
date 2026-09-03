package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The judgement that decides whether the page went away.
 *
 * Every case here is a way of getting an *unwanted* page turn, which is the failure that matters:
 * the artist rests a hand, shifts it, and the page they were drawing on is gone with the marks
 * still on their way to the file. The gesture that turns a page has to be one a hand does not make
 * by accident, and this is where that is stated in numbers.
 *
 * The panel is the NA5C's 1860 px across, and the platform's fling floor on it is around 50 px/s at
 * this density — the numbers below are that shape rather than exact, since the rule is written in
 * fractions and multiples of whatever it is handed.
 */
class SwipeRuleTest {

    private val width = 1860f
    private val flingFloor = 50f

    private fun evaluate(dx: Float, dy: Float, vx: Float) =
        SwipeRule.evaluate(dx, dy, vx, width, flingFloor)

    /** Comfortably past every threshold, so a test about one thing fails for that one thing. */
    private val far = 0.6f * width
    private val fast = 10f * flingFloor

    @Test
    fun `a long horizontal haul turns the page whichever way it went`() {
        assertEquals(SwipeRule.Flip.NEXT, evaluate(-far, 0f, 0f))
        assertEquals(SwipeRule.Flip.PREVIOUS, evaluate(far, 0f, 0f))
    }

    @Test
    fun `a hand travelling mostly down the page is never a page turn`() {
        assertEquals(
            "a diagonal drag that covers more height than width is a hand moving, not a swipe",
            SwipeRule.Flip.NONE,
            evaluate(-far, far * 1.1f, -fast),
        )
    }

    @Test
    fun `a short shift is refused however fast it was`() {
        val shift = 0.14f * width
        assertEquals(
            "under a sixth of the panel is inside the distance a resting hand moves on its own",
            SwipeRule.Flip.NONE,
            evaluate(-shift, 0f, -fast),
        )
    }

    @Test
    fun `past the floor it has to be either fast or long`() {
        val middling = 0.25f * width
        assertEquals(
            "far enough to be deliberate, slow enough and short enough to be a hand settling",
            SwipeRule.Flip.NONE,
            evaluate(-middling, 0f, -1.9f * flingFloor),
        )
        assertEquals(
            "the same distance, flicked",
            SwipeRule.Flip.NEXT,
            evaluate(-middling, 0f, -2f * flingFloor),
        )
        assertEquals(
            "and the same speed, hauled the long way instead",
            SwipeRule.Flip.NEXT,
            evaluate(-SwipeRule.LONG_DISTANCE_FRAC * width, 0f, 0f),
        )
    }

    @Test
    fun `the direction comes from where the finger ended up, never from how fast it was going`() {
        // A finger that decelerates into its lift can report a velocity with the opposite sign to
        // the journey it made. Reading that number would turn the page backwards at the end of a
        // forward swipe — and it would do it intermittently, which is the worst way to be wrong.
        assertEquals(SwipeRule.Flip.NEXT, evaluate(-far, 0f, +fast))
        assertEquals(SwipeRule.Flip.PREVIOUS, evaluate(far, 0f, -fast))
    }

    @Test
    fun `a finger that did not move is nothing at all`() {
        assertEquals(SwipeRule.Flip.NONE, evaluate(0f, 0f, 0f))
    }
}
