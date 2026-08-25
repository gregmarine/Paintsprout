package com.symmetricalpalmtree.paintsprout.crypto

import java.security.SecureRandom

/**
 * Holds the device's global passphrase, and mints it the first time anyone asks.
 *
 * [ensureGlobal] is the only way a global passphrase comes into existence, and it
 * is process-wide synchronized for a reason that is easy to miss: two concurrent
 * first-callers — the index opening while something else warms a key — would
 * otherwise mint two different secrets, and the loser's write would strand
 * whatever the winner had already encrypted behind a passphrase the user was
 * never shown.
 *
 * A per-sketchbook passphrase is **never** stored here. It lives in RAM for as
 * long as the document is open and is then gone; that is what the scope means.
 */
class PassphraseVault(private val store: SecureStore) {

    /** The global passphrase, or null if this device has never had one. */
    fun globalOrNull(): String? = store.getString(KEY_GLOBAL)

    /**
     * The global passphrase, minting one if absent.
     *
     * [mint] is a parameter so tests can be deterministic; production takes the
     * default, which is 160 bits of [SecureRandom].
     */
    fun ensureGlobal(mint: () -> String = { RecoveryKey.mint() }): String = synchronized(LOCK) {
        globalOrNull()?.let { return it }
        val minted = mint()
        store.putString(KEY_GLOBAL, minted)
        return minted
    }

    /**
     * Replaces the cached global passphrase.
     *
     * Rotation calls this **only after every pending file has been re-keyed**.
     * During a partial rotation the cache must keep the old value, so that files
     * already carrying the new one fall through to a prompt and re-cache on
     * success — rather than silently failing against a passphrase that is only
     * half true.
     */
    fun setGlobal(passphrase: String) = synchronized(LOCK) {
        store.putString(KEY_GLOBAL, passphrase)
    }

    /** "Forget on this device." The files stay exactly as they are. */
    fun clearGlobal() = synchronized(LOCK) { store.remove(KEY_GLOBAL) }

    /** Whether [candidate] is what this device believes the global passphrase to be. */
    fun matchesGlobal(candidate: String): Boolean = globalOrNull() == candidate

    private companion object {
        /**
         * Static, not per-instance: the guarantee needed is "one mint per process",
         * and instances are cheap enough that someone will eventually construct two.
         */
        val LOCK = Any()
        const val KEY_GLOBAL = "global_passphrase"
    }
}
