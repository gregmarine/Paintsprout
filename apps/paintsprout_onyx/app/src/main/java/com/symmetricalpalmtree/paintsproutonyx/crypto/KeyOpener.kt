package com.symmetricalpalmtree.paintsproutonyx.crypto

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Picks the fastest open that is still correct for an **existing** encrypted
 * file, sitting on top of the raw-key cache ([KeyMaterial]).
 *
 * With a cached raw key the open costs tens of milliseconds — but the key is
 * verified against the file first, because a cache can go stale: if the file
 * behind this id was ever replaced, its salt changed and the cached key fits
 * nothing. A stale key is invalidated and the passphrase path taken, rather
 * than surfacing as a lockout against a file the artist's passphrase would
 * happily open. On a cache miss the passphrase opens this connection (SQLCipher
 * runs its own KDF once) while the raw key derives in the background, so the
 * slow open is paid at most once per file per install.
 *
 * Blocking — the verify, and possibly a KDF — so call on Dispatchers.IO.
 * Logs carry file ids only, never key material.
 */
object KeyOpener {

    private const val TAG = "KeyOpener"
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Room factory for the existing encrypted [file] known as [fileId]. Throws
     * [SoilLockedException] when the file is missing or empty — this path never
     * creates anything, and must not: see [SoilCrypto].
     */
    fun roomFactoryFor(
        context: Context,
        fileId: String,
        file: File,
        passphrase: String,
    ): SupportSQLiteOpenHelper.Factory {
        SoilCrypto.requireExisting(file)
        val cached = KeyMaterial.peekOrLoad(context, fileId)
        if (cached != null) {
            if (SoilCrypto.verifyRawKey(file, cached)) {
                Log.d(TAG, "raw-key open: $fileId")
                return SoilCrypto.roomFactoryRawKey(cached)
            }
            Log.d(TAG, "cached raw key stale for $fileId — invalidating")
            KeyMaterial.invalidate(context, fileId)
        }
        warm(context, fileId, file, passphrase)
        Log.d(TAG, "passphrase open (cold; warming raw key): $fileId")
        return SoilCrypto.roomFactory(passphrase)
    }

    /**
     * Derive and cache [file]'s raw key in the background so the *next* open is
     * the fast one. No-op when already cached; never throws — a failed warm
     * costs nothing but the speed it would have bought.
     */
    fun warm(context: Context, fileId: String, file: File, passphrase: String) {
        val app = context.applicationContext
        warmScope.launch {
            runCatching { KeyMaterial.rawKey(app, fileId, file, passphrase) }
                .onFailure { Log.d(TAG, "warm failed for $fileId: ${it.message}") }
        }
    }
}
