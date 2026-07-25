package com.symmetricalpalmtree.paintsprout.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptLimiterTest {

    private val store = FakeSecureStore()
    private var clock = 1_000_000L
    private val limiter = AttemptLimiter(store) { clock }

    @Test
    fun `the first two failures cost nothing`() {
        limiter.recordFailure("doc")
        assertFalse(limiter.isLocked("doc"))
        limiter.recordFailure("doc")
        assertFalse(limiter.isLocked("doc"))
    }

    @Test
    fun `the schedule escalates and then caps`() {
        assertEquals(0L, AttemptLimiter.lockoutMs(1))
        assertEquals(0L, AttemptLimiter.lockoutMs(2))
        assertEquals(30_000L, AttemptLimiter.lockoutMs(3))
        assertEquals(30_000L, AttemptLimiter.lockoutMs(4))
        assertEquals(300_000L, AttemptLimiter.lockoutMs(5))
        assertEquals(300_000L, AttemptLimiter.lockoutMs(9))
        assertEquals(3_600_000L, AttemptLimiter.lockoutMs(10))
        assertEquals(3_600_000L, AttemptLimiter.lockoutMs(1000))
    }

    @Test
    fun `the third failure locks for thirty seconds and then expires`() {
        repeat(3) { limiter.recordFailure("doc") }
        assertTrue(limiter.isLocked("doc"))
        assertEquals(30_000L, limiter.remainingMs("doc"))

        clock += 29_999L
        assertTrue(limiter.isLocked("doc"))

        clock += 2L
        assertFalse(limiter.isLocked("doc"))
        assertEquals(0L, limiter.remainingMs("doc"))
    }

    /** The counter is *consecutive* failures, so a success has to clear it. */
    @Test
    fun `success clears the count and the lockout`() {
        repeat(5) { limiter.recordFailure("doc") }
        assertTrue(limiter.isLocked("doc"))

        limiter.recordSuccess("doc")

        assertEquals(0, limiter.failureCount("doc"))
        assertFalse(limiter.isLocked("doc"))
    }

    /**
     * Fumbling one private sketchbook's passphrase must not lock the library, and
     * an unrecognised import must not lock either.
     */
    @Test
    fun `buckets are independent`() {
        repeat(10) { limiter.recordFailure("one-sketchbook") }

        assertTrue(limiter.isLocked("one-sketchbook"))
        assertFalse(limiter.isLocked("another-sketchbook"))
        assertFalse(limiter.isLocked(AttemptLimiter.GLOBAL_BUCKET))
        assertFalse(limiter.isLocked(AttemptLimiter.IMPORT_BUCKET))
    }

    /** A limiter that forgets on process death is not a limiter. */
    @Test
    fun `state survives a new instance over the same store`() {
        repeat(3) { limiter.recordFailure(AttemptLimiter.GLOBAL_BUCKET) }

        val reborn = AttemptLimiter(store) { clock }

        assertEquals(3, reborn.failureCount(AttemptLimiter.GLOBAL_BUCKET))
        assertTrue(reborn.isLocked(AttemptLimiter.GLOBAL_BUCKET))
    }

    /** Counts and timestamps only — never anything that could reconstruct a secret. */
    @Test
    fun `nothing resembling passphrase material is stored`() {
        repeat(4) { limiter.recordFailure("doc") }
        assertEquals(
            setOf("attempt_failures_doc", "attempt_lockout_doc"),
            store.keys(),
        )
        assertTrue(store.values.values.all { it is Int || it is Long })
    }
}
