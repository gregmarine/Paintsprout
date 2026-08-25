package com.symmetricalpalmtree.paintsproutonyx.crypto

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The single place a file's raw key is asked for — the derive-once cache.
 *
 * Resolution order is cheapest first: process RAM, then the Keystore-backed
 * [DerivedKeyStore], then an actual derivation (persisted on the way out).
 * Arc 1 has only the global scope, so every derived key is safe to persist.
 * A miss pays the KDF — call on a background dispatcher.
 */
object KeyMaterial {

    /**
     * The index's stable id in the raw-key cache. Sketchbooks are keyed by their
     * UUID; the underscores keep this sentinel from ever colliding with one.
     */
    const val INDEX_FILE_ID = "__paintsprout_index__"

    private val ram = ConcurrentHashMap<String, ByteArray>()

    /** The raw key for [fileId]: RAM, else Keystore, else derive against [file]'s salt and persist. */
    fun rawKey(context: Context, fileId: String, file: File, passphrase: String): ByteArray {
        ram[fileId]?.let { return it }
        DerivedKeyStore.get(context, fileId)?.let { ram[fileId] = it; return it }
        val key = RawKeyDerivation.deriveKey(file, passphrase)
        DerivedKeyStore.put(context, fileId, key)
        ram[fileId] = key
        return key
    }

    /** RAM or Keystore hit only — never derives. Null means this device has not derived it yet. */
    fun peekOrLoad(context: Context, fileId: String): ByteArray? {
        ram[fileId]?.let { return it }
        return DerivedKeyStore.get(context, fileId)?.also { ram[fileId] = it }
    }

    /**
     * Drop one file's key from BOTH tiers. Dropping only the Keystore half leaks
     * the RAM copy for the life of the process — a deleted sketchbook's key would
     * quietly outlive the sketchbook, and a swapped file would keep "opening"
     * with the old file's key and reporting itself corrupt. Paper shipped that
     * bug once so this app does not have to.
     */
    fun invalidate(context: Context, fileId: String) {
        ram.remove(fileId)
        DerivedKeyStore.remove(context, fileId)
    }

    /** Wipe every cached key — the debug "forget cached key" path, and any future rotation. */
    fun clearAll(context: Context) {
        ram.clear()
        DerivedKeyStore.clear(context)
    }
}
