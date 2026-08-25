package com.symmetricalpalmtree.paintsproutonyx.data

import com.symmetricalpalmtree.paintsproutonyx.data.soil.FolderRef
import com.symmetricalpalmtree.paintsproutonyx.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SketchbookMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sketchbook_meta` is the row a `.soil` found on its own is read by — the part that says which
 * sketchbook this is when there is no index left to ask. That makes it the one piece of the file
 * whose *tolerance* matters as much as its content: it has to survive being written by a version that
 * knew more than the reader does, and being read by a version that knows more than the writer did.
 *
 * These tests pin both directions, plus the plain round trip.
 */
class SketchbookMetaTest {

    private fun sample() = SketchbookMeta(
        sketchbookId = "6b1b7f52-0a3e-4d7a-9c1e-2f8b4a6d3c11",
        name = "Studies from the window",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_900_000L,
        folderPath = listOf(
            FolderRef("11111111-1111-1111-1111-111111111111", "Sketchbooks", null),
            FolderRef("22222222-2222-2222-2222-222222222222", "2026", "11111111-1111-1111-1111-111111111111"),
        ),
        appVersionCode = 1,
    )

    @Test
    fun `a meta row survives the trip to json and back unchanged`() {
        val original = sample()
        val restored = SketchbookMeta.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `defaults are written out rather than left to be guessed at`() {
        // A field left at its default still appears in the JSON: a reader that has to infer what an
        // absent field meant is a reader that will eventually infer wrong.
        val json = sample().toJson()
        assertTrue(json.contains("\"formatVersion\""))
        assertTrue(json.contains("\"encrypted\""))
        assertTrue(json.contains("\"keyScope\""))
        assertTrue(json.contains("\"textDocument\""))
    }

    @Test
    fun `nulls are absent rather than spelled out`() {
        // Nothing here treats "absent" and "null" as different, so writing null would only make the
        // row longer.
        val json = sample().toJson()
        assertFalse(json.contains("\"cover\":null"))
        assertFalse(json.contains("\"exportedAt\":null"))
    }

    @Test
    fun `a field this version has never heard of is stepped over`() {
        // Written by a later version of this app, or by a sibling in the .soil family. Refusing to read
        // the row that describes the sketchbook because it says one thing too many would be exactly
        // backwards — the marks are all still in there.
        val json = """
            {
              "formatVersion": 1,
              "sketchbookId": "abc",
              "name": "Later",
              "createdAt": 1,
              "updatedAt": 2,
              "encrypted": true,
              "keyScope": "GLOBAL",
              "textDocument": false,
              "pigmentModel": "kubelka-munk",
              "surfaces": [{ "kind": "cold-press", "seed": 42 }]
            }
        """.trimIndent()

        val meta = SketchbookMeta.fromJson(json)
        assertEquals("abc", meta.sketchbookId)
        assertEquals("Later", meta.name)
    }

    @Test
    fun `a field this version expects but the file omits falls back to its default`() {
        // The mirror image: a row written before a field existed. Every field carrying a default has to
        // be able to go missing without taking the whole row down with it, or adding one field in a
        // future version makes every sketchbook written before it unreadable.
        val json = """
            {
              "sketchbookId": "def",
              "name": "Earlier",
              "createdAt": 3,
              "updatedAt": 4
            }
        """.trimIndent()

        val meta = SketchbookMeta.fromJson(json)
        assertEquals(1, meta.formatVersion)
        assertTrue(meta.encrypted)
        assertEquals(KEY_SCOPE_GLOBAL, meta.keyScope)
        assertNull(meta.cover)
        assertNull(meta.exportedAt)
        assertNull(meta.appVersionCode)
        assertTrue(meta.folderPath.isEmpty())
        // This app draws; it is never a text document. The field is here for family membership only.
        assertFalse(meta.textDocument)
    }

    @Test
    fun `the folder path keeps its order`() {
        // Root-first, so a sketchbook found without its index still says roughly where it belonged —
        // which is the difference between putting it back and starting again.
        val restored = SketchbookMeta.fromJson(sample().toJson())
        assertEquals(listOf("Sketchbooks", "2026"), restored.folderPath.map { it.name })
        assertNull(restored.folderPath.first().parentId)
    }
}
