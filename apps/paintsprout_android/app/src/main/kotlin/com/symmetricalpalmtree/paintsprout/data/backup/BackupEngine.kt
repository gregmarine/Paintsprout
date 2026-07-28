package com.symmetricalpalmtree.paintsprout.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.symmetricalpalmtree.paintsprout.BuildConfig
import com.symmetricalpalmtree.paintsprout.crypto.CryptoStores
import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.crypto.RawKeyCache
import com.symmetricalpalmtree.paintsprout.crypto.SoilCrypto
import com.symmetricalpalmtree.paintsprout.data.DbProbe
import com.symmetricalpalmtree.paintsprout.data.DbState
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.IndexRepository
import com.symmetricalpalmtree.paintsprout.data.soil.OpenDocuments
import com.symmetricalpalmtree.paintsprout.data.soil.SketchbookStore
import com.symmetricalpalmtree.paintsprout.data.soil.SoilDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One backup run.
 *
 * Copies every sketchbook that has changed since it last landed, plus the whole
 * index, to every enabled destination. Manual only — the user presses a button —
 * and incremental by timestamp, which is what keeps a run over a library of
 * paintings from being a full re-upload every time.
 *
 * Three orderings in here are load-bearing:
 *
 * 1. **Destinations fail on their own.** A Drive folder that cannot be resolved
 *    records its error and drops out of the run; the local copy still goes. A
 *    backup where one of two destinations is broken is still a backup.
 * 2. **Compact, then copy.** Each sketchbook in the work list is opened and
 *    sealed once first, which is also what absorbs its WAL — so the `.soil` alone
 *    is a complete copy. Compaction preserves `updatedAt`, so a book stays
 *    flagged and is sent in its now-smaller form.
 * 3. **The index goes last.** After every per-sketchbook stamp has been written,
 *    so the backed-up index describes the run that just finished rather than the
 *    one before it.
 */
object BackupEngine {

    private const val TAG = "BackupEngine"

    /**
     * Debug builds write into a `dev/` subfolder at each destination, so a
     * development device pointed at the same folder as a real one cannot
     * overwrite real backups.
     */
    private const val DEV_SUBFOLDER = "dev"

