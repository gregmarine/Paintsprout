package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTurnTest {

    private val min = 180f // ~72dp on the target device

    @Test
    fun `a sweep to the left goes forward, to the right goes back`() {
        assertEquals(PageTurn.FORWARD, PageTurn.of(dx = -400f, dy = 10f, minimum = min))
        assertEquals(PageTurn.BACK, PageTurn.of(dx = 400f, dy = -10f, minimum = min))
    }

    /** A finger shifting where it rests is not a page turn. */
    @Test
    fun `a short drag turns nothing`() {
        assertNull(PageTurn.of(dx = -179f, dy = 0f, minimum = min))
        assertNull(PageTurn.of(dx = 0f, dy = 0f, minimum = min))
    }

    /** Exactly at the threshold counts — the boundary belongs to the gesture. */
    @Test
    fun `the threshold itself turns`() {
        assertEquals(PageTurn.FORWARD, PageTurn.of(dx = -180f, dy = 0f, minimum = min))
    }

    /** Vertical and diagonal drags are somebody reaching, not turning. */
    @Test
    fun `a drag that is not sideways turns nothing`() {
        assertNull("straight down", PageTurn.of(dx = 0f, dy = 600f, minimum = min))
        assertNull("far, but diagonal", PageTurn.of(dx = -400f, dy = 400f, minimum = min))
        assertNull("just off sideways", PageTurn.of(dx = -400f, dy = 201f, minimum = min))
    }

    @Test
    fun `sideways enough is sideways`() {
        assertEquals(PageTurn.FORWARD, PageTurn.of(dx = -400f, dy = 199f, minimum = min))
    }

    // --- Turned tablet -------------------------------------------------------
    //
    // The sheet stays pinned to the glass, so at a quarter turn the drag arrives
    // rotated. Sideways has to mean sideways to the person, not to the paper.

    /**
     * The same sweep the artist makes, whichever way up the tablet is.
     *
     * Written as "what reaches the sheet when the hand goes left" for each
     * quarter, so a wrong sign here is a page turning the wrong way rather than
     * an abstraction that merely looks plausible.
     */
    @Test
    fun `a leftward sweep turns forward at every quarter`() {
        assertEquals("upright", PageTurn.FORWARD, PageTurn.of(-400f, 0f, min, quarter = 0))
        assertEquals("quarter turn", PageTurn.FORWARD, PageTurn.of(0f, -400f, min, quarter = 1))
        assertEquals("upside down", PageTurn.FORWARD, PageTurn.of(400f, 0f, min, quarter = 2))
        assertEquals("three quarters", PageTurn.FORWARD, PageTurn.of(0f, 400f, min, quarter = 3))
    }

    @Test
    fun `a rightward sweep turns back at every quarter`() {
        assertEquals("upright", PageTurn.BACK, PageTurn.of(400f, 0f, min, quarter = 0))
        assertEquals("quarter turn", PageTurn.BACK, PageTurn.of(0f, 400f, min, quarter = 1))
        assertEquals("upside down", PageTurn.BACK, PageTurn.of(-400f, 0f, min, quarter = 2))
        assertEquals("three quarters", PageTurn.BACK, PageTurn.of(0f, -400f, min, quarter = 3))
    }

    /**
     * And the drag that *used* to work at a quarter turn now correctly does
     * nothing: across the sheet is up the page when the page is on its side.
     */
    @Test
    fun `a sweep across the sheet turns nothing once the tablet is turned`() {
        assertNull(PageTurn.of(-400f, 0f, min, quarter = 1))
        assertNull(PageTurn.of(400f, 0f, min, quarter = 3))
    }

    @Test
    fun `the short and the diagonal are still refused when turned`() {
        assertNull("short", PageTurn.of(0f, -179f, min, quarter = 1))
        assertNull("diagonal", PageTurn.of(400f, -400f, min, quarter = 1))
        assertEquals("sideways enough", PageTurn.FORWARD, PageTurn.of(199f, -400f, min, quarter = 1))
    }

    /** A quarter is a count, not an angle: it wraps, and it may arrive negative. */
    @Test
    fun `quarters outside zero to three wrap`() {
        assertEquals(PageTurn.FORWARD, PageTurn.of(0f, -400f, min, quarter = 5))
        assertEquals(PageTurn.FORWARD, PageTurn.of(0f, -400f, min, quarter = -3))
        assertEquals(PageTurn.FORWARD, PageTurn.of(-400f, 0f, min, quarter = 4))
    }
}
