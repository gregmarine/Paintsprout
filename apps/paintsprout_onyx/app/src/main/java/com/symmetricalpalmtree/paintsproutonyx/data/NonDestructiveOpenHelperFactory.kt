package com.symmetricalpalmtree.paintsproutonyx.data

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Wraps a [SupportSQLiteOpenHelper.Factory] so that "this database is corrupt"
 * can NEVER delete the file — and, since the G6 audit, says honestly which part
 * of that promise this class keeps and which part something else does.
 *
 * Room's stock helper (`FrameworkSQLiteOpenHelper`) answers corruption by calling
 * the androidx [SupportSQLiteOpenHelper.Callback.onCorruption], whose default
 * implementation deletes the database and starts over — reasonable for a cache,
 * catastrophic for an encrypted sketchbook, because opening an encrypted file
 * with the wrong key makes SQLite report ciphertext as corruption. Notesprout
 * lost a notebook exactly this way, once. This wrapper's override of that
 * callback logs and throws instead, and every factory in this app is wrapped
 * (see [com.symmetricalpalmtree.paintsproutonyx.crypto.SoilCrypto]).
 *
 * **Under SQLCipher the override is never reached, and the audit read the
 * bytecode to find out why.** Every factory here delegates to SQLCipher's own
 * `SupportOpenHelperFactory`, and its `SupportHelper` (4.6.1) builds an inner
 * `SQLiteOpenHelper` that forwards onCreate / onUpgrade / onDowngrade / onOpen /
 * onConfigure to the androidx callback and **passes `null` as its
 * `DatabaseErrorHandler`** — it never calls the callback's `onCorruption` at
 * all. A null handler means SQLCipher's `DefaultDatabaseErrorHandler`, and that
 * handler's first act after logging is `if (SQLiteDatabase.hasCodec()) return;`
 * — with the codec present it deletes nothing, on the grounds that "corrupt"
 * almost always means "wrong key". That guard is what actually spares a file
 * from a mis-keyed open, and G1's md5 of a file across a wrong-key attempt is
 * the measurement of it.
 *
 * Why keep the wrapper, then? Because it costs nothing, and the protection it
 * duplicates is a default inside a dependency. The day a factory here is not
 * SQLCipher's — a plaintext Room open for a fixture, a library swap, a cache —
 * it is `FrameworkSQLiteOpenHelper` that answers corruption, and without this
 * wrapper that answer is delete. So the wrapper is the belt and the codec guard
 * is the braces, and the rule that means neither has to be trusted lives in
 * `KeyOpener` and `PaintsproutIndex`: **Room is never handed a key that has not
 * first been verified read-only against the file.** A key that fits cannot
 * report corruption, whichever handler happens to be on duty.
 *
 * If SQLCipher is ever bumped, re-read `SupportHelper`'s constructor and
 * `DefaultDatabaseErrorHandler.onCorruption` before trusting any of this — the
 * claim above is about 4.6.1 and says nothing about a later build.
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
                // DO NOT delete. A wrong-key open of an encrypted file would land here
                // under Room's stock helper; deleting would destroy the drawings. Fail
                // loudly, leave the file. (SQLCipher's helper never calls this — see the
                // class comment — so a log line from here means the factory underneath
                // has changed, which is itself worth knowing.)
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
