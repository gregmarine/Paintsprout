package com.symmetricalpalmtree.paintsprout.crypto

import com.symmetricalpalmtree.paintsprout.crypto.RawKeyDerivation.toHex
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The derive-once cache: the single place a file's SQLCipher raw key comes from.
 *
 * ```
 * process RAM  →  keystore-backed store (GLOBAL only)  →  derive + store
 * ```
 *
 * The KDF runs at most once per file per device. Every open after that is a raw
 * key — about 35 ms against 300–700 ms — which matters most for the index,
 * because that open sits on the critical path of every single launch.
 *
 * The two scopes differ in exactly one respect, and it is the whole point of
 * having two: a [KeyScope.GLOBAL] key is persisted so cold launches are fast, and
 * a [KeyScope.SKETCHBOOK] key never touches disk. The user chose a separate
 * passphrase precisely so that content is not reachable with the global key;
 * persisting its derived key would quietly undo that.
 *
 * Every method here can block on the KDF. Call from a background dispatcher.
 * Key material is never logged.
 */
class RawKeyCache(
    private val persistent: SecureStore,
    /**
     * The KDF. A parameter so a test can count how many times it actually ran —
     * "derive once" is this class's entire promise, and timing a 256,000-iteration
     * hash is a poor way to assert it.
     */
    private val derive: (File, String) -> ByteArray = RawKeyDerivation::deriveKey,
) {

    /**
     * Process-wide, not per instance.
     *
     * "The key lives in RAM until the document closes" is the whole definition of
     * a private-passphrase document, and this class is constructed wherever it is
     * needed rather than injected — so a per-instance map means the screen that
     * took the passphrase caches the key into an object it then throws away.
     * Found on device: unlocking a book succeeded and the editor bounced straight
     * back to the library, because by then the cache was empty.
     */
    private val ram get() = SHARED_RAM

    /** Raw key for a global-scope file: RAM → store → derive and persist. */
    fun global(fileId: String, file: File, passphrase: String): ByteArray {
        ram[fileId]?.let { return it }
        load(fileId)?.let { return it }
        val key = derive(file, passphrase)
        persistent.putString(fileId, key.toHex())
        ram[fileId] = key
        return key
    }

    /** Raw key for a document with its own passphrase: RAM only, gone on close. */
    fun ephemeral(fileId: String, file: File, passphrase: String): ByteArray {
        ram[fileId]?.let { return it }
        val key = derive(file, passphrase)
        ram[fileId] = key
        return key
    }

    /** What we already have in RAM, or null. Never derives. */
    fun peek(fileId: String): ByteArray? = ram[fileId]

    /**
     * What we have in RAM or on disk, or null. **Never derives** — this is how the
     * open path decides between a raw-key open and a passphrase open without
     * paying for the answer.
     */
    fun peekOrLoad(fileId: String): ByteArray? = ram[fileId] ?: load(fileId)

    /**
     * Forget a file's key entirely.
     *
     * Call after **anything that changes the file's salt** — rotation, re-key,
     * encrypt, decrypt — and on delete. A stale raw key does not fail politely: it
     * looks exactly like corruption, which is the one thing this codebase treats
     * as an emergency.
     */
    fun invalidate(fileId: String) {
        ram.remove(fileId)
        persistent.remove(fileId)
    }

    /** Drop only the RAM copy — a private document closing, with nothing persisted. */
    fun forgetRam(fileId: String) {
        ram.remove(fileId)
    }

    /** After a global rotation every salt has changed, so nothing cached survives. */
    fun clearAll() {
        ram.clear()
        persistent.clear()
    }

    private fun load(fileId: String): ByteArray? {
        val hex = persistent.getString(fileId) ?: return null
        val key = try {
            RawKeyDerivation.hexToBytes(hex)
        } catch (e: Exception) {
            // Unreadable cache entry: drop it and let the caller derive again.
            persistent.remove(fileId)
            return null
        }
        if (key.size != RawKeyDerivation.KEY_LEN) {
            persistent.remove(fileId)
            return null
        }
        ram[fileId] = key
        return key
    }

    companion object {
        /**
         * The index is one more keyed file, and it needs a stable id that is not a
         * document UUID. Synthetic, and never a valid one — see
         * `SoilFiles.isDocumentId`.
         */
        const val INDEX_FILE_ID = "__paintsprout_index__"

        /** See [ram]. One per process, which is what "in memory" has to mean. */
        private val SHARED_RAM = ConcurrentHashMap<String, ByteArray>()
    }
}
