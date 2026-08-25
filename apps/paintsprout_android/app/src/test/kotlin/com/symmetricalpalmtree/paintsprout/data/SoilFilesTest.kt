package com.symmetricalpalmtree.paintsprout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SoilFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val id = "3f2a1b8c-4d5e-4f60-8a91-b2c3d4e5f607"

    @Test
    fun `document path is garden slash uuid dot soil`() {
        val root = tmp.newFolder()
        val f = SoilFiles.soilFile(root, id)
        assertEquals("$id.soil", f.name)
        assertEquals(SoilFiles.GARDEN_DIR, f.parentFile!!.name)
        assertEquals(root, f.parentFile!!.parentFile)
    }

    @Test
    fun `garden is created on demand`() {
        val root = tmp.newFolder()
        assertFalse(File(root, SoilFiles.GARDEN_DIR).exists())
        SoilFiles.garden(root)
        assertTrue(File(root, SoilFiles.GARDEN_DIR).isDirectory)
    }

    @Test
    fun `index sits beside the garden, not inside it`() {
        val root = tmp.newFolder()
        val index = SoilFiles.indexFile(root)
        assertEquals("paintsprout.db", index.name)
        assertEquals(root, index.parentFile)
    }

    /**
     * The one place untrusted input reaches the filesystem: an imported file's
     * manifest supplies the id that becomes a path component.
     */
    @Test
    fun `path traversal and junk ids are refused`() {
        val hostile = listOf(
            "../../../../etc/passwd",
            "..",
            "",
            "$id/../evil",
            "$id.soil",
            "not-a-uuid",
            "3f2a1b8c4d5e4f608a91b2c3d4e5f607", // right characters, no dashes
            "3f2a1b8c-4d5e-4f60-8a91-b2c3d4e5f60", // one short
            "3f2a1b8c-4d5e-4f60-8a91-b2c3d4e5f60g", // not hex
        )
        for (bad in hostile) {
            assertFalse("should reject: $bad", SoilFiles.isDocumentId(bad))
            var threw = false
            try {
                SoilFiles.soilFile(tmp.newFolder(), bad)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("soilFile should refuse: $bad", threw)
        }
    }

    @Test
    fun `a real uuid is accepted`() {
        repeat(20) { assertTrue(SoilFiles.isDocumentId(UUID.randomUUID().toString())) }
    }

    @Test
    fun `documentIdOf recognises documents and only documents`() {
        assertEquals(id, SoilFiles.documentIdOf(File("/x/Garden/$id.soil")))
        assertNull(SoilFiles.documentIdOf(File("/x/Garden/$id.soil.old.bak")))
        assertNull(SoilFiles.documentIdOf(File("/x/Garden/$id.soil.tmp")))
        assertNull(SoilFiles.documentIdOf(File("/x/Garden/$id.soil.new")))
        assertNull(SoilFiles.documentIdOf(File("/x/Garden/$id.soil-wal")))
        assertNull(SoilFiles.documentIdOf(File("/x/paintsprout.db")))
        assertNull(SoilFiles.documentIdOf(File("/x/Garden/notes.soil")))
    }

    /**
     * A sweep must never treat a half-written file as the real thing — that is how
     * an interrupted swap gets opened as a document and "verified" as intact.
     */
    @Test
    fun `listDocuments skips sidecars and in-flight names`() {
        val root = tmp.newFolder()
        val garden = SoilFiles.garden(root)
        val other = UUID.randomUUID().toString()
        listOf(
            "$id.soil", "$id.soil-wal", "$id.soil-shm",
            "$other.soil", "$other.soil.old.bak", "$other.soil.tmp",
            "stray.txt",
        ).forEach { File(garden, it).writeText("x") }

        val found = SoilFiles.listDocuments(root).map { it.name }
        assertEquals(listOf("$id.soil", "$other.soil").sorted(), found.sorted())
    }

    @Test
    fun `listDocuments on a missing garden is empty, not an error`() {
        assertTrue(SoilFiles.listDocuments(File(tmp.newFolder(), "nope")).isEmpty())
    }

    @Test
    fun `sidecar and swap names hang off the database path`() {
        val db = File("/x/Garden/$id.soil")
        assertEquals(
            listOf("$id.soil-wal", "$id.soil-shm", "$id.soil-journal"),
            SoilFiles.sidecars(db).map { it.name },
        )
        assertEquals("$id.soil.old.bak", SoilFiles.asideOf(db).name)
        assertEquals("$id.soil.tmp", SoilFiles.tempOf(db).name)
        assertEquals("$id.soil.new", SoilFiles.installOf(db).name)
    }
}
