package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic of a raster undo: which squares of the page an entry has to remember, and how few
 * times each of them is read.
 *
 * This is the half of R3 that can be proved with no tablet in the room, and it is the half where a
 * mistake would be silent. A cell read twice is invisible until a minute of scrubbing has quietly
 * filled the history; a cell not read at all is a square of the drawing that undo steps over; a
 * cell whose rect and pixel count disagree is refused by the engine with a log line nobody is
 * reading. None of the three looks like anything at the moment it happens.
 *
 * **What cannot be tested here**, and goes on the device walk instead: a raster edit made on one
 * leaf and taken back from another. That is [SketchbookActivity] — a page turn through `showPage`,
 * the pen-idle gate, and the swap into a live `PaperView` — and every part of it needs the panel.
 * The rule it has to prove is that the page is *not* shown again after the swap: a reload would
 * read the row, which still holds the picture as it was before the undo, and put it straight back.
 * The same goes for the swap being its own inverse; `swapPageRaster` is g-paper's, and g-paper
 * proves it there.
 */
class RasterTilesTest {

    private val cell = RasterTiles.CELL

    // ── The grid ─────────────────────────────────────────────────────────────

    @Test
    fun `a rect inside one cell touches one cell`() {
        val cells = RasterTiles.cellsTouching(70, 70, 80, 80, 256, 256)
        assertEquals(listOf(CellKey(1, 1)), cells)
    }

    @Test
    fun `a rect across a cell boundary touches both sides of it`() {
        // 60 to 70 crosses 64 on both axes, so this is the two-by-two block at the origin.
        val cells = RasterTiles.cellsTouching(60, 60, 70, 70, 256, 256)
        assertEquals(
            setOf(CellKey(0, 0), CellKey(1, 0), CellKey(0, 1), CellKey(1, 1)),
            cells.toSet(),
        )
        assertEquals("a cell must not be named twice in one sweep", 4, cells.size)
    }

    @Test
    fun `a rect that runs off the page is clipped rather than refused`() {
        // The engine clips its own rects, but a host that assumed so and was wrong would ask for a
        // cell off the edge and get a patch of a different shape back.
        val cells = RasterTiles.cellsTouching(-50, -50, 10, 10, 256, 256)
        assertEquals(listOf(CellKey(0, 0)), cells)
    }

    @Test
    fun `a rect wholly off the page touches nothing`() {
        assertTrue(RasterTiles.cellsTouching(300, 300, 400, 400, 256, 256).isEmpty())
        assertTrue(RasterTiles.cellsTouching(-40, -40, -10, -10, 256, 256).isEmpty())
        assertTrue("an empty rect is not a change", RasterTiles.cellsTouching(10, 10, 10, 20, 256, 256).isEmpty())
    }

    @Test
    fun `a page with no size has no grid`() {
        assertTrue(RasterTiles.cellsTouching(0, 0, 10, 10, 0, 0).isEmpty())
        assertNull(RasterTiles.cellRect(CellKey(0, 0), 0, 0))
    }

    @Test
    fun `the last column and row are partial, and that is what keeps a tile the shape it claims`() {
        val page = cell + 36
        val corner = RasterTiles.cellRect(CellKey(1, 1), page, page)!!
        assertEquals(CellRect(cell, cell, 36, 36), corner)
        val full = RasterTiles.cellRect(CellKey(0, 0), page, page)!!
        assertEquals(CellRect(0, 0, cell, cell), full)
    }

    @Test
    fun `a cell past the edge of the page is not a cell`() {
        assertNull(RasterTiles.cellRect(CellKey(4, 0), 100, 100))
        assertNull(RasterTiles.cellRect(CellKey(0, 4), 100, 100))
        assertNull(RasterTiles.cellRect(CellKey(-1, 0), 100, 100))
    }

    // ── The builder ──────────────────────────────────────────────────────────

    /** A page of exactly two cells by two. */
    private val page = cell * 2

    private class Reader {
        var reads = 0
        val seen = mutableListOf<CellRect>()
        fun read(rect: CellRect): IntArray {
            reads++
            seen.add(rect)
            return IntArray(rect.width * rect.height)
        }
    }

