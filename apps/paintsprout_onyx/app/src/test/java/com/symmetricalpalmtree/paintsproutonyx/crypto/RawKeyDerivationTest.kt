package com.symmetricalpalmtree.paintsproutonyx.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Pins the KDF byte-for-byte. Every expected hex string in this file was
 * computed by an independent implementation (Python's hashlib.pbkdf2_hmac),
 * so these are vectors against another codebase, not the function agreeing
 * with itself. If any of them fails, the derived keys no longer match what a
 * stock SQLCipher 4 derives — which surfaces on a device as "wrong key"
 * against a perfectly good file.
 */
class RawKeyDerivationTest {

    // ── The KDF itself ───────────────────────────────────────────────────────

    @Test
    fun pbkdf2_singleIteration_matchesIndependentVector() {
        // hashlib.pbkdf2_hmac('sha512', b'password', b'salt', 1, 64)
        val out = RawKeyDerivation.pbkdf2HmacSha512("password".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252" +
                "c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
            RawKeyDerivation.toHex(out),
        )
    }

    @Test
    fun pbkdf2_twoIterations_catchesTheXorLoopOffByOne() {
        // hashlib.pbkdf2_hmac('sha512', b'password', b'salt', 2, 32) — one
        // iteration too few or too many and this diverges.
        val out = RawKeyDerivation.pbkdf2HmacSha512("password".toByteArray(), "salt".toByteArray(), 2, 32)
        assertEquals(
            "e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53c",
            RawKeyDerivation.toHex(out),
        )
    }

    @Test
    fun pbkdf2_multiBlock_exercisesTheBlockCounter() {
        // dkLen 100 > hLen 64 forces a second block, so the INT(i) encoding and
        // the final partial copy are both on trial.
        // hashlib.pbkdf2_hmac('sha512', b'passwordPASSWORDpassword', b'saltSALTsaltSALTsaltSALTsaltSALTsalt', 3, 100)
        val out = RawKeyDerivation.pbkdf2HmacSha512(
            "passwordPASSWORDpassword".toByteArray(),
            "saltSALTsaltSALTsaltSALTsaltSALTsalt".toByteArray(),
            3,
            100,
        )
        assertEquals(
            "e3ad582d92516a866ef6a2725080fbee6f7cd51734047789cccdae6581e79529" +
                "601c42bf26261838b697a3a819e36dab84f1987867fc40a605429d6c540e3cb2" +
                "23551306ab87c412d04ce40f3def06757fe3789fdcf8e2ad8e4343427a94fe82" +
                "24aa48bb",
            RawKeyDerivation.toHex(out),
        )
    }

    // ── The whole contract, end to end ───────────────────────────────────────

    @Test
    fun deriveKey_fullContract_knownAnswer() {
        // The real thing: salt = the file's first 16 bytes, PBKDF2-HMAC-SHA512,
        // 256,000 iterations, 32 bytes out. Expected value from
        // hashlib.pbkdf2_hmac('sha512', b'PSPT-TEST', bytes(range(16)), 256000, 32).
        // Trailing bytes past the salt prove only the first 16 participate.
        val file = tempFileWith(ByteArray(16) { it.toByte() } + "not the salt".toByteArray())
        val key = RawKeyDerivation.deriveKey(file, "PSPT-TEST")
        assertEquals(RawKeyDerivation.KEY_LEN, key.size)
        assertEquals(
            "fafa0176776fe3cb161b2dafbdf672efdeb188808a9e8162c2ff9dfbbda41017",
            RawKeyDerivation.toHex(key),
        )
    }

    @Test
    fun readSalt_isExactlyTheFirst16Bytes() {
        val salt = ByteArray(16) { (0x10 + it).toByte() }
        val file = tempFileWith(salt + ByteArray(64) { 0x7F })
        assertArrayEquals(salt, RawKeyDerivation.readSalt(file))
    }

    @Test
    fun readSalt_refusesAShortFile() {
        // A truncated file must fail here, loudly — deriving against a partial
        // salt would produce a plausible-looking key that opens nothing.
        val file = tempFileWith(ByteArray(7) { it.toByte() })
        try {
            RawKeyDerivation.readSalt(file)
            throw AssertionError("readSalt accepted a 7-byte file")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    @Test
    fun rawKeyLiteral_shape() {
        assertEquals("x'0102'", RawKeyDerivation.rawKeyLiteral(byteArrayOf(1, 2)))
        assertEquals("x'00ff'", RawKeyDerivation.rawKeyLiteral(byteArrayOf(0, -1)))
    }

    @Test
    fun toHex_isLowercaseAscii_evenUnderAnArabicLocale() {
        // Under ar_EG a default-locale format can emit Eastern Arabic digits;
        // a key literal built from those opens nothing, and only on devices set
        // to that language. Locale.ROOT is the fix; this holds it in place.
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            val hex = RawKeyDerivation.toHex(byteArrayOf(0x00, 0x0A, 0x7F, 0xFF.toByte()))
            assertEquals("000a7fff", hex)
            assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun constants_areStockSqlcipher4() {
        // These three numbers ARE the portability contract. If this test needs
        // editing, the on-disk format just changed.
        assertEquals(256_000, RawKeyDerivation.KDF_ITER)
        assertEquals(32, RawKeyDerivation.KEY_LEN)
        assertEquals(16, RawKeyDerivation.SALT_LEN)
    }

    private fun tempFileWith(bytes: ByteArray): File =
        File.createTempFile("rawkey", ".bin").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
}
