package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the screen lands when a page leaves, and what number the page it landed on carries.
 *
 * Neither is a thing a device walk will catch. A delete that lands one leaf too far is a page turn
 * that looks like the artist mis-swiped, and a page indicator off by one is a caption nobody reads
 * closely enough to disbelieve — both survive indefinitely on a panel nobody is checking with a
 * finger on the page count.
 */
class PageMathTest {

    private val book = listOf("a", "b", "c")

    @Test
    fun `the page behind the one torn out is the next one`() {
        assertEquals("b", PageMath.neighbourAfterRemoving(book, "a"))
        assertEquals("c", PageMath.neighbourAfterRemoving(book, "b"))
    }

    @Test
    fun `the last leaf falls back to the one before it`() {
        assertEquals(
            "there is no forward left, and the drawing continues where it can",
            "b",
            PageMath.neighbourAfterRemoving(book, "c"),
        )
    }

    @Test
    fun `the only page in the book leaves nothing behind`() {
        assertNull(PageMath.neighbourAfterRemoving(listOf("a"), "a"))
    }

    @Test
    fun `a page that was never in this book has no neighbour to offer`() {
        assertNull(PageMath.neighbourAfterRemoving(book, "z"))
        assertNull(PageMath.neighbourAfterRemoving(emptyList(), "a"))
    }

    @Test
    fun `pages are counted the way a person counts them`() {
        assertEquals(1, PageMath.positionOf(book, "a"))
        assertEquals(2, PageMath.positionOf(book, "b"))
        assertEquals(3, PageMath.positionOf(book, "c"))
    }

    @Test
    fun `a page that is not in the book counts as zero`() {
        assertEquals(
            "0 / 7 reads as obviously wrong; a plausible page number would not",
            0,
            PageMath.positionOf(book, "z"),
        )
        assertEquals(0, PageMath.positionOf(emptyList(), "a"))
    }
}
