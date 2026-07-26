package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasSizeTest {

    // The Movink 14 Pro: 2880 × 1800 at its measured 242.69 PPI.
    private val movink14W = 2880f / 242.69312f // 11.866 in
    private val movink14H = 1800f / 242.69312f // 7.4166 in

    private fun labels(w: Float, h: Float) = CanvasSize.offered(w, h).map { it.label }

    @Test
    fun `nothing offered is larger than the screen`() {
        for ((w, h) in listOf(movink14W to movink14H, 12f to 9f, 6f to 4.2f, 3f to 2.5f)) {
            for (size in CanvasSize.offered(w, h)) {
                assertTrue("${size.label} is wider than $w", size.wIn <= w)
                assertTrue("${size.label} is taller than $h", size.hIn <= h)
            }
        }
    }

    /**
     * The bug this whole thing exists to prevent: the largest sheet is computed by
     * rounding DOWN, because the fit test is a raw float comparison. 7.4166 rounded
     * up to 7.42 is 1800.8 px on an 1800 px panel, and would vanish from the list.
     */
    @Test
    fun `the largest sizes actually fit rather than missing by a rounding`() {
        val maxSquare = CanvasSize.largestFitting(1f, movink14W, movink14H)
        val max45 = CanvasSize.largestFitting(1.25f, movink14W, movink14H)
        assertNotNull(maxSquare)
        assertNotNull(max45)
        assertEquals(7.41f, maxSquare!!.hIn, 0.001f)
        assertEquals(7.41f, maxSquare.wIn, 0.001f)
        assertEquals(7.41f, max45!!.hIn, 0.001f)
        assertEquals(9.26f, max45.wIn, 0.001f)
        assertTrue(max45.hIn <= movink14H)
        assertTrue(max45.wIn <= movink14W)
    }

    @Test
    fun `the Movink 14 Pro is offered every rung plus all three maxima, smallest first`() {
        assertEquals(
            listOf(
                "4 × 4 in",
                "4 × 6 in",
                "5 × 5 in",
                "5 × 7 in",
                "6 × 6 in",
                "7 × 7 in",
                "7.41 × 7.41 in",
                "7.41 × 9.26 in",
                "7.41 × 9.88 in",
            ),
            labels(movink14W, movink14H),
        )
    }

    /**
     * The Reflection Frame's shape. Its pixels are not square — 1200×1600 is
     * 1.3333 while its 8 × 10.5 in glass is 1.3125 — so the sheet matches the
     * pixel ratio and the frame does the stretching.
     */
    @Test
    fun `the 4 by 3 rung is the frame's pixel ratio, not its glass`() {
        val frame = CanvasSize.largestFitting(4f / 3f, movink14W, movink14H)
        assertNotNull(frame)
        assertEquals(7.41f, frame!!.hIn, 0.001f)
        assertEquals(9.88f, frame.wIn, 0.001f)
        assertEquals(4f / 3f, frame.wIn / frame.hIn, 0.001f)
        assertTrue(frame.wIn <= movink14W)
        assertTrue(frame.hIn <= movink14H)
    }

    /**
     * Height is what binds. Note that a rung's *short* side is its height: 5×7 is
     * seven inches wide and five tall, so it survives a panel far shallower than
     * seven inches.
     */
    @Test
    fun `a smaller panel simply gets fewer rungs`() {
        val roomy = labels(12f, 6.2f)
        assertTrue(roomy.contains("4 × 6 in"))
        assertTrue(roomy.contains("5 × 7 in")) // 7 wide, 5 tall — fits
        assertTrue(roomy.contains("6 × 6 in"))
        assertTrue(!roomy.contains("7 × 7 in")) // 7 tall — does not
        // All three maxima, at this panel's own short side.
        assertTrue(roomy.contains("6.2 × 6.2 in"))
        assertTrue(roomy.contains("6.2 × 7.75 in"))
        assertEquals("6.2 × 8.26 in", roomy.last())

        val shallow = labels(12f, 4.5f)
        assertTrue(shallow.contains("4 × 4 in"))
        assertTrue(shallow.contains("4 × 6 in"))
        assertTrue(!shallow.contains("5 × 5 in"))
        assertTrue(!shallow.contains("5 × 7 in"))
    }

    /** A maximum landing exactly on a rung loses to the rung's rounder label. */
    @Test
    fun `an exact fit is not offered twice`() {
        val offered = CanvasSize.offered(12f, 7f)
        assertEquals(1, offered.count { it.wIn == 7f && it.hIn == 7f })
        assertEquals("7 × 7 in", offered.first { it.wIn == 7f && it.hIn == 7f }.label)
    }

    /** Width binds instead of height on a panel that is wide but shallow. */
    @Test
    fun `the short side is not always the limit`() {
        val max45 = CanvasSize.largestFitting(1.25f, 5f, 8f)
        assertNotNull(max45)
        assertEquals(5f, max45!!.wIn, 0.001f)
        assertEquals(4f, max45.hIn, 0.001f)
    }

    @Test
    fun `a screen too small for any sheet offers nothing`() {
        assertTrue(CanvasSize.offered(0.5f, 0.5f).isEmpty())
        assertNull(CanvasSize.largestFitting(1f, 0.5f, 0.5f))
        assertNull(CanvasSize.largestFitting(1f, 0f, 0f))
    }

    /**
     * A slider dragged to the end sits at the panel's exact width. Rounding that
     * to the nearest tenth would round UP and produce a sheet wider than the
     * screen, which then gets silently clamped while keeping its oversized name.
     */
    @Test
    fun `a custom size at the very limit does not round past the screen`() {
        val custom = CanvasSize.custom(movink14W, movink14H)
        assertTrue("${custom.wIn} exceeds $movink14W", custom.wIn <= movink14W)
        assertTrue("${custom.hIn} exceeds $movink14H", custom.hIn <= movink14H)
        assertEquals(11.8f, custom.wIn, 0.001f)
        assertEquals(7.4f, custom.hIn, 0.001f)
    }

    /**
     * A typed field has no end stop, so anything at all can be entered. Whatever
     * is, what comes back has to fit.
     */
    @Test
    fun `a typed size is held to the screen however large it is typed`() {
        val huge = CanvasSize.custom(500f, 500f, movink14W, movink14H)
        assertEquals(11.8f, huge.wIn, 0.001f)
        assertEquals(7.4f, huge.hIn, 0.001f)

        val negative = CanvasSize.custom(-9f, 0f, movink14W, movink14H)
        assertTrue(negative.wIn >= 1f)
        assertTrue(negative.hIn >= 1f)

        // In range, it is left alone but for the truncation.
        val fine = CanvasSize.custom(9.88f, 7.41f, movink14W, movink14H)
        assertEquals(9.8f, fine.wIn, 0.001f)
        assertEquals(7.4f, fine.hIn, 0.001f)
    }

    @Test
    fun `a custom size is labelled short by long whichever way the fields sat`() {
        assertEquals("3 × 7 in", CanvasSize.custom(3f, 7f).label)
        assertEquals("3 × 7 in", CanvasSize.custom(7f, 3f).label)
    }

    @Test
    fun `labels read short by long, with no trailing zeros`() {
        assertEquals("2.5 × 2.5 in", CanvasSize.largestFitting(1f, 9f, 2.5f)?.label)
        assertEquals("4 × 5 in", CanvasSize.largestFitting(1.25f, 9f, 4f)?.label)
    }
}
