package com.symmetricalpalmtree.paintsprout.data.soil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookMetaTest {

    private val meta = NotebookMeta(
        notebookId = "3f2a1b8c-4d5e-4f60-8a91-b2c3d4e5f607",
        name = "Harbour studies",
        createdAt = 1_000,
        updatedAt = 2_000,
        encrypted = true,
        keyScope = "GLOBAL",
        folderPath = listOf(
            FolderRef("f1", "Sketches", null),
            FolderRef("f2", "2026", "f1"),
        ),
        appVersionCode = 1,
    )

    @Test
    fun `it round-trips`() {
        val json = SoilJson.encodeToString(NotebookMeta.serializer(), meta)
        assertEquals(meta, SoilJson.decodeFromString(NotebookMeta.serializer(), json))
    }

    /**
     * The field names belong to the container, not to Paintsprout. A reader from
     * another app in the family has to find what it expects at the name it
     * expects — which is also why `notebookId` is not `sketchbookId`.
     */
    @Test
    fun `the wire field names are the family's`() {
        val json = SoilJson.encodeToString(NotebookMeta.serializer(), meta)
        for (field in listOf(
            "formatVersion", "notebookId", "name", "createdAt", "updatedAt",
            "encrypted", "keyScope", "folderPath",
        )) {
            assertTrue("missing \"$field\"", json.contains("\"$field\""))
        }
        assertFalse(json.contains("sketchbookId"))
    }

    /**
     * The ancestry is carried root-first with stable folder UUIDs, which is what
     * lets three devices importing the same document converge on one hierarchy.
     */
    @Test
    fun `folderPath runs root to immediate parent`() {
        assertEquals(listOf("Sketches", "2026"), meta.folderPath.map { it.name })
        assertNull(meta.folderPath.first().parentId)
        assertEquals(meta.folderPath.first().id, meta.folderPath.last().parentId)
    }

    /** A field a newer build added must not make the record unreadable by this one. */
    @Test
    fun `unknown fields are ignored`() {
        val fromTheFuture = """
            {"formatVersion":2,"notebookId":"x","name":"n","createdAt":1,"updatedAt":2,
             "encrypted":true,"keyScope":"GLOBAL","canvasFinish":"matte","layerCount":7}
        """.trimIndent()
        val decoded = SoilJson.decodeFromString(NotebookMeta.serializer(), fromTheFuture)
        assertEquals("n", decoded.name)
        assertEquals(2, decoded.formatVersion)
    }

    /** And a field this build expects but an older writer omitted must default. */
    @Test
    fun `missing optional fields default`() {
        val minimal = """{"notebookId":"x","name":"n","createdAt":1,"updatedAt":2}"""
        val decoded = SoilJson.decodeFromString(NotebookMeta.serializer(), minimal)
        assertEquals(1, decoded.formatVersion)
        assertFalse(decoded.encrypted)
        assertNull(decoded.keyScope)
        assertNull(decoded.cover)
        assertTrue(decoded.folderPath.isEmpty())
    }

    /** A reader with the file but not the key must not be handed a picture of it. */
    @Test
    fun `an encrypted document carries no cover`() {
        assertNull(meta.cover)
    }
}
