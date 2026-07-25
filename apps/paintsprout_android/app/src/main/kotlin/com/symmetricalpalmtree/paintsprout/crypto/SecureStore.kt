package com.symmetricalpalmtree.paintsprout.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * A small key/value store for secrets.
 *
 * An interface rather than a concrete type for one reason: everything built on it
 * — the passphrase vault, the raw-key cache, the attempt limiter — is then
 * ordinary logic that can be tested on the JVM, instead of logic welded to a
 * platform keystore that only exists on a device.
 *
 * Whatever implements it must be encrypted at rest. Nothing here is ever logged.
 */
interface SecureStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun remove(key: String)
    fun keys(): Set<String>
    fun clear()
}

/** [SecureStore] over a keystore-backed preference file. */
class AndroidSecureStore(private val prefs: SharedPreferences) : SecureStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun keys(): Set<String> = prefs.all.keys.toSet()
    override fun clear() = prefs.edit().clear().apply()
}

/**
 * Builds and caches the app's encrypted preference files.
 *
 * Two rules, both paid for elsewhere in this family:
 *
 * - **One lock, one cached instance per file.** Two threads first-creating the
 *   same keystore-backed preference file is a known crash-and-corruption mode,
 *   and these stores are touched from independent threads — bootstrap, key
 *   warm-up, unlock prompts. Serialising creation removes the race rather than
 *   narrowing it.
 * - **One retry.** The platform keystore throws transiently right after a device
 *   boots, before the user has unlocked the device for the first time. A single
 *   retry absorbs that; a persistent failure still propagates, because a crypto
 *   store that silently isn't there is worse than a crash.
 *
 * The files are kept separate on purpose: clearing the derived-key cache (after a
 * re-key, say) must not be able to disturb the passphrase that would let us
 * rebuild it.
 */
object CryptoStores {

    /** The global passphrase, rotation markers, and failed-attempt counters. */
    private const val SECRETS_FILE = "paintsprout_secure"

    /** The 32-byte SQLCipher raw keys for global-scope files, keyed by file id. */
    private const val DERIVED_KEYS_FILE = "paintsprout_keys"

    private val cache = mutableMapOf<String, SecureStore>()

    /** Secrets: the global passphrase and the attempt counters. */
    fun secrets(context: Context): SecureStore = store(context, SECRETS_FILE)

    /** Derived raw keys. Cleared wholesale on rotation; never holds a passphrase. */
    fun derivedKeys(context: Context): SecureStore = store(context, DERIVED_KEYS_FILE)

    private fun store(context: Context, fileName: String): SecureStore = synchronized(cache) {
        cache.getOrPut(fileName) {
            val prefs = try {
                create(context, fileName)
            } catch (e: Exception) {
                // Transient keystore failure — see the class comment. Deliberately
                // not logged with any detail: this path is one line away from key
                // material.
                Thread.sleep(150)
                create(context, fileName)
            }
            AndroidSecureStore(prefs)
        }
    }

    private fun create(context: Context, fileName: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            fileName,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
