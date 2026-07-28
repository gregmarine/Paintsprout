package com.symmetricalpalmtree.paintsprout.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One restorable backup: what to call it, and how much is in it. */
data class RestoreDevice(val name: String, val sketchbookCount: Int)

/**
 * A backup destination, read for restore.
 *
 * One instance serves both calls: [listDevices] resolves and caches the handles,
 * and [fetchInto] selects by list position — so the screen only ever deals in
 * display names, and never in a Drive file id or a `DocumentFile`.
 */
interface RestoreSource {

    /** The device folders here — each one holds an index. Empty if none, or unreachable. */
    suspend fun listDevices(): List<RestoreDevice>

    /**
     * Copies the [deviceIndex]-th device's index to [indexDest] and its documents
     * into [gardenDir], returning how many documents were copied.
     *
     * **Throws on any single failure.** A short set staged silently would be
     * committed as the whole library.
     */
    suspend fun fetchInto(
        deviceIndex: Int,
        indexDest: File,
        gardenDir: File,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Int
}

/**
 * A SAF tree the user picked — either the backup root, or one device folder
 * inside it. Both are offered, because "choose your backup folder" means
 * different things to different people.
 */
class SafRestoreSource(private val context: Context, private val treeUri: Uri) : RestoreSource {

    private var devices: List<DocumentFile> = emptyList()

    override suspend fun listDevices(): List<RestoreDevice> = withContext(Dispatchers.IO) {
        val root = SafBackupReader.treeDir(context, treeUri) ?: return@withContext emptyList()
        val dirs = mutableListOf<DocumentFile>()
        if (SafBackupReader.hasIndex(root)) dirs.add(root)
        SafBackupReader.subDirs(root).forEach { if (SafBackupReader.hasIndex(it)) dirs.add(it) }
        devices = dirs
        dirs.map { RestoreDevice(it.name ?: "Backup", SafBackupReader.soilFiles(it).size) }
    }

    override suspend fun fetchInto(
        deviceIndex: Int,
        indexDest: File,
        gardenDir: File,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val dir = devices.getOrNull(deviceIndex) ?: error("That backup is no longer there.")
        val index = dir.findFile(SafBackupReader.INDEX_NAME) ?: error("That backup has no index.")
        val all = dir.listFiles().filter { it.isFile }
        val soils = all.filter { it.name?.endsWith(".${SoilFiles.EXTENSION}") == true }
        // A sidecar exists only for a sketchbook whose WAL could not be absorbed
        // at backup time. It travels with its document or those writes are lost.
        val walsByName = all.filter { it.name?.endsWith("-wal") == true }.associateBy { it.name }

        val total = soils.size + 1
        var done = 0
        if (!SafBackupReader.copyTo(context, index, indexDest)) error("The backup's index wouldn't read.")
        onProgress(++done, total)

        for (soil in soils) {
            val name = soil.name ?: continue
            if (!SafBackupReader.copyTo(context, soil, File(gardenDir, name))) {
                error("“$name” wouldn't read from the backup — your library is untouched.")
            }
            walsByName["$name-wal"]?.let { wal ->
                if (!SafBackupReader.copyTo(context, wal, File(gardenDir, "$name-wal"))) {
                    error("“$name-wal” wouldn't read from the backup — your library is untouched.")
                }
            }
            onProgress(++done, total)
        }
        soils.size
    }
}

/** Google Drive: `My Drive / Paintsprout Backups / <device> / {paintsprout.db, *.soil}`. */
class DriveRestoreSource(private val client: DriveApiClient) : RestoreSource {

    private var devices: List<DriveEntry> = emptyList()

    override suspend fun listDevices(): List<RestoreDevice> = withContext(Dispatchers.IO) {
        val backupsId = client.findChild(ROOT_BACKUP_FOLDER, "root", foldersOnly = true)
            ?: return@withContext emptyList()
        val withIndex = client.listChildren(backupsId, foldersOnly = true).mapNotNull { folder ->
            val children = client.listChildren(folder.id, foldersOnly = false)
            if (children.none { it.name == SafBackupReader.INDEX_NAME }) return@mapNotNull null
            folder to children.count { it.name.endsWith(".${SoilFiles.EXTENSION}") }
        }
        devices = withIndex.map { it.first }
        withIndex.map { RestoreDevice(it.first.name, it.second) }
    }

    override suspend fun fetchInto(
        deviceIndex: Int,
        indexDest: File,
        gardenDir: File,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val folder = devices.getOrNull(deviceIndex) ?: error("That backup is no longer there.")
        val children = client.listChildren(folder.id, foldersOnly = false)
        val index = children.firstOrNull { it.name == SafBackupReader.INDEX_NAME }
            ?: error("That backup has no index.")
        val soils = children.filter { it.name.endsWith(".${SoilFiles.EXTENSION}") }
        val walsByName = children.filter { it.name.endsWith("-wal") }.associateBy { it.name }

        val total = soils.size + 1
        var done = 0
        if (!client.downloadTo(index.id, indexDest)) error("The backup's index wouldn't download.")
        onProgress(++done, total)

        for (soil in soils) {
            if (!client.downloadTo(soil.id, File(gardenDir, soil.name))) {
                error("“${soil.name}” wouldn't download — your library is untouched.")
            }
            walsByName["${soil.name}-wal"]?.let { wal ->
                if (!client.downloadTo(wal.id, File(gardenDir, wal.name))) {
                    error("“${wal.name}” wouldn't download — your library is untouched.")
                }
            }
            onProgress(++done, total)
        }
        soils.size
    }
}
