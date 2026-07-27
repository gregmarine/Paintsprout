package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.paint.FolderAddOp
import com.symmetricalpalmtree.paintsprout.paint.FolderDeleteOp
import com.symmetricalpalmtree.paintsprout.paint.FolderOpacityOp
import com.symmetricalpalmtree.paintsprout.paint.FolderVisibilityOp
import com.symmetricalpalmtree.paintsprout.paint.LayerAddOp
import com.symmetricalpalmtree.paintsprout.paint.LayerOrderOp
import com.symmetricalpalmtree.paintsprout.paint.LayerStack
import com.symmetricalpalmtree.paintsprout.paint.StackEntry
import com.symmetricalpalmtree.paintsprout.paint.StackSpot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Folders as the file holds them, over the app's real SQL.
 *
 * The direction flip is what most of this is about: the panel reads a stack
 * top-down and `order` counts up from the bottom, and every one of these goes
 * through [Stacks] in both directions to make sure the two ends still agree.
 */
class FoldersTest {

    private lateinit var store: JdbcObjectStore
    private lateinit var repo: SketchbookRepository

    private var clock = 1_000L
    private var ids = 0
    private lateinit var pageId: String

    @Before
    fun setUp() {
        store = JdbcObjectStore()
        repo = SketchbookRepository(
            store = store,
            rootId = "book-1",
            now = { clock },
            newId = { "id-${ids++}" },
        )
        repo.createDocument("Studies")
        pageId = repo.pages().single().id
    }

    @After
    fun tearDown() = store.close()

    /** The page's stack as the panel would read it: top-down, ids only. */
    private fun readTopDown() = repo.stack(pageId).map { it.id }

    private fun loose(id: String) = StackEntry(id, isFolder = false)
    private fun folder(id: String, parent: String = "") = StackEntry(id, isFolder = true, parentId = parent)
    private fun inside(id: String, parent: String) = StackEntry(id, isFolder = false, parentId = parent)

    // --- Rows -----------------------------------------------------------------

    @Test
    fun `a folder is a row on the page like a layer is`() {
        val f = repo.addFolder(pageId, "Figure")
        assertEquals(SoilType.GROUP, f.type)
        assertEquals(pageId, f.parentId)
        assertEquals("Figure", f.text)
        assertEquals(listOf(f.id), repo.folders(pageId).map { it.id })
    }

    /** A folder is not a layer, however alike the rows look. */
    @Test
    fun `folders stay out of the paint order`() {
        val first = repo.contentLayer(pageId)!!
        repo.addFolder(pageId, "Figure")
        assertEquals(listOf(first.id), repo.layers(pageId).map { it.id })
    }

    @Test
    fun `a layer filed in a folder is still a layer of the page`() {
        val first = repo.contentLayer(pageId)!!
        val f = repo.addFolder(pageId, "Figure")
        val inner = repo.addLayer(f.id, "Line")

        val layers = repo.layers(pageId).map { it.id }
        assertEquals("both, wherever they are filed", setOf(first.id, inner.id), layers.toSet())
    }

    // --- Which way up ---------------------------------------------------------

    /**
     * The whole point of the flip. `order` counts up from the bottom, so the row
     * numbered highest is the one the panel shows first.
     */
    @Test
    fun `the panel reads the stack the opposite way the rows are numbered`() {
        val bottom = repo.contentLayer(pageId)!!
        val middle = repo.addLayer(pageId, "Middle")
        val top = repo.addLayer(pageId, "Top")

        assertEquals(listOf(0, 1, 2), listOf(bottom, middle, top).map { repo.stackRows(pageId).first { r -> r.id == it.id }.order })
        assertEquals(listOf(top.id, middle.id, bottom.id), readTopDown())
        assertEquals(listOf(bottom.id, middle.id, top.id), repo.layers(pageId).map { it.id })
    }

