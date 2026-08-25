package com.symmetricalpalmtree.paintsproutonyx.data

import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ORM and the hand-written DDL must describe the same tables, character for character in
 * the ways SQLite cares about.
 *
 * There are two descriptions of a sketchbook's shape in this app. `SoilSchema` holds the DDL
 * a new file is created with — plain SQL, readable, the thing a stock `sqlcipher` CLI would
 * agree with. Room holds its own idea of that shape, generated from the entity class, and on
 * every open it verifies the file against it by hash. Neither one is redundant: the DDL is
 * the format as written down, and Room's copy is the format as enforced.
 *
 * If they drift apart the failure lands in the worst possible place. The file is fine. The
 * drawings in it are fine. Room simply refuses to open it, on the device, with a message
 * about an identity hash — and the obvious reading of that message, to whoever meets it, is
 * that the sketchbook is corrupt. That is the road to deleting a perfectly good file to fix
 * a problem in the source code.
 *
 * So the two are compared here, on the JVM, where a mismatch is a red test instead of a lost
 * afternoon. Room's generated schema JSON is committed alongside the source for exactly this
 * reason: it is the ORM's answer written down where a test can read it without a device, an
 * emulator, or SQLCipher's native library.
 *
 * What is deliberately *not* compared is quoting and whitespace. Room backticks every
 * identifier; the hand-written DDL double-quotes only `order`, which is the one word SQLite
 * would otherwise read as a keyword. Both produce the same table. Comparing the raw strings
 * would fail on a difference that does not exist as far as the database is concerned, and a
 * test that cries wolf about formatting is a test people learn to re-baseline without
 * reading.
 */
class SchemaParityTest {

    @Test
    fun `the sketchbook table Room enforces is the sketchbook table we create`() {
        val roomSql = createSqlFor("com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilDatabase", SoilSchema.TABLE)

        assertEquals(
            "the sketchbook column list has drifted between SoilSchema and the Room entity",
            columnsOf(SoilSchema.CREATE_SKETCHBOOK),
            columnsOf(roomSql),
        )
    }

    @Test
    fun `the sketchbook primary key Room enforces is the one we create`() {
        val roomSql = createSqlFor("com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilDatabase", SoilSchema.TABLE)

        assertEquals(
            "the sketchbook primary key has drifted between SoilSchema and the Room entity",
            primaryKeyOf(SoilSchema.CREATE_SKETCHBOOK),
            primaryKeyOf(roomSql),
        )
        // Stated outright as well as compared, so a change to BOTH sides at once still trips.
        assertEquals(listOf("id"), primaryKeyOf(roomSql))
    }

    @Test
    fun `the index Room enforces on the sketchbook table is the one we create`() {
        val roomIndex = indexSqlFor("com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilDatabase", SoilSchema.TABLE)

        assertEquals(
            "the sketchbook index has drifted between SoilSchema and the Room entity",
            normalize(SoilSchema.CREATE_SKETCHBOOK_INDEX),
            normalize(roomIndex),
        )
    }

    @Test
    fun `the schema version Room stamps is the one SoilSchema declares`() {
        assertEquals(SoilSchema.SOIL_VERSION, versionOf("com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilDatabase"))
    }

    @Test
    fun `the index database is at the version the format expects`() {
        // The global index has no hand-written DDL to compare against — it is created by Room
        // and only ever read by Room. What still matters is that its version never moves by
        // accident, because a bump with no migration behind it is an index the next launch
        // cannot open, and the index is what makes a sketchbook findable at all.
        assertEquals(1, versionOf("com.symmetricalpalmtree.paintsproutonyx.data.index.IndexDatabase"))
    }

    @Test
    fun `the committed schema files exist`() {
        // If Room's schema export is ever switched off, every test above would quietly have
        // nothing to compare and could only fail by not running. This one fails loudly instead.
        assertTrue(
            "Room's exported schema JSON is missing — is exportSchema still true?",
            schemaDir().isDirectory,
        )
    }

    // ------------------------------------------------------------------ reading the JSON

