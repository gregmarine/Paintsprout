package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind a sketchbook's cover, checked where there is no panel to look at.
 *
 * Only [CoverSnapshot.shrink] can be tested here — the bake either side of it is `Bitmap` and
 * `Canvas`, which do nothing on the JVM. That is the right half to have covered anyway: a wrong
 * bake is a cover that is obviously wrong the moment anyone looks at the shelf, whereas a wrong
 * average is a cover that is slightly too dark down one edge, or a hairline that has quietly gone
 * missing, and nobody looks at a thumbnail closely enough to catch either.
 */
class CoverSnapshotTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private val white = argb(255, 255, 255, 255)
    private val black = argb(255, 0, 0, 0)

    @Test
    fun `a page of one tone comes back as that tone`() {
        val src = IntArray(9 * 9) { white }
        val out = CoverSnapshot.shrink(src, 9, 9, 3)
        assertEquals(9, out.size)
        for (p in out) {
            assertEquals(
                "a blank page whose cover is not blank is an averaging bug that will read as dirt",
                white,
                p,
            )
        }
    }

    @Test
    fun `one black pixel in a block darkens the whole block, it does not vanish`() {
        // This is the hairline case in miniature. A sampling shrink would step over this pixel
        // entirely and hand back paper white; the box average has to see it.
        val src = IntArray(9) { white }
        src[4] = black
        val out = CoverSnapshot.shrink(src, 3, 3, 3)
        assertEquals(1, out.size)
        val expected = 255 * 8 / 9
        assertEquals(226, expected)
        assertEquals(argb(255, expected, expected, expected), out[0])
    }

    @Test
    fun `a page that is not a multiple of the factor keeps its last row and column`() {
        // 7 by 3 is 3 blocks each way: two full ones and an edge block one pixel wide.
        val src = IntArray(7 * 7) { white }
        val out = CoverSnapshot.shrink(src, 7, 7, 3)
        assertEquals(9, out.size)
        for (p in out) {
            assertEquals(
                "an edge block averaged over nine pixels when only one is there counts the missing " +
                    "eight as black, and every cover gets a shadow down two sides",
                white,
                p,
            )
        }
    }

    @Test
    fun `an edge block averages over what is actually there`() {
        // A 4-wide row: the second block holds one pixel, and it is black. It must come back black,
        // not black diluted by two pixels that were never in the page.
        val src = intArrayOf(white, white, white, black)
        val out = CoverSnapshot.shrink(src, 4, 1, 3)
        assertEquals(2, out.size)
        assertEquals(white, out[0])
        assertEquals(black, out[1])
    }

    @Test
    fun `alpha is averaged with the rest of the channels`() {
        val src = IntArray(9) { argb(255, 0, 0, 0) }
        src[0] = argb(0, 0, 0, 0)
        val out = CoverSnapshot.shrink(src, 3, 3, 3)
        assertEquals(
            "a channel carried through untouched is the one that goes wrong in the format nobody " +
                "tested with",
            255 * 8 / 9,
            (out[0] ushr 24) and 0xFF,
        )
    }

    @Test
    fun `a factor of one is the page itself`() {
        val src = intArrayOf(white, black, white, black)
        val out = CoverSnapshot.shrink(src, 2, 2, 1)
        assertEquals(src.toList(), out.toList())
    }

    @Test
    fun `the shrink factor is the one the panel's page was chosen against`() {
        // 1860 x 2480 by three is 620 x 826-and-two-thirds, so the cover is 620 x 827 with a
        // two-pixel-tall block along the bottom. Checked on a page of the same shape scaled down,
        // rather than on eighteen megabytes of test fixture.
        assertEquals(3, CoverSnapshot.SHRINK)
        val out = CoverSnapshot.shrink(IntArray(186 * 248) { white }, 186, 248, CoverSnapshot.SHRINK)
        assertEquals(62 * 83, out.size)
        for (p in out) assertEquals(white, p)
    }
}
