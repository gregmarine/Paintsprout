package com.symmetricalpalmtree.paintsprout.crypto

import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Turns a passphrase into the 32-byte key SQLCipher would have derived from it —
 * once, instead of on every connection.
 *
 * SQLCipher's KDF costs 300–700 ms **per connection**. On a tablet opening a
 * sketchbook that is the entire perceived load time, and the index pays it on
 * every cold launch. But the KDF's output is deterministic for a given
 * (passphrase, file salt) pair, so: derive once, cache the 32 bytes, and reopen
 * with `PRAGMA key = "x'<64 hex>'"`, which SQLCipher recognises as a raw key and
 * applies directly. Roughly 35 ms including ORM overhead.
 *
 * **Portability is untouched.** The file is still passphrase-keyed; the raw key is
 * merely the KDF's output. A stock `sqlcipher` CLI still opens it with the
 * passphrase, which is the acceptance test for this whole subsystem.
 *
 * The parameters are SQLCipher 4.x stock defaults and must stay that way. A
 * custom `kdf_iter` or page size would mean only *this* app can open the file,
 * which is the opposite of the format's point.
 *
 * Key material is never logged.
 */
object RawKeyDerivation {

    const val KDF_ITER = 256_000
    const val KEY_LEN = 32 // AES-256

    /** SQLCipher keeps the KDF salt as the file's first 16 bytes, in the clear. */
    const val SALT_LEN = 16

    /**
     * The salt is readable without any key — it is deliberately outside the
     * encrypted region, which is what lets us derive before we can open.
     */
    fun readSalt(file: File): ByteArray {
        val salt = ByteArray(SALT_LEN)
        file.inputStream().use { input ->
            var got = 0
            while (got < SALT_LEN) {
                val n = input.read(salt, got, SALT_LEN - got)
                require(n >= 0) { "File is shorter than a SQLCipher salt: ${file.name}" }
                got += n
            }
        }
        return salt
    }

    /**
     * Expensive by design — call it off the UI thread and cache the result (see
     * [RawKeyCache]).
     *
     * The passphrase becomes bytes via UTF-8, which is the one canonical
     * encoding. Changing it would not fail loudly; it would silently produce files
     * that only open on the device that wrote them.
     */
    fun deriveKey(file: File, passphrase: String): ByteArray =
        pbkdf2HmacSha512(keyBytes(passphrase), readSalt(file), KDF_ITER, KEY_LEN)

    /** The canonical passphrase encoding. UTF-8, always, everywhere. */
    fun keyBytes(passphrase: String): ByteArray = passphrase.toByteArray(Charsets.UTF_8)

    /** The `x'<hex>'` literal SQLCipher accepts in place of a passphrase. */
    fun rawKeyLiteral(rawKey: ByteArray): String = "x'${rawKey.toHex()}'"

    /**
     * PBKDF2-HMAC-SHA512, by hand.
     *
     * Not because the JCE lacks it, but because `PBEKeySpec` takes a `char[]` and
     * leaves the password→bytes encoding to the provider. Here the input is
     * already the exact UTF-8 bytes SQLCipher hashes, with nothing in between to
     * disagree about. (It also measures faster on-device than the JCE factory.)
     */
    internal fun pbkdf2HmacSha512(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(password, "HmacSHA512")) }
        val hLen = mac.macLength
        val out = ByteArray(dkLen)
        var offset = 0
        var block = 1
        while (offset < dkLen) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(), (block ushr 16).toByte(),
                    (block ushr 8).toByte(), block.toByte(),
                ),
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

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