    /**
     * A deliberately small hand-rolled read of Room's schema JSON rather than a parser
     * dependency. The file has one shape, this test is the only reader of it, and adding a
     * library to a module that takes no dependency lightly would be a poor trade for four
     * string operations.
     */
    private fun schemaDir(): File {
        // Unit tests run with the module directory as the working directory, but a run from the
        // Gradle root is common enough to be worth surviving.
        val candidates = listOf(File("schemas"), File("app/schemas"))
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
    }

    private fun json(database: String): String {
        val file = File(schemaDir(), "$database/1.json")
        assertTrue("missing exported schema: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun versionOf(database: String): Int =
        Regex("\"version\"\\s*:\\s*(\\d+)").find(json(database))!!.groupValues[1].toInt()

    private fun createSqlFor(database: String, table: String): String =
        allCreateSql(database).first { it.contains("`$table`") }

    private fun indexSqlFor(database: String, table: String): String =
        allSql(database, "\"createSql\"").first { it.startsWith("CREATE INDEX") && it.contains("`$table`") }

    private fun allCreateSql(database: String): List<String> =
        allSql(database, "\"createSql\"").filter { it.startsWith("CREATE TABLE") }

    private fun allSql(database: String, key: String): List<String> =
        Regex("$key\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(json(database))
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\n", " ") }
            .map { it.replace("\${TABLE_NAME}", tableNameOf(database, it)) }
            .toList()

    /**
     * Room writes `${'$'}{TABLE_NAME}` as a placeholder in `createSql`. The table it stands for is
     * the `tableName` of the entity that statement belongs to, and since this module has one
     * entity per database, that is simply the file's only `tableName`.
     */
    private fun tableNameOf(database: String, ignored: String): String =
        Regex("\"tableName\"\\s*:\\s*\"([^\"]+)\"").find(json(database))!!.groupValues[1]

    // ------------------------------------------------------------------ comparing shapes

    /** Quoting and whitespace stripped; what is left is what SQLite would actually act on. */
    private fun normalize(sql: String): String =
        sql.replace("`", "")
            .replace("\"", "")
            .replace("IF NOT EXISTS ", "")
            .replace(Regex("\\s+"), " ")
            // `table (cols)` and `table(cols)` are the same statement. Room writes one, the
            // hand-written DDL writes the other, and neither is more correct.
            .replace(Regex("\\s*\\(\\s*"), "(")
            .replace(Regex("\\s*\\)"), ")")
            .trim()

    /**
     * The column list as `name TYPE [NOT NULL] [DEFAULT x]`, in declaration order, with the
     * primary key normalised — Room states it as a trailing `PRIMARY KEY(id)` clause and the
     * hand-written DDL states it inline on the column, which are the same table said two ways.
     */
    private fun columnsOf(createSql: String): List<String> {
        val body = normalize(createSql).substringAfter("(").substringBeforeLast(")")
        return splitTopLevel(body)
            .map { it.trim() }
            .filter { !it.startsWith("PRIMARY KEY", ignoreCase = true) }
            .map { it.replace(" PRIMARY KEY", "") }
            .map { it.replace(Regex("\\s+"), " ") }
    }

    /**
     * The primary key, stated the same way from either side.
     *
     * It is compared separately because the two sources say it differently — Room appends a
     * trailing `PRIMARY KEY(id)` clause, the hand-written DDL puts `PRIMARY KEY` inline on the
     * column — and [columnsOf] has to strip both to compare the columns at all. Stripping it
     * there and not checking it anywhere would leave the one column that decides row identity
     * unguarded: an entity whose key moved to another column would pass every other assertion
     * in this file.
     */
    private fun primaryKeyOf(createSql: String): List<String> {
        val body = normalize(createSql).substringAfter("(").substringBeforeLast(")")
        val parts = splitTopLevel(body).map { it.trim() }

        val trailing = parts.firstOrNull { it.startsWith("PRIMARY KEY", ignoreCase = true) }
        if (trailing != null) {
            return trailing.substringAfter("(").substringBefore(")")
                .split(",").map { it.trim() }
        }
        return parts.filter { it.contains("PRIMARY KEY", ignoreCase = true) }
            .map { it.substringBefore(" ") }
    }

    /** Split on commas that are not inside parentheses — `DEFAULT 0` is fine, `CHECK (a, b)` is not. */
    private fun splitTopLevel(body: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in body) {
            when {
                c == '(' -> { depth++; current.append(c) }
                c == ')' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { out += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }
}
