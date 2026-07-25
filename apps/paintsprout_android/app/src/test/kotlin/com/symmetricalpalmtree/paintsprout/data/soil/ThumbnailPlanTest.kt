package com.symmetricalpalmtree.paintsprout.data.soil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The invariant here is the one the first page strip broke: a page went from
 * full size to thumbnail in a single subsampled step, and pen lines one pixel
 * wide vanished. Every one of these tests is about *how far* a step may reduce,
 * not about what the picture looks like.
 */
class ThumbnailPlanTest {

    /** A seven-inch page at the device's buffer resolution. */
    private val pageW = 1460
    private val pageH = 1145

    @Test
    fun `no step discards more than three quarters of the pixels`() {
        var w = pageW
        var h = pageH
        for (step in ThumbnailPlan.steps(pageW, pageH, 240)) {
            // `>= w / 2` rather than `* 2 >= w`: halving an odd edge floors, and
            // a lost half-pixel is not a lost row of paint.
            assertTrue("width fell by more than half: $w -> ${step.width}", step.width >= w / 2)
            assertTrue("height fell by more than half: $h -> ${step.height}", step.height >= h / 2)
            w = step.width
            h = step.height
        }
    }

    @Test
    fun `the last step lands on the size asked for`() {
        val last = ThumbnailPlan.steps(pageW, pageH, 240).last()
        assertEquals(240, maxOf(last.width, last.height))
    }

    @Test
    fun `aspect ratio survives the walk down`() {
        val last = ThumbnailPlan.steps(pageW, pageH, 240).last()
        val source = pageW.toFloat() / pageH
        val thumb = last.width.toFloat() / last.height
        assertTrue("aspect drifted: $source -> $thumb", abs(source - thumb) < 0.02f)
    }

    @Test
    fun `every step actually shrinks`() {
        var w = pageW
        var h = pageH
        for (step in ThumbnailPlan.steps(pageW, pageH, 240)) {
            assertTrue(step.width < w || step.height < h)
            w = step.width
            h = step.height
        }
    }

    /** A page already small enough is left alone rather than scaled to itself. */
    @Test
    fun `a source that already fits needs no steps`() {
        assertEquals(emptyList<ThumbnailPlan.Size>(), ThumbnailPlan.steps(200, 120, 240))
        assertEquals(emptyList<ThumbnailPlan.Size>(), ThumbnailPlan.steps(240, 240, 240))
    }

    /** Between the target and twice it, one filtered step is the whole plan. */
    @Test
    fun `a source within one step is one step`() {
        assertEquals(1, ThumbnailPlan.steps(480, 300, 240).size)
    }

    @Test
    fun `degenerate sizes plan nothing rather than looping`() {
        assertEquals(emptyList<ThumbnailPlan.Size>(), ThumbnailPlan.steps(0, 100, 240))
        assertEquals(emptyList<ThumbnailPlan.Size>(), ThumbnailPlan.steps(100, 0, 240))
        assertEquals(emptyList<ThumbnailPlan.Size>(), ThumbnailPlan.steps(100, 100, 0))
    }

    /** A sliver of a page must never plan a zero-pixel bitmap. */
    @Test
    fun `an extreme aspect never reaches zero`() {
        for (step in ThumbnailPlan.steps(4000, 3, 240)) {
            assertTrue(step.width >= 1)
            assertTrue(step.height >= 1)
        }
    }

    /** The walk terminates for anything a device could hand it. */
    @Test
    fun `the plan is short and finite for very large pages`() {
        val steps = ThumbnailPlan.steps(20000, 16000, 240)
        assertTrue(steps.size < 12)
        assertEquals(240, maxOf(steps.last().width, steps.last().height))
    }
}
