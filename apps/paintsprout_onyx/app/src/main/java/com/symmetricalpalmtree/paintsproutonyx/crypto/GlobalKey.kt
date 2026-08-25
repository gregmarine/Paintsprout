package com.symmetricalpalmtree.paintsproutonyx.crypto

import android.content.Context
import java.security.SecureRandom

/**
 * The one secret in the whole app: a passphrase minted by the app itself, so the
 * artist gets an encrypted library without ever being asked to invent a password.
 * The same string is shown once as the **recovery key** — the only thing that
 * reopens the library after a reinstall or on a different tablet. Prefix and
 * dashes are part of the passphrase, not decoration around it.
 *
 * Nothing here may ever be logged. A key that leaks into logcat is a key that
 * leaks into every bug report.
 */
object GlobalKey {

    /**
     * Crockford's base32. I, L, O and U are left out on purpose: this key will be
     * copied by hand onto paper and typed back months later, and an alphabet where
     * 0/O and 1/I/l are different characters is an alphabet that punishes
     * handwriting. Omitting them means [normalize] can fold those confusables back
     * without ever corrupting a correctly written key.
     */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val PREFIX = "PSPT-"

    /** 160 bits — 32 base32 characters, which the format groups as 8 blocks of 4. */
    private const val ENTROPY_BYTES = 20

    /**
     * The cached global passphrase, minting and caching one if this install has
     * none yet. Synchronized because two first-callers racing here could each mint
     * a different key, one would win the cache, and whatever the loser encrypted
     * would be sealed under a passphrase nobody holds.
     */
    @Synchronized
    fun ensure(context: Context): String {
        PassphraseStore.getGlobalPassphrase(context)?.let { return it }
        val minted = mint()
        PassphraseStore.setGlobalPassphrase(context, minted)
        return minted
    }

    /** A fresh 160-bit recovery key — "PSPT-" plus 8 groups of 4. */
    fun mint(): String = format(ByteArray(ENTROPY_BYTES).also { SecureRandom().nextBytes(it) })

    /**
     * Fold a hand-typed key back onto the canonical alphabet: upper-case, then map
     * the very confusables the alphabet omits (O to 0, I and L to 1). Because a
     * genuine key never contains I, L, O or U, this fold can only rescue a reader
     * who wrote "O" where the key said "0" — it can never damage a correct one.
     * Typing this key is the only way back into a lost library, so the entry field
     * has to forgive everything the alphabet lets it forgive.
     */
    fun normalize(typed: String): String = buildString(typed.length) {
        for (c in typed.uppercase()) {
            append(
                when (c) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> c
                }
            )
        }
    }

    /**
     * Deterministic formatting of the 20 entropy bytes — the pure half of minting,
     * split out so a unit test can pin the exact string a known input produces.
     */
    fun format(entropy: ByteArray): String {
        require(entropy.size == ENTROPY_BYTES) { "expected $ENTROPY_BYTES bytes" }
        return PREFIX + base32(entropy).chunked(4).joinToString("-")
    }

    /** Plain base32 over [ALPHABET], no padding — 20 bytes become exactly 32 characters. */
    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        return sb.toString()
    }
}
