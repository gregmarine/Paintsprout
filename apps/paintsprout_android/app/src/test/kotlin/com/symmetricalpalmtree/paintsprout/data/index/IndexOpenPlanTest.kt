package com.symmetricalpalmtree.paintsprout.data.index

import com.symmetricalpalmtree.paintsprout.data.DbState
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexOpenPlanTest {

    private fun plan(state: DbState, passphrase: Boolean, rawKey: Boolean) =
        IndexOpenPlanner.plan(state, passphrase, rawKey)

    /**
     * The consequence of getting this one wrong is the whole library: INVALID
     * means "fresh install", and a fresh install creates an empty one. It is only
     * safe because interrupted-swap repair has already run.
     */
    @Test
    fun `nothing there means create it, encrypted from the first byte`() {
        assertEquals(IndexOpenPlan.CREATE_ENCRYPTED, plan(DbState.INVALID, false, false))
        assertEquals(IndexOpenPlan.CREATE_ENCRYPTED, plan(DbState.INVALID, true, true))
    }

    /** This app never creates a plaintext index, so one at that path isn't ours. */
    @Test
    fun `a plaintext index is refused rather than adopted`() {
        assertEquals(IndexOpenPlan.REFUSE_PLAINTEXT, plan(DbState.PLAINTEXT, false, false))
        assertEquals(IndexOpenPlan.REFUSE_PLAINTEXT, plan(DbState.PLAINTEXT, true, true))
    }

    @Test
    fun `no secret on this device means asking for one`() {
        assertEquals(IndexOpenPlan.NEEDS_UNLOCK, plan(DbState.ENCRYPTED, false, false))
    }

    /** A derived key can only be trusted alongside the passphrase it came from. */
    @Test
    fun `a cached key without a cached passphrase still prompts`() {
        assertEquals(IndexOpenPlan.NEEDS_UNLOCK, plan(DbState.ENCRYPTED, false, true))
    }

    @Test
    fun `a cached key is the fast path`() {
        assertEquals(IndexOpenPlan.OPEN_WITH_RAW_KEY, plan(DbState.ENCRYPTED, true, true))
    }

    /** First launch after an unlock: one slow open, then derive behind the user. */
    @Test
    fun `a passphrase with no derived key yet opens the slow way`() {
        assertEquals(IndexOpenPlan.OPEN_WITH_PASSPHRASE, plan(DbState.ENCRYPTED, true, false))
    }

    /**
     * A key that no longer opens the file — rotated on another device, restored
     * from a foreign backup — is indistinguishable from corruption. Never retry
     * with it, never conclude damage: drop it and ask.
     */
    @Test
    fun `a stale key leads to the prompt, not to a retry`() {
        assertEquals(IndexOpenPlan.NEEDS_UNLOCK, IndexOpenPlanner.afterStaleKey())
    }

    @Test
    fun `every combination is decided`() {
        for (state in DbState.entries) {
            for (passphrase in listOf(true, false)) {
                for (rawKey in listOf(true, false)) {
                    plan(state, passphrase, rawKey) // must not throw
                }
            }
        }
    }
}
