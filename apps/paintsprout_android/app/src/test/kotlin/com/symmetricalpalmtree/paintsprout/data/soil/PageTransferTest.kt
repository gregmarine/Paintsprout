package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.index.Sentinels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A page travelling between two documents — the sketchbook table in a file and
 * the scratchpad table in the index, driven through the *same* repository over
 * two real stores. Which is the point of the exercise: if the transfer needed to
 * know which end was which, this test could not be written this way.
 */
class PageTransferTest {

    private var clock = 1_000L
    private var ids = 0

    private fun repoOver(store: ObjectStore, root: String) =
        SketchbookRepository(store, rootId = root, now = { clock++ }, newId = { "t-${ids++}" })

    private fun op(color: String) = SoilObject(id = "", parentId = "", type = SoilType.STROKE, color = color)

    /** A book with one page carrying [marks], on named paper. */
    private fun bookWith(store: ObjectStore, marks: List<String>, surface: String = "WATERCOLOR"): SketchbookRepository {
        val repo = repoOver(store, "book")
        repo.createDocument("Source", surfaceKind = surface, surfaceSeed = 42L)
        val layer = repo.contentLayer(repo.pages().single().id)!!.id
        marks.forEach { repo.appendOp(layer, op(it)) }
        return repo
    }

    private fun both(body: (source: SketchbookRepository, pad: SketchbookRepository) -> Unit) {
        JdbcObjectStore(table = SchemaSql.SKETCHBOOK_TABLE).use { src ->
            JdbcObjectStore(table = SchemaSql.SCRATCHPAD_TABLE).use { dst ->
                val source = bookWith(src, listOf("#FF000001", "#FF000002"))
                val pad = repoOver(dst, Sentinels.SCRATCHPAD_ROOT_ID)
                pad.createDocument(Scratchpad.NAME)
                body(source, pad)
            }
        }
    }

    // --- What travels -------------------------------------------------------

    @Test
    fun `the page lands as the last page of the destination`() {
        both { source, pad ->
            val page = source.pages().single().id
            val landed = pad.insertPage(source.pageSubtree(page), page)

            assertNotNull(landed)
            assertEquals(2, pad.pageCount())
            assertEquals(landed!!.id, pad.pages().last().id)
            assertEquals(Sentinels.SCRATCHPAD_ROOT_ID, landed.parentId)
        }
    }

    @Test
    fun `the marks travel, in order`() {
        both { source, pad ->
            val page = source.pages().single().id
            val landed = pad.insertPage(source.pageSubtree(page), page)!!

            val layer = pad.contentLayer(landed.id)!!
            assertEquals(listOf("#FF000001", "#FF000002"), pad.committedOps(layer.id).map { it.color })
        }
    }

    /** The paper comes with the page: it is a column on the page row. */
    @Test
    fun `the surface travels`() {
        both { source, pad ->
            val page = source.pages().single()
            val landed = pad.insertPage(source.pageSubtree(page.id), page.id)!!

            assertEquals("WATERCOLOR", landed.kind)
            assertEquals(42L, landed.seed)
        }
    }

    /** So does the undo frontier — the page arrives mid-history, not flattened. */
    @Test
    fun `an undone mark is still undone at the other end`() {
        both { source, pad ->
            val page = source.pages().single().id
            source.undo(source.contentLayer(page)!!.id)

            val landed = pad.insertPage(source.pageSubtree(page), page)!!
            val layer = pad.contentLayer(landed.id)!!

            assertEquals(1, pad.undoDepth(layer.id))
            assertEquals(1, pad.committedOps(layer.id).size)
            assertEquals("and the redo is still there", 1, pad.redoableOps(layer.id).size)
        }
    }

    // --- Ids ----------------------------------------------------------------

    @Test
    fun `nothing keeps the id it had in the document it came from`() {
        both { source, pad ->
            val page = source.pages().single().id
            val before = source.pageSubtree(page).map { it.id }.toSet()
            val landed = pad.insertPage(source.pageSubtree(page), page)!!

            val after = (listOf(landed) + pad.pageSubtree(landed.id)).map { it.id }
            assertTrue(after.none { it in before })
        }
    }

    /** Sending the same page twice is the collision case, one door along. */
    @Test
    fun `sending the same page twice does not collide`() {
        both { source, pad ->
            val page = source.pages().single().id
            pad.insertPage(source.pageSubtree(page), page)
            pad.insertPage(source.pageSubtree(page), page)

            assertEquals(3, pad.pageCount())
            assertEquals(3, pad.pages().map { it.id }.toSet().size)
        }
    }

    /** And so is sending it straight back into the book it came from. */
    @Test
    fun `a page can be sent back where it came from`() {
        JdbcObjectStore(table = SchemaSql.SKETCHBOOK_TABLE).use { src ->
            val source = bookWith(src, listOf("#FF000001"))
            val page = source.pages().single().id

            source.insertPage(source.pageSubtree(page), page)

            assertEquals(2, source.pageCount())
            assertEquals(2, source.pages().map { it.id }.toSet().size)
            val copy = source.pages().last()
            assertEquals(listOf("#FF000001"), source.committedOps(source.contentLayer(copy.id)!!.id).map { it.color })
        }
    }

    // --- Refusals -----------------------------------------------------------

    @Test
    fun `a subtree without its own page lands nothing`() {
        both { source, pad ->
            assertNull(pad.insertPage(emptyList(), "nobody"))
            assertNull(pad.insertPage(source.pageSubtree(source.pages().single().id), "nobody"))
            assertEquals(1, pad.pageCount())
        }
    }

    @Test
    fun `asking for the subtree of something that is not a page gives nothing`() {
        both { source, _ ->
            val page = source.pages().single().id
            assertEquals(emptyList<SoilObject>(), source.pageSubtree(source.contentLayer(page)!!.id))
            assertEquals(emptyList<SoilObject>(), source.pageSubtree("no-such-id"))
        }
    }

    /** A deleted page is not a page you can send. */
    @Test
    fun `a soft-deleted page is not sendable`() {
        both { source, pad ->
            val extra = source.addPage()
            source.deletePage(extra.id)

            assertFalse(source.pageSubtree(extra.id).any { it.id == extra.id })
            assertNull(pad.insertPage(source.pageSubtree(extra.id), extra.id))
        }
    }
}
