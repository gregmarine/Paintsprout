package com.symmetricalpalmtree.paintsprout.crypto

/**
 * Escalating lockout on failed passphrase attempts, per bucket, persisted.
 *
 * | Consecutive failures | Lockout |
 * |---|---|
 * | 1–2 | none |
 * | 3–4 | 30 s |
 * | 5–9 | 5 min |
 * | ≥ 10 | 1 hr (cap) |
 *
 * Buckets are per document id, plus [GLOBAL_BUCKET] shared by every global-scope
 * prompt and [IMPORT_BUCKET] for files that aren't in the library yet. Separate
 * buckets matter: someone fumbling one private sketchbook's passphrase must not
 * lock themselves out of the whole library.
 *
 * State is persisted because a limiter that resets on process death is not a
 * limiter. **Only counts and timestamps live here — never passphrase material,
 * and nothing about an attempt is ever logged.**
 *
 * Two caller-side rules this class cannot enforce, and that matter as much as the
 * schedule: a *cancelled* prompt is not a failure and must not call
 * [recordFailure]; and a "the file is missing" outcome must be reported as itself
 * rather than looped back into the prompt, or the user will retry a file that
 * does not exist until the lockout hits.
 */
class AttemptLimiter(
    private val store: SecureStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Epoch ms at which the lockout expires, or 0 when an attempt is allowed now. */
    fun lockedUntil(bucket: String): Long {
        val until = store.getLong(lockoutKey(bucket), 0L)
        return if (until > now()) until else 0L
    }

    fun isLocked(bucket: String): Boolean = lockedUntil(bucket) > 0L

    /** How long the caller must wait, in ms; 0 when an attempt is allowed. */
    fun remainingMs(bucket: String): Long = (lockedUntil(bucket) - now()).coerceAtLeast(0L)

    fun failureCount(bucket: String): Int = store.getInt(failuresKey(bucket), 0)

    /** A genuinely wrong passphrase. Not a cancel, and not a missing file. */
    fun recordFailure(bucket: String) {
        val failures = failureCount(bucket) + 1
        val delay = lockoutMs(failures)
        store.putInt(failuresKey(bucket), failures)
        store.putLong(lockoutKey(bucket), if (delay > 0L) now() + delay else 0L)
    }

    /** A correct passphrase clears the history — the counter is consecutive failures. */
    fun recordSuccess(bucket: String) {
        store.remove(failuresKey(bucket))
        store.remove(lockoutKey(bucket))
    }

    private fun failuresKey(bucket: String) = PREFIX_FAILURES + bucket
    private fun lockoutKey(bucket: String) = PREFIX_LOCKOUT + bucket

    companion object {
        const val GLOBAL_BUCKET = "GLOBAL"
        const val IMPORT_BUCKET = "IMPORT"

        private const val PREFIX_FAILURES = "attempt_failures_"
        private const val PREFIX_LOCKOUT = "attempt_lockout_"

        fun lockoutMs(failures: Int): Long = when {
            failures < 3 -> 0L
            failures < 5 -> 30_000L
            failures < 10 -> 300_000L
            else -> 3_600_000L
        }
    }
}
