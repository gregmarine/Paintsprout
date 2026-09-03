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
 * file, sitting on top of the raw-key cache ([KeyMaterial]) — and never lets
 * Room near the file with a key that has not already been shown to fit it.
 *
 * With a cached raw key the open costs tens of milliseconds, once the key has
 * been verified against the file: a cache can go stale (the file behind this id
 * replaced, its salt with it), and a stale key is thrown away rather than
 * surfacing as a lockout against a file the artist's passphrase would happily
 * open. On a cache miss the raw key is derived **here, now** — the one KDF this
 * file will ever cost this install — verified the same way, cached, and the
 * open made with it.
 *
 * It used to be different, and the G6 audit changed it. The cold path handed
 * Room the passphrase and let SQLCipher run the KDF inside the open, while a
 * second derivation warmed the cache in the background: two KDFs for one open,
 * and the only open in the app made with a key nobody had checked. That was
 * survivable because SQLCipher's own corruption handler declines to delete
 * when a codec is present — a fact about a dependency's default, not about
 * this code (see `NonDestructiveOpenHelperFactory`). Deriving first and
 * verifying read-only costs the same wall-clock, since the KDF was being paid
 * either way; it saves the second derivation; and it makes one sentence true
 * everywhere: **a key reaches Room only after it has opened the file
 * read-only.** That is a guarantee this app can own.
 *
 * Blocking — a verify, and possibly a KDF — so call on Dispatchers.IO.
 * Logs carry file ids only, never key material.
 */
object KeyOpener {

    private const val TAG = "KeyOpener"
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Room factory for the existing encrypted [file] known as [fileId]. Throws
     * [SoilLockedException] when the file is missing or empty — this path never
     * creates anything, and must not: see [SoilCrypto] — and again when the
     * passphrase does not open the file, which is not this app's file to touch.
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
        val fresh = KeyMaterial.rawKey(context, fileId, file, passphrase)
        if (!SoilCrypto.verifyRawKey(file, fresh)) {
            // The passphrase does not open this file. The key just derived from it goes
            // straight back out of the cache, so no later open is handed it as if it were
            // known-good, and the open is refused out loud rather than passed down to Room
            // to discover the same thing as "corruption".
            KeyMaterial.invalidate(context, fileId)
            throw SoilLockedException("the key does not open ${file.name}")
        }
        Log.d(TAG, "derived-key open (cold; now cached): $fileId")
        return SoilCrypto.roomFactoryRawKey(fresh)
    }

    /**
     * Derive and cache [file]'s raw key in the background so the *next* open is
     * the fast one. Used by the create path, where the file has just been made
     * under this passphrase and there is nothing to verify. No-op when already
     * cached; never throws — a failed warm costs nothing but the speed it would
     * have bought.
     */
    fun warm(context: Context, fileId: String, file: File, passphrase: String) {
        val app = context.applicationContext
        warmScope.launch {
            runCatching { KeyMaterial.rawKey(app, fileId, file, passphrase) }
                .onFailure { Log.d(TAG, "warm failed for $fileId: ${it.message}") }
        }
    }
}
