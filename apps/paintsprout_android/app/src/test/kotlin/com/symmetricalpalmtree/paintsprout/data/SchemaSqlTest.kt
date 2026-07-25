package com.symmetricalpalmtree.paintsprout.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The schema constants are executed against a real SQLite engine here, not just
 * inspected as strings. Every site that creates these tables must produce a
 * schema that validates identically; the cheapest way to keep that true is to
 * run the statements on every build.
 */
class SchemaSqlTest {

    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun rows(sql: String): List<Map<String, String?>> =
        db.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val out = mutableListOf<Map<String, String?>>()
                val meta = rs.metaData
                while (rs.next()) {
                    out += (1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getString(it) }
                }
                out
            }
        }

    private fun columns(table: String) = rows("PRAGMA table_info(`$table`)")

    private fun indexNames(table: String) =
        rows("PRAGMA index_list(`$table`)").mapNotNull { it["name"] }.filterNot {
            it.startsWith("sqlite_autoindex")
        }

    // --- Bootstraps run -----------------------------------------------------

    @Test
    fun `soil bootstrap creates the sketchbook and meta tables`() {
        SchemaSql.SOIL_BOOTSTRAP.forEach(::exec)
        val tables = rows("SELECT name FROM sqlite_master WHERE type='table'").mapNotNull { it["name"] }
        assertTrue(tables.contains(SchemaSql.SKETCHBOOK_TABLE))
        assertTrue(tables.contains(SchemaSql.META_TABLE))
    }

    @Test
    fun `index bootstrap creates objects, both document tables, and activity`() {
        SchemaSql.INDEX_BOOTSTRAP.forEach(::exec)
        val tables = rows("SELECT name FROM sqlite_master WHERE type='table'").mapNotNull { it["name"] }
        assertTrue(tables.containsAll(
            listOf(
                SchemaSql.INDEX_OBJECTS_TABLE,
                SchemaSql.SCRATCHPAD_TABLE,
                SchemaSql.CLIPBOARD_TABLE,
                SchemaSql.ACTIVITY_TABLE,
            ),
        ))
    }

    @Test
    fun `every bootstrap statement is idempotent`() {
        SchemaSql.SOIL_BOOTSTRAP.forEach(::exec)
        SchemaSql.INDEX_BOOTSTRAP.forEach(::exec)
        // Running the whole thing twice must be a no-op, not a duplicate-table error.
        SchemaSql.SOIL_BOOTSTRAP.forEach(::exec)
        SchemaSql.INDEX_BOOTSTRAP.forEach(::exec)
    }

    // --- The document row ---------------------------------------------------

    @Test
    fun `the document row carries the universal columns with the right nullability`() {
        SchemaSql.SOIL_BOOTSTRAP.forEach(::exec)
        val cols = columns(SchemaSql.SKETCHBOOK_TABLE).associateBy { it["name"]!! }

        // The universal row: identical names and semantics across the Sprout family.
        for (name in listOf("id", "parentId", "type", "order", "createdAt", "updatedAt")) {
            assertEquals("$name must be NOT NULL", "1", cols[name]!!["notnull"])
        }
        assertEquals("1", cols["id"]!!["pk"])
        assertEquals("TEXT", cols["id"]!!["type"])
        assertEquals("INTEGER", cols["order"]!!["type"])
        assertEquals("0", cols["order"]!!["dflt_value"])

        // deletedAt NULL means alive — soft delete is the only delete.
        assertEquals("0", cols["deletedAt"]!!["notnull"])

        // The columnar payload is wide and sparse: every one of these is nullable.
        val payload = listOf(
            "x", "y", "width", "height", "text", "color", "refId", "flags", "seed", "kind",
            "params", "tool", "strokeWidth", "opacity", "blendMode", "undoDepth", "opCount",
            "amount", "blob",
        )
        for (name in payload) {
            assertEquals("$name must exist", true, cols.containsKey(name))
            assertEquals("$name must be nullable", "0", cols[name]!!["notnull"])
        }

        // Greenfield: no legacy JSON columns to coexist with.
        assertFalse(cols.containsKey("data"))
        assertFalse(cols.containsKey("boundingBox"))
    }

    /**
     * The whole point of parameterising on the table name: one definition, so the
     * sketchbook file, the scratchpad and the clipboard share every serializer,
     * codec and subtree walk.
     */
    @Test
    fun `all three document tables are identical apart from their name`() {
        SchemaSql.SOIL_BOOTSTRAP.forEach(::exec)
        SchemaSql.INDEX_BOOTSTRAP.forEach(::exec)

        val shape = SchemaSql.DOCUMENT_TABLES.map { table ->
            columns(table).map { listOf(it["name"], it["type"], it["notnull"], it["dflt_value"], it["pk"]) }
        }
        assertEquals(shape[0], shape[1])
        assertEquals(shape[0], shape[2])

        for (table in SchemaSql.DOCUMENT_TABLES) {
            assertEquals(
                listOf("index_${table}_parentId_order_deletedAt"),
                indexNames(table),
            )
        }
    }

    /** `order` is a SQLite reserved word. This is the test that proves it's quoted. */
    @Test
    fun `order is quoted everywhere it appears`() {
        SchemaSql.SOIL_BOOTSTRAP.forEach(::exec)
        exec(
            "INSERT INTO `sketchbook` (`id`,`parentId`,`type`,`order`,`createdAt`,`updatedAt`) " +
                "VALUES ('a','','page',3,1,1)",
        )
        val got = rows("SELECT `order` FROM `sketchbook` ORDER BY `order`")
        assertEquals("3", got.single()["order"])
    }

    // --- The identity table -------------------------------------------------

    /** "There is exactly one" is a database invariant here, not a convention. */
    @Test
    fun `notebook_meta admits row zero and refuses any other`() {
        exec(SchemaSql.META_TABLE_DDL)
        exec("INSERT INTO `notebook_meta` (`id`,`json`) VALUES (0,'{}')")
        var refused = false
        try {
            exec("INSERT INTO `notebook_meta` (`id`,`json`) VALUES (1,'{}')")
        } catch (e: Exception) {
            refused = true
        }
        assertTrue("CHECK (id = 0) must reject a second row", refused)
    }

    // --- The index ----------------------------------------------------------

    @Test
    fun `index objects promotes name and allows a null parent`() {
        SchemaSql.INDEX_BOOTSTRAP.forEach(::exec)
        val cols = columns(SchemaSql.INDEX_OBJECTS_TABLE).associateBy { it["name"]!! }

        // name is a top-level column here, unlike the document row where it is payload.
        assertEquals("1", cols["name"]!!["notnull"])
        // NULL parentId is root — the document row uses "" instead.
        assertEquals("0", cols["parentId"]!!["notnull"])

        exec(
            "INSERT INTO `objects` (`id`,`type`,`name`,`parentId`,`createdAt`,`updatedAt`) " +
                "VALUES ('f','folder','Sketches',NULL,1,1)",
        )
        assertEquals(1, rows("SELECT * FROM `objects` WHERE `parentId` IS NULL").size)

        assertEquals(
            listOf("index_objects_parentId_type_deletedAt"),
            indexNames(SchemaSql.INDEX_OBJECTS_TABLE),
        )
    }

    @Test
    fun `activity log holds ids and verbs only`() {
        SchemaSql.INDEX_BOOTSTRAP.forEach(::exec)
        val cols = columns(SchemaSql.ACTIVITY_TABLE).map { it["name"] }
        assertEquals(listOf("id", "sketchbookId", "activityType", "timestamp"), cols)
        assertEquals(
            listOf(
                "index_sketchbook_activity_activityType_timestamp",
                "index_sketchbook_activity_sketchbookId",
            ).sorted(),
            indexNames(SchemaSql.ACTIVITY_TABLE).sorted(),
        )
    }

    // --- Versions -----------------------------------------------------------

    @Test
    fun `schema versions start at one`() {
        assertEquals(1, SchemaSql.SOIL_SCHEMA_VERSION)
        assertEquals(1, SchemaSql.INDEX_SCHEMA_VERSION)
        assertEquals(1, SchemaSql.CONTAINER_FORMAT_VERSION)
    }

    /** The container's identity table keeps its family name, not an app-specific one. */
    @Test
    fun `the identity table is notebook_meta and the object table is sketchbook`() {
        assertEquals("notebook_meta", SchemaSql.META_TABLE)
        assertEquals("sketchbook", SchemaSql.SKETCHBOOK_TABLE)
    }
}
