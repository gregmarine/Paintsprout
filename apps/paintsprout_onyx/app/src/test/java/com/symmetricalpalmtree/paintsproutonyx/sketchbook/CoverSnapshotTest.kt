package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind a sketchbook's cover, checked where there is no panel to look at.
 *
 * Only the arithmetic can be tested here — [CoverSnapshot.shrink], and from R3 [CoverSnapshot.overWhite]
 * and [CoverSnapshot.isBlank]. The bake either side of it is `Bitmap` and `Canvas`, which do
 * nothing on the JVM. That is the right half to have covered anyway: a wrong bake is a cover that
 * is obviously wrong the moment anyone looks at the shelf, whereas a wrong average is a cover that
 * is slightly too dark down one edge, or a hairline that has quietly gone missing, and nobody looks
 * at a thumbnail closely enough to catch either.
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

    // ── A raster page's cover ────────────────────────────────────────────────

    private val clear = argb(0, 0, 0, 0)

    @Test
    fun `paper shows through where nothing was drawn`() {
        // The page image is a layer over the paper, not the paper: unmarked pixels are transparent
        // and the eraser clears back to transparent. Encoded as it stands, a drawing would come
        // back as smoke on whatever the card happened to draw behind it.
        val pixels = intArrayOf(clear)
        CoverSnapshot.overWhite(pixels)
        assertEquals(white, pixels[0])
    }

    @Test
    fun `graphite that covers the paper comes through as itself`() {
        val graphite = argb(255, 0x50, 0x50, 0x50)
        val pixels = intArrayOf(graphite, black)
        CoverSnapshot.overWhite(pixels)
        assertEquals(graphite, pixels[0])
        assertEquals(black, pixels[1])
    }

    @Test
    fun `a half-covered pixel is the blend, and it is straight alpha`() {
        // getPixels hands back straight ARGB whatever the bitmap's own config is. Treating it as
        // premultiplied would make every cover uniformly paler, which reads as the pencil being
        // light rather than as a bug — the kind of wrong that never gets found.
        val pixels = intArrayOf(argb(128, 0, 0, 0))
        CoverSnapshot.overWhite(pixels)
        val expected = (0 * 128 + 255 * 127) / 255
        assertEquals(127, expected)
        assertEquals(argb(255, expected, expected, expected), pixels[0])
    }

    @Test
    fun `a page with nothing on it is blank, and one with a single fleck is not`() {
        assertTrue(CoverSnapshot.isBlank(IntArray(16) { clear }))
        val one = IntArray(16) { clear }
        one[7] = argb(1, 0, 0, 0)
        assertFalse(
            "a pixel the hand put there is a drawing, however faint — a blank card for a page with " +
                "marks on it is the shelf lying about the book",
            CoverSnapshot.isBlank(one),
        )
    }

    @Test
    fun `a raster page nobody has drawn on has no cover, and never reaches the encoder`() {
        // Blank is answered before anything Android is touched, which is why this half can be
        // proved here at all. A page erased back to nothing takes the same road, deliberately:
        // to the artist looking at the shelf there is no difference between the two.
        assertSame(
            CoverSnapshot.Cover.Blank,
            CoverSnapshot.render(IntArray(4 * 4) { clear }, 4, 4),
        )
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
