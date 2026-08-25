package com.symmetricalpalmtree.paintsprout.data.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.WalConfig
import java.io.File

/**
 * The global index: one per install, open for the whole app lifetime.
 *
 * Room owns `objects` and `sketchbook_activity`. The two document-shaped tables —
 * `scratchpad` and `clipboard` — are created from [SchemaSql] instead, because
 * they are column-for-column the `.soil` object table and are read and written by
 * the same table-name-parameterised code. Room ignores tables it has no entity
 * for, which is exactly the behaviour wanted: one definition, used by both
 * databases.
 *
 * **This class does not decide how to open itself.** It takes a factory, so the
 * key resolution, the probe, the interrupted-swap repair and the unlock prompt
 * all live in the bootstrap that Phase 5 builds. What it does guarantee is that
 * whatever factory arrives has been wrapped non-destructively — see
 * [SoilCrypto][com.symmetricalpalmtree.paintsprout.crypto.SoilCrypto], which is
 * the only place such a factory is made.
 */
@Database(
    entities = [IndexObject::class, ActivityRow::class],
    version = SchemaSql.INDEX_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class IndexDatabase : RoomDatabase() {

    abstract fun objects(): IndexDao

    abstract fun activity(): ActivityDao

    companion object {

        /**
         * Opens (creating if absent) the index at [file] with [factory].
         *
         * Creation is legitimate here — this is the index's one bootstrap path, and
         * the caller has already probed and repaired. Every *other* open in the app
         * is exists-guarded.
         */
        fun open(
            context: Context,
            file: File,
            factory: SupportSQLiteOpenHelper.Factory,
        ): IndexDatabase = Room.databaseBuilder(
            context.applicationContext,
            IndexDatabase::class.java,
            file.absolutePath,
        )
            .openHelperFactory(factory)
            .addCallback(Bootstrap)
            .addMigrations(MIGRATION_1_2)
            // No fallbackToDestructiveMigration, ever. A schema Room doesn't
            // recognise must be an error the user can be told about, not a library
            // that quietly empties itself.
            .build()

        /**
         * The backup columns, added to an index that predates them.
         *
         * Additive DDL and nothing else — the statements live in [SchemaSql] so
         * the migration and the fresh-install bootstrap cannot describe different
         * tables. A row already here keeps NULL in all three, which reads as
         * "never backed up, not excluded": exactly right for a library the first
         * backup run has not seen yet.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SchemaSql.INDEX_BACKUP_COLUMNS_DDL.forEach(db::execSQL)
            }
        }

        private object Bootstrap : RoomDatabase.Callback() {

            override fun onCreate(db: SupportSQLiteDatabase) {
                createDocumentTables(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Idempotent, and repeated on every open on purpose: a database
                // created by an older build, or restored from elsewhere, must still
                // end up with the tables this build expects.
                createDocumentTables(db)

                // wal_autocheckpoint is connection-level and is not persisted in the
                // file, so this belongs on every open rather than at creation.
                //
                // auto_vacuum only takes effect on an empty database or after a full
                // VACUUM; the compaction pass issues that one, and until then the
                // setting is simply inert rather than wrong.
                WalConfig.applyOnOpen(db)
            }

            private fun createDocumentTables(db: SupportSQLiteDatabase) {
                for (table in listOf(SchemaSql.SCRATCHPAD_TABLE, SchemaSql.CLIPBOARD_TABLE)) {
                    SchemaSql.documentTableDdl(table).forEach(db::execSQL)
                }
            }
        }
    }
}
