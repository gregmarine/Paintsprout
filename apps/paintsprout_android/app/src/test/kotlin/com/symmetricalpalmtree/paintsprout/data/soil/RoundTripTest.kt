package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import com.symmetricalpalmtree.paintsprout.paint.CanvasParams
import com.symmetricalpalmtree.paintsprout.paint.StoneParams
import com.symmetricalpalmtree.paintsprout.paint.Stroke
import com.symmetricalpalmtree.paintsprout.paint.StrokePoint
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.SurfaceOp
import com.symmetricalpalmtree.paintsprout.paint.Tool
import com.symmetricalpalmtree.paintsprout.paint.Vec2
import com.symmetricalpalmtree.paintsprout.paint.WoodParams
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A page written and read back: the claim the whole storage layer exists to make.
 *
 * `readOp` builds Android objects for the mask-carrying types, which a JVM test
 * cannot construct — so the ops covered here are the ones that are pure (strokes
 * and surface changes) plus everything the *structure* has to get right: the
 * order, the frontier, which side of it each op falls on, and the surface a page
 * resolves to. The mask types' encoding is covered in `MaskAndPaletteTest`, and
 * on device.
 */
class RoundTripTest {

    private lateinit var store: JdbcObjectStore
    private lateinit var repo: SketchbookRepository
    private var ids = 0

    @Before
    fun setUp() {
        store = JdbcObjectStore()
        repo = SketchbookRepository(store, rootId = "book", now = { 1_000 }, newId = { "id-${ids++}" })
        repo.createDocument("Harbour", surfaceKind = "PAPER", surfaceSeed = 4242L)
    }

    @After
    fun tearDown() = store.close()

    private val page get() = repo.pages().single().id
    private val layer get() = repo.contentLayer(page)!!.id

    private fun stroke(tool: Tool, color: Int, points: Int = 4) =
        Stroke(tool, color, seed = 99, baseWidth = 2.5f).apply {
            repeat(points) { add(StrokePoint(Vec2(it * 3f, it * 7f), 1f + it, 0.9f)) }
        }

    @Test
    fun `strokes come back with their geometry and their scalars`() {
        val original = stroke(Tool.BRUSH, 0xFF1B1BB3.toInt())
        repo.appendOp(layer, OpRows.strokeRow(original))

        val row = repo.committedOps(layer).single()
        val back = OpRows.readStroke(row)!!

        assertEquals(Tool.BRUSH, back.tool)
        assertEquals(0xFF1B1BB3.toInt(), back.color)
        assertEquals(99, back.seed)
        assertEquals(2.5f, back.baseWidth)
        assertEquals(original.points, back.points)
    }

    @Test
    fun `a surface change comes back with every parameter struct`() {
        val op = SurfaceOp(
            kind = SurfaceKind.WOOD,
            plainColor = 0xFFEEDDCC.toInt(),
            canvas = CanvasParams(weave = 0.41f),
            wood = WoodParams(grain = 0.62f, scale = 0.33f),
            stone = StoneParams(cracks = 0.7f),
        )
        repo.appendOp(layer, OpRows.surfaceRow(op))

        val back = OpRows.readSurfaceOp(repo.committedOps(layer).single())

        assertEquals(SurfaceKind.WOOD, back.kind)
        assertEquals(0xFFEEDDCC.toInt(), back.plainColor)
        assertEquals(0.41f, back.canvas.weave, 1e-6f)
        assertEquals(0.62f, back.wood.grain, 1e-6f)
        assertEquals(0.33f, back.wood.scale, 1e-6f)
        assertEquals(0.7f, back.stone.cracks, 1e-6f)
    }

    /**
     * The frontier is what makes undo survive a close: the ops on both sides of
     * it are still there, and which side each falls on is the whole state.
     */
    @Test
    fun `the history comes back split at the frontier`() {
        val tools = listOf(Tool.PENCIL, Tool.PEN, Tool.BRUSH, Tool.MARKER, Tool.SPRAY)
        tools.forEach { repo.appendOp(layer, OpRows.strokeRow(stroke(it, 0xFF000000.toInt()))) }
        repo.undo(layer)
        repo.undo(layer)

        val committed = repo.committedOps(layer).mapNotNull(OpRows::readStroke)
        val undone = repo.redoableOps(layer).mapNotNull(OpRows::readStroke)

        assertEquals(listOf(Tool.PENCIL, Tool.PEN, Tool.BRUSH), committed.map { it.tool })
        assertEquals("oldest-undone first, as the view will reverse it", listOf(Tool.MARKER, Tool.SPRAY), undone.map { it.tool })
    }

    @Test
    fun `the page resolves to the surface the history says it is on`() {
        assertEquals("PAPER", repo.resolvedSurface(page)!!.kind)

        repo.appendOp(layer, OpRows.surfaceRow(SurfaceOp(SurfaceKind.CHALKBOARD, 0xFF1B1B1B.toInt())))
        assertEquals("CHALKBOARD", repo.resolvedSurface(page)!!.kind)

        repo.undo(layer)
        assertEquals("PAPER", repo.resolvedSurface(page)!!.kind)
    }