    @Test
    fun `a folder's contents follow it, top-down`() {
        val sky = repo.contentLayer(pageId)!!
        val f = repo.addFolder(pageId, "Figure")
        val shadow = repo.addLayer(f.id, "Shadow")
        val line = repo.addLayer(f.id, "Line")

        assertEquals(listOf(f.id, line.id, shadow.id, sky.id), readTopDown())
        // And the paint still goes down bottom-first, nesting or no nesting.
        assertEquals(listOf(sky.id, shadow.id, line.id), repo.layers(pageId).map { it.id })
    }

    // --- Writing an arrangement back ------------------------------------------

    @Test
    fun `the arrangement written is the arrangement read back`() {
        val sky = repo.contentLayer(pageId)!!
        val f = repo.addFolder(pageId, "Figure")
        val line = repo.addLayer(pageId, "Line")

        repo.setStackOrder(
            pageId,
            LayerStack(listOf(folder(f.id), inside(line.id, f.id), loose(sky.id))),
        )

        assertEquals(listOf(f.id, line.id, sky.id), readTopDown())
        assertEquals(f.id, store.byId(line.id)!!.parentId)
    }

    @Test
    fun `a layer dragged out of a folder is a child of the page again`() {
        val f = repo.addFolder(pageId, "Figure")
        val line = repo.addLayer(f.id, "Line")
        val sky = repo.contentLayer(pageId)!!

        repo.setStackOrder(
            pageId,
            LayerStack(listOf(folder(f.id), loose(line.id), loose(sky.id))),
        )

        assertEquals(pageId, store.byId(line.id)!!.parentId)
        assertEquals(listOf(f.id, line.id, sky.id), readTopDown())
    }

    /** Siblings are numbered among themselves, not across the page. */
    @Test
    fun `order restarts inside each folder`() {
        val sky = repo.contentLayer(pageId)!!
        val f = repo.addFolder(pageId, "Figure")
        val line = repo.addLayer(f.id, "Line")
        val shadow = repo.addLayer(f.id, "Shadow")

        repo.setStackOrder(
            pageId,
            LayerStack(listOf(folder(f.id), inside(line.id, f.id), inside(shadow.id, f.id), loose(sky.id))),
        )

        assertEquals("bottom of the folder", 0, store.byId(shadow.id)!!.order)
        assertEquals("above it", 1, store.byId(line.id)!!.order)
        assertEquals("bottom of the page", 0, store.byId(sky.id)!!.order)
        assertEquals("above it", 1, store.byId(f.id)!!.order)
    }

    @Test
    fun `nesting a folder inside a folder round-trips`() {
        val outer = repo.addFolder(pageId, "Outer")
        val inner = repo.addFolder(pageId, "Inner")
        val deep = repo.addLayer(pageId, "Deep")
        val sky = repo.contentLayer(pageId)!!

        repo.setStackOrder(
            pageId,
            LayerStack(
                listOf(
                    folder(outer.id),
                    folder(inner.id, outer.id),
                    inside(deep.id, inner.id),
                    loose(sky.id),
                ),
            ),
        )

        assertEquals(listOf(outer.id, inner.id, deep.id, sky.id), readTopDown())
        val back = Stacks.stackOf(pageId, repo.stackRows(pageId))
        assertEquals(listOf(inner.id, outer.id), back.ancestors(deep.id))
    }

    /**
     * The whole cycle the editor actually performs: make a folder above the
     * layer being worked on, write the arrangement the canvas now has, read it
     * back. Found on glass — the folder came back one place lower than it was
     * made.
     */
    @Test
    fun `a new folder is still where it was made after a reload`() {
        val paint = repo.contentLayer(pageId)!!
        val l3 = repo.addLayer(pageId, "Layer 3")
        val l2 = repo.addLayer(pageId, "Layer 2")
        // Top-down, the page reads: Layer 2, Layer 3, Paint.
        assertEquals(listOf(l2.id, l3.id, paint.id), readTopDown())

        val f = repo.addFolder(pageId, "Folder 1")
        assertEquals("made above the working layer", listOf(f.id, l2.id, l3.id, paint.id), readTopDown())

        // What the canvas would hand back, unchanged, and write.
        repo.setStackOrder(
            pageId,
            LayerStack(listOf(folder(f.id), loose(l2.id), loose(l3.id), loose(paint.id))),
        )

        assertEquals(listOf(f.id, l2.id, l3.id, paint.id), readTopDown())
    }