    @Test
    fun `a cell is read once however many times the sweep crosses it`() {
        // This is the whole reason the grid exists. The engine reports an erase once per batch and
        // the batches of one slow scrub overlap almost entirely; a tile per batch would fill the
        // history from inside a single contact.
        val reader = Reader()
        val builder = RasterEditBuilder("p1", page, page)
        repeat(20) { builder.touch(10, 10, 40, 40, reader::read) }
        assertEquals(1, reader.reads)
        assertEquals(1, builder.build()!!.tiles.size)
    }

    @Test
    fun `a sweep across the page picks up each new cell as it reaches it`() {
        val reader = Reader()
        val builder = RasterEditBuilder("p1", page, page)
        builder.touch(0, 0, 10, 10, reader::read)
        assertEquals(1, reader.reads)
        builder.touch(0, 0, cell + 10, 10, reader::read)
        assertEquals("the cell already held is not read again", 2, reader.reads)
        builder.touch(0, 0, cell + 10, cell + 10, reader::read)
        assertEquals(4, reader.reads)
        builder.touch(0, 0, cell + 10, cell + 10, reader::read)
        assertEquals(4, reader.reads)
    }

    @Test
    fun `the tiles of one entry never overlap`() {
        val builder = RasterEditBuilder("p1", page, page)
        builder.touch(0, 0, page, page) { IntArray(it.width * it.height) }
        val tiles = builder.build()!!.tiles
        assertEquals(4, tiles.size)
        for (a in tiles.indices) {
            for (b in a + 1 until tiles.size) {
                val x = tiles[a]
                val y = tiles[b]
                val overlaps = x.left < y.left + y.width && y.left < x.left + x.width &&
                    x.top < y.top + y.height && y.top < x.top + x.height
                assertFalse(
                    "tiles that overlap have to be swapped in a particular order, and the replayer " +
                        "deliberately does not remember one",
                    overlaps,
                )
            }
        }
    }

    @Test
    fun `an entry costs the sum of its tiles`() {
        val builder = RasterEditBuilder("p1", page, page)
        builder.touch(0, 0, page, page) { IntArray(it.width * it.height) }
        val expected = page.toLong() * page.toLong() * 4L
        assertEquals(expected, builder.bytes)
        assertEquals(expected, builder.build()!!.bytes)
    }

    @Test
    fun `a contact bigger than the history can hold records nothing at all`() {
        // One cell fits; the second one does not. The entry is dropped whole rather than kept as a
        // corner of a sweep — an undo that restores part of a rub is worse than one that admits it
        // cannot.
        val oneCell = cell.toLong() * cell.toLong() * 4L
        val builder = RasterEditBuilder("p1", page, page, capBytes = oneCell + 1)
        builder.touch(0, 0, page, page) { IntArray(it.width * it.height) }
        assertTrue(builder.tooBig)
        assertEquals(0L, builder.bytes)
        assertNull(builder.build())
    }

    @Test
    fun `a contact already too big reads nothing more`() {
        val reader = Reader()
        val builder = RasterEditBuilder("p1", page, page, capBytes = 8L)
        builder.touch(0, 0, 10, 10, reader::read)
        val after = reader.reads
        builder.touch(0, 0, page, page, reader::read)
        assertEquals("a contact that has given up must stop asking the paper for pixels", after, reader.reads)
    }

    @Test
    fun `a contact that changed nothing the paper would give up records nothing`() {
        val builder = RasterEditBuilder("p1", page, page)
        builder.touch(0, 0, 10, 10) { null }
        assertNull(builder.build())
        assertEquals(0L, builder.bytes)
    }

    @Test
    fun `a read that comes back the wrong shape is left out`() {
        // The engine refuses a patch whose pixel count does not match its rect, and refuses it with
        // a log line rather than an exception — so a tile kept like this would be a square of the
        // page the undo silently steps over.
        val builder = RasterEditBuilder("p1", page, page)
        builder.touch(0, 0, 10, 10) { IntArray(3) }
        assertNull(builder.build())
    }

    @Test
    fun `an entry remembers the page the contact began on`() {
        val builder = RasterEditBuilder("p7", page, page)
        builder.touch(0, 0, 10, 10) { IntArray(it.width * it.height) }
        assertEquals("p7", builder.build()!!.pageId)
    }
}
