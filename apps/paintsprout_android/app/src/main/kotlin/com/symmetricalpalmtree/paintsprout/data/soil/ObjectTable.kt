package com.symmetricalpalmtree.paintsprout.data.soil

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
 * Every statement comes from [ObjectSql], so the tests execute exactly what the
 * app executes — and every identifier there is backtick-quoted, because `order`
 * is a reserved word and it appears in the one index every content read uses.
 */
class ObjectTable(
    private val db: SupportSQLiteDatabase,
    override val table: String,
) : ObjectStore {

    private val sql = ObjectSql(table)

    // --- Reads --------------------------------------------------------------

    override fun byId(id: String): SoilObject? = queryOne(sql.byId, arrayOf(id))

    override fun children(parentId: String): List<SoilObject> =
        query(sql.liveChildren, arrayOf(parentId))

    override fun childrenOfType(parentId: String, type: String): List<SoilObject> =
        query(sql.liveChildrenOfType, arrayOf(parentId, type))

    override fun childrenOf(parentIds: Collection<String>): List<SoilObject> {
        if (parentIds.isEmpty()) return emptyList()
        return query(sql.liveChildrenIn(parentIds.size), parentIds.toTypedArray())
    }

    override fun opsBelow(layerId: String, undoDepth: Int): List<SoilObject> =
        query(sql.opsBelow, arrayOf(layerId, undoDepth))

    override fun opsAtOrAbove(layerId: String, undoDepth: Int): List<SoilObject> =
        query(sql.opsAtOrAbove, arrayOf(layerId, undoDepth))

    override fun countOps(layerId: String): Int = scalar(sql.countOps, arrayOf(layerId))

    override fun count(parentId: String): Int = scalar(sql.countLiveChildren, arrayOf(parentId))

    // --- Writes -------------------------------------------------------------

    override fun insert(row: SoilObject) = db.execSQL(sql.insert, bind(row))

    override fun upsert(row: SoilObject) = db.execSQL(sql.upsert, bind(row))

    override fun upsertAll(rows: Iterable<SoilObject>) = rows.forEach(::upsert)

    override fun softDelete(id: String, at: Long) =
        db.execSQL(sql.softDelete, arrayOf<Any?>(at, at, id))

    override fun hardDelete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.execSQL(sql.hardDeleteIn(ids.size), ids.toTypedArray())
    }

    override fun hardDeleteOpsAtOrAbove(layerId: String, undoDepth: Int) =
        db.execSQL(sql.hardDeleteOpsAtOrAbove, arrayOf<Any?>(layerId, undoDepth))

    override fun <T> transaction(body: () -> T): T {
        db.beginTransaction()
        return try {
            val result = body()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    // --- Plumbing -----------------------------------------------------------

    private fun bind(row: SoilObject): Array<Any?> =
        SoilObjectMapper.write(row).values.toTypedArray()

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

    private fun scalar(statement: String, args: Array<*>): Int =
        db.query(statement, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Adapts a platform [Cursor] to the mapper's column-name view. */
    private class CursorReader(private val cursor: Cursor) : RowReader {
        private fun present(column: String): Int? =
            cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }

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

    private val columnList = SoilObjectMapper.COLUMNS.joinToString(", ") { "`$it`" }
    private val marks = SoilObjectMapper.COLUMNS.joinToString(", ") { "?" }

    val byId = "SELECT $columnList FROM `$table` WHERE `id` = ?"

    val liveChildren =
        "SELECT $columnList FROM `$table` WHERE `parentId` = ? AND `deletedAt` IS NULL " +
            "ORDER BY `order`"

    val liveChildrenOfType =
        "SELECT $columnList FROM `$table` WHERE `parentId` = ? AND `type` = ? " +
            "AND `deletedAt` IS NULL ORDER BY `order`"

    fun liveChildrenIn(count: Int) =
        "SELECT $columnList FROM `$table` WHERE `parentId` IN (${placeholders(count)}) " +
            "AND `deletedAt` IS NULL ORDER BY `parentId`, `order`"

    /**
     * `order >= 0` is what keeps the raster cache row — which sits at
     * [CACHE_ORDER] and is not an op — out of every history read.
     */
    val opsBelow =
        "SELECT $columnList FROM `$table` WHERE `parentId` = ? AND `deletedAt` IS NULL " +
            "AND `order` >= 0 AND `order` < ? ORDER BY `order`"

    val opsAtOrAbove =
        "SELECT $columnList FROM `$table` WHERE `parentId` = ? AND `deletedAt` IS NULL " +
            "AND `order` >= ? ORDER BY `order`"

    val countOps =
        "SELECT COUNT(*) FROM `$table` WHERE `parentId` = ? AND `deletedAt` IS NULL AND `order` >= 0"

    val countLiveChildren =
        "SELECT COUNT(*) FROM `$table` WHERE `parentId` = ? AND `deletedAt` IS NULL"

    val insert = "INSERT INTO `$table` ($columnList) VALUES ($marks)"

    /** Full-row replace, so a cleared field really clears. */
    val upsert = "INSERT OR REPLACE INTO `$table` ($columnList) VALUES ($marks)"

    /** Deleting is a modification, so it moves `updatedAt` too. */
    val softDelete = "UPDATE `$table` SET `deletedAt` = ?, `updatedAt` = ? WHERE `id` = ?"

    fun hardDeleteIn(count: Int) = "DELETE FROM `$table` WHERE `id` IN (${placeholders(count)})"

    val hardDeleteOpsAtOrAbove =
        "DELETE FROM `$table` WHERE `parentId` = ? AND `order` >= ?"

    private fun placeholders(count: Int) = List(count) { "?" }.joinToString(", ")
}
