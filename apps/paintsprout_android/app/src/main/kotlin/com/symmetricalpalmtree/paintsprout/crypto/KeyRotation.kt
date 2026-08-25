package com.symmetricalpalmtree.paintsprout.crypto

import android.content.Context
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.soil.OpenDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Changing the passphrase the whole library is keyed with.
 *
 * Every global-scope document and the index itself have to be converted, one
 * file at a time, and the run **will** be interrupted at some point — so the
 * whole design is about what a half-finished rotation looks like from a cold
 * start. Three rules carry it:
 *
 * 1. **The marker is written before the first file is touched.** It holds the
 *    passphrase being rotated *to*; losing that mid-run leaves documents keyed to
 *    something nobody knows. It lives in the secure store beside the global
 *    passphrase itself, never in plaintext prefs.
 * 2. **Every file is verify-then-skip** ([RotationPlan]): a document the
 *    interrupted run already converted is recognised, not converted again — which
 *    would fail, because the old key no longer opens it.
 * 3. **The cached global passphrase changes last**, only once nothing is pending.
 *    Until then the old key is still the library's key, which is what lets an
 *    interrupted rotation resume from a normal launch instead of a rescue path.
 *
 * The index is converted **after** every document, for the same reason: while it
 * is still on the old key, a cold start opens the library normally and this can
 * simply carry on.
 */
object KeyRotation {

    class Outcome(val converted: Int, val skipped: Int, val quarantined: List<String>)

    /** Whether a rotation is part-done and should be resumed before anything reads. */
    fun isPending(context: Context): Boolean = target(context) != null

    /**
     * The passphrase a half-finished rotation was heading for.
     *
     * [IndexGate] needs this: if the run got as far as the index before it was
     * interrupted, the library is already on the new key and the cached one will
     * not open it.
     */
    fun target(context: Context): String? =
        CryptoStores.secrets(context).getString(KEY_TARGET)

    /**
     * Rotates every global-scope document and then the index.
     *
     * Safe to call when a rotation is already part-done: [resume] is this with the
     * marker that is already there.
     */
    suspend fun start(context: Context, newPassphrase: String): Outcome = withContext(Dispatchers.IO) {
        val secrets = CryptoStores.secrets(context)
        val old = PassphraseVault(secrets).globalOrNull()
            ?: throw IOException("There is no global key to rotate")
        if (newPassphrase == old) throw IOException("That is already the key")
        if (OpenDocuments.ids().isNotEmpty()) {
            throw IOException("Refusing to rotate while a document is open")
        }

        // Everything to do, written down before anything is done.
        val ids = SoilFiles.listDocuments(SoilFiles.storageRoot(context))
            .mapNotNull(SoilFiles::documentIdOf)
        secrets.putString(KEY_TARGET, newPassphrase)
        secrets.putString(KEY_PENDING, RotationPlan.encode(ids))
        secrets.putString(KEY_QUARANTINE, "")
        run(context, old, newPassphrase)
    }

    /** Picks up an interrupted rotation. No-op when there is nothing pending. */
    suspend fun resume(context: Context): Outcome? = withContext(Dispatchers.IO) {
        val secrets = CryptoStores.secrets(context)
        val to = secrets.getString(KEY_TARGET) ?: return@withContext null
        val from = PassphraseVault(secrets).globalOrNull() ?: return@withContext null
        run(context, from, to)
    }

    private fun run(context: Context, from: String, to: String): Outcome {
        val secrets = CryptoStores.secrets(context)
        val keys = RawKeyCache(CryptoStores.derivedKeys(context))
        var progress = RotationPlan.Progress(
            pending = RotationPlan.decode(secrets.getString(KEY_PENDING)),
            quarantined = RotationPlan.decode(secrets.getString(KEY_QUARANTINE)),
        )
        var converted = 0
        var skipped = 0

        for (id in progress.pending.toList()) {
            val file = SoilFiles.soilFile(context, id)
            progress = when (step(file, from, to)) {
                RotationPlan.Verdict.CONVERT -> {
                    converted++
                    // The derived key is of the old passphrase and is now wrong;
                    // a cached key that no longer opens its file is
                    // indistinguishable from corruption at the next open.
                    keys.invalidate(id)
                    progress.done(id)
                }

                RotationPlan.Verdict.SKIP -> {
                    skipped++
                    progress.done(id)
                }

                RotationPlan.Verdict.QUARANTINE -> progress.quarantine(id)
            }
            secrets.putString(KEY_PENDING, RotationPlan.encode(progress.pending))
            secrets.putString(KEY_QUARANTINE, RotationPlan.encode(progress.quarantined))
        }

        // The index last: while it is still on the old key a cold start opens the
        // library normally, which is what makes an interrupted run resumable
        // rather than a rescue.
        val index = SoilFiles.indexFile(context)
        if (step(index, from, to) == RotationPlan.Verdict.CONVERT) {
            keys.invalidate(RawKeyCache.INDEX_FILE_ID)
        }

        // Only now is the new passphrase the library's, and only now is the
        // marker gone. Both in that order: a crash between them resumes and finds
        // nothing left to do.
        PassphraseVault(secrets).setGlobal(to)
        secrets.remove(KEY_TARGET)
        secrets.remove(KEY_PENDING)
        return Outcome(converted, skipped, progress.quarantined)
    }

    /**
     * One file. Never throws for a file it could not convert — a rotation that
     * stops at the first oddity leaves the library in two keys, which is worse
     * than one document set aside and reported.
     */
    private fun step(file: File, from: String, to: String): RotationPlan.Verdict {
        if (!file.exists()) return RotationPlan.Verdict.SKIP
        val fromKeying = ReKey.Keying.of(from)
        val toKeying = ReKey.Keying.of(to)
        val verdict = RotationPlan.verdictFor(
            opensWithOld = ReKey.opens(file, fromKeying),
            opensWithNew = ReKey.opens(file, toKeying),
        )
        if (verdict != RotationPlan.Verdict.CONVERT) return verdict
        return runCatching { ReKey.convert(file, fromKeying, toKeying) }
            .fold(onSuccess = { RotationPlan.Verdict.CONVERT }, onFailure = { RotationPlan.Verdict.QUARANTINE })
    }

    /** Documents the last rotation could not open with either key. */
    fun quarantined(context: Context): List<String> =
        RotationPlan.decode(CryptoStores.secrets(context).getString(KEY_QUARANTINE))

    fun clearQuarantine(context: Context) = CryptoStores.secrets(context).remove(KEY_QUARANTINE)

    private const val KEY_TARGET = "rotation_target"
    private const val KEY_PENDING = "rotation_pending"
    private const val KEY_QUARANTINE = "rotation_quarantine"
}
