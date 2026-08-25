package com.symmetricalpalmtree.paintsprout.data.backup

import java.io.File

/**
 * The engine's view of the DRIVE destination: a folder path and three verbs.
 *
 * Folders are resolved **find-or-create on every run**, with no cached ids. A
 * user who deletes "Paintsprout Backups" in Drive gets it quietly recreated on
 * the next run rather than a run that fails against an id pointing at nothing.
 */
object DriveBackupWriter {

    /** `My Drive / Paintsprout Backups / <deviceFolderName>`, made if absent. */
    fun resolveDeviceFolderId(client: DriveApiClient, deviceFolderName: String): String? {
        val root = client.ensureFolder(ROOT_BACKUP_FOLDER, "root") ?: return null
        return client.ensureFolder(deviceFolderName, root)
    }

    fun resolveChildFolderId(client: DriveApiClient, parentFolderId: String, name: String): String? =
        client.ensureFolder(name, parentFolderId)

    fun replaceFile(
        client: DriveApiClient,
        deviceFolderId: String,
        fileName: String,
        source: File,
    ): Boolean = client.uploadOrReplace(fileName, deviceFolderId, source)

    /** True when [fileName] is absent afterwards — including when it never existed. */
    fun deleteFile(client: DriveApiClient, deviceFolderId: String, fileName: String): Boolean {
        val id = client.findChild(fileName, deviceFolderId, foldersOnly = false) ?: return true
        return client.delete(id)
    }
}
