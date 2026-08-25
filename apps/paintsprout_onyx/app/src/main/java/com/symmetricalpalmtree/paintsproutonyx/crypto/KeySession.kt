package com.symmetricalpalmtree.paintsproutonyx.crypto

/**
 * The process-RAM copy of the global passphrase, set by the bootstrap once the
 * index has actually opened with it.
 *
 * It exists so a later open — a sketchbook whose raw key is not cached yet —
 * can reach the passphrase without a Keystore round-trip and without every
 * caller re-reading [PassphraseStore]. It is deliberately the *least* durable
 * home the passphrase has: never written to an Intent, prefs, or disk from
 * here, gone with the process. Anything needing the passphrase to survive
 * restarts already has [PassphraseStore]; anything reaching for this before
 * bootstrap has run is a sequencing bug this null makes loud.
 */
object KeySession {
    @Volatile
    private var passphrase: String? = null

    fun set(value: String) { passphrase = value }

    fun get(): String? = passphrase

    fun clear() { passphrase = null }
}
