package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * An [ObjectStore] over a real SQLite database, on the JVM.
 *
 * It runs the very statements [ObjectTable] runs — the same [ObjectSql] strings,
 * the same [SoilObjectMapper] — against tables built from the same [SchemaSql].
 * The only thing that differs is the driver, so what these tests exercise is the
 * repository's actual behaviour against actual SQL, not a mock's idea of it.
 */
class JdbcObjectStore(
    override val table: String = SchemaSql.SKETCHBOOK_TABLE,
    private val path: String = ":memory:",
) : ObjectStore, Closeable {

    private val sql = ObjectSql(table)
    private var connection: Connection = open()

    private fun open(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$path").also { db ->
            SchemaSql.documentTableDdl(table).forEach { stmt ->
                db.createStatement().use { it.execute(stmt) }
            }
        }

    /** Closes and reopens the file — the only honest way to test "reopen later". */
    fun reopen() {
        check(path != ":memory:") { "An in-memory database cannot survive a reopen" }
        connection.close()
        connection = open()
    }

    override fun close() = connection.close()

    // --- Reads --------------------------------------------------------------

    override fun byId(id: String): SoilObject? = queryOne(sql.byId, listOf(id))

    override fun children(parentId: String) = query(sql.liveChildren, listOf(parentId))

    override fun childrenOfType(parentId: String, type: String) =
        query(sql.liveChildrenOfType, listOf(parentId, type))

    override fun childrenOf(parentIds: Collection<String>): List<SoilObject> =
        if (parentIds.isEmpty()) emptyList()
        else query(sql.liveChildrenIn(parentIds.size), parentIds.toList())

    override fun opsBelow(layerId: String, undoDepth: Int) =
        query(sql.opsBelow, listOf(layerId, undoDepth))

    override fun opsAtOrAbove(layerId: String, undoDepth: Int) =
        query(sql.opsAtOrAbove, listOf(layerId, undoDepth))

    override fun countOps(layerId: String) = scalar(sql.countOps, listOf(layerId))

    override fun count(parentId: String) = scalar(sql.countLiveChildren, listOf(parentId))

    override fun tombstonedBefore(at: Long) = query(sql.tombstonedBefore, listOf(at))

    override fun ofType(type: String) = query(sql.ofType, listOf(type))

    // --- Writes -------------------------------------------------------------

    override fun insert(row: SoilObject) = exec(sql.insert, bind(row))

    override fun upsert(row: SoilObject) = exec(sql.upsert, bind(row))

    override fun upsertAll(rows: Iterable<SoilObject>) = rows.forEach(::upsert)

    override fun softDelete(id: String, at: Long) = exec(sql.softDelete, listOf(at, at, id))

    override fun hardDelete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        exec(sql.hardDeleteIn(ids.size), ids.toList())
    }

    override fun hardDeleteOpsAtOrAbove(layerId: String, undoDepth: Int) =
        exec(sql.hardDeleteOpsAtOrAbove, listOf(layerId, undoDepth))

    override fun <T> transaction(body: () -> T): T {
        if (!connection.autoCommit) return body() // already inside one; do not nest
        connection.autoCommit = false
        return try {
            val result = body()
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    // --- Plumbing -----------------------------------------------------------

    private fun bind(row: SoilObject): List<Any?> = SoilObjectMapper.write(row).values.toList()

    private fun exec(statement: String, args: List<Any?>) {
        connection.prepareStatement(statement).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    private fun query(statement: String, args: List<Any?>): List<SoilObject> =
        connection.prepareStatement(statement).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(SoilObjectMapper.read(ResultSetReader(rs))) }
            }
        }

    private fun queryOne(statement: String, args: List<Any?>): SoilObject? =
        query(statement, args).firstOrNull()

    private fun scalar(statement: String, args: List<Any?>): Int =
        connection.prepareStatement(statement).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private class ResultSetReader(private val rs: ResultSet) : RowReader {
        private fun <T> nullable(value: T): T? = if (rs.wasNull()) null else value
        override fun getStringOrNull(column: String) = rs.getString(column)
        override fun getIntOrNull(column: String) = nullable(rs.getInt(column))
        override fun getLongOrNull(column: String) = nullable(rs.getLong(column))
        override fun getFloatOrNull(column: String) = nullable(rs.getFloat(column))
        override fun getBlobOrNull(column: String) = rs.getBytes(column)
    }
}
