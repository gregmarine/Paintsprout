package com.symmetricalpalmtree.paintsproutonyx.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lockout schedule, pinned across every boundary. The schedule is a family
 * contract (1–2 free · 3–4 → 30 s · 5–9 → 5 min · ≥ 10 → 1 h), and an
 * off-by-one at a boundary is exactly the kind of drift a reader of the docs
 * would never notice — so every edge is asserted, both sides.
 */
class AttemptLimiterTest {

    @Test
    fun firstTwoFailures_areFree() {
        assertEquals(0L, AttemptLimiter.lockoutDelayMs(0))
        assertEquals(0L, AttemptLimiter.lockoutDelayMs(1))
        assertEquals(0L, AttemptLimiter.lockoutDelayMs(2))
    }

    @Test
    fun thirdAndFourth_cost30Seconds() {
        assertEquals(30_000L, AttemptLimiter.lockoutDelayMs(3))
        assertEquals(30_000L, AttemptLimiter.lockoutDelayMs(4))
    }

    @Test
    fun fifthThroughNinth_costFiveMinutes() {
        assertEquals(300_000L, AttemptLimiter.lockoutDelayMs(5))
        assertEquals(300_000L, AttemptLimiter.lockoutDelayMs(6))
        assertEquals(300_000L, AttemptLimiter.lockoutDelayMs(9))
    }

    @Test
    fun tenthAndBeyond_costAnHour() {
        assertEquals(3_600_000L, AttemptLimiter.lockoutDelayMs(10))
        assertEquals(3_600_000L, AttemptLimiter.lockoutDelayMs(11))
        assertEquals(3_600_000L, AttemptLimiter.lockoutDelayMs(99))
        assertEquals(3_600_000L, AttemptLimiter.lockoutDelayMs(Int.MAX_VALUE))
    }
}
