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

        /** Made and dropped in the same breath so the header can be written. See [stampAutoVacuum]. */
        private const val VACUUM_SEED = "__vacuum_seed"

        /** What `PRAGMA auto_vacuum` answers when INCREMENTAL took. 0 is NONE, 1 is FULL. */
        private const val AUTO_VACUUM_INCREMENTAL = 2

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
            stampAutoVacuum(file, passphrase)
            val db = build(context, file, SoilCrypto.roomFactory(passphrase))
            forceOpen(db)
            // The file has a salt now, so the raw key can be cached and every later open of this
            // sketchbook skips the derivation.
            KeyOpener.warm(context, sketchbookId, file, passphrase)
            return db
        }

        /**
         * Decide, once and for all, that this sketchbook *can* reclaim its own space — before Room has
         * ever seen the file.
         *
         * `auto_vacuum` is not a setting, it is a fact about the file's very first page. SQLite
         * accepts the pragma only while the database has no tables in it; after that it is silently
         * ignored, and the only way to change the answer is a full VACUUM, which on a sketchbook full
         * of drawings means rewriting the whole encrypted file. So the moment a sketchbook is made is
         * the only moment this can be chosen at all, and that moment is here.
         *
         * The seed table is the awkward part and it earns its place. The pragma alone changes nothing
         * on disk, because an empty database has no page one to record it on; it is the first table
         * that fixes the header. So one is made and immediately dropped, leaving a file that is still
         * empty, still at `user_version` 0 — so Room takes its ordinary create path and builds the
         * real schema — and now carries the flag for the rest of its life.
         *
         * What it buys is the *possibility* of shrinking, and only that — INCREMENTAL reclaims nothing
         * on its own, it keeps a free-page list so that a later `PRAGMA incremental_vacuum` can hand
         * pages back cheaply instead of rewriting the whole encrypted file. Nothing runs that yet,
         * because nothing yet frees pages: **G4 is the first phase that tears a page out of a
         * sketchbook, and it owes the `incremental_vacuum` step.** The stamp has to be here anyway,
         * a phase early, because by the time G4 needs it the file is long past the only moment it
         * could have been chosen — a sketchbook made today and drawn in for a month cannot be given
         * this later without a full VACUUM.
         *
         * The earlier version of this app put the pragma in Room's `onCreate` callback, which runs
         * *after* Room has created the tables — so it was ignored on every file ever made, and read as
         * a solved problem for as long as nobody checked. That is why it is spelled out at length here
         * rather than being a line somebody could move back.
         */
        private fun stampAutoVacuum(file: File, passphrase: String) {
            val raw = SoilCrypto.createRaw(file, passphrase)
            try {
                raw.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
                raw.execSQL("CREATE TABLE $VACUUM_SEED (x)")
                raw.execSQL("DROP TABLE $VACUUM_SEED")
                // Read it straight back. The whole trap this replaces was a pragma that ran, returned
                // no error, and did nothing — so the only honest way to know it worked is to ask the
                // file what it thinks it is. A warning rather than a throw: a sketchbook that does not
                // reclaim its own space is a sketchbook, and refusing to make one over a housekeeping
                // setting would be losing the drawing to save the file.
                val mode = raw.rawQuery("PRAGMA auto_vacuum", null).use {
                    if (it.moveToFirst()) it.getInt(0) else -1
                }
                if (mode != AUTO_VACUUM_INCREMENTAL) {
                    Log.w(TAG, "${file.name} was made with auto_vacuum=$mode, not INCREMENTAL")
                }
            } finally {
                runCatching { raw.close() }
            }
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
                // `auto_vacuum` deliberately does NOT live here. It was here once and did nothing at
                // all, because SQLite accepts that pragma only while the file has no tables and Room
                // runs this callback after making them. It now happens in [stampAutoVacuum], before
                // Room ever opens the file, which is the only moment it can work — see the argument
                // there before moving it back.
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
