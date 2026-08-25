package com.symmetricalpalmtree.paintsproutonyx.data.soil

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeyOpener
import com.symmetricalpalmtree.paintsproutonyx.crypto.SoilCrypto
import java.io.File

/**
 * One open sketchbook.
 *
 * **Never a singleton.** The session that opened the sketchbook owns this instance and seals it when
 * the sketchbook closes. A process-wide one would mean a sketchbook still holding a write connection
 * long after the artist put it down, and a second sketchbook opened over the top of the first.
 *
 * There are exactly two ways in, and the difference between them is the whole point:
 *
 *  - [open] requires the file to be **already there**. A missing file is a failure to be reported, not
 *    a blank sketchbook to be made — the one thing a drawing app must never do is answer "I cannot
 *    find your work" by handing over an empty page that looks just like it.
 *  - [create] is the new-sketchbook path and nothing else, and it refuses a file that already has
 *    anything in it.
 *
 * Leave through [seal].
 */
@Database(entities = [SoilObjectEntity::class], version = SoilSchema.SOIL_VERSION, exportSchema = true)
abstract class SoilDatabase : RoomDatabase() {

    abstract fun dao(): SoilDao

    /** The raw connection, for `sketchbook_meta` and the PRAGMAs Room has no opinion about. */
    fun raw(): SupportSQLiteDatabase = openHelper.writableDatabase

    companion object {
        private const val TAG = "SoilDatabase"

        /**
         * Open a sketchbook that exists. Throws `SoilLockedException` if it does not.
         *
         * The key comes through [KeyOpener], which uses the cached raw key when it fits this
         * particular file and falls back to the passphrase when it does not. Deriving the key from the
         * passphrase costs a quarter of a million hash rounds — once, at first launch, that is
         * invisible; on every sketchbook open it is a pause between tapping a cover and seeing a page.
         *
         * IO thread.
         */
        fun open(context: Context, sketchbookId: String, file: File, passphrase: String): SoilDatabase {
            SoilCrypto.requireExisting(file)
            val factory = KeyOpener.roomFactoryFor(context, sketchbookId, file, passphrase)
            return build(context, file, factory).also { forceOpen(it) }
        }

        /**
         * Make a new sketchbook file with its schema in place, encrypted from its first byte.
         *
         * The guard is not defensive tidiness. This is the only create-capable open in the app, and
         * pointed at a path that already holds a sketchbook it would put an empty one over the top.
         *
         * IO thread.
         */
        fun create(context: Context, sketchbookId: String, file: File, passphrase: String): SoilDatabase {
            require(!file.exists() || file.length() == 0L) {
                "refusing to create a sketchbook over a file that already exists: ${file.name}"
            }
            file.parentFile?.mkdirs()
            val db = build(context, file, SoilCrypto.roomFactory(passphrase))
            forceOpen(db)
            // The file has a salt now, so the raw key can be cached and every later open of this
            // sketchbook skips the derivation.
            KeyOpener.warm(context, sketchbookId, file, passphrase)
            return db
        }

        private fun build(
            context: Context,
            file: File,
            factory: SupportSQLiteOpenHelper.Factory,
        ): SoilDatabase =
            Room.databaseBuilder(context.applicationContext, SoilDatabase::class.java, file.absolutePath)
                .openHelperFactory(factory)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(openCallback())
                .build()

        /**
         * Room would not touch the file until the first query, which would push the create — and any
         * failure of it — out into whatever screen read first. A trivial PRAGMA makes the open happen
         * where the caller is still in a position to answer for it.
         */
        private fun forceOpen(db: SoilDatabase) {
            db.raw().query("PRAGMA user_version").use { it.moveToFirst() }
        }

        private fun openCallback(): Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(SoilSchema.CREATE_META)
                // There was an `auto_vacuum = INCREMENTAL` here, and it was doing nothing at all.
                //
                // SQLite will only accept that pragma while the file has no tables yet; afterwards
                // it is silently ignored unless a full VACUUM follows. Room runs this callback
                // *after* it has created the tables, so the pragma arrived too late every single
                // time and the file stayed at auto_vacuum = NONE. It read as a solved problem for
                // as long as nobody checked, which is the worst state for a line of code to be in.
                //
                // The consequence is real but not urgent: a sketchbook that has had pages torn out
                // of it keeps those pages' bytes until something vacuums the file. Nothing in arc 1
                // deletes at that scale — G4 is the first phase that removes a page at all — and
                // choosing it properly means setting it on the file at creation, before Room opens
                // it, which belongs with the new-sketchbook flow in G2 rather than here. Left out
                // rather than left in and inert, so the next reader is not told a lie by a line
                // that runs.
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Also on open, for a file made by a build that predates this table — see
                // SketchbookMetaStore.write for the same argument at more length.
                db.execSQL(SoilSchema.CREATE_META)
                // Connection settings, not file settings: they last exactly as long as this handle.
                db.query("PRAGMA wal_autocheckpoint = 100").use { it.moveToFirst() }
                db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() }
            }
        }
    }

    /**
     * Put the sketchbook away.
     *
     * Fold the write-ahead log back into the file, close, and clear up an empty stray journal. The
     * fold is what makes the closed file the whole sketchbook: leave marks sitting in a `-wal`
     * alongside it and the file is only most of the drawing, which matters the moment it is copied
     * anywhere by anything that does not know to take the sidecars too.
     *
     * Every step is guarded and this never throws. By the time it runs the artist has already put the
     * sketchbook down and moved on, and a failure to tidy up is not worth a crash on the way out. The
     * meta row is refreshed by the caller before this, because refreshing it needs the index and this
     * does not.
     *
     * IO thread.
     */
    fun seal(file: File) {
        try {
            raw().query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "could not fold the write-ahead log back into ${file.name}", e)
        }
        try {
            close()
        } catch (e: Exception) {
            Log.w(TAG, "could not close ${file.name}", e)
        }
        // Only an empty one. A journal with anything in it is a rollback somebody still needs.
        val journal = File(file.path + "-journal")
        if (journal.exists() && journal.length() == 0L) journal.delete()
    }
}
