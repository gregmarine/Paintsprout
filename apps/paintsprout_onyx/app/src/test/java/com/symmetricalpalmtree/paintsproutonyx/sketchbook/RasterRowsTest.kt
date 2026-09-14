package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A page's picture put down and picked back up, and the guard that stands between a damaged row and
 * the process.
 *
 * Like `MarkRowsTest`, this is the part of a page that can be *proved* without a panel in the room:
 * whether the drawing that goes into the file is the drawing that comes out, and whether an image
 * that does not belong to this page is refused before anything tries to open it. How graphite reads
 * on the panel and how a rub feels are the hand's; these are arithmetic.
 *
 * **The PNG headers here are built by hand, byte by byte, and that is deliberate.** The guard exists
 * to answer *before* any decoder has been handed anything, so a test that asked a decoder to make its
 * fixtures would be testing the guard against exactly the input it is meant to be independent of —
 * and would need a device to run at all.
 */
class RasterRowsTest {

    private val pageId = "page-1"

    /** The eight bytes every PNG starts with, then an IHDR chunk claiming [width] × [height]. */
    private fun pngHeader(width: Int, height: Int, chunkType: String = "IHDR"): ByteArray {
        val out = ByteArray(33)
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        signature.copyInto(out)
        // The IHDR chunk: a big-endian length of 13, the four-character type, then the width and
        // height as big-endian ints. Everything after that (bit depth, colour type, the CRC) is not
        // read by the guard and is left as zeroes.
        putInt(out, 8, 13)
        for (i in 0 until 4) out[12 + i] = chunkType[i].code.toByte()
        putInt(out, 16, width)
        putInt(out, 20, height)
        return out
    }

    private fun putInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    @Test
    fun `a picture comes back the picture that went down`() {
        val png = pngHeader(1860, 2480)
        val row = RasterRows.toRow(pageId, png, id = "image-1", now = 1_000L)

        assertEquals("image-1", row.id)
        assertEquals(pageId, row.parentId)
        assertEquals(SoilSchema.TYPE_RASTER, row.type)
        assertEquals(SoilSchema.RASTER_ORDER, row.order)
        assertEquals(1_000L, row.createdAt)
        assertEquals(1_000L, row.updatedAt)
        assertNull(row.deletedAt)
        assertArrayEqualsBytes(png, RasterRows.pngBytes(row))
    }

    @Test
    fun `a raster row sits out of the marks' stacking space`() {
        // Marks count up from zero, so anything at or above zero would one day be sorted against
        // them. The family's own raster cache sits at -1 for exactly this reason.
        assertTrue(SoilSchema.RASTER_ORDER < 0)
    }

    @Test
    fun `a row of some other type is not a picture`() {
        val mark = SoilObjectEntity(
            id = "mark-1",
            parentId = pageId,
            type = SoilSchema.TYPE_MARK,
            createdAt = 1L,
            updatedAt = 1L,
            blob = pngHeader(10, 10),
        )
        assertNull(RasterRows.pngBytes(mark))
    }

    @Test
    fun `a raster row carrying nothing is not a picture`() {
        val empty = RasterRows.toRow(pageId, ByteArray(0), id = "image-1", now = 1L)
        assertNull(RasterRows.pngBytes(empty))
    }

    @Test
    fun `a header says how big the picture is`() {
        assertEquals(1860 to 2480, RasterRows.pngSize(pngHeader(1860, 2480)))
    }

    @Test
    fun `anything that is not a PNG has no size`() {
        // Right length, wrong signature: a WEBP cover, a mark's geometry, a stretch of noise.
        val notPng = ByteArray(33) { 0x42 }
        assertNull(RasterRows.pngSize(notPng))
        // A PNG signature followed by some other chunk first. The format requires IHDR there, so
        // this is either damage or not a PNG, and "somewhere I did not look" is not an answer.
        assertNull(RasterRows.pngSize(pngHeader(100, 100, chunkType = "sRGB")))
        // A zero dimension is a picture of nothing, and no page is that size.
        assertNull(RasterRows.pngSize(pngHeader(0, 100)))
    }

    @Test
    fun `a header cut short has no size`() {
        val truncated = pngHeader(1860, 2480).copyOf(20)
        assertNull(RasterRows.pngSize(truncated))
        assertNull(RasterRows.pngSize(ByteArray(0)))
    }

    @Test
    fun `only the picture of this page fits this page`() {
        assertTrue(RasterRows.fitsPage(pngHeader(1860, 2480), 1860, 2480))
        // Not "no larger than" and not "close enough": a page image registers with the page
        // one-to-one, so any other size is some other page.
        assertFalse(RasterRows.fitsPage(pngHeader(1860, 2479), 1860, 2480))
        assertFalse(RasterRows.fitsPage(pngHeader(930, 1240), 1860, 2480))
        assertFalse(RasterRows.fitsPage(pngHeader(3720, 4960), 1860, 2480))
    }

    @Test
    fun `a picture that is not a picture never reaches the decoder`() {
        assertFalse(RasterRows.fitsPage(ByteArray(33) { 0x42 }, 1860, 2480))
        assertFalse(RasterRows.fitsPage(pngHeader(1860, 2480).copyOf(20), 1860, 2480))
    }

    @Test
    fun `a page with no recorded size takes no picture at all`() {
        // Zero is g-paper's "fit the view", which means there is nothing to check an image against —
        // and an unbounded decode is the whole of what this guard exists to prevent.
        assertFalse(RasterRows.fitsPage(pngHeader(1860, 2480), 0, 2480))
        assertFalse(RasterRows.fitsPage(pngHeader(1860, 2480), 1860, 0))
    }

    @Test
    fun `the mode flag says what it was stamped with`() {
        assertTrue(RasterRows.isRaster(RasterRows.flagsFor(true)))
        assertFalse(RasterRows.isRaster(RasterRows.flagsFor(false)))
        assertEquals(0, RasterRows.flagsFor(false))
        assertEquals(SoilSchema.FLAG_RASTER, RasterRows.flagsFor(true))
    }

    @Test
    fun `a book with no flags at all is a stroke book`() {
        // Every sketchbook made before the bit existed. "Nothing was written here" and "strokes" are
        // the same answer, and they had better stay the same answer.
        assertFalse(RasterRows.isRaster(null))
        assertFalse(RasterRows.isRaster(0))
    }

    @Test
    fun `the mode is one bit and leaves the rest of the flags alone`() {
        // Bit 0, so a later arc can spend the other thirty-one on something else without this one
        // starting to read as raster.
        assertTrue(RasterRows.isRaster(SoilSchema.FLAG_RASTER or 0b1010))
        assertFalse(RasterRows.isRaster(0b1010))
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray?) {
        assertTrue("the blob came back as nothing", actual != null)
        assertTrue("the blob came back changed", expected.contentEquals(actual))
    }
}
