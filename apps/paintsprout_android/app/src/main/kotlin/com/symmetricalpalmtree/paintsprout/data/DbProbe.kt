package com.symmetricalpalmtree.paintsprout.data

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** What a file on disk turned out to be. */
enum class DbState {
    /** Missing, empty, or not a database at all. */
    INVALID,

    /** Opens as plain SQLite. */
    PLAINTEXT,

    /** Has no SQLite header, or has one and still won't open. Needs a key. */
    ENCRYPTED,
}

/**
 * Decides what an unknown file is, before anything tries to open it for real.
 *
 * The order of the checks is the whole design:
 *
 * 1. **Empty or missing → INVALID.** Nothing else can distinguish "no file" from
 *    "unreadable file", and the two must not be confused: for the global index,
 *    INVALID means "fresh install", which means creating an empty library. That
 *    is why [SwapRecovery] runs *before* any probe.
 * 2. **No `SQLite format 3\0` magic → ENCRYPTED.** SQLCipher encrypts the first
 *    page including the header, so the magic is simply absent. Skip this check and
 *    a plain driver will "successfully" open an encrypted file and read garbage —
 *    which the corruption handler then acts on.
 * 3. **Opens as plain SQLite and reads `sqlite_master` → PLAINTEXT.**
 * 4. **Anything else → ENCRYPTED.** A definitive encrypted-vs-damaged distinction
 *    needs the passphrase; this is the honest answer without it.
 */
object DbProbe {

    /**
     * The 16 bytes every unencrypted SQLite file starts with: the ASCII string
     * `SQLite format 3` followed by a NUL. Built from an explicit terminator byte
     * rather than an embedded one, because a raw NUL inside a source literal is
     * invisible in every editor and is the single byte this whole check turns on.
     */
    internal val MAGIC: ByteArray =
        "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()

    fun probe(file: File): DbState = probe(file, ::opensAsPlaintext)

    /** Injectable seam: tests supply their own opener, since step 3 needs a device. */
    internal fun probe(file: File, opensAsPlaintext: (File) -> Boolean): DbState {
        if (!existsAsDatabase(file)) return DbState.INVALID
        if (!hasSqliteMagic(file)) return DbState.ENCRYPTED
        return if (opensAsPlaintext(file)) DbState.PLAINTEXT else DbState.ENCRYPTED
    }

    internal fun hasSqliteMagic(file: File): Boolean {
        val head = ByteArray(MAGIC.size)
        try {
            file.inputStream().use { input ->
                var got = 0
                while (got < head.size) {
                    val n = input.read(head, got, head.size - got)
                    if (n < 0) return false // shorter than a header: not a database
                    got += n
                }
            }
        } catch (e: Exception) {
            return false
        }
        return head.contentEquals(MAGIC)
    }

    /**
     * Two flags here are load-bearing:
     *
     * - **No `CREATE_IF_NECESSARY`.** A probe that fabricates the database it was
     *   probing is the worst possible version of this function.
     * - **`OPEN_READWRITE`, never `OPEN_READONLY`.** A read-only WAL connection
     *   *recreates* `-shm` and then cannot unlink `-wal`/`-shm` when it closes,
     *   permanently stranding sidecars beside a file the user can see.
     *
     * The error handler is a no-op for the usual reason: the default one deletes.
     */
    private fun opensAsPlaintext(file: File): Boolean = try {
        val params = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE)
            .setErrorHandler(DatabaseErrorHandler { /* report nothing, delete nothing */ })
            .build()
        SQLiteDatabase.openDatabase(file, params).use { db ->
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
        true
    } catch (t: Throwable) {
        false
    }
}
