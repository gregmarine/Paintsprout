package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.index.IndexObject
import com.symmetricalpalmtree.paintsprout.data.index.IndexType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Import is the one path where the app acts on a file it did not write, and
 * every id in that file becomes a row key or a filename. These are the checks
 * that happen before anything is believed.
 */
class ImportPlanTest {

    private val docId = UUID.randomUUID().toString()
    private val folderId = UUID.randomUUID().toString()

    private fun meta(
        id: String = docId,
        name: String = "Harbour",
        path: List<FolderRef> = emptyList(),
    ) = SketchbookMeta(sketchbookId = id, name = name, createdAt = 1, updatedAt = 2, folderPath = path)

    private fun folder(id: String, name: String = "Sketches", parentId: String? = null) =
        IndexObject(id = id, type = IndexType.FOLDER, name = name, parentId = parentId, createdAt = 1, updatedAt = 1)

    // --- Validation ---------------------------------------------------------

    @Test
    fun `a well-formed manifest passes`() {
        val checked = ImportPlan.check(meta(path = listOf(FolderRef(folderId, "Sketches"))))
        assertEquals(ImportPlan.Verdict.OK, checked.verdict)
        assertTrue(checked.isOk)
    }

    @Test
    fun `no manifest at all is refused`() {
        assertEquals(ImportPlan.Verdict.NO_MANIFEST, ImportPlan.check(null).verdict)
        assertFalse(ImportPlan.check(null).isOk)
    }

    /**
     * The id becomes `Garden/<id>.soil`. This is the check that stops a crafted
     * file writing wherever the sender likes.
     */
    @Test
    fun `a document id that is not a UUID is refused`() {
        for (bad in listOf("../../etc/passwd", "not-a-uuid", "", "  ", "$docId/../x", "$docId.soil")) {
            val checked = ImportPlan.check(meta(id = bad))
            assertEquals("should refuse: $bad", ImportPlan.Verdict.BAD_ID, checked.verdict)
            assertNull(checked.meta)
        }
    }

    /** Folder ids become index keys that other rows point at. Same rule. */
    @Test
    fun `a folder id that is not a UUID is refused`() {
        val checked = ImportPlan.check(meta(path = listOf(FolderRef("../evil", "Sketches"))))
        assertEquals(ImportPlan.Verdict.BAD_ID, checked.verdict)
        assertEquals("../evil", checked.badId)
    }

    @Test
    fun `a folder's parent id is checked too`() {
        val checked = ImportPlan.check(
            meta(path = listOf(FolderRef(folderId, "Sketches", parentId = "../evil"))),
        )
        assertEquals(ImportPlan.Verdict.BAD_ID, checked.verdict)
    }

    /** A root-level folder legitimately has no parent. */
    @Test
    fun `a null parent is fine`() {
        assertEquals(
            ImportPlan.Verdict.OK,
            ImportPlan.check(meta(path = listOf(FolderRef(folderId, "Sketches", parentId = null)))).verdict,
        )
    }

    // --- Collisions ---------------------------------------------------------

    @Test
    fun `collision states`() {
        val row = IndexObject(id = docId, type = IndexType.SKETCHBOOK, name = "x", parentId = null, createdAt = 1, updatedAt = 1)
        assertEquals(ImportPlan.Collision.NONE, ImportPlan.collisionOf(null, isOpen = false))
        assertEquals(ImportPlan.Collision.EXISTS, ImportPlan.collisionOf(row, isOpen = false))
        assertEquals(ImportPlan.Collision.EXISTS_AND_OPEN, ImportPlan.collisionOf(row, isOpen = true))
    }

    /** Nothing here yet: "open" is about a document, and there isn't one. */
    @Test
    fun `an open flag with nothing to collide with is still no collision`() {
        assertEquals(ImportPlan.Collision.NONE, ImportPlan.collisionOf(null, isOpen = true))
    }

    /**
     * Found on device: deleting is soft, `byId` answers for tombstones, and a
     * book the user had deleted offered to "replace" itself on re-import.
     */
    @Test
    fun `a deleted book is not something you already have`() {
        val tombstone = IndexObject(
            id = docId, type = IndexType.SKETCHBOOK, name = "x", parentId = null,
            createdAt = 1, updatedAt = 2, deletedAt = 3,
        )
        assertEquals(ImportPlan.Collision.NONE, ImportPlan.collisionOf(tombstone, isOpen = false))
    }

    /** And a deleted folder is recreated rather than adopted. */
    @Test
    fun `a deleted folder does not count as present`() {
        val known = mapOf(folderId to folder(folderId).copy(deletedAt = 9))
        val steps = ImportPlan.folderSteps(listOf(FolderRef(folderId, "Sketches"))) { known[it] }
        assertFalse(steps.single().exists)
    }

    // --- Folder recreation --------------------------------------------------

    @Test
    fun `missing folders are marked to create, present ones are not`() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val known = mapOf(a to folder(a))
        val steps = ImportPlan.folderSteps(
            listOf(FolderRef(a, "Sketches"), FolderRef(b, "Studies", parentId = a)),
        ) { known[it] }

        assertEquals(listOf(true, false), steps.map { it.exists })
        assertEquals(listOf("Sketches", "Studies"), steps.map { it.ref.name })
    }

    /**
     * Create-only: an existing folder is used as it stands. The incoming record
     * is a snapshot of somebody else's library and does not get to rename this
     * one's folders.
     */
    @Test
    fun `an existing folder with a different name is left alone`() {
        val known = mapOf(folderId to folder(folderId, name = "My own name"))
        val steps = ImportPlan.folderSteps(listOf(FolderRef(folderId, "Their name"))) { known[it] }
        assertTrue(steps.single().exists)
    }

    /** Rewriting what a row *is* on the word of a file is not an import's job. */
    @Test
    fun `an id that is a sketchbook here is not adopted as a folder`() {
        val known = mapOf(
            folderId to IndexObject(
                id = folderId, type = IndexType.SKETCHBOOK, name = "A book",
                parentId = null, createdAt = 1, updatedAt = 1,
            ),
        )
        val steps = ImportPlan.folderSteps(listOf(FolderRef(folderId, "Sketches"))) { known[it] }
        assertFalse("must not treat a book as its folder", steps.single().exists)
    }

    @Test
    fun `the document lands in the last folder of the path, or the root`() {
        assertNull(ImportPlan.parentOf(emptyList()))
        assertEquals(
            "f2",
            ImportPlan.parentOf(listOf(FolderRef("f1", "a"), FolderRef("f2", "b", parentId = "f1"))),
        )
    }

    // --- Names --------------------------------------------------------------

    @Test
    fun `a free name is used as it is`() {
        assertEquals("Harbour", ImportPlan.uniqueName("Harbour", listOf("Studies")))
    }

    @Test
    fun `a taken name is suffixed, and keeps counting`() {
        assertEquals("Harbour (2)", ImportPlan.uniqueName("Harbour", listOf("Harbour")))
        assertEquals("Harbour (3)", ImportPlan.uniqueName("Harbour", listOf("Harbour", "Harbour (2)")))
        assertEquals("Harbour (2)", ImportPlan.uniqueName("Harbour", listOf("Harbour", "Harbour (3)")))
    }

    @Test
    fun `a blank name gets a default rather than nothing`() {
        assertEquals("Sketchbook", ImportPlan.uniqueName("", emptyList()))
        assertEquals("Sketchbook (2)", ImportPlan.uniqueName("   ", listOf("Sketchbook")))
    }
}
