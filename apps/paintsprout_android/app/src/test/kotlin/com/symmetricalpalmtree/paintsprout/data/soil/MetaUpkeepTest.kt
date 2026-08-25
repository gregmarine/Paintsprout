package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.index.IndexObject
import com.symmetricalpalmtree.paintsprout.data.index.IndexType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which half of a document's identity the library owns and which the file owns.
 *
 * The stakes are export: a `.soil` is copied out byte for byte, so whatever this
 * left in the record is what an importing device believes months later.
 */
class MetaUpkeepTest {

    private val meta = SketchbookMeta(
        sketchbookId = "doc-1",
        name = "Old name",
        createdAt = 100,
        updatedAt = 200,
        appVersionCode = 3,
    )

    private fun row(name: String, parentId: String? = null) = IndexObject(
        id = "doc-1", type = IndexType.SKETCHBOOK, name = name, parentId = parentId,
        createdAt = 100, updatedAt = 500,
    )

    private fun folder(id: String, name: String, parentId: String? = null) = IndexObject(
        id = id, type = IndexType.FOLDER, name = name, parentId = parentId,
        createdAt = 1, updatedAt = 1,
    )

    // --- What the index owns ------------------------------------------------

    @Test
    fun `the name comes from the library`() {
        val out = MetaUpkeep.refresh(meta, row("Harbour"), emptyList(), now = 900)
        assertEquals("Harbour", out.name)
        assertEquals(900, out.updatedAt)
    }

    @Test
    fun `the ancestry comes from the library, root first`() {
        val path = MetaUpkeep.folderPathOf(
            listOf(folder("f1", "Sketches"), folder("f2", "Studies", parentId = "f1")),
        )
        val out = MetaUpkeep.refresh(meta, row("Harbour", parentId = "f2"), path, now = 900)

        assertEquals(listOf("Sketches", "Studies"), out.folderPath.map { it.name })
        assertEquals(listOf("f1", "f2"), out.folderPath.map { it.id })
        assertEquals("and each one remembers its own parent", "f1", out.folderPath[1].parentId)
    }

    /** A book moved back to the root has no ancestry, and must not keep the old one. */
    @Test
    fun `moving to the root empties the path`() {
        val filed = MetaUpkeep.refresh(meta, row("Harbour"), MetaUpkeep.folderPathOf(listOf(folder("f1", "Sketches"))), now = 900)
        val unfiled = MetaUpkeep.refresh(filed, row("Harbour"), emptyList(), now = 950)
        assertEquals(emptyList<FolderRef>(), unfiled.folderPath)
    }

    // --- What the file owns -------------------------------------------------

    /**
     * The index is a separate database and could be restored from a different
     * backup than the document beside it. Everything about *what this file is*
     * stays in the file.
     */
    @Test
    fun `identity and keying are never taken from the index`() {
        val encrypted = meta.copy(encrypted = true, keyScope = "SKETCHBOOK")
        val out = MetaUpkeep.refresh(encrypted, row("Harbour"), emptyList(), now = 900)

        assertEquals("doc-1", out.sketchbookId)
        assertEquals(100, out.createdAt)
        assertEquals(true, out.encrypted)
        assertEquals("SKETCHBOOK", out.keyScope)
        assertEquals(1, out.formatVersion)
    }

    /** No row — a document the index has lost — leaves the name alone. */
    @Test
    fun `a missing or blank index row does not blank the name`() {
        assertEquals("Old name", MetaUpkeep.refresh(meta, null, emptyList(), now = 900).name)
        assertEquals("Old name", MetaUpkeep.refresh(meta, row("   "), emptyList(), now = 900).name)
    }

    @Test
    fun `the build that wrote it is recorded`() {
        assertEquals(7, MetaUpkeep.refresh(meta, row("x"), emptyList(), now = 900, appVersionCode = 7).appVersionCode)
    }

    // --- The cover rule -----------------------------------------------------

    /**
     * A reader holding an encrypted file it cannot open must not be handed a
     * picture of what is inside it.
     */
    @Test
    fun `an encrypted document carries no cover, whatever it is offered`() {
        val encrypted = meta.copy(encrypted = true, cover = "already-here")
        val out = MetaUpkeep.refresh(encrypted, row("x"), emptyList(), cover = "new-one", now = 900)
        assertNull(out.cover)
    }

    @Test
    fun `a plaintext document keeps its cover, and takes a new one`() {
        val plain = meta.copy(cover = "already-here")
        assertEquals("already-here", MetaUpkeep.refresh(plain, row("x"), emptyList(), now = 900).cover)
        assertEquals("new-one", MetaUpkeep.refresh(plain, row("x"), emptyList(), cover = "new-one", now = 900).cover)
    }

    // --- Round trip ---------------------------------------------------------

    /** What upkeep writes has to survive the JSON it is written as. */
    @Test
    fun `a refreshed record round-trips through the stored JSON`() {
        val out = MetaUpkeep.refresh(
            meta,
            row("Harbour", parentId = "f1"),
            MetaUpkeep.folderPathOf(listOf(folder("f1", "Sketches"))),
            now = 900,
        )
        val json = SoilJson.encodeToString(SketchbookMeta.serializer(), out)
        assertEquals(out, SoilJson.decodeFromString<SketchbookMeta>(json))
        assertEquals("the record names what it holds", true, json.contains("\"sketchbookId\""))
        assertEquals(true, json.contains("\"folderPath\""))
    }
}
