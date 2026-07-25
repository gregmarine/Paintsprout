package com.symmetricalpalmtree.paintsprout.data.index

import com.symmetricalpalmtree.paintsprout.data.DbState

/** What opening the index will take, decided before anything is opened. */
enum class IndexOpenPlan {
    /** Nothing is there: mint a key and create the file encrypted from its first byte. */
    CREATE_ENCRYPTED,

    /** The fast path — a derived key is cached, so the KDF is skipped. */
    OPEN_WITH_RAW_KEY,

    /** We know the passphrase but haven't derived its key yet. One slow open, then derive. */
    OPEN_WITH_PASSPHRASE,

    /** No secret on this device: prompt. */
    NEEDS_UNLOCK,

    /**
     * A plaintext index.
     *
     * This app has never created one — the index is encrypted from the first byte,
     * always — so a plaintext file at that path is somebody else's, or damage that
     * happens to parse. Either way it is not ours to adopt, and silently
     * encrypting it would destroy whatever it actually is.
     */
    REFUSE_PLAINTEXT,
}

/**
 * The index's open decision, as a function rather than as control flow buried in
 * a coroutine.
 *
 * The one that matters is the first: **INVALID means fresh install**, and fresh
 * install means creating an empty library. That is only safe because
 * [SwapRecovery][com.symmetricalpalmtree.paintsprout.data.SwapRecovery] has
 * already run — a probe of a file that is merely *mid-swap* also returns INVALID,
 * and acting on it would replace the user's entire library with an empty one.
 * Repair first, then probe, then plan.
 */
object IndexOpenPlanner {

    fun plan(
        probe: DbState,
        hasCachedPassphrase: Boolean,
        hasCachedRawKey: Boolean,
    ): IndexOpenPlan = when (probe) {
        DbState.INVALID -> IndexOpenPlan.CREATE_ENCRYPTED
        DbState.PLAINTEXT -> IndexOpenPlan.REFUSE_PLAINTEXT
        DbState.ENCRYPTED -> when {
            !hasCachedPassphrase -> IndexOpenPlan.NEEDS_UNLOCK
            hasCachedRawKey -> IndexOpenPlan.OPEN_WITH_RAW_KEY
            else -> IndexOpenPlan.OPEN_WITH_PASSPHRASE
        }
    }

    /**
     * What to do when a cached key turns out not to open the file — it was rotated
     * on another device, or the library was restored from elsewhere.
     *
     * Always the prompt, never a retry with the same material and never an
     * assumption of damage. A stale key is indistinguishable from corruption at
     * the SQLite layer, so the cache is dropped and the user is asked.
     */
    fun afterStaleKey(): IndexOpenPlan = IndexOpenPlan.NEEDS_UNLOCK
}
