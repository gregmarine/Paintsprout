package com.symmetricalpalmtree.paintsproutonyx.crypto

import android.content.Context

/**
 * An escalating brake on unlock attempts, persisted so killing the process does
 * not reset it — the point of a lockout that anyone can clear by swiping the app
 * away is decoration, not a lockout.
 *
 * The schedule is deliberately gentle at the front: the first two misses are
 * free, because the person most likely to mistype a 32-character key twice is
 * its owner reading their own handwriting. Only a run of failures that no honest
 * transcription produces earns the long waits. Reference schedule, carried
 * verbatim from the family: 1–2 free · 3–4 → 30 s · 5–9 → 5 min · ≥ 10 → 1 h.
 * A success clears everything.
 *
 * Only counts and timestamps live here — never a passphrase, never a guess.
 */
object AttemptLimiter {

    const val GLOBAL_KEY = "GLOBAL"

    private const val PREFIX_FAILURES = "attempt_failures_"
    private const val PREFIX_LOCKOUT = "attempt_lockout_"

    private fun prefs(context: Context) = SecurePrefs.get(context, PassphraseStore.PREFS_FILE)

    /**
     * Milliseconds left on the current lockout, or 0 when an attempt is allowed now. Remaining
     * time rather than a deadline, because a deadline invites every caller to subtract the clock
     * for itself — and the one that forgets reads a lockout that ended an hour ago as an hour
     * still to wait.
     */
    fun check(context: Context, key: String = GLOBAL_KEY): Long {
        val until = prefs(context).getLong(PREFIX_LOCKOUT + key, 0L)
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    fun recordFailure(context: Context, key: String = GLOBAL_KEY) {
        val p = prefs(context)
        val failures = p.getInt(PREFIX_FAILURES + key, 0) + 1
        val delayMs = lockoutDelayMs(failures)
        val until = if (delayMs > 0L) System.currentTimeMillis() + delayMs else 0L
        p.edit()
            .putInt(PREFIX_FAILURES + key, failures)
            .putLong(PREFIX_LOCKOUT + key, until)
            .apply()
    }

    fun recordSuccess(context: Context, key: String = GLOBAL_KEY) {
        prefs(context).edit()
            .remove(PREFIX_FAILURES + key)
            .remove(PREFIX_LOCKOUT + key)
            .apply()
    }

    /** The pure schedule — the unit-tested half of the limiter. */
    fun lockoutDelayMs(failures: Int): Long = when {
        failures < 3 -> 0L
        failures < 5 -> 30_000L
        failures < 10 -> 300_000L
        else -> 3_600_000L
    }
}
