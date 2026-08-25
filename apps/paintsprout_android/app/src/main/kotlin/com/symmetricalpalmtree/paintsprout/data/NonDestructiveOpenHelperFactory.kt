package com.symmetricalpalmtree.paintsprout.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Wraps an open-helper factory so that "this database is corrupt" NEVER deletes
 * the file.
 *
 * The framework's default corruption handler deletes and recreates the database.
 * That behaviour is indefensible here for one reason: **an encrypted database
 * opened with the wrong key is indistinguishable from a corrupt one.** Open a
 * sketchbook as plaintext — from a picker, a thumbnail, a probe, anything — and
 * the default handler wipes the artwork and leaves an empty file that looks like
 * a brand-new sketchbook. Notesprout lost a real notebook exactly this way.
 *
 * So every open goes through here: plaintext, passphrase, raw-key, the transient
 * opens inside migrations, and the probes. There is no path that is exempt on the
 * grounds that "this one can't hit an encrypted file" — that reasoning is what
 * produced the bug. This wrapper exists before any key does, which is why it
 * lands in Phase 2 rather than alongside the encryption.
 *
 * Every other callback is delegated unchanged.
 */
class NonDestructiveOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {

    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration,
    ): SupportSQLiteOpenHelper {
        val safe = SupportSQLiteOpenHelper.Configuration
            .builder(configuration.context)
            .name(configuration.name)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .callback(nonDestructive(configuration.callback, configuration.name))
            .build()
        return delegate.create(safe)
    }
}

/**
 * The callback wrapper, separated from the factory so it can be exercised without
 * a `Context`. Delegates everything except corruption, which throws instead of
 * deleting and leaves the file completely intact for recovery.
 */
internal fun nonDestructive(
    inner: SupportSQLiteOpenHelper.Callback,
    name: String?,
): SupportSQLiteOpenHelper.Callback = object : SupportSQLiteOpenHelper.Callback(inner.version) {

    override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)

    override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
        inner.onUpgrade(db, oldVersion, newVersion)

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
        inner.onDowngrade(db, oldVersion, newVersion)

    override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)

    override fun onCorruption(db: SupportSQLiteDatabase) {
        // Deliberately NOT calling through to the delegate: its implementation is
        // the one that deletes. Fail loudly, touch nothing.
        throw DatabaseCorruptException(name)
    }
}
