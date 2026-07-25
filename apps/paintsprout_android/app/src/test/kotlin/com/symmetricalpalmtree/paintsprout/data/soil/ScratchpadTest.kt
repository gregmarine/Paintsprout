package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.index.Sentinels
import com.symmetricalpalmtree.paintsprout.paint.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scratchpad's *rules*, over the same SQL the app runs.
 *
 * The lifecycle itself is not retested here — [SketchbookRepositoryTest] already
 * runs it over the `scratchpad` table, which is the point of the table being the
 * same shape. What this covers is what makes a scratchpad a scratchpad: which
 * tools it offers, and that its root and tray are its own.
 */
class ScratchpadTest {

    private fun store() = JdbcObjectStore(table = SchemaSql.SCRATCHPAD_TABLE)

    private fun pad(store: ObjectStore) =
        SketchbookRepository(store, rootId = Sentinels.SCRATCHPAD_ROOT_ID)

    // --- The tool set -------------------------------------------------------

    @Test
    fun `the offered tools are dry, inked, wet, an eraser and the two selectors`() {
        assertEquals(
            listOf(Tool.PENCIL, Tool.PEN, Tool.BRUSH, Tool.ERASER, Tool.WAND, Tool.LASSO),
            Scratchpad.TOOLS,
        )
    }

    /**
     * The shape tools are the deliberate omission — a line plotted with handles
     * is something you meant, and belongs in a book.
     */
    @Test
    fun `the shape tools are not on offer`() {
        for (t in listOf(Tool.LINE, Tool.ARC, Tool.POLYLINE, Tool.POLYARC)) {
            assertFalse("$t should not be on the scratch rail", t in Scratchpad.TOOLS)
        }
    }

    /** Whatever the rail falls back to has to be something the rail shows. */
    @Test
    fun `the default tool is one of the offered ones`() {
        assertTrue(Scratchpad.DEFAULT_TOOL in Scratchpad.TOOLS)
    }

    @Test
    fun `every offered tool draws or selects, and none is listed twice`() {
        assertEquals(Scratchpad.TOOLS.size, Scratchpad.TOOLS.toSet().size)
        for (t in Scratchpad.TOOLS) assertTrue(t.isDrawing || t.isSelector)
    }

    // --- The pad itself -----------------------------------------------------

    /** Ensured at every launch, never created by a migration — so it must be idempotent. */
    @Test
    fun `opening twice does not make a second pad`() {
        store().use { s ->
            val pad = pad(s)
            pad.createDocument(Scratchpad.NAME)
            val firstPage = pad.pages().single().id

            // What Scratchpad.open does on a launch where the pad already exists.
            if (pad.pages().isEmpty()) pad.createDocument(Scratchpad.NAME)

            assertEquals(1, pad.pages().size)
            assertEquals(firstPage, pad.pages().single().id)
        }
    }

    @Test
    fun `the pad hangs off the scratchpad sentinel`() {
        store().use { s ->
            val pad = pad(s)
            pad.createDocument(Scratchpad.NAME)
            assertEquals(Sentinels.SCRATCHPAD_ROOT_ID, pad.root()!!.id)
            assertEquals("a root has no parent", "", pad.root()!!.parentId)
            assertEquals(Sentinels.SCRATCHPAD_ROOT_ID, pad.pages().single().parentId)
        }
    }

    /** Its own tray, in its own table — nothing shared with any sketchbook. */
    @Test
    fun `the tray belongs to the pad`() {
        store().use { s ->
            val pad = pad(s)
            pad.createDocument(Scratchpad.NAME)
            pad.addPot("Scratch red", "#FFCC0000", custom = true)

            assertNotNull(pad.palette())
            assertEquals(Sentinels.SCRATCHPAD_ROOT_ID, pad.palette()!!.parentId)
            assertEquals(listOf("Scratch red"), pad.pots().map { it.text })
        }
    }

    /** Multi-page, like a book: the same page verbs work over the same rows. */
    @Test
    fun `a pad takes more pages, and remembers the one it was left on`() {
        store().use { s ->
            val pad = pad(s)
            pad.createDocument(Scratchpad.NAME)
            val second = pad.addPage()
            pad.setLastOpenedPage(second.id)

            assertEquals(2, pad.pageCount())
            assertEquals(second.id, pad.lastOpenedPage()?.id)
        }
    }

    /** A deleted page is not resurrected by the pointer that still names it. */
    @Test
    fun `a stale last-opened page resolves to nothing`() {
        store().use { s ->
            val pad = pad(s)
            pad.createDocument(Scratchpad.NAME)
            val second = pad.addPage()
            pad.setLastOpenedPage(second.id)
            pad.deletePage(second.id)

            assertEquals(null, pad.lastOpenedPage())
        }
    }
}
