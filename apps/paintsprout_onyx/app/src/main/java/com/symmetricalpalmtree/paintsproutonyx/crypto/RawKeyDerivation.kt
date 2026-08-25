package com.symmetricalpalmtree.paintsproutonyx.crypto

import java.io.File
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives the SQLCipher raw key a passphrase would produce, so later opens can
 * skip the derivation.
 *
 * SQLCipher runs PBKDF2-HMAC-SHA512 for 256,000 rounds on every new connection —
 * several hundred milliseconds on an e-ink CPU, paid at every open of every
 * file. But the output is the same 32 bytes every time for the same passphrase
 * and the same file, because the salt is simply the file's first 16 bytes,
 * stored in plaintext exactly so that any SQLCipher build can re-derive the key.
 * So we derive once, byte-exactly the way SQLCipher would, cache the result
 * ([KeyMaterial]), and hand later opens the `x'<hex>'` literal that SQLCipher
 * applies directly. The file itself never changes and never stops answering to
 * the passphrase — a cached key is a shortcut, not a format.
 *
 * These four constants ARE the portability contract. Change any of them and the
 * derived key stops matching what a stock SQLCipher 4 derives, which surfaces as
 * "wrong key" against a perfectly good file. Key material is never logged.
 */
object RawKeyDerivation {

    const val KDF_ITER = 256_000
    const val KEY_LEN = 32
    const val SALT_LEN = 16

    /** The 16-byte KDF salt — the plaintext header SQLCipher writes as the file's first bytes. */
    fun readSalt(file: File): ByteArray {
        val salt = ByteArray(SALT_LEN)
        file.inputStream().use {
            // readNBytes rather than a single read: a stream is allowed to hand back fewer bytes
            // than asked for, and a plain read would then throw on a perfectly good file. A local
            // file will not do that in practice — which is exactly why the failure would be rare,
            // baffling, and reported as "it stopped opening my library", so it is worth the one
            // word it costs to be right instead of nearly always right.
            val n = it.readNBytes(salt, 0, SALT_LEN)
            require(n == SALT_LEN) { "short salt read ($n bytes)" }
        }
        return salt
    }

    /** The expensive call — run it on a background dispatcher and cache the answer. UTF-8 passphrase bytes. */
    fun deriveKey(file: File, passphrase: String): ByteArray =
        pbkdf2HmacSha512(SoilCrypto.keyBytes(passphrase), readSalt(file), KDF_ITER, KEY_LEN)

    /** The SQLCipher raw-key literal, `x'<hex>'`. */
    fun rawKeyLiteral(rawKey: ByteArray): String = "x'${toHex(rawKey)}'"

    /**
     * Lower-case ASCII hex, pinned to Locale.ROOT. A default-locale format can
     * emit digits that are not ASCII at all — Eastern Arabic numerals under an
     * Arabic locale — and a key literal built from those opens nothing, in a way
     * that only reproduces on a device set to that language.
     */
    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    /**
     * PBKDF2 by hand rather than through SecretKeyFactory, so the JVM tests can
     * pin it against an independent implementation and the exact bytes cannot
     * drift with a provider. RFC 2898: per block, U1 = HMAC(salt ‖ blockIndex),
     * then iterate the HMAC and XOR the stream together.
     */
    internal fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(password, "HmacSHA512")) }
        val hLen = mac.macLength
        val out = ByteArray(dkLen)
        var offset = 0
        var block = 1
        while (offset < dkLen) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte(),
                )
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }
}
