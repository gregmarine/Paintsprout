package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.index.Sentinels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard's rules, over the same SQL the app runs.
 *
 * The one that matters most is the id remap. A copied subtree carries its
 * *source's* live child ids, and pasting it twice — or copying twice from the
 * same page — is a `UNIQUE` failure in the middle of somebody's work.
 * Notesprout shipped that bug twice, once on each side, which is the actual
 * lesson: the rule is not hard, but two copies of it means fixing it once isn't
 * enough.
 */
class ClipboardTest {

    private var ids = 0
    private val newId = { "c-${ids++}" }

    private fun store() = JdbcObjectStore(table = SchemaSql.CLIPBOARD_TABLE)

    private fun stroke(id: String, color: String) = SoilObject(
        id = id, parentId = "layer-1", type = SoilType.STROKE, color = color, order = 0,
    )

    private fun clip(id: String, parent: String) =
        SoilObject(id = id, parentId = parent, type = SoilType.STROKE_CLIP)

    // --- Copying in ---------------------------------------------------------

    @Test
    fun `copied ops land on the root in the order they were drawn`() {
        store().use { s ->
            val a = stroke("a", "#FF000000")
            val b = stroke("b", "#FFFF0000")
            Clipboard.replaceIn(s, listOf(a, b), listOf("a", "b"), "book-1", now = 100, newId = newId)

            val ops = Clipboard.contentsIn(s).filter { it.parentId == Sentinels.CLIPBOARD_ROOT_ID }
            assertEquals(2, ops.size)
            assertEquals(listOf(0, 1), ops.map { it.order })
            assertEquals(listOf("#FF000000", "#FFFF0000"), ops.map { it.color })
        }
    }

    /** Nothing that went in keeps the id it had in the document it came from. */
    @Test
    fun `every copied row gets a fresh id`() {
        store().use { s ->
            val a = stroke("a", "#FF000000")
            Clipboard.replaceIn(s, listOf(a, clip("a-clip", "a")), listOf("a"), "book-1", 100, newId)

            val rows = Clipboard.contentsIn(s)
            assertEquals(2, rows.size)
            assertTrue(rows.none { it.id == "a" || it.id == "a-clip" })
        }
    }

    /** The attachment still hangs off *its own* stroke, not off the old id. */
    @Test
    fun `a rewired child follows its parent`() {
        store().use { s ->
            Clipboard.replaceIn(
                s,
                listOf(stroke("a", "#FF000000"), clip("a-clip", "a")),
                listOf("a"),
                "book-1", 100, newId,
            )

            val rows = Clipboard.contentsIn(s)
            val op = rows.single { it.parentId == Sentinels.CLIPBOARD_ROOT_ID }
            val child = rows.single { it.type == SoilType.STROKE_CLIP }
            assertEquals(op.id, child.parentId)
        }
    }

    /** The bug that broke Notesprout: the same subtree copied twice. */
    @Test
    fun `copying the same rows twice does not collide`() {
        store().use { s ->
            val subtree = listOf(stroke("a", "#FF000000"), clip("a-clip", "a"))
            Clipboard.replaceIn(s, subtree, listOf("a"), "book-1", 100, newId)
            Clipboard.replaceIn(s, subtree, listOf("a"), "book-1", 200, newId)

            // Replaced, not appended, and no duplicate-key failure on the way.
            val rows = Clipboard.contentsIn(s)
            assertEquals(2, rows.size)
            assertEquals(1, rows.count { it.parentId == Sentinels.CLIPBOARD_ROOT_ID })
        }
    }

    @Test
    fun `a second copy replaces the first`() {
        store().use { s ->
            Clipboard.replaceIn(s, listOf(stroke("a", "#FF000000")), listOf("a"), "book-1", 100, newId)
            Clipboard.replaceIn(s, listOf(stroke("b", "#FFFF0000")), listOf("b"), "book-2", 200, newId)

            val ops = Clipboard.contentsIn(s).filter { it.parentId == Sentinels.CLIPBOARD_ROOT_ID }
            assertEquals(1, ops.size)
            assertEquals("#FFFF0000", ops.single().color)
            assertEquals("book-2", Clipboard.summaryIn(s).sourceDocumentId)
        }
    }

    // --- The summary --------------------------------------------------------

    @Test
    fun `the summary counts the ops and names the source`() {
        store().use { s ->
            assertTrue(Clipboard.summaryIn(s).isEmpty)
            assertNull(Clipboard.summaryIn(s).sourceDocumentId)

            Clipboard.replaceIn(
                s,
                listOf(stroke("a", "#FF000000"), stroke("b", "#FFFF0000"), clip("c", "a")),
                listOf("a", "b"),
                "book-1", 100, newId,
            )

            assertEquals(2, Clipboard.summaryIn(s).count)
            assertEquals("book-1", Clipboard.summaryIn(s).sourceDocumentId)
        }
    }

    /** Ids and counts only. Content copied under one key must not be cached here. */
    @Test
    fun `the metadata row carries no payload`() {
        store().use { s ->
            Clipboard.replaceIn(s, listOf(stroke("a", "#FF000000")), listOf("a"), "book-1", 100, newId)
            val root = s.byId(Sentinels.CLIPBOARD_ROOT_ID)!!
            assertNull(root.blob)
            assertNull(root.text)
            assertEquals(1, root.opCount)
        }
    }

    // --- Clearing -----------------------------------------------------------

    /** A hard delete: a tombstoned clipboard would grow for the life of the install. */
    @Test
    fun `clearing leaves no rows behind at all`() {
        store().use { s ->
            Clipboard.replaceIn(
                s,
                listOf(stroke("a", "#FF000000"), clip("a-clip", "a")),
                listOf("a"),
                "book-1", 100, newId,
            )
            s.transaction { Clipboard.clearIn(s) }

            assertEquals(emptyList<SoilObject>(), Clipboard.contentsIn(s))
            assertTrue(Clipboard.summaryIn(s).isEmpty)
            // The root survives — it is the sentinel, and it is where the next
            // copy's metadata goes.
            assertNotNull(s.byId(Sentinels.CLIPBOARD_ROOT_ID))
        }
    }

    @Test
    fun `an empty clipboard reads as empty rather than failing`() {
        store().use { s ->
            assertEquals(emptyList<SoilObject>(), Clipboard.contentsIn(s))
            assertEquals(0, Clipboard.summaryIn(s).count)
        }
    }
}
