package com.symmetricalpalmtree.paintsprout.crypto

import com.symmetricalpalmtree.paintsprout.crypto.RawKeyDerivation.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random

/**
 * If this derivation is wrong, a raw-key open silently fails and every launch
 * pays the full KDF — or worse, we conclude a file is corrupt. So it is checked
 * against the JCE's own PBKDF2 rather than against itself.
 */
class RawKeyDerivationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The reference implementation, for ASCII where char[] encoding can't differ. */
    private fun jce(password: String, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, dkLen * 8))
            .encoded

    @Test
    fun `matches the JCE's PBKDF2-HMAC-SHA512`() {
        val salt = Random(3).nextBytes(16)
        for (password in listOf("a", "correct horse battery staple", "PSPT-4K7P-9WXQ-2M3F")) {
            assertArrayEquals(
                "mismatch for '$password'",
                jce(password, salt, 1000, 32),
                RawKeyDerivation.pbkdf2HmacSha512(password.toByteArray(Charsets.UTF_8), salt, 1000, 32),
            )
        }
    }

    /** More output than one HMAC block, to exercise the block-counter loop. */
    @Test
    fun `matches across a block boundary`() {
        val salt = Random(9).nextBytes(16)
        assertArrayEquals(
            jce("multi-block", salt, 200, 100),
            RawKeyDerivation.pbkdf2HmacSha512("multi-block".toByteArray(Charsets.UTF_8), salt, 200, 100),
        )
    }

    /**
     * UTF-8 is the one canonical encoding, and getting it wrong wouldn't fail
     * loudly — it would produce files that only open on the device that wrote them.
     */
    @Test
    fun `the passphrase is encoded as UTF-8`() {
        val phrase = "pässwörd — 絵の具 🎨"
        assertArrayEquals(phrase.toByteArray(Charsets.UTF_8), RawKeyDerivation.keyBytes(phrase))
        assertNotEquals(
            phrase.toByteArray(Charsets.UTF_8).size,
            phrase.toByteArray(Charsets.UTF_16).size,
        )
    }

    @Test
    fun `stock SQLCipher parameters, unchanged`() {
        assertEquals(256_000, RawKeyDerivation.KDF_ITER)
        assertEquals(32, RawKeyDerivation.KEY_LEN)
        assertEquals(16, RawKeyDerivation.SALT_LEN)
    }

    @Test
    fun `the salt is the file's first sixteen bytes`() {
        val f = tmp.newFile("salted.soil")
        val head = Random(11).nextBytes(16)
        f.writeBytes(head + Random(12).nextBytes(4096))
        assertArrayEquals(head, RawKeyDerivation.readSalt(f))
    }

    @Test
    fun `a file too short to hold a salt is refused`() {
        val f = tmp.newFile("stub.soil")
        f.writeBytes(ByteArray(8))
        var threw = false
        try {
            RawKeyDerivation.readSalt(f)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    /** The literal SQLCipher recognises in place of a passphrase. */
    @Test
    fun `the raw key literal is 64 lowercase hex characters in quotes`() {
        val key = Random(5).nextBytes(32)
        val literal = RawKeyDerivation.rawKeyLiteral(key)
        assertTrue(literal.startsWith("x'"))
        assertTrue(literal.endsWith("'"))
        assertEquals(64, literal.length - 3)
        assertTrue(literal.drop(2).dropLast(1).all { it in "0123456789abcdef" })
    }

    @Test
    fun `hex round-trips`() {
        val key = Random(6).nextBytes(32)
        assertArrayEquals(key, RawKeyDerivation.hexToBytes(key.toHex()))
        assertEquals("00ff7f", byteArrayOf(0, 0xFF.toByte(), 0x7F).toHex())
    }

    /** Same passphrase, different file: different key. The salt is what makes it so. */
    @Test
    fun `the key is bound to the file's salt`() {
        val a = tmp.newFile("a.soil").apply { writeBytes(Random(1).nextBytes(64)) }
        val b = tmp.newFile("b.soil").apply { writeBytes(Random(2).nextBytes(64)) }
        val phrase = "same"
        val ka = RawKeyDerivation.pbkdf2HmacSha512(
            RawKeyDerivation.keyBytes(phrase), RawKeyDerivation.readSalt(a), 100, 32,
        )
        val kb = RawKeyDerivation.pbkdf2HmacSha512(
            RawKeyDerivation.keyBytes(phrase), RawKeyDerivation.readSalt(b), 100, 32,
        )
        assertNotEquals(ka.toHex(), kb.toHex())
    }
}
