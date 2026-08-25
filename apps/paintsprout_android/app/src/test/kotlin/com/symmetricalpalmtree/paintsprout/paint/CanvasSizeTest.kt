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

    private fun labels(w: Float, h: Float) = CanvasSize.choices(w, h).map { it.label }

    @Test
    fun `no print offered is larger than the screen`() {
        for ((w, h) in listOf(movink14W to movink14H, 12f to 9f, 6f to 4.2f, 3f to 2.5f)) {
            for (size in CanvasSize.choices(w, h).filterIsInstance<CanvasSize.Print>()) {
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
    fun `the largest size actually fits rather than missing by a rounding`() {
        val max = CanvasSize.largestFitting(1.5f, movink14W, movink14H)
        assertNotNull(max)
        assertEquals(7.41f, max!!.hIn, 0.001f)
        assertEquals(11.11f, max.wIn, 0.001f)
        assertTrue(max.hIn <= movink14H)
        assertTrue(max.wIn <= movink14W)
    }

    @Test
    fun `the Movink 14 Pro is offered every rung, both frames and the 2 by 3 maximum`() {
        assertEquals(
            listOf(
                "4 × 4 in",
                "4 × 6 in",
                "5 × 7 in",
                "Spectra 6 7.3 in (480 × 800)",
                "Spectra 6 13.3 in (1200 × 1600)",
                "7.41 × 11.11 in",
                "Full screen",
            ),
            labels(movink14W, movink14H),
        )
    }

    /** Custom is the picker's own affair; the list itself never contains one. */
    @Test
    fun `the list runs prints, frames, maximum, panel — in that order`() {
        val choices = CanvasSize.choices(movink14W, movink14H)
        val kinds = choices.map {
            when (it) {
                is CanvasSize.Print -> "print"
                is CanvasSize.Frame -> "frame"
                CanvasSize.FullScreen -> "panel"
            }
        }
        assertEquals(listOf("print", "print", "print", "frame", "frame", "print", "panel"), kinds)
        assertEquals(CanvasSize.FullScreen, choices.last())
    }

    /**
     * A frame is not withheld from a small panel the way a print is. It never
     * claims to be true size on the glass, so shrinking it to fit is a view of the
     * frame rather than a lie about it — and the buffer it paints into is the
     * frame's own grid either way.
     */
    @Test
    fun `both frames are offered however small the panel`() {
        for ((w, h) in listOf(movink14W to movink14H, 9.58f to 6.27f, 4f to 3f, 2f to 1.5f)) {
            assertEquals(CanvasSize.FRAMES, CanvasSize.choices(w, h).filterIsInstance<CanvasSize.Frame>())
        }
    }

    /**
     * 480 × 800 is 3:5 and 1200 × 1600 is 3:4 — neither is the 2:3 of the print
     * rung, which is a paper ratio and has nothing to do with the frames.
     */
    @Test
    fun `each frame carries its own grid, shape and resolution`() {
        val small = CanvasSize.FRAMES[0]
        assertEquals(800, small.pxW)
        assertEquals(480, small.pxH)
        assertEquals(6.3f, small.longIn, 0.001f)
        assertEquals(3.78f, small.shortIn, 0.001f) // 3:5
        assertEquals(127f, small.dpi, 0.5f)

        val big = CanvasSize.FRAMES[1]
        assertEquals(1600, big.pxW)
        assertEquals(1200, big.pxH)
        assertEquals(10.5f, big.longIn, 0.001f)
        assertEquals(7.875f, big.shortIn, 0.001f) // 3:4
        assertEquals(152.4f, big.dpi, 0.5f)
    }

    /**
     * The frames lie down like every other sheet. Their panels are *specified*
     * portrait, which is a fact about the datasheet — a sheet standing up beside
     * six that lie down reads as a bug, and did.
     */
    @Test
    fun `every offered size is landscape, frames included`() {
        for (size in CanvasSize.choices(movink14W, movink14H)) {
            when (size) {
                is CanvasSize.Print -> assertTrue(size.label, size.wIn >= size.hIn)
                is CanvasSize.Frame -> assertTrue(size.label, size.pxW > size.pxH)
                CanvasSize.FullScreen -> {}
            }
        }
    }

    /** Labels read short × long, so they are unchanged by the sheet lying down. */
    @Test
    fun `a frame is labelled short by long like a print is`() {
        assertEquals("Spectra 6 7.3 in (480 × 800)", CanvasSize.FRAMES[0].label)
        assertEquals("Spectra 6 13.3 in (1200 × 1600)", CanvasSize.FRAMES[1].label)
        assertEquals("4 × 6 in", CanvasSize.PRESETS.first { it.wIn == 6f }.label)
    }

    /**
     * The 7.3 is 6.3 × 3.78 in lying down and fits both tablets outright, so it is
     * drawn at true size on each. The 13.3 is 10.5 × 7.88 and fits neither — its
     * short side alone exceeds both panels' height — so it fills the panel's
     * height and takes its width from that.
     */
    @Test
    fun `a frame is drawn true size where it fits and shrunk to the panel where it does not`() {
        val small = CanvasSize.FRAMES[0]
        val big = CanvasSize.FRAMES[1]

        // Movink 14 Pro: 2880 × 1800 at 242.69 PPI.
        val (sw14, sh14) = small.displayPx(242.69312f, 2880, 1800)
        assertEquals(1529, sw14) // 6.3 in across — true size, room to spare
        assertEquals(917, sh14)
        val (bw14, bh14) = big.displayPx(242.69312f, 2880, 1800)
        assertEquals(1800, bh14) // 7.88 in would be 1911 — pinned to the panel
        assertEquals(2400, bw14) // and 4:3 of that, well inside the 2880

        // MovinkPad 11: 2200 × 1440 at ~229.6 PPI. Lying down, the 7.3 now clears
        // the panel easily — standing up it wanted 1447 px of 1440 and lost seven.
        val (sw11, sh11) = small.displayPx(229.6f, 2200, 1440)
        assertEquals(1446, sw11)
        assertEquals(868, sh11)
        val (bw11, bh11) = big.displayPx(229.6f, 2200, 1440)
        assertEquals(1440, bh11)
        assertEquals(1920, bw11)
    }

    /**
     * The scale between buffer and screen is one number, which only holds if the
     * sheet is drawn in the buffer's own proportion. A frame off by a pixel either
     * way would shear every stroke a little more the further it got from the top
     * left, which is exactly the kind of fault nobody can point at.
     */
    @Test
    fun `a frame on screen keeps its pixel aspect`() {
        for (frame in CanvasSize.FRAMES) {
            for ((ppi, w, h) in listOf(
                Triple(242.69312f, 2880, 1800),
                Triple(229.6f, 2200, 1440),
                Triple(160f, 800, 600),
            )) {
                val (dw, dh) = frame.displayPx(ppi, w, h)
                assertEquals(
                    "${frame.label} at $ppi on $w×$h",
                    frame.pxW.toFloat() / frame.pxH,
                    dw.toFloat() / dh,
                    0.002f,
                )
                assertTrue(dw <= w && dh <= h)
            }
        }
    }

    /**
     * A frame from a later build keeps its pixels exactly — that is what the
     * artwork occupies — and only guesses at how large it hangs.
     */
    @Test
    fun `an unknown frame keeps its grid and guesses only its size`() {
        assertEquals(CanvasSize.FRAMES[1], CanvasSize.frameOf(1600, 1200))

        // The order is not part of the identity: a book written while the frames
        // stood portrait is the same book, and must not come back as a stranger
        // just because the app changed its mind about which way up they hang.
        assertEquals(CanvasSize.FRAMES[1], CanvasSize.frameOf(1200, 1600))
        assertEquals(CanvasSize.FRAMES[0], CanvasSize.frameOf(480, 800))
        assertEquals(CanvasSize.FRAMES[0], CanvasSize.frameOf(800, 480))

        val future = CanvasSize.frameOf(1600, 2400)
        assertEquals(1600, future.pxW)
        assertEquals(2400, future.pxH)
        assertEquals("1600 × 2400 px", future.label)
        assertTrue(future.longIn > 0f)
    }

    /**
     * Height is what binds. Note that a rung's *short* side is its height: 5×7 is
     * seven inches wide and five tall, so it survives a panel far shallower than
     * seven inches.
     */
    @Test
    fun `a smaller panel simply gets fewer rungs`() {
        val roomy = labels(12f, 6.2f)
        assertTrue(roomy.contains("4 × 4 in"))
        assertTrue(roomy.contains("4 × 6 in"))
        assertTrue(roomy.contains("5 × 7 in")) // 7 wide, 5 tall — fits
        assertTrue(roomy.contains("6.2 × 9.3 in")) // the 2:3 maximum

        val shallow = labels(12f, 4.5f)
        assertTrue(shallow.contains("4 × 4 in"))
        assertTrue(shallow.contains("4 × 6 in"))
        assertTrue(!shallow.contains("5 × 7 in"))
    }

    /** A maximum landing exactly on a rung loses to the rung's rounder label. */
    @Test
    fun `an exact fit is not offered twice`() {
        val prints = CanvasSize.choices(6f, 4f).filterIsInstance<CanvasSize.Print>()
        assertEquals(1, prints.count { it.wIn == 6f && it.hIn == 4f })
        assertEquals("4 × 6 in", prints.first { it.wIn == 6f && it.hIn == 4f }.label)
    }

    /** Width binds instead of height on a panel that is wide but shallow. */
    @Test
    fun `the short side is not always the limit`() {
        val max = CanvasSize.largestFitting(1.5f, 6f, 8f)
        assertNotNull(max)
        assertEquals(6f, max!!.wIn, 0.001f)
        assertEquals(4f, max.hIn, 0.001f)
    }

    /** A screen too small for any print still has the frames and the panel. */
    @Test
    fun `a screen too small for any sheet offers no prints`() {
        assertTrue(CanvasSize.choices(0.5f, 0.5f).filterIsInstance<CanvasSize.Print>().isEmpty())
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

    /** A reopened print wears its preset's label rather than a raw float pair. */
    @Test
    fun `a stored print is recognised as its preset`() {
        assertEquals("5 × 7 in", CanvasSize.printOf(7f, 5f).label)
        assertEquals("7.41 × 11.11 in", CanvasSize.printOf(11.11f, 7.41f).label)
    }

    @Test
    fun `labels read short by long, with no trailing zeros`() {
        assertEquals("2.5 × 2.5 in", CanvasSize.largestFitting(1f, 9f, 2.5f)?.label)
        assertEquals("4 × 5 in", CanvasSize.largestFitting(1.25f, 9f, 4f)?.label)
    }
}
