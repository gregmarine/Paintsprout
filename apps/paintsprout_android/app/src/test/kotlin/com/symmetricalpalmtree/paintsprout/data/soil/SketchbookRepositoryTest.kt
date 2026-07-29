package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SketchbookRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JdbcObjectStore
    private lateinit var repo: SketchbookRepository

    private var clock = 1_000L
    private var ids = 0

    @Before
    fun setUp() {
        store = JdbcObjectStore()
        repo = build(store)
    }

    private fun build(over: ObjectStore) = SketchbookRepository(
        store = over,
        rootId = "book-1",
        now = { clock },
        newId = { "id-${ids++}" },
    )

    @After
    fun tearDown() = store.close()

    private fun op(type: String = SoilType.STROKE, color: String? = null) =
        SoilObject(id = "", parentId = "", type = type, color = color)

    // --- Creation -----------------------------------------------------------

    @Test
    fun `a new document has a root, a page, a layer and a palette`() {
        repo.createDocument("Harbour studies", canvasKind = "PRINT", canvasWidth = 7f, canvasHeight = 5f)

        val root = repo.root()!!
        assertEquals(SoilType.SKETCHBOOK, root.type)
        assertEquals("Harbour studies", root.text)
        assertEquals("", root.parentId)
        assertEquals(7f, root.width)

        assertEquals(1, repo.pageCount())
        assertNotNull(repo.contentLayer(repo.pages().single().id))
        assertNotNull(repo.palette())
    }

    /** The scratchpad's root is a sentinel that gets ensured at every launch. */
    @Test
    fun `ensureRoot is idempotent`() {
        val first = repo.ensureRoot("Scratch")
        clock += 100
        val second = repo.ensureRoot("Something else")
        assertEquals(first.id, second.id)
        assertEquals("the first one wins", "Scratch", second.text)
    }

    @Test
    fun `a new layer is visible, unlocked and has no history`() {
        repo.createDocument("x")
        val layer = repo.contentLayer(repo.pages().single().id)!!
        assertTrue(layer.hasFlag(SoilFlags.LAYER_VISIBLE))
        assertFalse(layer.hasFlag(SoilFlags.LAYER_LOCKED))
        assertEquals(0, layer.undoDepth)
        assertEquals(1f, layer.opacity)
    }

    // --- The undo frontier --------------------------------------------------

    @Test
    fun `appending ops moves the frontier and keeps order dense`() {
        repo.createDocument("x")
        val layer = repo.contentLayer(repo.pages().single().id)!!.id

        repeat(5) { repo.appendOp(layer, op(color = "#$it")) }

        assertEquals(5, repo.undoDepth(layer))
        assertEquals(listOf(0, 1, 2, 3, 4), repo.committedOps(layer).map { it.order })
    }

    @Test
    fun `undo and redo move only the frontier`() {
        repo.createDocument("x")
        val layer = repo.contentLayer(repo.pages().single().id)!!.id
        repeat(3) { repo.appendOp(layer, op(color = "#$it")) }

        assertTrue(repo.undo(layer))
        assertEquals(2, repo.committedOps(layer).size)
        assertEquals("nothing was deleted", 3, repo.committedOps(layer).size + repo.redoableOps(layer).size)

        assertTrue(repo.redo(layer))
        assertEquals(3, repo.committedOps(layer).size)
        assertTrue(repo.redoableOps(layer).isEmpty())
    }

    @Test
    fun `undo stops at the beginning and redo stops at the end`() {
        repo.createDocument("x")
        val layer = repo.contentLayer(repo.pages().single().id)!!.id

        assertFalse(repo.canUndo(layer))
        assertFalse(repo.undo(layer))
        assertFalse(repo.canRedo(layer))
        assertFalse(repo.redo(layer))

        repo.appendOp(layer, op())
        assertTrue(repo.canUndo(layer))
        repo.undo(layer)
        assertTrue(repo.canRedo(layer))
        assertFalse(repo.redo(layer).let { repo.redo(layer) })
        assertEquals(1, repo.undoDepth(layer))
    }

    /**
     * A new op replaces the future. The undone ops are hard-deleted rather than
     * tombstoned, which is what keeps `order` dense — and dense is what lets one
     * integer be the whole undo model.
     */
    @Test
    fun `a new op truncates the redo tail`() {
        repo.createDocument("x")
        val layer = repo.contentLayer(repo.pages().single().id)!!.id
        repeat(4) { repo.appendOp(layer, op(color = "#$it")) }

        repo.undo(layer)
        repo.undo(layer)
        assertEquals(2, repo.redoableOps(layer).size)

        repo.appendOp(layer, op(color = "#new"))

        assertFalse("the future is gone", repo.canRedo(layer))
        assertEquals(3, repo.undoDepth(layer))
        assertEquals(3, store.countOps(layer))
        assertEquals(listOf(0, 1, 2), repo.committedOps(layer).map { it.order })
        assertEquals("#new", repo.committedOps(layer).last().color)
    }

    @Test
    fun `attachments hang off their op and are read in one batch`() {
        repo.createDocument("x")
        val layer = repo.contentLayer(repo.pages().single().id)!!.id
        val a = repo.appendOp(layer, op())
        val b = repo.appendOp(layer, op())
        repo.attach(a.id, SoilObject(id = "", parentId = "", type = SoilType.STROKE_CLIP))
        repo.attach(a.id, SoilObject(id = "", parentId = "", type = SoilType.WET_STATE))

        val found = repo.attachmentsOf(listOf(a.id, b.id))
        assertEquals(2, found.size)
        assertTrue(found.all { it.parentId == a.id })
        assertEquals("an attachment is not an op", 2, store.countOps(layer))
    }

    // --- Pages --------------------------------------------------------------

    @Test
    fun `pages keep their order and each gets its own layer`() {
        repo.createDocument("x")
        repo.addPage(surfaceKind = "CANVAS")
        repo.addPage(surfaceKind = "WATERCOLOR")

        val pages = repo.pages()
        assertEquals(3, pages.size)
        assertEquals(listOf(0, 1, 2), pages.map { it.order })
        assertTrue(pages.all { repo.contentLayer(it.id) != null })
        assertEquals("CANVAS", pages[1].kind)
    }

    @Test
    fun `a page records the surface it was created on`() {
        repo.createDocument("x")
        val page = repo.pages().single().id
        repo.setInitialSurface(page, "WOOD", "#FFBA9C78", 12345L, Params.of("grain" to 0.34f))

        val stored = repo.pages().single()
        assertEquals("WOOD", stored.kind)
        assertEquals(12345L, stored.seed)
        assertEquals(0.34f, Params.decode(stored.params).float("grain", 0f), 1e-6f)
    }

    @Test
    fun `moving a page renumbers the rest`() {
        repo.createDocument("x")
        repo.addPage(surfaceKind = "B")
        repo.addPage(surfaceKind = "C")
        val third = repo.pages()[2].id

        repo.movePage(third, 0)

        assertEquals(listOf("C", null, "B"), repo.pages().map { it.kind })
        assertEquals(listOf(0, 1, 2), repo.pages().map { it.order })
    }

    @Test
    fun `moving to an out-of-range index clamps instead of failing`() {
        repo.createDocument("x")
        repo.addPage(surfaceKind = "B")
        repo.movePage(repo.pages()[0].id, 99)
        assertEquals(listOf("B", null), repo.pages().map { it.kind })
    }

    /** Delete stamps the page only; the subtree stays put and stays invisible. */
    @Test
    fun `deleting a page hides it without touching its contents`() {
        repo.createDocument("x")
        val page = repo.pages().single()
        val layer = repo.contentLayer(page.id)!!.id
        repo.appendOp(layer, op())

        repo.deletePage(page.id)

        assertEquals(0, repo.pageCount())
        assertEquals("the ops were not touched", 1, store.countOps(layer))

        repo.restorePage(page.id)
        assertEquals(1, repo.pageCount())
        assertEquals(1, repo.committedOps(layer).size)
    }

    @Test
    fun `duplicating a page copies its whole subtree with fresh ids`() {
        repo.createDocument("x")
        val page = repo.pages().single()
        val layer = repo.contentLayer(page.id)!!
        repeat(2) { repo.appendOp(layer.id, op(color = "#$it")) }

        val copy = repo.duplicatePage(page.id)!!

        assertEquals(2, repo.pageCount())
        assertFalse(copy.id == page.id)

        val copiedLayer = repo.contentLayer(copy.id)!!
        assertFalse(copiedLayer.id == layer.id)
        assertEquals(2, repo.undoDepth(copiedLayer.id))
        assertEquals(listOf("#0", "#1"), repo.committedOps(copiedLayer.id).map { it.color })
        assertEquals("the original is untouched", 2, repo.committedOps(layer.id).size)
    }

    /** Where the copy lands: beside its original, not at the back of the book. */
    @Test
    fun `a duplicate is inserted after the page it copies`() {
        repo.createDocument("x")
        repeat(3) { repo.addPage() }
        val pages = repo.pages()
        assertEquals(4, pages.size)
        val second = pages[1]

        val copy = repo.duplicatePage(second.id)!!

        assertEquals(
            listOf(pages[0].id, second.id, copy.id, pages[2].id, pages[3].id),
            repo.pages().map { it.id },
        )
        assertEquals("and the order column is dense", listOf(0, 1, 2, 3, 4), repo.pages().map { it.order })
    }

    /** Duplicating the last page has nowhere after it to go but the end. */
    @Test
    fun `duplicating the last page appends`() {
        repo.createDocument("x")
        repo.addPage()
        val last = repo.pages().last()

        val copy = repo.duplicatePage(last.id)!!

        assertEquals(copy.id, repo.pages().last().id)
        assertEquals(3, repo.pageCount())
    }

    /** Duplicating twice is the id-collision case that crashes without a remap. */
    @Test
    fun `duplicating the same page twice is safe`() {
        repo.createDocument("x")
        val page = repo.pages().single()
        repo.appendOp(repo.contentLayer(page.id)!!.id, op())

        repo.duplicatePage(page.id)
        repo.duplicatePage(page.id)

        assertEquals(3, repo.pageCount())
        assertEquals(3, repo.pages().map { it.id }.toSet().size)
    }

    // --- The raster cache ---------------------------------------------------

    @Test
    fun `a cache is returned only while it matches the frontier`() {
        repo.createDocument("x")
        val layer = repo.contentLayer(repo.pages().single().id)!!.id
        repeat(3) { repo.appendOp(layer, op()) }
        repo.writeCache(layer, byteArrayOf(1, 2, 3), 1920f, 1200f)

        assertNotNull(repo.cache(layer))
        assertEquals(3, repo.cache(layer)!!.opCount)

        repo.undo(layer)
        assertNull("stale after an undo — replay instead", repo.cache(layer))
        assertNotNull("but the row is still there to overwrite", repo.cacheRow(layer))

        repo.redo(layer)
        assertNotNull("and current again once the frontier matches", repo.cache(layer))
    }

    /** The cache lives outside `order` space so no history read can ever see it. */
    @Test
    fun `the cache is not an op`() {
        repo.createDocument("x")
        val layer = repo.contentLayer(repo.pages().single().id)!!.id
        repo.appendOp(layer, op())
        repo.writeCache(layer, byteArrayOf(9), 10f, 10f)

        assertEquals(1, store.countOps(layer))
        assertEquals(1, repo.committedOps(layer).size)
        assertEquals(CACHE_ORDER, repo.cacheRow(layer)!!.order)
    }

    @Test
    fun `writing a cache twice replaces it rather than accumulating`() {
        repo.createDocument("x")
        val layer = repo.contentLayer(repo.pages().single().id)!!.id
        repo.writeCache(layer, byteArrayOf(1), 10f, 10f)
        repo.writeCache(layer, byteArrayOf(2), 10f, 10f)

        assertEquals(1, store.childrenOfType(layer, SoilType.RASTER_CACHE).size)
        assertEquals(2, repo.cacheRow(layer)!!.blob!!.single().toInt())

        repo.invalidateCache(layer)
        assertNull(repo.cacheRow(layer))
    }

    // --- The tray -----------------------------------------------------------

    @Test
    fun `the palette and its pots travel with the document`() {
        repo.createDocument("x")
        repo.addPot("Ultramarine Blue", "#FF1B1BB3", custom = false)
        repo.addPot("A mixed green", "#FF3F7A2A", custom = true)
        repo.writePaletteState(Params.of("capacity" to 1f, "mixture" to "1B1BB3:0.5"))

        val pots = repo.pots()
        assertEquals(2, pots.size)
        assertFalse(pots[0].hasFlag(SoilFlags.POT_CUSTOM))
        assertTrue(pots[1].hasFlag(SoilFlags.POT_CUSTOM))
        assertEquals(1f, repo.paletteState().float("capacity", 0f), 0f)

        repo.removePot(pots[0].id)
        assertEquals(1, repo.pots().size)
    }

    // --- Across a close and reopen ------------------------------------------

    /**
     * The claim persistence exists to make: reopen a page days later and the undo
     * history is still there, both directions.
     */
    @Test
    fun `undo history survives closing and reopening the document`() {
        val file = tmp.newFile("book.soil").also { it.delete() }
        val onDisk = JdbcObjectStore(path = file.absolutePath)
        try {
            val first = build(onDisk)
            first.createDocument("Harbour")
            val page = first.pages().single().id
            val layer = first.contentLayer(page)!!.id
            repeat(4) { first.appendOp(layer, op(color = "#$it")) }
            first.undo(layer)
            first.undo(layer)
            first.setLastOpenedPage(page)
            first.writeCache(layer, byteArrayOf(7), 100f, 50f)

            onDisk.reopen()

            val later = build(onDisk)
            assertEquals("Harbour", later.root()!!.text)
            assertEquals(page, later.lastOpenedPage()!!.id)
            assertEquals(2, later.undoDepth(layer))
            assertEquals(listOf("#0", "#1"), later.committedOps(layer).map { it.color })
            assertEquals("the redo stack came back too", 2, later.redoableOps(layer).size)

            assertTrue(later.redo(layer))
            assertEquals("#2", later.committedOps(layer).last().color)

            // The cache was written at depth 2 and the frontier has moved to 3.
            assertNull(later.cache(layer))
        } finally {
            onDisk.close()
        }
    }

    /** The same repository, over the scratchpad table in the other database. */
    @Test
    fun `the repository works over the scratchpad table unchanged`() {
        JdbcObjectStore(table = SchemaSql.SCRATCHPAD_TABLE).use { scratch ->
            val pad = SketchbookRepository(scratch, rootId = "scratch-root", now = { clock }, newId = { "s-${ids++}" })
            pad.createDocument("Scratch")
            val layer = pad.contentLayer(pad.pages().single().id)!!.id
            pad.appendOp(layer, op())

            assertEquals(SchemaSql.SCRATCHPAD_TABLE, scratch.table)
            assertEquals(1, pad.committedOps(layer).size)
        }
    }

    // --- The resolved surface -----------------------------------------------

    /**
     * The bug this replaces: the page row used to cache "the current surface",
     * which undo then silently invalidated — the page reloaded on the wrong paper.
     * Now the page holds only what it was created on and the answer is derived.
     */
    @Test
    fun `the current surface follows the undo frontier`() {
        repo.createDocument("x", surfaceKind = "PAPER")
        val page = repo.pages().single().id
        val layer = repo.contentLayer(page)!!.id

        assertEquals("PAPER", repo.resolvedSurface(page)!!.kind)

        repo.appendOp(layer, SoilObject(id = "", parentId = "", type = SoilType.SURFACE_OP, kind = "CANVAS"))
        assertEquals("CANVAS", repo.resolvedSurface(page)!!.kind)

        repo.undo(layer)
        assertEquals("undo puts the old paper back", "PAPER", repo.resolvedSurface(page)!!.kind)

        repo.redo(layer)
        assertEquals("CANVAS", repo.resolvedSurface(page)!!.kind)
    }

    @Test
    fun `the newest surface change wins`() {
        repo.createDocument("x", surfaceKind = "PAPER")
        val page = repo.pages().single().id
        val layer = repo.contentLayer(page)!!.id

        for (kind in listOf("CANVAS", "WOOD", "STONE")) {
            repo.appendOp(layer, SoilObject(id = "", parentId = "", type = SoilType.SURFACE_OP, kind = kind))
        }
        assertEquals("STONE", repo.resolvedSurface(page)!!.kind)

        repo.undo(layer)
        assertEquals("WOOD", repo.resolvedSurface(page)!!.kind)
    }

    /** The page keeps the one surface fact that never changes. */
    @Test
    fun `the page row is not rewritten by a surface change`() {
        repo.createDocument("x", surfaceKind = "PAPER")
        val page = repo.pages().single().id
        val layer = repo.contentLayer(page)!!.id

        repo.appendOp(layer, SoilObject(id = "", parentId = "", type = SoilType.SURFACE_OP, kind = "METAL"))

        assertEquals("PAPER", repo.pages().single().kind)
    }

    /** A stroke after a surface change must not be mistaken for one. */
    @Test
    fun `only surface ops resolve the surface`() {
        repo.createDocument("x", surfaceKind = "PAPER")
        val page = repo.pages().single().id
        val layer = repo.contentLayer(page)!!.id

        repo.appendOp(layer, SoilObject(id = "", parentId = "", type = SoilType.SURFACE_OP, kind = "WOOD"))
        repo.appendOp(layer, op())

        assertEquals("WOOD", repo.resolvedSurface(page)!!.kind)
    }

    // --- Pages, as the page strip drives them -------------------------------

    /**
     * Reordering has to renumber the survivors, or the next append lands on top
     * of something.
     */
    @Test
    fun `pages stay a dense sequence through reordering`() {
        repo.createDocument("x", surfaceKind = "A")
        listOf("B", "C", "D", "E").forEach { repo.addPage(surfaceKind = it) }

        repo.movePage(repo.pages()[4].id, 0)
        repo.movePage(repo.pages()[2].id, 4)

        assertEquals(listOf(0, 1, 2, 3, 4), repo.pages().map { it.order })
        assertEquals(listOf("E", "A", "C", "D", "B"), repo.pages().map { it.kind })
    }

    /** Deleting a page must not leave the ones after it sharing positions. */
    @Test
    fun `deleting a middle page leaves the rest in order`() {
        repo.createDocument("x", surfaceKind = "A")
        listOf("B", "C").forEach { repo.addPage(surfaceKind = it) }

        repo.deletePage(repo.pages()[1].id)

        assertEquals(listOf("A", "C"), repo.pages().map { it.kind })
        assertEquals(3, repo.addPage(surfaceKind = "D").order)
        assertEquals("a tombstone still holds its place", listOf("A", "C", "D"), repo.pages().map { it.kind })
    }

    /** Each page gets its own paper and its own seed. */
    @Test
    fun `pages carry their own surface`() {
        repo.createDocument("x", surfaceKind = "PAPER", surfaceSeed = 11L)
        val second = repo.addPage(surfaceKind = "WATERCOLOR", surfaceSeed = 22L)

        assertEquals("PAPER", repo.pages()[0].kind)
        assertEquals(11L, repo.pages()[0].seed)
        assertEquals("WATERCOLOR", repo.pages()[1].kind)
        assertEquals(22L, second.seed)
    }

    /** Reopening lands where you left off, and only if that page still exists. */
    @Test
    fun `the last opened page is remembered and validated`() {
        repo.createDocument("x")
        val second = repo.addPage()
        repo.setLastOpenedPage(second.id)
        assertEquals(second.id, repo.lastOpenedPage()!!.id)

        repo.deletePage(second.id)
        assertNull("a deleted page is not somewhere to land", repo.lastOpenedPage())
    }

    /**
     * The view size a page's marks are in, so a sketchbook drawn on one tablet
     * can be opened on another. A page created without one stays without one —
     * that is every page written before this existed, and the right thing to do
     * with those is nothing.
     */
    @Test
    fun `a page records the size it was drawn at, or honestly records nothing`() {
        repo.createDocument("x")
        val blank = repo.pages().single()
        assertNull(blank.width)
        assertNull(blank.height)

        val stamped = repo.addPage(drawnWidth = 1606.5f, drawnHeight = 1147.5f)
        assertEquals(1606.5f, stamped.width)
        assertEquals(1147.5f, stamped.height)

        // And one can claim its space later, which is what an empty page does the
        // moment it takes a mark.
        repo.setPageSize(blank.id, 2200f, 1440f)
        val claimed = repo.pages().first { it.id == blank.id }
        assertEquals(2200f, claimed.width)
        assertEquals(1440f, claimed.height)
    }
}
