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
}
