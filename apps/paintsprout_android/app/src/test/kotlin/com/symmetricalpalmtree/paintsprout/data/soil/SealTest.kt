package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rules the seal has to keep, stated where they can be checked.
 *
 * The seal itself needs a real connection and a real device, so what is pinned
 * here is the surrounding discipline: which edits may move `updatedAt`, and what
 * a clean close is allowed to leave on disk.
 */
class SealTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * `updatedAt` drives the backup predicate. Bumping it for opening a document,
     * or for a bookkeeping write, re-flags the file for copying; not bumping it
     * for a rename strands the file's backup forever.
     */
    @Test
    fun `only real modifications move updatedAt`() {
        assertTrue(IndexEdit.PAGE_COUNT_REFRESH.bumpsUpdatedAt)
        assertTrue(IndexEdit.COVER_REFRESH.bumpsUpdatedAt)
        assertTrue(IndexEdit.RENAME.bumpsUpdatedAt)

        assertFalse("logging that a book was opened is not an edit", IndexEdit.ACTIVITY_LOG.bumpsUpdatedAt)
        assertFalse(IndexEdit.PIN_TOGGLE.bumpsUpdatedAt)
        assertFalse(IndexEdit.FORMAT_CONVERSION.bumpsUpdatedAt)
    }

    /**
     * A file browser must show sketchbooks and nothing else. The index is the one
     * documented exception — it never closes, so it has no clean-close moment.
     */
    @Test
    fun `a document's sidecars are the three the seal must remove`() {
        val doc = File("/x/Garden/3f2a1b8c-4d5e-4f60-8a91-b2c3d4e5f607.soil")
        assertEquals(
            listOf("-wal", "-shm", "-journal").map { doc.name + it },
            SoilFiles.sidecars(doc).map { it.name },
        )
    }

    /** After a clean seal, only the document itself may remain. */
    @Test
    fun `listDocuments ignores anything a seal should have removed`() {
        val root = tmp.newFolder()
        val garden = SoilFiles.garden(root)
        val id = "3f2a1b8c-4d5e-4f60-8a91-b2c3d4e5f607"
        listOf("$id.soil", "$id.soil-wal", "$id.soil-shm", "$id.soil-journal")
            .forEach { File(garden, it).writeText("x") }

        assertEquals(listOf("$id.soil"), SoilFiles.listDocuments(root).map { it.name })
    }

    /**
     * The seal writes the cache at the frontier it was composited at, so an undo
     * afterwards makes it stale rather than wrong — and stale means replay.
     */
    @Test
    fun `a cache written at the seal is invalidated by a later undo`() {
        JdbcObjectStore().use { store ->
            var n = 0
            val repo = SketchbookRepository(store, "book", now = { 1_000 }, newId = { "s-${n++}" })
            repo.createDocument("x")
            val layer = repo.contentLayer(repo.pages().single().id)!!.id
            repeat(2) { repo.appendOp(layer, SoilObject(id = "", parentId = "", type = SoilType.STROKE)) }

            repo.writeCache(layer, byteArrayOf(1), 100f, 100f)
            assertEquals(2, repo.cache(layer)!!.opCount)

            repo.undo(layer)
            assertTrue("stale, so the next open replays", repo.cache(layer) == null)
        }
    }
}
