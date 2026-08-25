package com.symmetricalpalmtree.paintsproutonyx.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf does not scroll, so how many cards fit on a page is not a detail of the layout — it is
 * the layout. Getting it wrong by one is not a crash and not a visible fault: it is a library that
 * paginates a beat early, forever, on a device nobody is looking at with a ruler.
 *
 * The numbers below are the NA5C's own: 1860 × 2480 px at density 1.875, which is 992 dp across. The
 * grid area is what is left after the screen margin, the two chrome bars and the status-bar guard,
 * measured on the built layout rather than guessed.
 */
class GridGeometryTest {

    private val density = 1.875f
    /** 992 dp of panel less 24 dp of screen margin either side, in pixels. */
    private val gridWidth = 1860 - 2 * 45
    /** What is left below the top bar and above the bottom bar, in pixels. */
    private val gridHeight = 2140
    /** `card_gap` at the sw720dp tier: 12 dp. */
    private val gap = 22

    @Test
    fun `the panel takes three columns and two rows`() {
        val geometry = GridGeometry.measure(gridWidth, gridHeight, density, gap)
        assertEquals("a ten-inch sheet is three cards wide", 3, geometry.columns)
        assertEquals(2, geometry.rows)
        assertEquals("six cards to a page, as chosen for this panel", 6, geometry.cardsPerPage)
    }

    @Test
    fun `the cards fit inside the grid area rather than overflowing it`() {
        val geometry = GridGeometry.measure(gridWidth, gridHeight, density, gap)
        assertTrue(
            "three columns of cells must fit the width, or the third column is silently not there",
            geometry.columns * geometry.cardWidthPx <= gridWidth,
        )
        assertTrue(
            "the rows must fit the height, or the bottom row is drawn half off the screen",
            geometry.rows * geometry.cardHeightPx <= gridHeight,
        )
    }

    @Test
    fun `a card keeps a page's proportions`() {
        val geometry = GridGeometry.measure(gridWidth, gridHeight, density, gap)
        val aspect = geometry.cardHeightPx.toFloat() / geometry.cardWidthPx
        assertEquals(
            "the cover has to stay page-shaped — from G5 it is a photograph of a real page",
            GridGeometry.CARD_ASPECT,
            aspect,
            0.01f,
        )
    }

    @Test
    fun `a narrow window falls back to two columns`() {
        // Not a device this app installs to. It is the desk-testing case, and one card cropped in
        // half is a worse answer than two small ones.
        val geometry = GridGeometry.measure(1080, 1600, 2.75f, 22)
        assertEquals(2, geometry.columns)
    }

    @Test
    fun `a container too short for a whole card still has one row`() {
        val geometry = GridGeometry.measure(gridWidth, 100, density, gap)
        assertEquals(
            "zero rows is a division by zero in every pagination sum downstream",
            1,
            geometry.rows,
        )
    }

    @Test
    fun `an empty shelf is page one of one`() {
        assertEquals("the pager reads 1 / 1, never 1 / 0", 1, GridGeometry.pageCount(0, 6))
    }

    @Test
    fun `pages fill before a new one starts`() {
        assertEquals(1, GridGeometry.pageCount(1, 6))
        assertEquals(1, GridGeometry.pageCount(6, 6))
        assertEquals("the seventh card is what makes a second page", 2, GridGeometry.pageCount(7, 6))
        assertEquals(2, GridGeometry.pageCount(12, 6))
        assertEquals(3, GridGeometry.pageCount(13, 6))
    }
}
