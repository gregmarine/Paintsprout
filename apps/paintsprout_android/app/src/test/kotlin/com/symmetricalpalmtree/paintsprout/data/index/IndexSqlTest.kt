package com.symmetricalpalmtree.paintsprout.data.index

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The DAO's queries, run against a real SQLite built from the real schema.
 *
 * These are the same strings the `@Query` annotations use, so what is checked
 * here is what the app executes. Room's own plumbing needs a device; the
 * semantics — soft-delete exclusion, NULL-root matching, edge scrubbing, LIKE
 * escaping — do not, and they are where the bugs live.
 */
class IndexSqlTest {

    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaSql.INDEX_BOOTSTRAP.forEach { sql -> db.createStatement().use { it.execute(sql) } }
    }

    @After
    fun close() = db.close()

    /**
     * Binds `:name` parameters the way Room does — **by name**. One argument per
     * distinct parameter, in order of first appearance; a name used twice (as
     * `SOFT_DELETE` uses `:at`) is bound twice from the one argument. Binding
     * positionally instead silently shifts every argument after a repeat.
     */
    private fun bind(sql: String, args: Array<out Any?>): Pair<String, List<Any?>> {
        val occurrences = Regex(":(\\w+)").findAll(sql).map { it.groupValues[1] }.toList()
        val distinct = occurrences.distinct()
        require(args.size == distinct.size) {
            "expected ${distinct.size} arguments $distinct but got ${args.size}"
        }
        val byName = distinct.zip(args.toList()).toMap()
        return sql.replace(Regex(":\\w+"), "?") to occurrences.map { byName[it] }
    }

    private fun query(sql: String, vararg args: Any?): List<Map<String, String?>> {
        val (jdbc, values) = bind(sql, args)
        return db.prepareStatement(jdbc).use { st ->
            values.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                val out = mutableListOf<Map<String, String?>>()
                val meta = rs.metaData
                while (rs.next()) {
                    out += (1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getString(it) }
                }
                out
            }
        }
    }

    private fun exec(sql: String, vararg args: Any?): Int {
        val (jdbc, values) = bind(sql, args)
        return db.prepareStatement(jdbc).use { st ->
            values.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    private fun insert(
        id: String,
        type: String,
        name: String,
        parentId: String? = null,
        deletedAt: Long? = null,
        refId: String? = null,
        sortOrder: Int? = null,
    ) = exec(
        "INSERT INTO objects (id,type,name,parentId,createdAt,updatedAt,deletedAt,refId,sortOrder) " +
            "VALUES (:a,:b,:c,:d,1,1,:e,:f,:g)",
        id, type, name, parentId, deletedAt, refId, sortOrder,
    )

    private fun names(rows: List<Map<String, String?>>) = rows.map { it["name"] }

    /**
     * Root is NULL, and `=` never matches NULL. Written with `=`, this query would
     * return an empty root folder on a library full of sketchbooks.
     */
    @Test
    fun `children of the root are found even though root is NULL`() {
        insert("a", IndexType.FOLDER, "Studies")
        insert("b", IndexType.SKETCHBOOK, "Harbour")
        insert("c", IndexType.SKETCHBOOK, "Nested", parentId = "a")

        assertEquals(listOf("Studies"), names(query(IndexSql.LIVE_CHILDREN_OF_TYPE, null, IndexType.FOLDER)))
        assertEquals(listOf("Harbour"), names(query(IndexSql.LIVE_CHILDREN_OF_TYPE, null, IndexType.SKETCHBOOK)))
        assertEquals(listOf("Nested"), names(query(IndexSql.LIVE_CHILDREN_OF_TYPE, "a", IndexType.SKETCHBOOK)))
    }

    @Test
    fun `soft-deleted rows are excluded from every listing`() {
        insert("a", IndexType.SKETCHBOOK, "Alive")
        insert("b", IndexType.SKETCHBOOK, "Gone", deletedAt = 5_000L)

        assertEquals(listOf("Alive"), names(query(IndexSql.LIVE_CHILDREN_OF_TYPE, null, IndexType.SKETCHBOOK)))
        assertEquals(listOf("Alive"), names(query(IndexSql.LIVE_CHILDREN, null)))
        assertEquals("1", query(IndexSql.COUNT_LIVE_CHILDREN, null).single().values.first())
        // ...but it is still there, and still readable by id. Soft delete only.
        assertEquals("Gone", query(IndexSql.BY_ID, "b").single()["name"])
    }

    @Test
    fun `listings are ordered by name, case-insensitively`() {
        insert("a", IndexType.SKETCHBOOK, "zebra")
        insert("b", IndexType.SKETCHBOOK, "Apple")
        insert("c", IndexType.SKETCHBOOK, "banana")

        assertEquals(
            listOf("Apple", "banana", "zebra"),
            names(query(IndexSql.LIVE_CHILDREN_OF_TYPE, null, IndexType.SKETCHBOOK)),
        )
    }

    @Test
    fun `soft delete stamps both timestamps`() {
        insert("a", IndexType.SKETCHBOOK, "Doomed")
        exec(IndexSql.SOFT_DELETE, 1234L, "a")

        val row = query(IndexSql.BY_ID, "a").single()
        assertEquals("1234", row["deletedAt"])
        assertEquals("1234", row["updatedAt"])
    }

    // --- Search -------------------------------------------------------------

    @Test
    fun `search matches anywhere in the name and only live sketchbooks`() {
        insert("a", IndexType.SKETCHBOOK, "Harbour studies")
        insert("b", IndexType.SKETCHBOOK, "Studio light")
        insert("c", IndexType.FOLDER, "Studies")
        insert("d", IndexType.SKETCHBOOK, "Old studies", deletedAt = 1L)

        assertEquals(
            listOf("Harbour studies", "Studio light"),
            names(query(IndexSql.SEARCH_BY_NAME, IndexType.SKETCHBOOK, "stud")),
        )
    }

    /** A `%` a user types is a literal character to them and a wildcard to SQL. */
    @Test
    fun `wildcards typed by the user are matched literally`() {
        insert("a", IndexType.SKETCHBOOK, "100% cotton")
        insert("b", IndexType.SKETCHBOOK, "anything at all")

        val escaped = IndexRepository.escapeLike("100%")
        assertEquals(listOf("100% cotton"), names(query(IndexSql.SEARCH_BY_NAME, IndexType.SKETCHBOOK, escaped)))

        // Unescaped, the same query would match everything.
        assertEquals(2, query(IndexSql.SEARCH_BY_NAME, IndexType.SKETCHBOOK, "%").size)
    }

    @Test
    fun `underscore and backslash are escaped too`() {
        insert("a", IndexType.SKETCHBOOK, "a_b")
        insert("b", IndexType.SKETCHBOOK, "axb")

        assertEquals(
            listOf("a_b"),
            names(query(IndexSql.SEARCH_BY_NAME, IndexType.SKETCHBOOK, IndexRepository.escapeLike("a_b"))),
        )
        assertEquals("\\\\", IndexRepository.escapeLike("\\"))
    }

    // --- Membership ---------------------------------------------------------

    @Test
    fun `list members come back in sortOrder`() {
        insert("e2", IndexType.LIST_ITEM, "", parentId = Sentinels.PINNED_LIST_ID, refId = "book2", sortOrder = 1)
        insert("e0", IndexType.LIST_ITEM, "", parentId = Sentinels.PINNED_LIST_ID, refId = "book0", sortOrder = 0)

        assertEquals(
            listOf("book0", "book2"),
            query(IndexSql.LIST_MEMBER_EDGES, Sentinels.PINNED_LIST_ID).map { it["refId"] },
        )
        assertEquals("1", query(IndexSql.MAX_SORT_ORDER, Sentinels.PINNED_LIST_ID).single().values.first())
    }

    /**
     * Membership churn hard-deletes. Tombstoning a pin toggle would leave every
     * list accumulating dead rows forever.
     */
    @Test
    fun `unpinning removes the edge outright`() {
        insert("e", IndexType.LIST_ITEM, "", parentId = Sentinels.PINNED_LIST_ID, refId = "book", sortOrder = 0)
        exec(IndexSql.DELETE_EDGE, Sentinels.PINNED_LIST_ID, "book")

        assertTrue(query(IndexSql.LIST_MEMBER_EDGES, Sentinels.PINNED_LIST_ID).isEmpty())
        assertTrue(query(IndexSql.BY_ID, "e").isEmpty())
    }

    /** Deleting a member scrubs its edges everywhere, so no list is left dangling. */
    @Test
    fun `deleting a member scrubs every edge that points at it`() {
        insert("e1", IndexType.LIST_ITEM, "", parentId = Sentinels.PINNED_LIST_ID, refId = "book", sortOrder = 0)
        insert("e2", IndexType.LIST_ITEM, "", parentId = "some-other-list", refId = "book", sortOrder = 0)
        insert("e3", IndexType.LIST_ITEM, "", parentId = Sentinels.PINNED_LIST_ID, refId = "other", sortOrder = 1)

        exec(IndexSql.DELETE_EDGES_FOR, "book")

        assertEquals(listOf("other"), query(IndexSql.LIST_MEMBER_EDGES, Sentinels.PINNED_LIST_ID).map { it["refId"] })
        assertTrue(query(IndexSql.BY_ID, "e2").isEmpty())
    }

    // --- Recents ------------------------------------------------------------

    @Test
    fun `recents are one entry per book, most recently touched first`() {
        fun log(id: String, book: String, type: String, at: Long) = exec(
            "INSERT INTO sketchbook_activity (id,sketchbookId,activityType,timestamp) VALUES (:a,:b,:c,:d)",
            id, book, type, at,
        )
        log("1", "old", ActivityRow.OPENED, 100)
        log("2", "new", ActivityRow.OPENED, 300)
        log("3", "old", ActivityRow.OPENED, 400) // old was touched again, most recently
        log("4", "edited-only", ActivityRow.EDITED, 500)

        assertEquals(
            listOf("old", "new"),
            query(ActivitySql.RECENT_IDS, ActivityRow.OPENED, 10).map { it["sketchbookId"] },
        )
        assertEquals(1, query(ActivitySql.RECENT_IDS, ActivityRow.OPENED, 1).size)
    }

    @Test
    fun `a deleted book's history goes with it`() {
        exec(
            "INSERT INTO sketchbook_activity (id,sketchbookId,activityType,timestamp) VALUES ('1','book','OPENED',1)",
        )
        exec(ActivitySql.DELETE_FOR, "book")
        assertEquals("0", query(ActivitySql.COUNT).single().values.first())
    }
}