    /** The sheet's own seed: regenerating it would change the artwork's ground. */
    @Test
    fun `the per-artwork seed survives`() {
        assertEquals(4242L, repo.pages().single().seed)
    }

    // --- The raster cache ---------------------------------------------------

    @Test
    fun `the cache is offered only while it matches the frontier`() {
        repeat(3) { repo.appendOp(layer, OpRows.strokeRow(stroke(Tool.PEN, 0))) }
        repo.writeCache(layer, byteArrayOf(1, 2, 3), 1920f, 1200f)

        assertEquals(3, repo.cache(layer)!!.opCount)

        repo.undo(layer)
        assertNull("stale after an undo — replay instead", repo.cache(layer))

        repo.redo(layer)
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.cache(layer)!!.blob)
    }

    @Test
    fun `a page with no cache simply has none`() {
        repo.appendOp(layer, OpRows.strokeRow(stroke(Tool.PEN, 0)))
        assertNull(repo.cache(layer))
    }

    // --- Everything at once -------------------------------------------------

    /**
     * Write a page, close the file, open it again, and find the same page —
     * paper, palette, history and frontier.
     */
    @Test
    fun `a whole page survives a close and reopen`() {
        val file = kotlin.io.path.createTempFile("book", ".soil").toFile().also { it.delete() }
        val disk = JdbcObjectStore(path = file.absolutePath)
        try {
            var n = 0
            val first = SketchbookRepository(disk, "book", now = { 1_000 }, newId = { "d-${n++}" })
            first.createDocument("Harbour", surfaceKind = "PAPER", surfaceSeed = 777L)
            val p = first.pages().single().id
            val l = first.contentLayer(p)!!.id

            first.appendOp(l, OpRows.strokeRow(stroke(Tool.PENCIL, 0xFF000000.toInt())))
            first.appendOp(l, OpRows.surfaceRow(SurfaceOp(SurfaceKind.CANVAS, 0xFFFFFFFF.toInt())))
            first.appendOp(l, OpRows.strokeRow(stroke(Tool.BRUSH, 0xFF1B1BB3.toInt())))
            first.undo(l)
            first.addPot("Sap Green", "#FF507D2A", custom = true)
            first.writePaletteState(
                OpRows.paletteParams(
                    com.symmetricalpalmtree.paintsprout.paint.Recipe.of(0xFF507D2A.toInt(), 1f),
                    com.symmetricalpalmtree.paintsprout.paint.BrushLoad.of(0xFF507D2A.toInt()),
                ),
            )

            disk.reopen()

            val later = SketchbookRepository(disk, "book", now = { 2_000 }, newId = { "e-${n++}" })
            assertEquals("Harbour", later.root()!!.text)
            assertEquals(777L, later.pages().single().seed)

            val committed = later.committedOps(l)
            assertEquals(2, committed.size)
            assertEquals(Tool.PENCIL, OpRows.readStroke(committed[0])!!.tool)
            assertEquals(SurfaceKind.CANVAS, OpRows.readSurfaceOp(committed[1]).kind)
            assertEquals("the surface follows the history", "CANVAS", later.resolvedSurface(p)!!.kind)

            assertEquals("the redo stack came back too", 1, later.redoableOps(l).size)
            assertTrue(later.redo(l))
            assertEquals(Tool.BRUSH, OpRows.readStroke(later.committedOps(l).last())!!.tool)

            val pots = later.pots()
            assertEquals("Sap Green", pots.single().text)
            assertTrue(pots.single().hasFlag(SoilFlags.POT_CUSTOM))
            assertEquals(0xFF507D2A.toInt(), OpRows.readLoad(later.paletteState()).color)
        } finally {
            disk.close()
            file.delete()
        }
    }

    /** A damaged stroke costs its own mark and nothing else. */
    @Test
    fun `one unreadable op does not take the page with it`() {
        repo.appendOp(layer, OpRows.strokeRow(stroke(Tool.PENCIL, 0)))
        repo.appendOp(layer, OpRows.strokeRow(stroke(Tool.PEN, 0)).copy(blob = byteArrayOf(9, 9)))
        repo.appendOp(layer, OpRows.strokeRow(stroke(Tool.BRUSH, 0)))

        val rebuilt = repo.committedOps(layer).mapNotNull(OpRows::readStroke)
        assertEquals(listOf(Tool.PENCIL, Tool.BRUSH), rebuilt.map { it.tool })
    }

    @Test
    fun `an empty palette reads as an untouched tray`() {
        assertTrue(OpRows.readMixture(Params.decode(repo.palette()!!.params)).isEmpty)
        assertTrue(repo.pots().isEmpty())
    }
}
