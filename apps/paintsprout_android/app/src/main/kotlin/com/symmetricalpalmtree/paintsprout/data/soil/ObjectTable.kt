package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.ContentValues
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Reads and writes a document object table, whichever one it is.
 *
 * Parameterised on the table name because the sketchbook file, the scratchpad and
 * the clipboard are the same table in three places. That is what turns "send this
 * page to the scratchpad" and "paste into another book" into the same code path
 * with a different string.
 *
 * Every statement is built here rather than scattered, and every identifier is
 * backtick-quoted — `order` is a SQLite reserved word, and it appears in the one
 * index every content read uses.
 */
class ObjectTable(private val db: SupportSQLiteDatabase, val table: String) {

    private val sql = ObjectSql(table)

    // --- Reads --------------------------------------------------------------

    fun byId(id: String): SoilObject? = queryOne(sql.byId, arrayOf(id))

    /** Live children, in sibling order. The shape of essentially every read. */
    fun children(parentId: String): List<SoilObject> = query(sql.liveChildren, arrayOf(parentId))

    fun childrenOfType(parentId: String, type: String): List<SoilObject> =
        query(sql.liveChildrenOfType, arrayOf(parentId, type))

    /**
     * One query for a whole level. Loading a page's ops and then their
     * attachments is two round trips, not one per row.
     */
    fun childrenOf(parentIds: Collection<String>): List<SoilObject> {
        if (parentIds.isEmpty()) return emptyList()
        return query(sql.liveChildrenIn(parentIds.size), parentIds.toTypedArray())
    }

    /** A layer's committed ops: `order` below its undo frontier, cache row excluded. */
    fun opsBelow(layerId: String, undoDepth: Int): List<SoilObject> =
        query(sql.opsBelow, arrayOf(layerId, undoDepth))

    /** A layer's undone ops — the redo stack, oldest first. */
    fun opsAtOrAbove(layerId: String, undoDepth: Int): List<SoilObject> =
        query(sql.opsAtOrAbove, arrayOf(layerId, undoDepth))

    fun count(parentId: String): Int =
        db.query(sql.countLiveChildren, arrayOf<Any?>(parentId)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    // --- Writes -------------------------------------------------------------

    fun insert(row: SoilObject) {
        db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values(row))
    }

    fun insertAll(rows: Iterable<SoilObject>) = rows.forEach(::insert)

    /** Full-row replace; every column is written, so a cleared field really clears. */
    fun upsert(row: SoilObject) {
        db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, values(row))
    }

    fun upsertAll(rows: Iterable<SoilObject>) = rows.forEach(::upsert)

    fun softDelete(id: String, at: Long) {
        db.execSQL(sql.softDelete, arrayOf<Any?>(at, at, id))
    }

    /**
     * Really gone. Reserved for the seal's purge of rows tombstoned in *prior*
     * sessions, and for dropping a redo tail that a new op has overwritten —
     * never for anything the user might still want back.
     */
    fun hardDelete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.execSQL(sql.hardDeleteIn(ids.size), ids.toTypedArray())
    }

    /** Drops a layer's undone ops. Called when a new op truncates the redo stack. */
    fun hardDeleteOpsAtOrAbove(layerId: String, undoDepth: Int) {
        db.execSQL(sql.hardDeleteOpsAtOrAbove, arrayOf<Any?>(layerId, undoDepth))
    }

    fun nextOrder(parentId: String): Int = Subtrees.nextOrder(children(parentId))

    // --- Plumbing -----------------------------------------------------------

    private fun query(statement: String, args: Array<*>): List<SoilObject> =
        db.query(statement, args).use { cursor ->
            buildList {
                val reader = CursorReader(cursor)
                while (cursor.moveToNext()) add(SoilObjectMapper.read(reader))
            }
        }

    private fun queryOne(statement: String, args: Array<*>): SoilObject? =
        db.query(statement, args).use { cursor ->
            if (!cursor.moveToFirst()) null else SoilObjectMapper.read(CursorReader(cursor))
        }

    private fun values(row: SoilObject): ContentValues = ContentValues().apply {
        for ((column, value) in SoilObjectMapper.write(row)) {
            when (value) {
                null -> putNull(column)
                is String -> put(column, value)
                is Int -> put(column, value)
                is Long -> put(column, value)
                is Float -> put(column, value)
                is ByteArray -> put(column, value)
                else -> error("Unmappable value for $column: ${value::class}")
            }
        }
    }

    /** Adapts a platform [Cursor] to the mapper's column-name view. */
    private class CursorReader(private val cursor: Cursor) : RowReader {
        private fun index(column: String) = cursor.getColumnIndex(column)
        private fun present(column: String): Int? =
            index(column).takeIf { it >= 0 && !cursor.isNull(it) }

        override fun getStringOrNull(column: String) = present(column)?.let(cursor::getString)
        override fun getIntOrNull(column: String) = present(column)?.let(cursor::getInt)
        override fun getLongOrNull(column: String) = present(column)?.let(cursor::getLong)
        override fun getFloatOrNull(column: String) = present(column)?.let(cursor::getFloat)
        override fun getBlobOrNull(column: String) = present(column)?.let(cursor::getBlob)
    }
}

/**
 * The statements, built for one table.
 *
 * Separate from [ObjectTable] so tests can execute exactly these strings against
 * a real SQLite engine without a device — the queries and the schema then cannot
 * drift apart without something failing.
 */
class ObjectSql(private val table: String) {

    private val columns = SoilObjectMapper.COLUMNS.joinToString(", ") { "`$it`" }

    val byId = "SELECT $columns FROM `$table` WHERE `id` = ?"

    val liveChildren =
        "SELECT $columns FROM `$table` WHERE `parentId` = ? AND `deletedAt` IS NULL " +
            "ORDER BY `order`"

    val liveChildrenOfType =
        "SELECT $columns FROM `$table` WHERE `parentId` = ? AND `type` = ? " +
            "AND `deletedAt` IS NULL ORDER BY `order`"

    fun liveChildrenIn(count: Int) =
        "SELECT $columns FROM `$table` WHERE `parentId` IN (${placeholders(count)}) " +
            "AND `deletedAt` IS NULL ORDER BY `parentId`, `order`"

    /**
     * A layer's committed ops. `order >= 0` is what keeps the raster cache row —
     * which sits at -1 and is not an op — out of every history read.
     */
    val opsBelow =
        "SELECT $columns FROM `$table` WHERE `parentId` = ? AND `deletedAt` IS NULL " +
            "AND `order` >= 0 AND `order` < ? ORDER BY `order`"

    val opsAtOrAbove =
        "SELECT $columns FROM `$table` WHERE `parentId` = ? AND `deletedAt` IS NULL " +
            "AND `order` >= ? ORDER BY `order`"

    val countLiveChildren =
        "SELECT COUNT(*) FROM `$table` WHERE `parentId` = ? AND `deletedAt` IS NULL"

    /** Deleting is a modification, so it moves `updatedAt` too. */
    val softDelete = "UPDATE `$table` SET `deletedAt` = ?, `updatedAt` = ? WHERE `id` = ?"

    fun hardDeleteIn(count: Int) = "DELETE FROM `$table` WHERE `id` IN (${placeholders(count)})"

    val hardDeleteOpsAtOrAbove =
        "DELETE FROM `$table` WHERE `parentId` = ? AND `order` >= ?"

    private fun placeholders(count: Int) = List(count) { "?" }.joinToString(", ")
}
