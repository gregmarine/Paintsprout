package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lasso closes itself, so every drag encloses *something*. These are the cases
 * where the honest answer is "nothing was selected" — the ones that decide
 * whether a slip of the hand silently clips the next stroke the user paints.
 */
class LassoLoopTest {

    /** A square, corner points only. */
    private fun square(side: Float) =
        floatArrayOf(0f, 0f, side, 0f, side, side, 0f, side)

    @Test
    fun `a square encloses its side squared`() {
        assertEquals(100f * 100f, LassoLoop.area(square(100f)), 0.01f)
    }

    /** Wound the other way round, the area is the same size. */
    @Test
    fun `winding direction does not change the area`() {
        val clockwise = floatArrayOf(0f, 0f, 0f, 100f, 100f, 100f, 100f, 0f)
        assertEquals(LassoLoop.area(square(100f)), LassoLoop.area(clockwise), 0.01f)
    }

    @Test
    fun `a triangle is half its box`() {
        assertEquals(5000f, LassoLoop.area(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f)), 0.01f)
    }

    /** The case a path-length threshold would wave through. */
    @Test
    fun `a scribble back and forth over its own tracks encloses nothing`() {
        val xy = FloatArray(200)
        for (i in 0 until 50) {
            // Four passes along the same line: long, and flat as a board.
            xy[4 * i] = i * 20f
            xy[4 * i + 1] = 0f
            xy[4 * i + 2] = i * 20f
            xy[4 * i + 3] = 0.5f
        }
        assertTrue("area was ${LassoLoop.area(xy)}", LassoLoop.area(xy) < LassoLoop.MIN_AREA)
        assertFalse(LassoLoop.encloses(xy))
    }

    @Test
    fun `a tap and a two-point flick enclose nothing`() {
        assertEquals(0f, LassoLoop.area(floatArrayOf(10f, 10f)), 0f)
        assertEquals(0f, LassoLoop.area(floatArrayOf(10f, 10f, 400f, 400f)), 0f)
        assertFalse(LassoLoop.encloses(floatArrayOf(10f, 10f)))
        assertFalse(LassoLoop.encloses(floatArrayOf(10f, 10f, 400f, 400f)))
        assertFalse(LassoLoop.encloses(FloatArray(0)))
    }

    /** A real loop, drawn small but deliberately, still counts. */
    @Test
    fun `a loop at the threshold selects`() {
        assertTrue(LassoLoop.encloses(square(30f))) // 900 square px
        assertFalse(LassoLoop.encloses(square(29f)))
    }

    /**
     * A figure eight's halves cancel in the signed sum. Ambiguous shapes err
     * towards "not a selection", which is the recoverable mistake.
     */
    @Test
    fun `a figure eight of equal halves encloses nothing`() {
        val eight = floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)
        assertEquals(0f, LassoLoop.area(eight), 0.01f)
        assertFalse(LassoLoop.encloses(eight))
    }

    // --- Bounds -------------------------------------------------------------

    @Test
    fun `bounds are the box the loop was drawn in`() {
        val b = LassoLoop.bounds(floatArrayOf(30f, 90f, 10f, 20f, 70f, 50f))!!
        assertEquals(10f, b[0], 0f)
        assertEquals(20f, b[1], 0f)
        assertEquals(70f, b[2], 0f)
        assertEquals(90f, b[3], 0f)
    }

    @Test
    fun `a single point has a box of no size, and no points has none at all`() {
        val b = LassoLoop.bounds(floatArrayOf(5f, 6f))!!
        assertEquals(floatArrayOf(5f, 6f, 5f, 6f).toList(), b.toList())
        assertNull(LassoLoop.bounds(FloatArray(0)))
    }

    /** Negative coordinates happen: a loop can be drawn off the edge of the sheet. */
    @Test
    fun `bounds survive a loop drawn past the edge`() {
        val b = LassoLoop.bounds(floatArrayOf(-40f, -10f, 60f, 30f))!!
        assertEquals(-40f, b[0], 0f)
        assertEquals(-10f, b[1], 0f)
        assertTrue(LassoLoop.encloses(floatArrayOf(-50f, -50f, 50f, -50f, 50f, 50f, -50f, 50f)))
    }
}
