package com.symmetricalpalmtree.paintsprout.data.backup

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.paintsprout.crypto.CryptoStores
import com.symmetricalpalmtree.paintsprout.crypto.PassphraseVault
import com.symmetricalpalmtree.paintsprout.crypto.RawKeyCache
import com.symmetricalpalmtree.paintsprout.data.DbProbe
import com.symmetricalpalmtree.paintsprout.data.DbState
import com.symmetricalpalmtree.paintsprout.data.LastOpen
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.soil.OpenDocuments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Putting a backup back, in place of everything currently here.
 *
 * This **replaces the whole library**. It is not a merge and not a per-sketchbook
 * import — importing one `.soil` is what that is for. The user is told so in
 * those words before anything happens.
 *
 * The safety model is the same one [CommitSwap][com.symmetricalpalmtree.paintsprout.data.CommitSwap]
 * uses on a single file, scaled up to the whole library: **never hold zero
 * copies.**
 *
 * ```
 * 1. fetch into cacheDir/restore_staging   ← one file failing aborts; nothing live is touched
 * 2. validate every staged file, and check there is room for the commit
 * 3. seal the index, rename the live library aside, copy the staged one in
 * 4. install the staged index LAST — it is the commit marker
 * 5. only then: drop every cached key, and delete the aside copy
 * ```
 *
 * A failure anywhere before step 3 leaves the live library open and untouched. A
 * failure *inside* step 3 rolls the aside copy back and reopens the index, so the
 * app keeps working without a restart. A process kill mid-commit is repaired by
 * [recoverInterrupted] at the next launch.
 */
object RestoreEngine {

    private const val TAG = "RestoreEngine"

    /** Where the replaced library waits while the restored one is installed. */
    private const val ASIDE_DIR = "restore_replaced"

    private const val STAGING_DIR = "restore_staging"

    /** Room for WAL growth and journals on top of the staged payload itself. */
    private const val FREE_SPACE_HEADROOM = 64L * 1024 * 1024

