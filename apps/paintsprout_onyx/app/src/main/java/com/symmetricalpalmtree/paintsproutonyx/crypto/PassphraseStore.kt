package com.symmetricalpalmtree.paintsproutonyx.crypto

import android.content.Context

/**
 * The device-local cache of the global passphrase, plus the one flag recording
 * that the artist has seen their recovery key.
 *
 * The passphrase itself is what encrypts every file; the Keystore protects only
 * this cache of it. That split is what keeps the files portable — a pulled
 * `.soil` opens in a stock sqlcipher CLI with nothing but the passphrase string,
 * while the cache that spares the artist retyping it can never leave this
 * device. Nothing in here is ever logged, and no passphrase travels anywhere
 * but this file and process RAM ([KeySession]).
 */
object PassphraseStore {
    internal const val PREFS_FILE = "paintsprout_secure"
    private const val KEY_GLOBAL = "global_passphrase"
    private const val KEY_ACK = "recovery_key_acknowledged"

    private fun prefs(context: Context) = SecurePrefs.get(context, PREFS_FILE)

    fun getGlobalPassphrase(context: Context): String? =
        prefs(context).getString(KEY_GLOBAL, null)

    fun setGlobalPassphrase(context: Context, passphrase: String) {
        prefs(context).edit().putString(KEY_GLOBAL, passphrase).apply()
    }

    fun clearGlobalPassphrase(context: Context) {
        prefs(context).edit().remove(KEY_GLOBAL).apply()
    }

    /**
     * True once the artist has ticked "I've saved it" on the recovery-key screen.
     * Kept apart from the passphrase itself: losing this flag re-shows a screen,
     * losing the passphrase loses a library.
     */
    fun isRecoveryKeyAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACK, false)

    fun setRecoveryKeyAcknowledged(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACK, true).apply()
    }
}