    suspend fun run(
        context: Context,
        repo: IndexRepository,
        config: BackupConfig,
        onProgress: (current: Int, total: Int, label: String) -> Unit,
    ): BackupResult = withContext(Dispatchers.IO) {
        val runStart = System.currentTimeMillis()
        val results = mutableMapOf<BackupKind, DestResult>()

        // --- Resolve LOCAL --------------------------------------------------

        var localDir: DocumentFile? = null
        if (config.localEnabled && config.localTreeUri != null) {
            localDir = try {
                SafBackupWriter.rootDir(context, Uri.parse(config.localTreeUri))
            } catch (e: Exception) {
                null
            }
            if (localDir == null) {
                results[BackupKind.LOCAL] = failedDestination(
                    "That folder is no longer reachable. Choose it again in Backup.",
                )
            } else if (BuildConfig.DEBUG) {
                localDir = SafBackupWriter.ensureChildDir(localDir, DEV_SUBFOLDER) ?: run {
                    results[BackupKind.LOCAL] =
                        failedDestination("Couldn't make the dev/ folder in the backup folder.")
                    null
                }
            }
        }

        // --- Resolve DRIVE --------------------------------------------------

        var driveClient: DriveApiClient? = null
        var driveFolderId: String? = null
        if (config.driveEnabled && config.driveAccountEmail != null) {
            when (val token = DriveAuth.getAccessTokenSilent(context)) {
                is DriveAuth.TokenResult.Token -> {
                    val client = DriveApiClient(token.accessToken)
                    val folderId = DriveBackupWriter.resolveDeviceFolderId(client, config.deviceFolderName)
                    if (folderId == null) {
                        results[BackupKind.DRIVE] = failedDestination(
                            "Couldn't reach the Google Drive backup folder. Check the connection and try again.",
                        )
                    } else {
                        driveClient = client
                        driveFolderId = if (BuildConfig.DEBUG) {
                            DriveBackupWriter.resolveChildFolderId(client, folderId, DEV_SUBFOLDER) ?: run {
                                results[BackupKind.DRIVE] = failedDestination(
                                    "Couldn't make the dev/ folder in Google Drive.",
                                )
                                null
                            }
                        } else {
                            folderId
                        }
                        if (driveFolderId == null) driveClient = null
                    }
                }

                is DriveAuth.TokenResult.Error -> {
                    results[BackupKind.DRIVE] =
                        failedDestination("Reconnect Google Drive in Backup: ${token.message}")
                }
            }
        }

        // --- The work list --------------------------------------------------

        data class Work(val id: String, val name: String, val kind: BackupKind)

        val work = mutableListOf<Work>()
        if (localDir != null) {
            repo.sketchbooksNeedingBackup(BackupKind.LOCAL)
                .forEach { work.add(Work(it.id, it.name, BackupKind.LOCAL)) }
        }
        if (driveClient != null) {
            repo.sketchbooksNeedingBackup(BackupKind.DRIVE)
                .forEach { work.add(Work(it.id, it.name, BackupKind.DRIVE)) }
        }

        // --- Compact, once per sketchbook, whatever it is going ---------------
        //
        // Unique across destinations: a book bound for both is opened once. The
        // ids that were actually opened are remembered, because that open/close is
        // also what folds the WAL back in — the ones that could not be opened keep
        // whatever an abnormal exit left them, and their sidecar travels below.

        val toCompact = work.map { it.id to it.name }.distinctBy { it.first }
        val walAbsorbed = mutableSetOf<String>()
        toCompact.forEachIndexed { i, (id, name) ->
            onProgress(i + 1, toCompact.size, name)
            if (compactInPlace(context, repo, id)) walAbsorbed.add(id)
        }

        // --- The copies -------------------------------------------------------

        var localAttempted = 0
        var localSucceeded = 0
        var localFailed = 0
        var localSkipped = 0
        val localErrors = mutableListOf<String>()

        var driveAttempted = 0
        var driveSucceeded = 0
        var driveFailed = 0
        var driveSkipped = 0
        val driveErrors = mutableListOf<String>()

        work.forEachIndexed { i, item ->
            onProgress(i + 1, work.size, item.name)
            val soil = SoilFiles.soilFile(context, item.id)
            if (!soil.exists()) {
                // A card with no file. Not a failed copy — nothing was copied — and
                // saying "failed" would send the user looking at their storage.
                Log.w(TAG, "No file for sketchbook ${item.id} — skipping")
                if (item.kind == BackupKind.LOCAL) localSkipped++ else driveSkipped++
                return@forEachIndexed
            }

            // A book that could not be opened may hold committed writes in its
            // `-wal`. That sidecar travels with the `.soil` and BOTH must land
            // before the book is stamped backed-up. When the WAL *was* absorbed,
            // any stale sidecar at the destination is deleted instead: a fresh
            // `.soil` paired with somebody's old `-wal` is corruption on restore.
            val wal = File("${soil.absolutePath}-wal")
            val needsWal = item.id !in walAbsorbed && wal.exists() && wal.length() > 0L
            val soilName = "${item.id}.${SoilFiles.EXTENSION}"
            val walName = "$soilName-wal"

            when (item.kind) {
                BackupKind.LOCAL -> {
                    localAttempted++
                    val dir = localDir!!
                    val ok = SafBackupWriter.replaceFile(context, dir, soilName, soil) &&
                        if (needsWal) {
                            SafBackupWriter.replaceFile(context, dir, walName, wal)
                        } else {
                            SafBackupWriter.deleteFile(dir, walName)
                        }
                    if (ok) {
                        localSucceeded++
                        repo.markBackedUp(item.id, BackupKind.LOCAL, runStart)
                    } else {
                        localFailed++
                        localErrors.add("“${item.name}” didn't copy to the local folder.")
                    }
                }

                BackupKind.DRIVE -> {
                    driveAttempted++
                    val client = driveClient!!
                    val folder = driveFolderId!!
                    val ok = DriveBackupWriter.replaceFile(client, folder, soilName, soil) &&
                        if (needsWal) {
                            DriveBackupWriter.replaceFile(client, folder, walName, wal)
                        } else {
                            DriveBackupWriter.deleteFile(client, folder, walName)
                        }
                    if (ok) {
                        driveSucceeded++
                        repo.markBackedUp(item.id, BackupKind.DRIVE, runStart)
                    } else {
                        driveFailed++
                        driveErrors.add("“${item.name}” didn't copy to Google Drive.")
                    }
                }
            }
        }

        // --- The index, last --------------------------------------------------

        IndexGate.checkpointAndVacuum()
        val indexFile = SoilFiles.indexFile(context)

        // Snapshotted locally first. The index stays open across the (slow,
        // especially over the network) destination writes, and a concurrent write
        // plus an auto-checkpoint could tear a copy streamed straight from the live
        // file. The local copy's window is milliseconds, and the probe rejects a
        // torn snapshot before it can replace a good backup.
        val snapshot = File(context.cacheDir, "backup_index_snapshot.db")
        val indexSource = runCatching {
            indexFile.copyTo(snapshot, overwrite = true)
            check(DbProbe.probe(snapshot) != DbState.INVALID)
            snapshot
        }.getOrElse {
            Log.w(TAG, "Index snapshot failed — streaming the live file: ${it.message}")
            indexFile
        }

        var localIndexCopied = false
        if (localDir != null) {
            localIndexCopied =
                SafBackupWriter.replaceFile(context, localDir, SoilFiles.INDEX_FILE, indexSource)
            if (!localIndexCopied) localErrors.add("The library index didn't copy to the local folder.")
        }

        var driveIndexCopied = false
        if (driveClient != null && driveFolderId != null) {
            driveIndexCopied = DriveBackupWriter.replaceFile(
                driveClient, driveFolderId, SoilFiles.INDEX_FILE, indexSource,
            )
            if (!driveIndexCopied) driveErrors.add("The library index didn't copy to Google Drive.")
        }
        snapshot.delete()

        // --- Finish -----------------------------------------------------------

        if (localDir != null) {
            results[BackupKind.LOCAL] = DestResult(
                localAttempted, localSucceeded, localFailed, localSkipped, localIndexCopied, localErrors,
            )
        }
        if (driveClient != null) {
            results[BackupKind.DRIVE] = DestResult(
                driveAttempted, driveSucceeded, driveFailed, driveSkipped, driveIndexCopied, driveErrors,
            )
        }

        // "Last backup: just now" only when something actually landed. A run where
        // every destination failed used to say it anyway, which is the one lie a
        // backup screen must never tell.
        if (results.values.any { it.indexCopied || it.succeeded > 0 }) {
            repo.saveBackupConfig(config.copy(lastRunAt = runStart))
        }

        BackupResult(results)
    }

