package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import com.symmetricalpalmtree.paintsprout.paint.CanvasParams
import com.symmetricalpalmtree.paintsprout.paint.ChalkboardParams
import com.symmetricalpalmtree.paintsprout.paint.ConcreteParams
import com.symmetricalpalmtree.paintsprout.paint.MetalParams
import com.symmetricalpalmtree.paintsprout.paint.StoneParams
import com.symmetricalpalmtree.paintsprout.paint.Stroke
import com.symmetricalpalmtree.paintsprout.paint.StrokePoint
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.SurfaceOp
import com.symmetricalpalmtree.paintsprout.paint.Tool
import com.symmetricalpalmtree.paintsprout.paint.Vec2
import com.symmetricalpalmtree.paintsprout.paint.WatercolorParams
import com.symmetricalpalmtree.paintsprout.paint.WoodParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OpRowsTest {

    private fun stroke(
        tool: Tool = Tool.PENCIL,
        color: Int = 0xFF1B1BB3.toInt(),
        seed: Int = 7,
        baseWidth: Float = 3.5f,
        water: Boolean = false,
        points: Int = 3,
    ) = Stroke(tool, color, seed, baseWidth, water).apply {
        repeat(points) { add(StrokePoint(Vec2(it.toFloat(), it * 2f), 1f + it, 0.5f)) }
    }

    // --- Strokes ------------------------------------------------------------

    @Test
    fun `a stroke round-trips through a row`() {
        val original = stroke()
        val back = OpRows.readStroke(OpRows.strokeRow(original))!!

        assertEquals(original.tool, back.tool)
        assertEquals(original.color, back.color)
        assertEquals(original.seed, back.seed)
        assertEquals(original.baseWidth, back.baseWidth)
        assertEquals(original.water, back.water)
        assertEquals(original.points, back.points)
    }

    @Test
    fun `water mode is a flag and survives`() {
        val row = OpRows.strokeRow(stroke(tool = Tool.WATERCOLOR, water = true))
        assertTrue(row.hasFlag(SoilFlags.STROKE_WATER))
        assertTrue(OpRows.readStroke(row)!!.water)
    }

    /**
     * The blob is pure geometry: everything scalar is a column, so a colour change
     * is a scalar update rather than a re-encode, and a query can ask which tools
     * a page used without decompressing anything.
     */
    @Test
    fun `the scalars are columns, not blob`() {
        val row = OpRows.strokeRow(stroke(tool = Tool.BRUSH))
        assertEquals("BRUSH", row.tool)
        assertEquals("#FF1B1BB3", row.color)
        assertEquals(3.5f, row.strokeWidth)
        assertEquals(7L, row.seed)
        assertEquals(SoilType.STROKE, row.type)
    }

    /** A page that renders imperfectly beats a page that refuses to open. */
    @Test
    fun `an unknown tool draws as a pen rather than not at all`() {
        val row = OpRows.strokeRow(stroke()).copy(tool = "AIRBRUSH_FROM_2027")
        assertEquals(Tool.PEN, OpRows.readStroke(row)!!.tool)
    }

    @Test
    fun `a stroke with unreadable geometry is dropped, not thrown`() {
        assertNull(OpRows.readStroke(OpRows.strokeRow(stroke()).copy(blob = null)))
        assertNull(OpRows.readStroke(OpRows.strokeRow(stroke()).copy(blob = byteArrayOf(9, 9, 9))))
    }

    @Test
    fun `an empty stroke is still a stroke`() {
        val back = OpRows.readStroke(OpRows.strokeRow(stroke(points = 0)))!!
        assertTrue(back.isEmpty)
    }

    // --- Colours ------------------------------------------------------------

    @Test
    fun `colours round-trip, including the alpha byte`() {
        for (argb in listOf(0xFF000000.toInt(), -1, 0x80FF00FF.toInt(), 0x00000000)) {
            assertEquals(argb, ArgbHex.decodeOrNull(ArgbHex.encode(argb)))
        }
    }

    @Test
    fun `a six-digit colour is read as opaque`() {
        assertEquals(0xFF1B1BB3.toInt(), ArgbHex.decodeOrNull("#1B1BB3"))
    }

    @Test
    fun `junk decodes to the default rather than a wrong colour`() {
        for (junk in listOf(null, "", "#", "#12345", "#GGGGGGGG", "blue", "0xFF0000")) {
            assertNull("accepted '$junk'", ArgbHex.decodeOrNull(junk))
        }
        assertEquals(42, ArgbHex.decode("nonsense", 42))
    }

    /**
     * A value that is both a formatted number and a database field must never see
     * the device locale — `%08X` under `ar` writes Eastern-Arabic digits, and the
     * user's colours would decode to the default the day they changed language.
     */
    @Test
    fun `colour formatting ignores the device locale`() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("fa-IR"))) {
                Locale.setDefault(locale)
                val text = ArgbHex.encode(0xFF1B1BB3.toInt())
                assertEquals("#FF1B1BB3", text)
                assertEquals(0xFF1B1BB3.toInt(), ArgbHex.decodeOrNull(text))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- Surface ops --------------------------------------------------------

    @Test
    fun `a surface op round-trips every parameter struct`() {
        val op = SurfaceOp(
            kind = SurfaceKind.WOOD,
            plainColor = 0xFFEEDDCC.toInt(),
            canvas = CanvasParams(tint = 0xFF112233.toInt(), weave = 0.4f, grain = 0.05f),
            watercolor = WatercolorParams(texture = 0.2f, mottle = 0.11f),
            wood = WoodParams(tint = 0xFFBA9C78.toInt(), grain = 0.5f, scale = 0.6f, weathering = 0.2f),
            stone = StoneParams(mottle = 0.3f, cracks = 0.4f),
            concrete = ConcreteParams(staining = 0.15f, grit = 0.25f),
            metal = MetalParams(sheen = 0.7f, scratches = 0.35f),
            chalkboard = ChalkboardParams(ghosting = 0.12f, dust = 0.08f),
        )
        val row = OpRows.surfaceRow(op)
        val params = Params.decode(row.params)

        assertEquals("WOOD", row.kind)
        assertEquals("#FFEEDDCC", row.color)
        assertEquals(op.canvas, SurfaceParamsCodec.canvas(params))
        assertEquals(op.watercolor, SurfaceParamsCodec.watercolor(params))
        assertEquals(op.wood, SurfaceParamsCodec.wood(params))
        assertEquals(op.stone, SurfaceParamsCodec.stone(params))
        assertEquals(op.concrete, SurfaceParamsCodec.concrete(params))
        assertEquals(op.metal, SurfaceParamsCodec.metal(params))
        assertEquals(op.chalkboard, SurfaceParamsCodec.chalkboard(params))
    }

    /**
     * All seven are stored even though one is in use: a user's canvas tuning is
     * still their canvas tuning while they work on wood, and switching back has to
     * find it.
     */
    @Test
    fun `the surfaces not in use are stored too`() {
        val row = OpRows.surfaceRow(SurfaceOp(SurfaceKind.PAPER, 0xFFFFFFFF.toInt()))
        val params = Params.decode(row.params)
        assertTrue(params.keys.any { it.startsWith("chalkboard.") })
        assertTrue(params.keys.any { it.startsWith("metal.") })
    }

    /** An older build's bag is missing the newest keys and must still render. */
    @Test
    fun `missing parameters fall back to their defaults`() {
        val partial = Params.decode("""{"wood.grain":0.9}""")
        val wood = SurfaceParamsCodec.wood(partial)
        assertEquals(0.9f, wood.grain, 1e-6f)
        assertEquals(WoodParams().tint, wood.tint)
        assertEquals(WoodParams().scale, wood.scale, 1e-6f)
        assertEquals(CanvasParams(), SurfaceParamsCodec.canvas(Params.EMPTY))
    }

    @Test
    fun `an unknown surface name reads as paper`() {
        assertEquals(SurfaceKind.PAPER, SurfaceParamsCodec.kindOf("VELVET"))
        assertEquals(SurfaceKind.PAPER, SurfaceParamsCodec.kindOf(null))
        assertEquals(SurfaceKind.CHALKBOARD, SurfaceParamsCodec.kindOf("CHALKBOARD"))
    }

    /** The seed belongs to the page, not to a moment in its history. */
    @Test
    fun `a surface op carries no seed`() {
        assertNull(OpRows.surfaceRow(SurfaceOp(SurfaceKind.STONE, 0)).seed)
    }
}
