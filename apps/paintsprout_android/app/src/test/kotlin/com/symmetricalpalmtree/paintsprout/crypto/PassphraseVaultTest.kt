package com.symmetricalpalmtree.paintsprout.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PassphraseVaultTest {

    private val store = FakeSecureStore()
    private val vault = PassphraseVault(store)

    @Test
    fun `a fresh device has no global passphrase until one is asked for`() {
        assertNull(vault.globalOrNull())
        val minted = vault.ensureGlobal()
        assertEquals(minted, vault.globalOrNull())
    }

    @Test
    fun `minting happens once and then the same value comes back`() {
        val first = vault.ensureGlobal()
        repeat(5) { assertEquals(first, vault.ensureGlobal()) }
    }

    @Test
    fun `the minted key is a recovery key`() {
        assertTrue(vault.ensureGlobal().startsWith("PSPT-"))
    }

    /**
     * The failure this guards against is invisible: two first-callers mint two
     * secrets, and the loser's write strands whatever the winner already encrypted
     * behind a passphrase the user was never shown.
     */
    @Test
    fun `concurrent first callers agree on one secret`() {
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val mints = AtomicInteger()
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())

        repeat(threads) {
            pool.submit {
                start.await()
                results += vault.ensureGlobal {
                    mints.incrementAndGet()
                    RecoveryKey.mint()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals("exactly one secret was minted", 1, mints.get())
        assertEquals("every caller saw the same one", 1, results.toSet().size)
        assertEquals(threads, results.size)
    }

    @Test
    fun `setGlobal replaces the cached value`() {
        vault.ensureGlobal()
        vault.setGlobal("a memorable passphrase")
        assertEquals("a memorable passphrase", vault.globalOrNull())
        assertTrue(vault.matchesGlobal("a memorable passphrase"))
        assertFalse(vault.matchesGlobal("something else"))
    }

    /** "Forget on this device" — the files are untouched, only the cache is gone. */
    @Test
    fun `clearGlobal forgets it and a later ensure mints a new one`() {
        val first = vault.ensureGlobal()
        vault.clearGlobal()
        assertNull(vault.globalOrNull())
        assertFalse(first == vault.ensureGlobal())
    }
}
