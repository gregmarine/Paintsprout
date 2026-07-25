package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The mapper, round-tripped through a real SQLite engine using the real DDL.
 *
 * Not a self-consistency check: the row is written with the mapper's own column
 * names into a table built from `SchemaSql`, so a column that exists in one and
 * not the other fails here rather than on a device. It runs against all three
 * document tables, because "the same shape in three places" is the claim the rest
 * of the storage layer is built on.
 */
class SoilObjectMapperTest {

    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        (SchemaSql.SOIL_BOOTSTRAP + SchemaSql.INDEX_BOOTSTRAP).forEach { sql ->
            db.createStatement().use { it.execute(sql) }
        }
    }

    @After
    fun close() = db.close()

    /** Every column populated, so nothing can be silently dropped. */
    private val full = SoilObject(
        id = "row-1",
        parentId = "layer-1",
        type = SoilType.STROKE,
        order = 7,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_001_000,
        deletedAt = 1_700_000_002_000,
        x = 1.5f, y = 2.5f, width = 300.25f, height = 400.75f,
        text = "Harbour — studies ✏️",
        color = "#FF1B1BB3",
        refId = "page-9",
        flags = SoilFlags.STROKE_WATER,
        seed = 9_223_372_036_854_775_806L,
        kind = "PAPER",
        params = """{"tint":16777215,"grain":0.04}""",
        tool = "WATERCOLOR",
        strokeWidth = 12.5f,
        opacity = 0.85f,
        blendMode = "NORMAL",
        undoDepth = 4,
        opCount = 11,
        amount = 2f,
        blob = byteArrayOf(0, 1, 2, 127, -128, -1),
    )

    private fun insert(table: String, row: SoilObject) {
        val values = SoilObjectMapper.write(row)
        val columns = values.keys.joinToString(", ") { "`$it`" }
        val marks = values.keys.joinToString(", ") { "?" }
        db.prepareStatement("INSERT OR REPLACE INTO `$table` ($columns) VALUES ($marks)").use { st ->
            values.values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate()
        }
    }

    private fun readBack(table: String, id: String): SoilObject? {
        val sql = ObjectSql(table).byId
        db.prepareStatement(sql).use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                return SoilObjectMapper.read(ResultSetReader(rs))
            }
        }
    }

    @Test
    fun `a fully populated row survives a round trip through every document table`() {
        for (table in SchemaSql.DOCUMENT_TABLES) {
            insert(table, full)
            assertEquals("round trip failed for $table", full, readBack(table, full.id))
        }
    }

    @Test
    fun `a sparse row keeps its nulls`() {
        val sparse = SoilObject(id = "p", parentId = "book", type = SoilType.PAGE, createdAt = 1, updatedAt = 1)
        insert(SchemaSql.SKETCHBOOK_TABLE, sparse)
        val back = readBack(SchemaSql.SKETCHBOOK_TABLE, "p")!!

        assertEquals(sparse, back)
        assertNull(back.blob)
        assertNull(back.text)
        assertTrue(back.isAlive)
    }

    /**
     * A writer that skips its nulls can only ever add information — clearing a
     * field would silently leave the old value behind.
     */
    @Test
    fun `writing null actually clears a column`() {
        insert(SchemaSql.SKETCHBOOK_TABLE, full)
        insert(SchemaSql.SKETCHBOOK_TABLE, full.copy(blob = null, text = null, deletedAt = null))

        val back = readBack(SchemaSql.SKETCHBOOK_TABLE, full.id)!!
        assertNull(back.blob)
        assertNull(back.text)
        assertNull(back.deletedAt)
        assertTrue("the rest is untouched", back.color == full.color && back.seed == full.seed)
    }

    /** The mapper's columns and the schema's columns are the same set. */
    @Test
    fun `every mapped column exists in the table, and every column is mapped`() {
        val inTable = db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`${SchemaSql.SKETCHBOOK_TABLE}`)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
        }
        assertEquals(inTable, SoilObjectMapper.COLUMNS)
    }

    /** Binary geometry is the dominant payload; a byte lost here is ink lost. */
    @Test
    fun `blobs come back byte for byte`() {
        val bytes = ByteArray(4096) { (it * 31 % 251).toByte() }
        insert(SchemaSql.SKETCHBOOK_TABLE, full.copy(id = "big", blob = bytes))
        assertTrue(bytes.contentEquals(readBack(SchemaSql.SKETCHBOOK_TABLE, "big")!!.blob))
    }

    @Test
    fun `an empty blob is not the same as no blob`() {
        insert(SchemaSql.SKETCHBOOK_TABLE, full.copy(id = "empty", blob = ByteArray(0)))
        insert(SchemaSql.SKETCHBOOK_TABLE, full.copy(id = "none", blob = null))
        assertEquals(0, readBack(SchemaSql.SKETCHBOOK_TABLE, "empty")!!.blob!!.size)
        assertNull(readBack(SchemaSql.SKETCHBOOK_TABLE, "none")!!.blob)
    }

    /** `order` is a reserved word, and it is in every read this layer performs. */
    @Test
    fun `order survives being a reserved word`() {
        insert(SchemaSql.SKETCHBOOK_TABLE, full.copy(id = "ordered", order = 42))
        assertEquals(42, readBack(SchemaSql.SKETCHBOOK_TABLE, "ordered")!!.order)
    }

    /** A seed is a full 64-bit value; truncating it re-rolls the artwork's texture. */
    @Test
    fun `a large seed is not truncated`() {
        assertEquals(full.seed, readBack(SchemaSql.SKETCHBOOK_TABLE, full.id).let {
            insert(SchemaSql.SKETCHBOOK_TABLE, full); readBack(SchemaSql.SKETCHBOOK_TABLE, full.id)!!.seed
        })
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
