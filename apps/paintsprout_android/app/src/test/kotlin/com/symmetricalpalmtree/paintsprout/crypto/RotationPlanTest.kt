package com.symmetricalpalmtree.paintsprout.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rotation touches every file in the library one at a time and **will** be
 * interrupted. These are the rules that decide what a cold start does with a
 * library that is half on one key and half on another.
 */
class RotationPlanTest {

    @Test
    fun `a file on the old key is converted`() {
        assertEquals(
            RotationPlan.Verdict.CONVERT,
            RotationPlan.verdictFor(opensWithOld = true, opensWithNew = false),
        )
    }

    /**
     * The resumption case: a file the interrupted run already converted. Trying
     * again would fail — the old key no longer opens it — so it has to be
     * recognised rather than retried.
     */
    @Test
    fun `a file already on the new key is skipped`() {
        assertEquals(
            RotationPlan.Verdict.SKIP,
            RotationPlan.verdictFor(opensWithOld = false, opensWithNew = true),
        )
    }

    /** Keyed to something else entirely: set aside, and the rotation carries on. */
    @Test
    fun `a file on neither key is quarantined`() {
        assertEquals(
            RotationPlan.Verdict.QUARANTINE,
            RotationPlan.verdictFor(opensWithOld = false, opensWithNew = false),
        )
    }

    /**
     * Both keys opening it can only mean the two passphrases are the same, which
     * `start` refuses — but if it ever happened, converting is the safe reading:
     * the file ends up on the new key either way.
     */
    @Test
    fun `a file both keys open is converted rather than skipped`() {
        assertEquals(
            RotationPlan.Verdict.CONVERT,
            RotationPlan.verdictFor(opensWithOld = true, opensWithNew = true),
        )
    }

    // --- Progress -----------------------------------------------------------

    @Test
    fun `finishing a file removes it from pending`() {
        val start = RotationPlan.Progress(listOf("a", "b", "c"), emptyList())
        val next = start.done("b")
        assertEquals(listOf("a", "c"), next.pending)
        assertEquals(emptyList<String>(), next.quarantined)
        assertFalse(next.isFinished)
    }

    @Test
    fun `quarantining moves a file out of pending and into the list`() {
        val next = RotationPlan.Progress(listOf("a", "b"), emptyList()).quarantine("a")
        assertEquals(listOf("b"), next.pending)
        assertEquals(listOf("a"), next.quarantined)
    }

    @Test
    fun `nothing pending is finished`() {
        assertTrue(RotationPlan.Progress(emptyList(), listOf("x")).isFinished)
    }

    /** The order is the order the files were listed; resumption walks the same one. */
    @Test
    fun `pending keeps its order`() {
        val p = RotationPlan.Progress(listOf("a", "b", "c", "d"), emptyList()).done("c")
        assertEquals(listOf("a", "b", "d"), p.pending)
    }

    // --- The written form ---------------------------------------------------

    @Test
    fun `ids round-trip through the store`() {
        val ids = listOf("a-1", "b-2", "c-3")
        assertEquals(ids, RotationPlan.decode(RotationPlan.encode(ids)))
    }

    @Test
    fun `an empty or absent record decodes to nothing`() {
        assertEquals(emptyList<String>(), RotationPlan.decode(null))
        assertEquals(emptyList<String>(), RotationPlan.decode(""))
        assertEquals(emptyList<String>(), RotationPlan.decode("\n\n  \n"))
    }

    @Test
    fun `encoding nothing is nothing`() {
        assertEquals("", RotationPlan.encode(emptyList()))
        assertEquals(emptyList<String>(), RotationPlan.decode(RotationPlan.encode(emptyList())))
    }
}
