package com.symmetricalpalmtree.paintsproutonyx.data

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Wraps a [SupportSQLiteOpenHelper.Factory] so that "this database is corrupt"
 * can NEVER delete the file.
 *
 * Room's default corruption handler deletes the database and starts over —
 * reasonable for a cache, catastrophic for an encrypted sketchbook. Opening an
 * encrypted file with the wrong key makes SQLite read ciphertext as corruption,
 * so with the default handler a mis-keyed open would wipe a perfectly healthy
 * library and leave an empty file wearing its name. Notesprout lost a notebook
 * exactly this way, once, which is why this wrapper is not optional here:
 * every Room open in this app goes through it (see
 * [com.symmetricalpalmtree.paintsproutonyx.crypto.SoilCrypto]).
 *
 * [SupportSQLiteOpenHelper.Callback.onCorruption] logs and throws. Every other
 * callback is delegated unchanged.
 */
class NonDestructiveOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val inner = configuration.callback
        val safeCallback = object : SupportSQLiteOpenHelper.Callback(inner.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                inner.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                inner.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                // DO NOT delete. A wrong-key open of an encrypted file lands here;
                // deleting would destroy the drawings. Fail loudly, leave the file.
                Log.e(TAG, "Corruption reported on open — refusing to delete, file left intact")
                throw SQLiteDatabaseCorruptException(
                    "Database reported corruption on open; refusing to delete the file"
                )
            }
        }
        val safeConfig = SupportSQLiteOpenHelper.Configuration
            .builder(configuration.context)
            .name(configuration.name)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .callback(safeCallback)
            .build()
        return delegate.create(safeConfig)
    }

    private companion object {
        const val TAG = "NonDestructiveOpen"
    }
}
