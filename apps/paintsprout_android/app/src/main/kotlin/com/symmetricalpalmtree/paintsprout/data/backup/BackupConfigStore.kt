package com.symmetricalpalmtree.paintsprout.data.backup

import com.symmetricalpalmtree.paintsprout.data.index.IndexDao
import com.symmetricalpalmtree.paintsprout.data.index.IndexObject
import com.symmetricalpalmtree.paintsprout.data.index.IndexType
import com.symmetricalpalmtree.paintsprout.data.index.Sentinels

/**
 * The backup settings, as one row in the index.
 *
 * A singleton at a sentinel id holding JSON in `params`, which is the one place
 * this index keeps a JSON bag — the settings are a single low-traffic blob
 * nothing queries into, and a column per field would be a dozen columns every
 * other row leaves NULL.
 *
 * Reached only through `IndexRepository`, which is what keeps "one writer" true.
 * Unreadable JSON degrades to "no config", never to a crash: the recovery from
 * that is one screen and a folder picker, and the alternative is a library that
 * won't open because of a settings row.
 */
object BackupConfigStore {

    suspend fun read(dao: IndexDao): BackupConfig? {
        val row = dao.byId(Sentinels.BACKUP_CONFIG_ID) ?: return null
        if (!row.isAlive) return null
        val json = row.params ?: return null
        return try {
            BackupConfig.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun write(dao: IndexDao, config: BackupConfig) {
        val now = System.currentTimeMillis()
        val existing = dao.byId(Sentinels.BACKUP_CONFIG_ID)
        dao.upsert(
            IndexObject(
                id = Sentinels.BACKUP_CONFIG_ID,
                type = IndexType.BACKUP_CONFIG,
                name = "Backup",
                parentId = null,
                // When it was configured, not when it was last touched: a settings
                // row that keeps its original createdAt is the honest one.
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                params = config.toJson(),
            ),
        )
    }

    /** Reads, or writes a fresh default and returns that. */
    suspend fun ensure(dao: IndexDao, defaultDeviceFolderName: String): BackupConfig {
        read(dao)?.let { return it }
        val fresh = BackupConfig.newDefault(defaultDeviceFolderName)
        write(dao, fresh)
        return fresh
    }
}