    // --- Coming and going -----------------------------------------------------

    @Test
    fun `a deleted folder leaves the stack and its contents do not`() {
        val f = repo.addFolder(pageId, "Figure")
        val line = repo.addLayer(f.id, "Line")

        // Contents lifted out first, the way the editor does it, then the folder.
        repo.setStackOrder(pageId, LayerStack(listOf(folder(f.id), loose(line.id))))
        repo.removeFolder(f.id)

        assertTrue(repo.folders(pageId).isEmpty())
        assertTrue("the layer is still here", repo.layers(pageId).any { it.id == line.id })
        assertFalse(f.id in readTopDown())
    }

    /**
     * Undo brings back *the same* folder. Every step still on the timeline names
     * it by the id it had, so a new one would leave them pointing at nothing.
     */
    @Test
    fun `a folder comes back under the id it went away with`() {
        val f = repo.addFolder(pageId, "Figure")
        repo.removeFolder(f.id)
        assertTrue(repo.folders(pageId).isEmpty())

        repo.ensureFolder(f.id, pageId, "Figure")

        assertEquals(listOf(f.id), repo.folders(pageId).map { it.id })
        assertEquals("Figure", store.byId(f.id)!!.text)
        assertNull(store.byId(f.id)!!.deletedAt)
    }

    @Test
    fun `a folder's eye, dial and twisty survive a write`() {
        val f = repo.addFolder(pageId, "Figure")
        repo.setLayerState(f.id, visible = false, opacity = 0.4f)
        repo.setFolderCollapsed(f.id, true)

        val row = store.byId(f.id)!!
        assertEquals(0.4f, row.opacity)
        assertEquals(0, (row.flags ?: 0) and SoilFlags.LAYER_VISIBLE)
        assertTrue(row.hasFlag(SoilFlags.FOLDER_COLLAPSED))
    }

    /** Shutting a folder is not hiding it; the two flags do not run together. */
    @Test
    fun `collapsed and hidden are different bits`() {
        val f = repo.addFolder(pageId, "Figure")
        repo.setFolderCollapsed(f.id, true)

        val row = store.byId(f.id)!!
        assertTrue(row.hasFlag(SoilFlags.FOLDER_COLLAPSED))
        assertTrue("still showing", (row.flags ?: 0) and SoilFlags.LAYER_VISIBLE != 0)
    }

    /**
     * A deleted layer keeps its row exactly where it was, so the step that
     * removed it stays undoable. Tombstoning the folder over the top of one
     * would strand it where no walk from the page reaches it, and the undo that
     * was meant to bring the layer back with all its paint would find nothing.
     */
    @Test
    fun `deleting a folder does not strand a deleted layer filed in it`() {
        val f = repo.addFolder(pageId, "Figure")
        val shelved = repo.addLayer(f.id, "Line")

        repo.removeFolder(f.id)

        val row = store.byId(shelved.id)!!
        assertNull("still alive, as a shelved layer is", row.deletedAt)
        assertEquals("and reachable from the page again", pageId, row.parentId)
        assertTrue(repo.layers(pageId).any { it.id == shelved.id })
    }

    // --- Steps on the timeline ------------------------------------------------

    /**
     * A folder's move is filed under whichever layer was being worked on, so the
     * row has to say which folder it was actually about. Read it back naming the
     * layer and undo would move the wrong thing.
     */
    @Test
    fun `a folder's move names the folder, not the layer it is filed under`() {
        val op = LayerOrderOp(
            from = StackSpot("outer", 0),
            to = StackSpot(StackSpot.LOOSE, 2),
            subject = "the-folder",
        ).also { it.layerId = "the-working-layer" }

        val back = OpRows.readOp(OpRows.layerOrderRow(op)) { emptyList() } as LayerOrderOp
        back.layerId = "the-working-layer"

        assertEquals("the-folder", back.moved)
        assertEquals("outer", back.from.folder)
        assertEquals(0, back.from.at)
        assertEquals(StackSpot.LOOSE, back.to.folder)
        assertEquals(2, back.to.at)
    }

