package com.symmetricalpalmtree.paintsprout.crypto

import java.security.SecureRandom

/**
 * The 160-bit secret minted at first launch, before the user has been asked for
 * anything.
 *
 * Encryption-by-default only costs the user nothing if the app can mint its own
 * key, so it does — and that string doubles as the **recovery key**: the one
 * secret that opens the library on another device or after a reinstall.
 * Onboarding shows it; the rotation flow later lets the user replace it with
 * something memorable.
 *
 * It is an ordinary passphrase fed to SQLCipher's KDF. The `PSPT-` prefix and the
 * dashes are *part of the string*, not a structured token to be parsed off —
 * whatever is displayed is what must be typed back.
 *
 * ```
 * PSPT-4K7P-9WXQ-2M3F-8VBN-5H0T-…      8 groups of 4
 * ```
 *
 * Never logged. Not the value, not a prefix of it, not its length.
 */
object RecoveryKey {

    /**
     * Crockford base32: no `I`, `L`, `O` or `U`. The omissions are the point —
     * this string gets read off one screen and typed into another, sometimes from
     * a photograph, and `I`/`1`, `O`/`0` and `U`/`V` are where that goes wrong.
     */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** The app's own prefix, so a key is recognisable at a glance as Paintsprout's. */
    const val PREFIX = "PSPT"

    /** 160 bits. Enough that the KDF is the only thing anyone can attack. */
    const val ENTROPY_BYTES = 20

    private const val GROUP = 4

    fun mint(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(ENTROPY_BYTES).also { random.nextBytes(it) }
        return "$PREFIX-" + base32(bytes).chunked(GROUP).joinToString("-")
    }

    /**
     * Base32 over the Crockford alphabet, no padding: 20 bytes → exactly 32
     * characters, which is why there is no partial-group tail to worry about in
     * practice (the branch is kept for correctness, not for this caller).
     */
    fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }
}
