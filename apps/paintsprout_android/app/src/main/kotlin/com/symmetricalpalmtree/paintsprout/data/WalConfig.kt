package com.symmetricalpalmtree.paintsprout.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The WAL discipline every database in the app opens and closes under.
 *
 * Two rules here were bugs before they were rules:
 *
 * 1. **`wal_autocheckpoint` is connection-level and is not persisted in the
 *    file.** It must be re-applied on *every* open, not once at creation.
 * 2. **A PRAGMA that returns a result set must be run as a query whose cursor is
 *    actually stepped.** `execSQL` silently does not run them — the statement
 *    appears to succeed and nothing happens. Every statement here goes through
 *    [step] for exactly that reason, including the ones that arguably don't need
 *    it, because the day someone adds one that does is the day the exception
 *    becomes the rule.
 *
 * The payoff is the no-stray-files rule: a file browser must show sketchbooks and
 * nothing else — never a `-wal`, `-shm` or `-journal`. The one documented
 * exception is the global index, which is open for the whole app lifetime and so
 * has no clean-close moment at which to remove them.
 */
object WalConfig {

    /**
     * Applied on every open.
     *
     * `auto_vacuum = INCREMENTAL` only takes effect on a database that has it set
     * before its first content, or after a full `VACUUM`; issuing it on every open
     * is harmless and means a file created by any path ends up configured.
     */
    val OPEN_PRAGMAS: List<String> = listOf(
        "PRAGMA journal_mode = WAL",
        "PRAGMA wal_autocheckpoint = 100",
        "PRAGMA auto_vacuum = INCREMENTAL",
    )

    /**
     * Applied on a clean close, after the content is flushed.
     *
     * `incremental_vacuum` returns freed pages to the file; the truncating
     * checkpoint folds the WAL back in and shrinks it to nothing so the sidecars
     * can be removed. Order matters — vacuum first, then checkpoint, then close.
     */
    val SEAL_PRAGMAS: List<String> = listOf(
        "PRAGMA incremental_vacuum",
        "PRAGMA wal_checkpoint(TRUNCATE)",
    )

    fun applyOnOpen(db: SupportSQLiteDatabase) = applyOnOpen { step(db, it) }

    fun seal(db: SupportSQLiteDatabase) = seal { step(db, it) }

    /** Testable seams: the app passes a real connection, tests pass a recorder. */
    internal fun applyOnOpen(exec: (String) -> Unit) = OPEN_PRAGMAS.forEach(exec)

    internal fun seal(exec: (String) -> Unit) = SEAL_PRAGMAS.forEach(exec)

    /**
     * Runs one PRAGMA and consumes its first row. Never `execSQL` — see the class
     * comment; and never leave the cursor unstepped, which is the same bug wearing
     * a different hat.
     */
    private fun step(db: SupportSQLiteDatabase, sql: String) {
        db.query(sql).use { it.moveToFirst() }
    }
}
