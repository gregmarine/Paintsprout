package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.paint.PasteOp
import com.symmetricalpalmtree.paintsprout.paint.Stroke
import com.symmetricalpalmtree.paintsprout.paint.StrokeOp
import com.symmetricalpalmtree.paintsprout.paint.StrokePoint
import com.symmetricalpalmtree.paintsprout.paint.Tool
import com.symmetricalpalmtree.paintsprout.paint.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A paste is one step in the timeline holding several marks. These are the two
 * halves of that claim: it stores as a parent row with ops beneath it, and it
 * comes back as one op the undo frontier steps over in a single move.
 */
class PasteRowsTest {

    private var clock = 1_000L
    private var ids = 0

    private fun repo(store: ObjectStore) =
        SketchbookRepository(store, rootId = "book", now = { clock++ }, newId = { "p-${ids++}" })

    private fun strokeRow(color: String) = OpRows.strokeRow(
        Stroke(tool = Tool.PEN, color = 0xFF112233.toInt()).apply {
            add(StrokePoint(Vec2(1f, 2f), 3f))
            add(StrokePoint(Vec2(4f, 5f), 3f))
        },
    ).copy(color = color)

    /** Writes a paste of [colors] the way the session does, and returns its row. */
    private fun writePaste(repo: SketchbookRepository, layer: String, colors: List<String>): SoilObject {
        val parent = repo.appendOp(layer, OpRows.pasteRow(colors.size))
        colors.forEachIndexed { i, c -> repo.attach(parent.id, strokeRow(c).copy(order = i)) }
        return parent
    }

    private fun layerOf(repo: SketchbookRepository): String {
        repo.createDocument("x")
        return repo.contentLayer(repo.pages().single().id)!!.id
    }

    @Test
    fun `a paste is one op on the timeline, whatever it holds`() {
        JdbcObjectStore(table = SchemaSql.SKETCHBOOK_TABLE).use { s ->
            val repo = repo(s)
            val layer = layerOf(repo)
            writePaste(repo, layer, listOf("#FF000001", "#FF000002", "#FF000003"))

            assertEquals("three marks, one step", 1, repo.committedOps(layer).size)
            assertEquals(1, repo.undoDepth(layer))
        }
    }

    @Test
    fun `undo takes the whole paste back, and redo brings it all`() {
        JdbcObjectStore(table = SchemaSql.SKETCHBOOK_TABLE).use { s ->
            val repo = repo(s)
            val layer = layerOf(repo)
            repo.appendOp(layer, strokeRow("#FFAAAAAA"))
            writePaste(repo, layer, listOf("#FF000001", "#FF000002"))

            repo.undo(layer)
            assertEquals(1, repo.committedOps(layer).size)
            assertEquals("the stroke drawn before it", "#FFAAAAAA", repo.committedOps(layer).single().color)

            repo.redo(layer)
            assertEquals(2, repo.committedOps(layer).size)
        }
    }

    /** The pasted ops are children, so they never appear as steps of their own. */
    @Test
    fun `the pasted ops are not on the layer`() {
        JdbcObjectStore(table = SchemaSql.SKETCHBOOK_TABLE).use { s ->
            val repo = repo(s)
            val layer = layerOf(repo)
            val paste = writePaste(repo, layer, listOf("#FF000001", "#FF000002"))

            assertEquals(listOf(paste.id), repo.committedOps(layer).map { it.id })
            assertEquals(2, repo.attachmentsOf(listOf(paste.id)).size)
        }
    }

    @Test
    fun `it reads back as one op holding its marks in order`() {
        JdbcObjectStore(table = SchemaSql.SKETCHBOOK_TABLE).use { s ->
            val repo = repo(s)
            val layer = layerOf(repo)
            val paste = writePaste(repo, layer, listOf("#FF000001", "#FF000002", "#FF000003"))

            val children = repo.attachmentsOf(listOf(paste.id)).groupBy { it.parentId }
            val op = OpRows.readOp(repo.committedOps(layer).single()) { id -> children[id].orEmpty() }

            assertTrue(op is PasteOp)
            val ops = (op as PasteOp).ops
            assertEquals(3, ops.size)
            assertEquals(
                listOf(0xFF000001.toInt(), 0xFF000002.toInt(), 0xFF000003.toInt()),
                ops.map { (it as StrokeOp).stroke.color },
            )
        }
    }

    /** One damaged mark costs that mark; the rest of the paste still replays. */
    @Test
    fun `a paste survives an unreadable child`() {
        JdbcObjectStore(table = SchemaSql.SKETCHBOOK_TABLE).use { s ->
            val repo = repo(s)
            val layer = layerOf(repo)
            val parent = repo.appendOp(layer, OpRows.pasteRow(2))
            repo.attach(parent.id, strokeRow("#FF000001").copy(order = 0))
            repo.attach(parent.id, strokeRow("#FF000002").copy(order = 1, blob = byteArrayOf(9, 9)))

            val children = repo.attachmentsOf(listOf(parent.id)).groupBy { it.parentId }
            val op = OpRows.readOp(repo.committedOps(layer).single()) { id -> children[id].orEmpty() }

            assertEquals(1, (op as PasteOp).ops.size)
        }
    }

    /** An empty paste is still a paste — it just replays nothing. */
    @Test
    fun `a paste with nothing under it reads as empty`() {
        JdbcObjectStore(table = SchemaSql.SKETCHBOOK_TABLE).use { s ->
            val repo = repo(s)
            val layer = layerOf(repo)
            repo.appendOp(layer, OpRows.pasteRow(0))

            val op = OpRows.readOp(repo.committedOps(layer).single()) { emptyList() }
            assertEquals(0, (op as PasteOp).ops.size)
        }
    }
}
