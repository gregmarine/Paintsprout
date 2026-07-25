package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.WalConfig
import com.symmetricalpalmtree.paintsprout.data.requireExistingDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * One open `.soil` document.
 *
 * Unlike the index, this is **not** a Room database, and the reason is the format
 * rather than convenience. A `.soil` is portable: it can arrive from another
 * device, from an older build, or from another app in the family — and a single
 * file is explicitly allowed to carry more than one app's object table. Room's
 * open path validates the file against its entity definitions and rejects
 * anything it doesn't recognise, which is the correct instinct for a database we
 * own outright and the wrong one for a document somebody hands us.
 *
 * So the index keeps Room (device-local, entirely ours) and the document opens
 * through `SupportSQLiteOpenHelper` directly, which still gives us the
 * `user_version` lifecycle — create, upgrade, downgrade — without the validation.
 * The schema comes from [SchemaSql] either way, and the same drift test covers it.
 *
 * Creation is a separate, explicitly named entry point. Everything else requires
 * the file to already exist.
 */
class SoilDatabase private constructor(
    val documentId: String,
    val file: File,
    private val helper: SupportSQLiteOpenHelper,
) : Closeable {

    val db: SupportSQLiteDatabase get() = helper.writableDatabase

    // --- The identity record ------------------------------------------------

    fun readMeta(): NotebookMeta? =
        db.query("SELECT json FROM ${SchemaSql.META_TABLE} WHERE id = 0").use { cursor ->
            if (!cursor.moveToFirst()) return null
            // A damaged record must degrade to "no metadata", never to an
            // unopenable document — the content is what matters and it is fine.
            runCatching { SoilJson.decodeFromString<NotebookMeta>(cursor.getString(0)) }.getOrNull()
        }

    fun writeMeta(meta: NotebookMeta) {
        db.execSQL(
            "INSERT OR REPLACE INTO ${SchemaSql.META_TABLE} (id, json) VALUES (0, ?)",
            arrayOf(SoilJson.encodeToString(NotebookMeta.serializer(), meta)),
        )
    }

    /** Which content tables this file actually carries. */
    fun tables(): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    // --- Closing ------------------------------------------------------------

    /**
     * The seal: everything that must happen before the file goes cold.
     *
     * Each step is guarded on its own, because a disk-full failure seconds after
     * the user left a page must not crash the app or skip the checkpoint. The
     * caller runs this on an application-scoped, non-cancellable coroutine so it
     * survives the screen going away — see Phase 13.
     *
     * Content flushing, lazy-format compaction and purging tombstones from *prior*
     * sessions belong in this sequence too, and arrive with the phases that create
     * something to flush, convert or purge.
     */
    fun seal(refresh: (NotebookMeta) -> NotebookMeta = { it }) {
        // Baked in before the file goes cold, which is what lets export stay a
        // prompt-free copy: the embedded metadata is already current.
        runCatching { readMeta()?.let { writeMeta(refresh(it)) } }
        runCatching { WalConfig.seal(db) }
        runCatching { helper.close() }
        // SQLite removes -wal and -shm itself when the last connection closes;
        // deleting them by hand while a connection is open corrupts its view. Only
        // a stray rollback journal is ours to sweep.
        runCatching { File(file.path + "-journal").delete() }
        OpenDocuments.unregister(documentId)
    }

    override fun close() = seal()

    /** Sidecars still on disk. Should be empty after a clean [seal]. */
    fun strandedSidecars(): List<File> = SoilFiles.sidecars(file).filter { it.exists() }

    companion object {

        /**
         * Brings a new document into existence. **The only path allowed to.**
         *
         * Every other open is exists-guarded, because the underlying open is
         * create-if-missing and a fabricated empty database masquerades as the real
         * document — and, when encrypted, "verifies" any passphrase it is given.
         */
        fun create(
            context: Context,
            file: File,
            documentId: String,
            factory: SupportSQLiteOpenHelper.Factory,
            meta: NotebookMeta,
        ): SoilDatabase {
            if (file.exists()) throw IOException("Refusing to overwrite ${file.name}")
            val soil = SoilDatabase(documentId, file, helper(context, file, factory))
            soil.db // force creation, so onCreate runs the bootstrap here
            soil.writeMeta(meta)
            OpenDocuments.register(documentId)
            return soil
        }

        /** Opens an existing document. Fails loudly if it is missing or empty. */
        fun open(
            context: Context,
            file: File,
            documentId: String,
            factory: SupportSQLiteOpenHelper.Factory,
        ): SoilDatabase {
            requireExistingDatabase(file)
            val soil = SoilDatabase(documentId, file, helper(context, file, factory))
            soil.db // surface a bad key here, not at the first read
            OpenDocuments.register(documentId)
            return soil
        }

        private fun helper(
            context: Context,
            file: File,
            factory: SupportSQLiteOpenHelper.Factory,
        ): SupportSQLiteOpenHelper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context.applicationContext)
                .name(file.absolutePath)
                .callback(Schema)
                .build(),
        )

        /**
         * The schema lifecycle, driven by `user_version` exactly as the platform's
         * own open helper does: version 0 means brand new, higher means upgrade,
         * lower means the file came from a build that knows more than we do.
         */
        private object Schema : SupportSQLiteOpenHelper.Callback(SchemaSql.SOIL_SCHEMA_VERSION) {

            override fun onCreate(db: SupportSQLiteDatabase) {
                SchemaSql.SOIL_BOOTSTRAP.forEach(db::execSQL)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // Additive DDL only, and never a row rewrite: a migration runs on
                // the critical path of opening a page. There is nothing to do at v1.
            }

            /**
             * A file stamped **newer** than this build understands.
             *
             * Refuse it. The platform's default would throw anyway, but the reason
             * matters: the alternative — carrying on — ends with this build writing
             * its own lower version number over the file's, so the newer build that
             * created it would then try to "upgrade" a file it had already written.
             */
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                throw IOException(
                    "This file was written by a newer version of Paintsprout " +
                        "(format $oldVersion, this build reads $newVersion). It has not been changed.",
                )
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Deliberately NOT re-running the bootstrap here. A file may
                // legitimately belong to another app in the family, and quietly
                // adding our tables to a document we were only asked to open is
                // exactly the "never touch a table you don't own" line.
                WalConfig.applyOnOpen(db)
            }
        }
    }
}
