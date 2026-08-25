package com.symmetricalpalmtree.paintsprout.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class RecoveryKeyTest {

    /** Deterministic bytes, so the encoder can be checked rather than sampled. */
    private class FixedRandom(private val fill: ByteArray) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = fill[i % fill.size]
        }
    }

    @Test
    fun `the key is PSPT plus eight groups of four`() {
        val key = RecoveryKey.mint(FixedRandom(byteArrayOf(0x1F)))
        val parts = key.split("-")
        assertEquals("PSPT", parts.first())
        assertEquals(9, parts.size) // prefix + 8 groups
        assertTrue(parts.drop(1).all { it.length == 4 })
        assertEquals(32 + 8, key.length - "PSPT".length) // 32 chars + 8 dashes
    }

    /**
     * The omissions are the point: this string is read off one screen and typed
     * into another, sometimes from a photograph.
     */
    @Test
    fun `no I, L, O or U anywhere in the alphabet or in a key`() {
        assertEquals(32, RecoveryKey.ALPHABET.length)
        for (c in "ILOU") {
            assertFalse("alphabet contains $c", RecoveryKey.ALPHABET.contains(c))
        }
        repeat(50) {
            val body = RecoveryKey.mint().removePrefix("PSPT-").replace("-", "")
            assertTrue(body.all { it in RecoveryKey.ALPHABET })
        }
    }

    @Test
    fun `160 bits of entropy become exactly 32 characters`() {
        assertEquals(20, RecoveryKey.ENTROPY_BYTES)
        assertEquals(32, RecoveryKey.base32(ByteArray(RecoveryKey.ENTROPY_BYTES)).length)
    }

    @Test
    fun `base32 packs five bits at a time, most significant first`() {
        // 0xFF00 = 11111 11100 00000 0(000) -> indices 31, 28, 0 and a padded 0
        assertEquals("ZW00", RecoveryKey.base32(byteArrayOf(0xFF.toByte(), 0x00)))
        assertEquals("00", RecoveryKey.base32(byteArrayOf(0x00)))
        assertEquals("", RecoveryKey.base32(ByteArray(0)))
    }

    @Test
    fun `two keys are never the same`() {
        val keys = List(200) { RecoveryKey.mint() }
        assertEquals(keys.size, keys.toSet().size)
    }

    /** Prefix and dashes are part of the passphrase, not a token to be parsed off. */
    @Test
    fun `the whole string is the passphrase`() {
        val key = RecoveryKey.mint()
        assertTrue(key.startsWith("PSPT-"))
        assertEquals(key, key.trim())
    }
}
