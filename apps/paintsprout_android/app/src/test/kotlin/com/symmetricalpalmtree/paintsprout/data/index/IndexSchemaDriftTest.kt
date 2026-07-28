package com.symmetricalpalmtree.paintsprout.data.index

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Room creates the index's tables from its entities; `SchemaSql` describes the
 * same tables by hand, for the importer, the repair paths and the document
 * container that shares the row shape.
 *
 * Two descriptions of one thing is a drift waiting to happen, and the way it
 * surfaces is the worst kind: a file created by one path fails Room's on-open
 * validation on a device, and the user sees a library that won't open. So the
 * exported schema is committed and compared here, on every build.
 */
class IndexSchemaDriftTest {

    private fun exportedSchema(): JsonObject {
        // Named for the version the code claims, so a bump that forgets to commit
        // the new export fails here rather than comparing against the old shape.
        val dir = "schemas/com.symmetricalpalmtree.paintsprout.data.index.IndexDatabase"
        val name = "${SchemaSql.INDEX_SCHEMA_VERSION}.json"
        val candidates = listOf(File("$dir/$name"), File("app/$dir/$name"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Room schema not exported — looked in ${candidates.map { it.absolutePath }}")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun database(): JsonObject = exportedSchema()["database"]!!.jsonObject

    private fun entities(): List<JsonObject> =
        database()["entities"]!!.jsonArray.map { it.jsonObject }

    private fun roomCreateSql(table: String): Pair<String, List<String>> {
        val entity = entities().firstOrNull { it["tableName"]!!.jsonPrimitive.content == table }
            ?: error("No entity for table $table in the exported schema")
        val create = entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
        val indices = entity["indices"]?.jsonArray.orEmpty().map {
            it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
        }
        return create to indices
    }

    /** The comparison that matters: what the engine ends up with, not the text. */
    private fun shapeOf(statements: List<String>, table: String): Pair<List<String>, List<String>> {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            statements.forEach { sql -> db.createStatement().use { it.execute(sql) } }
            return columns(db, table) to indices(db, table)
        }
    }

    private fun columns(db: Connection, table: String): List<String> =
        rows(db, "PRAGMA table_info(`$table`)").map {
            "${it["name"]} ${it["type"]} notnull=${it["notnull"]} default=${it["dflt_value"]} pk=${it["pk"]}"
        }

    private fun indices(db: Connection, table: String): List<String> =
        rows(db, "PRAGMA index_list(`$table`)")
            .mapNotNull { it["name"] }
            .filterNot { it.startsWith("sqlite_autoindex") }
            .sorted()
            .map { name ->
                name + ":" + rows(db, "PRAGMA index_info(`$name`)").joinToString(",") { it["name"] ?: "" }
            }

    private fun rows(db: Connection, sql: String): List<Map<String, String?>> =
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

    @Test
    fun `the objects table Room creates is the objects table SchemaSql describes`() {
        val (create, indices) = roomCreateSql(SchemaSql.INDEX_OBJECTS_TABLE)
        assertEquals(
            shapeOf(listOf(create) + indices, SchemaSql.INDEX_OBJECTS_TABLE),
            shapeOf(
                listOf(SchemaSql.INDEX_OBJECTS_DDL, SchemaSql.INDEX_OBJECTS_INDEX_DDL),
                SchemaSql.INDEX_OBJECTS_TABLE,
            ),
        )
    }

    @Test
    fun `the activity table Room creates is the one SchemaSql describes`() {
        val (create, indices) = roomCreateSql(SchemaSql.ACTIVITY_TABLE)
        assertEquals(
            shapeOf(listOf(create) + indices, SchemaSql.ACTIVITY_TABLE),
            shapeOf(
                listOf(
                    SchemaSql.ACTIVITY_DDL,
                    SchemaSql.ACTIVITY_TYPE_INDEX_DDL,
                    SchemaSql.ACTIVITY_ID_INDEX_DDL,
                ),
                SchemaSql.ACTIVITY_TABLE,
            ),
        )
    }

    @Test
    fun `the exported schema is at the version the code claims`() {
        assertEquals(
            SchemaSql.INDEX_SCHEMA_VERSION,
            database()["version"]!!.jsonPrimitive.int,
        )
    }

    /**
     * Room has no entity for the document-shaped tables — they are created from
     * `SchemaSql` in the database callback — and it must stay that way, or the two
     * definitions of the universal row start competing.
     */
    @Test
    fun `Room owns only the two index tables`() {
        val names = entities().map { it["tableName"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(SchemaSql.ACTIVITY_TABLE, SchemaSql.INDEX_OBJECTS_TABLE).sorted(),
            names.sorted(),
        )
        assertTrue(names.none { it in SchemaSql.DOCUMENT_TABLES })
    }
}