    sealed class Result {
        data class Success(val sketchbookCount: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun restore(
        context: Context,
        source: RestoreSource,
        deviceIndex: Int,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val staging = File(app.cacheDir, STAGING_DIR)
        val root = app.getExternalFilesDir(null)
            ?: return@withContext Result.Failed("There's no storage to restore onto.")
        val aside = File(root, ASIDE_DIR)
        var sealedIndex = false
        var movedAside = false
        var committed = false

        try {
            staging.deleteRecursively()
            staging.mkdirs()
            val stagedIndex = File(staging, SoilFiles.INDEX_FILE)
            val stagedGarden = File(staging, SoilFiles.GARDEN_DIR).apply { mkdirs() }

            // 1. Fetch. Any failure throws out of here with the library untouched.
            val count = source.fetchInto(deviceIndex, stagedIndex, stagedGarden, onProgress)

            // 2. Validate before anything live is touched. The probe rejects
            //    missing, empty, and not-a-database; an encrypted file passes,
            //    because it cannot be read any deeper without the backup's key —
            //    which the unlock after the restart is what collects.
            if (DbProbe.probe(stagedIndex) == DbState.INVALID) {
                return@withContext Result.Failed("That backup's index couldn't be read.")
            }
            val bad = stagedGarden.listFiles()
                ?.filter { it.name.endsWith(".${SoilFiles.EXTENSION}") } // sidecars aren't databases
                ?.firstOrNull { DbProbe.probe(it) == DbState.INVALID }
            if (bad != null) {
                return@withContext Result.Failed("“${bad.name}” in that backup isn't a readable sketchbook.")
            }

            // 3. Room to commit. The copy in happens while the old library still
            //    exists aside, so the staged set has to fit twice, transiently.
            val stagedBytes = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val needed = stagedBytes + FREE_SPACE_HEADROOM
            val usable = root.usableSpace
            if (usable < needed) {
                val shortMb = (needed - usable) / (1024 * 1024) + 1
                return@withContext Result.Failed(
                    "Not enough room to restore safely — free up about $shortMb MB and try again.",
                )
            }

            // 4. Commit. Close the index, move the live library aside by rename
            //    (no copies), copy the staged garden in, install the index last.
            // Flagged before the attempt, not after: a seal that threw partway may
            // still have closed the connection, and the recovery below has to know
            // to reopen it either way.
            sealedIndex = true
            runCatching { IndexGate.seal() }
                .onFailure { Log.w(TAG, "Sealing before restore failed, continuing: ${it.message}") }

            aside.deleteRecursively()
            aside.mkdirs()
            val asideGarden = File(aside, SoilFiles.GARDEN_DIR).apply { mkdirs() }
            movedAside = true

            // The index and its sidecars together — a `-wal` left behind would be
            // paired with the restored index, which is corruption.
            root.listFiles { f -> f.isFile && f.name.startsWith(SoilFiles.INDEX_FILE) }?.forEach { f ->
                if (!f.renameTo(File(aside, f.name))) throw IOException("Couldn't set ${f.name} aside.")
            }
            val garden = SoilFiles.garden(root)
            garden.listFiles()?.forEach { f ->
                if (!f.renameTo(File(asideGarden, f.name))) throw IOException("Couldn't set ${f.name} aside.")
            }
            garden.mkdirs()

            stagedGarden.listFiles()?.forEach { it.copyTo(File(garden, it.name), overwrite = true) }

            val liveIndex = SoilFiles.indexFile(root)
            val part = File("${liveIndex.absolutePath}.part")
            stagedIndex.copyTo(part, overwrite = true)
            runCatching { FileInputStream(part).use { it.fd.sync() } }
            if (!part.renameTo(liveIndex)) throw IOException("Couldn't install the restored index.")
            committed = true

            // 5. In place. Every cached key belongs to the library that just went —
            //    a stale one fails verification and looks exactly like corruption —
            //    and the pointer to "where you were" names ids from it.
            PassphraseVault(CryptoStores.secrets(app)).clearGlobal()
            RawKeyCache(CryptoStores.derivedKeys(app)).clearAll()
            OpenDocuments.clear()
            LastOpen.clear(app)
            aside.deleteRecursively()

            Result.Success(count)
        } catch (e: CancellationException) {
            // The screen went away mid-restore. The rollback is plain file work and
            // runs regardless; reopening the index is a suspending call, so it needs
            // to be told this cancellation does not apply to it.
            if (movedAside && !committed) rollBack(root, aside)
            if (sealedIndex && !committed) {
                withContext(NonCancellable) { runCatching { IndexGate.ensureReady(app) } }
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            if (movedAside && !committed) rollBack(root, aside)
            // The index was closed for the swap that then didn't happen. Reopen it
            // so the app keeps working without a restart — no key was cleared on
            // this path, so it opens silently.
            if (sealedIndex && !committed) runCatching { IndexGate.ensureReady(app) }
            Result.Failed(e.message ?: "That didn't restore.")
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Launch-time repair for a restore killed mid-commit.
     *
     * The installed index is the commit marker, which makes the two cases
     * distinguishable with no state written anywhere:
     *
     * - aside present, **no** live index → the swap never finished. Put the old
     *   library back.
     * - aside present, live index present → the commit landed and only the tidying
     *   didn't. The aside copy is the library that was replaced; drop it.
     *
     * Either way the `.part` index from the interrupted install is stale.
     */
    fun recoverInterrupted(context: Context) {
        val root = context.applicationContext.getExternalFilesDir(null) ?: return
        val aside = File(root, ASIDE_DIR)
        if (!aside.exists()) return
        val liveIndex = SoilFiles.indexFile(root)
        if (File(aside, SoilFiles.INDEX_FILE).exists() && !liveIndex.exists()) {
            Log.w(TAG, "A restore was interrupted mid-commit — putting the previous library back")
            rollBack(root, aside)
        } else {
            aside.deleteRecursively()
        }
        File("${liveIndex.absolutePath}.part").delete()
    }

    /** Moves the aside library home, over whatever was partially installed. */
    private fun rollBack(root: File, aside: File) {
        runCatching {
            val garden = SoilFiles.garden(root)
            File(aside, SoilFiles.GARDEN_DIR).listFiles()?.forEach { f ->
                val dest = File(garden, f.name)
                dest.delete()
                f.renameTo(dest)
            }
            aside.listFiles { f -> f.isFile }?.forEach { f ->
                val dest = File(root, f.name)
                dest.delete()
                f.renameTo(dest)
            }
            aside.deleteRecursively()
        }.onFailure { Log.e(TAG, "Rollback failed — the old library is still in $ASIDE_DIR", it) }
    }
}
