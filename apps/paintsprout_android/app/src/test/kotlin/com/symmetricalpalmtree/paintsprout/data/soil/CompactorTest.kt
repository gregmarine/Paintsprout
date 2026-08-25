package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reclaiming space is the one job where a mistake is silent and permanent: a row
 * purged too eagerly is gone, and nothing complains until somebody looks for it.
 * These pin both edges — what may go, and what may not.
 */
class CompactorTest {

    private var clock = 1_000L
    private var ids = 0

    private fun store() = JdbcObjectStore(table = SchemaSql.SKETCHBOOK_TABLE)

    private fun repo(store: ObjectStore) =
        SketchbookRepository(store, rootId = "book", now = { clock++ }, newId = { "k-${ids++}" })

    private fun op(color: String) = SoilObject(id = "", parentId = "", type = SoilType.STROKE, color = color)

    private fun cache(id: String, at: Long) = SoilObject(
        id = id, parentId = "layer", type = SoilType.RASTER_CACHE, order = CACHE_ORDER,
        createdAt = at, updatedAt = at, blob = ByteArray(16),
    )

    // --- Tombstones ---------------------------------------------------------

    /** A page deleted in an earlier session, and everything that hung off it. */
    @Test
    fun `a tombstone from a previous session is purged with its subtree`() {
        store().use { s ->
            val repo = repo(s)
            repo.createDocument("x")
            val extra = repo.addPage()
            val layer = repo.contentLayer(extra.id)!!
            repo.appendOp(layer.id, op("#FF000001"))
            repo.deletePage(extra.id)

            val sessionStart = clock + 1 // the delete happened before this session
            val result = Compactor.sweep(s, openedAt = sessionStart, keepCaches = 99)

            assertTrue(result.changed)
            assertNull("the page", s.byId(extra.id))
            assertNull("its layer", s.byId(layer.id))
            assertEquals("and its op", 0, s.childrenOf(setOf(layer.id)).size)
        }
    }

    /**
     * The safety margin. A row tombstoned *while this document is open* is still
     * this session's business — it is what an undelete would put back.
     */
    @Test
    fun `a tombstone from this session is left alone`() {
        store().use { s ->
            val repo = repo(s)
            repo.createDocument("x")
            val sessionStart = clock
            val extra = repo.addPage()
            repo.deletePage(extra.id)

            val result = Compactor.sweep(s, openedAt = sessionStart, keepCaches = 99)

            assertFalse(result.changed)
            assertNotNull(s.byId(extra.id))
        }
    }

    @Test
    fun `live rows are never touched`() {
        store().use { s ->
            val repo = repo(s)
            repo.createDocument("x")
            val page = repo.pages().single()
            val layer = repo.contentLayer(page.id)!!
            repo.appendOp(layer.id, op("#FF000001"))

            Compactor.sweep(s, openedAt = clock + 1, keepCaches = 99)

            assertNotNull(s.byId(page.id))
            assertNotNull(s.byId(layer.id))
            assertEquals(1, repo.committedOps(layer.id).size)
        }
    }

    /**
     * Compaction is not an edit. `updatedAt` is what the backup predicate reads,
     * and reclaiming space must not make a document look changed.
     */
    @Test
    fun `a survivor's updatedAt does not move`() {
        store().use { s ->
            val repo = repo(s)
            repo.createDocument("x")
            val extra = repo.addPage()
            repo.deletePage(extra.id)
            val before = repo.pages().first().updatedAt

            Compactor.sweep(s, openedAt = clock + 1, keepCaches = 99)

            assertEquals(before, repo.pages().first().updatedAt)
        }
    }

    @Test
    fun `sweeping a document with nothing to do changes nothing`() {
        store().use { s ->
            repo(s).createDocument("x")
            val result = Compactor.sweep(s, openedAt = clock + 1, keepCaches = 99)
            assertFalse("no VACUUM should follow", result.changed)
            assertEquals(0, result.purged)
        }
    }

    // --- Cache retention ----------------------------------------------------

    /** Measured at 75–88% of a document: the reason this policy exists at all. */
    @Test
    fun `only the most recently written caches are kept`() {
        val caches = listOf(cache("a", 100), cache("b", 500), cache("c", 300), cache("d", 400), cache("e", 200))
        val stale = Compactor.staleCaches(caches, keep = 2).map { it.id }
        assertEquals(listOf("c", "e", "a"), stale)
    }

    @Test
    fun `a book with fewer pages than the limit loses nothing`() {
        val caches = listOf(cache("a", 100), cache("b", 200))
        assertEquals(emptyList<SoilObject>(), Compactor.staleCaches(caches, keep = 4))
        assertEquals(emptyList<SoilObject>(), Compactor.staleCaches(emptyList(), keep = 4))
    }

    /** Keeping none is a legitimate policy; keeping "minus one" is not. */
    @Test
    fun `a zero limit drops them all and a negative one drops nothing`() {
        val caches = listOf(cache("a", 100), cache("b", 200))
        assertEquals(2, Compactor.staleCaches(caches, keep = 0).size)
        assertEquals(0, Compactor.staleCaches(caches, keep = -1).size)
    }

    @Test
    fun `the sweep drops stale caches and reports how many`() {
        store().use { s ->
            val repo = repo(s)
            repo.createDocument("x")
            repeat(5) { i ->
                val page = repo.addPage()
                val layer = repo.contentLayer(page.id)!!
                repo.writeCache(layer.id, ByteArray(32), 100f, 100f)
            }

            val result = Compactor.sweep(s, openedAt = clock + 1, keepCaches = 2)

            assertEquals(3, result.cachesDropped)
            assertEquals(2, s.ofType(SoilType.RASTER_CACHE).size)
        }
    }

    /** The ops are still there, so a page that lost its cache still opens. */
    @Test
    fun `dropping a cache leaves the marks that rebuild it`() {
        store().use { s ->
            val repo = repo(s)
            repo.createDocument("x")
            val layer = repo.contentLayer(repo.pages().single().id)!!
            repo.appendOp(layer.id, op("#FF000001"))
            repo.writeCache(layer.id, ByteArray(32), 100f, 100f)

            Compactor.sweep(s, openedAt = clock + 1, keepCaches = 0)

            assertNull(repo.cacheRow(layer.id))
            assertEquals(1, repo.committedOps(layer.id).size)
        }
    }
}