    private fun failedDestination(message: String) =
        DestResult(0, 0, 0, 0, indexCopied = false, errors = listOf(message))

    /**
     * Opens and seals one sketchbook, so the bytes about to be copied are its
     * leanest form.
     *
     * The seal is the same one a document gets on close: purge tombstones from
     * prior sessions, drop stale raster caches (75–88% of a document, measured),
     * `VACUUM` only if something went, then truncate the WAL and close. None of it
     * touches the index, so `updatedAt` does not move — reclaiming space must not
     * make a painting look edited.
     *
     * Every failure is swallowed. This is an optimisation, never a reason to skip
     * the copy that follows. Returns true when the file was actually opened, which
     * is the caller's signal that its WAL is now absorbed.
     */
    private suspend fun compactInPlace(context: Context, repo: IndexRepository, id: String): Boolean {
        return try {
            val row = repo.byId(id) ?: return false
            val file = SoilFiles.soilFile(context, id)
            if (!file.exists()) return false
            // A book the editor is holding open would be sealed out from under it.
            if (OpenDocuments.isOpen(id)) return false

            val soil = when {
                // Not encrypted at all: opened with no key, and only ever like this.
                !row.isEncrypted -> SoilDatabase.open(
                    context, file, id, SoilCrypto.plaintextFactory(),
                )

                // Its own passphrase. Openable unattended only if this session
                // already unlocked it, which puts the derived key in RAM; otherwise
                // it goes as it stands, with its sidecar.
                row.isPrivateScope -> {
                    val cached = RawKeyCache(CryptoStores.derivedKeys(context)).peek(id)
                        ?: return false
                    SoilDatabase.open(context, file, id, SoilCrypto.roomFactoryRawKey(cached))
                }

                else -> SketchbookStore.open(context, id, KeyScope.GLOBAL)
            }
            soil.seal()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pre-backup compaction failed for $id — backing it up as it is: ${e.message}")
            false
        }
    }
}