    /** A layer is filed under itself and needs no second name. */
    @Test
    fun `a layer's move is about the layer it is filed under`() {
        val op = LayerOrderOp(StackSpot(StackSpot.LOOSE, 0), StackSpot("f", 1))
            .also { it.layerId = "the-layer" }
        val back = OpRows.readOp(OpRows.layerOrderRow(op)) { emptyList() } as LayerOrderOp
        back.layerId = "the-layer"
        assertEquals("the-layer", back.moved)
    }

    /**
     * The reason no step written before folders existed needed rewriting: a page
     * with no folders is one set of siblings, and counting up from the bottom of
     * it gives exactly the numbers those steps already carry.
     */
    @Test
    fun `a step from before folders reads as one nothing holds`() {
        val old = SoilObject(id = "", parentId = "", type = SoilType.LAYER_ADD, text = "Layer 2", opCount = 1)
        val back = OpRows.readOp(old) { emptyList() } as LayerAddOp
        assertEquals(StackSpot.LOOSE, back.spot.folder)
        assertEquals(1, back.spot.at)
    }

    @Test
    fun `a folder's own steps round-trip`() {
        val added = OpRows.readOp(
            OpRows.folderAddRow(FolderAddOp("f1", "Figure", StackSpot("outer", 2))),
        ) { emptyList() } as FolderAddOp
        assertEquals("f1", added.folderId)
        assertEquals("Figure", added.name)
        assertEquals("outer", added.spot.folder)
        assertEquals(2, added.spot.at)

        val gone = OpRows.readOp(
            OpRows.folderDeleteRow(FolderDeleteOp("f1", "Figure", StackSpot(StackSpot.LOOSE, 0), held = 3)),
        ) { emptyList() } as FolderDeleteOp
        assertEquals("f1", gone.folderId)
        assertEquals("how many spilled out, which undo cannot work out", 3, gone.held)

        val dimmed = OpRows.readOp(
            OpRows.folderOpacityRow(FolderOpacityOp("f1", 0.25f)),
        ) { emptyList() } as FolderOpacityOp
        assertEquals("f1", dimmed.folderId)
        assertEquals(0.25f, dimmed.opacity, 1e-6f)

        val hidden = OpRows.readOp(
            OpRows.folderVisibilityRow(FolderVisibilityOp("f1", false)),
        ) { emptyList() } as FolderVisibilityOp
        assertEquals("f1", hidden.folderId)
        assertFalse(hidden.visible)
    }

    /** A step about no folder would sit in the timeline swallowing an undo. */
    @Test
    fun `a folder step naming no folder is dropped`() {
        val row = SoilObject(id = "", parentId = "", type = SoilType.FOLDER_OPACITY, opacity = 0.5f)
        assertNull(OpRows.readOp(row) { emptyList() })
    }

    // --- Files we did not write -----------------------------------------------

    /** A parent cycle must cost a page load an answer, not a hang. */
    @Test
    fun `a cycle among folders does not hang the read`() {
        val a = repo.addFolder(pageId, "A")
        val b = repo.addFolder(a.id, "B")
        store.upsert(store.byId(a.id)!!.copy(parentId = b.id))

        val read = Stacks.topDown(pageId, repo.stackRows(pageId))
        assertFalse("neither is reachable from the page", read.any { it.id == a.id })
    }

    /** Deeper than the editor allows: read what fits and stop. */
    @Test
    fun `the read stops at the nesting cap`() {
        var parent = pageId
        val chain = (0..LayerStack.MAX_NESTING + 2).map {
            repo.addFolder(parent, "f$it").also { row -> parent = row.id }
        }
        val read = Stacks.topDown(pageId, repo.stackRows(pageId)).map { it.id }
        assertTrue("the shallow ones are there", chain.take(LayerStack.MAX_NESTING).all { it.id in read })
        assertFalse("the deepest is not", chain.last().id in read)
    }
}
