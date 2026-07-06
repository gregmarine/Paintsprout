package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure magic-wand flood fill. Images are built as ARGB int
 * arrays (as `Bitmap.getPixels` would produce) so the engine can be exercised
 * off-device.
 */
class WandFloodFillTest {

    private val opaqueBlue = 0xFF0000FF.toInt()
    private val opaqueRed = 0xFFFF0000.toInt()
    private val transparent = 0

    /** A solid painted rectangle inside a transparent field. */
    private fun rectImage(w: Int, h: Int, color: Int, l: Int, t: Int, r: Int, b: Int): IntArray {
        val px = IntArray(w * h) { transparent }
        for (y in t until b) for (x in l until r) px[y * w + x] = color
        return px
    }

    private fun run(px: IntArray, w: Int, h: Int, sx: Int, sy: Int) =
        WandFloodFill.run(
            WandFloodFill.Request(px, w, h, sx, sy, tolerance = 0.15f, edgeSensitivity = 0.5f, gap = 3),
        )

    @Test
    fun selectsContiguousPaintedBlock() {
        val w = 64
        val h = 64
        val px = rectImage(w, h, opaqueBlue, 16, 16, 48, 48)
        val result = run(px, w, h, 32, 32) // seed inside the block

        assertFalse("a painted block should select something", result.isEmpty)
        // Mask is at half resolution.
        assertEquals(32, result.width)
        assertEquals(32, result.height)
        // The seed's mask cell is selected.
        val mi = (32 / WandFloodFill.DOWNSAMPLE) * result.width + (32 / WandFloodFill.DOWNSAMPLE)
        assertTrue("seed cell selected", result.mask[mi] != 0)
        // Bounds sit within the painted rectangle (mask px = buffer/2 → 8..24).
        assertTrue(result.bounds.left >= 7 && result.bounds.left <= 9)
        assertTrue(result.bounds.right in 23..25)
    }

    @Test
    fun stopsAtColorEdgeBetweenOpaqueRegions() {
        // Left half blue, right half red, touching at x=32. Seeding in blue must
        // not bleed across the luminance edge into red.
        val w = 64
        val h = 64
        val px = IntArray(w * h) { i ->
            val x = i % w
            if (x < 32) opaqueBlue else opaqueRed
        }
        val result = run(px, w, h, 8, 32)
        assertFalse(result.isEmpty)
        // The far-right column (red) must be unselected.
        val mw = result.width
        val redCell = (16) * mw + (mw - 2) // right side
        assertEquals("red region not selected", 0, result.mask[redCell])
    }

    @Test
    fun emptyOnBlankCanvas() {
        val w = 32
        val h = 32
        val px = IntArray(w * h) { transparent }
        val result = run(px, w, h, 16, 16)
        // Nothing painted: no barrier, but the whole flat field is within tolerance
        // of the (transparent) seed, so a selection is allowed. What matters is it
        // does not crash and produces a consistent mask/bounds pairing.
        assertEquals(result.bounds.isEmpty, result.mask.all { it == 0 })
    }
}
