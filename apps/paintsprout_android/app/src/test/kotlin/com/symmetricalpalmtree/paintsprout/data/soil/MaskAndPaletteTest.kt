package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.MaskCodec
import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import com.symmetricalpalmtree.paintsprout.paint.BrushLoad
import com.symmetricalpalmtree.paintsprout.paint.Recipe
import com.symmetricalpalmtree.paintsprout.paint.Stroke
import com.symmetricalpalmtree.paintsprout.paint.Tool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Masks, recipes and the rows that carry the selection ops. */
class MaskAndPaletteTest {

    // --- Mask cropping ------------------------------------------------------

    /** `0xFFRRGGBB` where selected, 0 elsewhere: the alpha channel is the mask. */
    @Test
    fun `alpha is taken from the top byte`() {
        val pixels = intArrayOf(0xFFFFFFFF.toInt(), 0, 0x80FFFFFF.toInt(), 0x00FFFFFF)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0, 0x80.toByte(), 0),
            MaskBitmaps.alphaOf(pixels),
        )
    }

    /**
     * A selection covers a fraction of the canvas. Cropping is most of why a mask
     * costs a fraction of a percent of its in-memory size.
     */
    @Test
    fun `cropping keeps only what is covered`() {
        // 6x4 field with a 2x2 block at (2,1)
        val alpha = ByteArray(24)
        for (y in 1..2) for (x in 2..3) alpha[y * 6 + x] = 0xFF.toByte()

        val cropped = MaskBitmaps.crop(alpha, 6, 4)!!
        assertEquals(2, cropped.left)
        assertEquals(1, cropped.top)
        assertEquals(2, cropped.mask.width)
        assertEquals(2, cropped.mask.height)
        assertEquals(6, cropped.fullWidth)
        assertEquals(4, cropped.fullHeight)
        assertTrue(cropped.mask.alpha.all { it == 0xFF.toByte() })
    }

    @Test
    fun `an empty mask crops to nothing at all`() {
        assertNull(MaskBitmaps.crop(ByteArray(24), 6, 4))
    }

    @Test
    fun `a full-coverage mask crops to itself`() {
        val alpha = ByteArray(24) { 0xFF.toByte() }
        val cropped = MaskBitmaps.crop(alpha, 6, 4)!!
        assertEquals(0, cropped.left)
        assertEquals(0, cropped.top)
        assertEquals(6, cropped.mask.width)
        assertEquals(4, cropped.mask.height)
    }

    /** Put back where it came from, byte for byte. */
    @Test
    fun `crop and expand round-trip`() {
        val alpha = ByteArray(40)
        for (y in 2..4) for (x in 1..6) alpha[y * 8 + x] = (x * 30).toByte()

        val cropped = MaskBitmaps.crop(alpha, 8, 5)!!
        assertArrayEquals(alpha, MaskBitmaps.expand(cropped))
    }

    @Test
    fun `partial coverage survives the round trip`() {
        val alpha = ByteArray(9)
        alpha[4] = 0x7F
        val cropped = MaskBitmaps.crop(alpha, 3, 3)!!
        assertEquals(0x7F.toByte(), cropped.mask.alpha.single())
        assertArrayEquals(alpha, MaskBitmaps.expand(cropped))
    }

    @Test
    fun `a single covered pixel crops to one by one`() {
        val alpha = ByteArray(100).also { it[55] = 0xFF.toByte() }
        val cropped = MaskBitmaps.crop(alpha, 10, 10)!!
        assertEquals(1, cropped.mask.width)
        assertEquals(1, cropped.mask.height)
        assertEquals(5, cropped.left)
        assertEquals(5, cropped.top)
    }

    // --- The selection op rows ----------------------------------------------

    private fun cropped(): MaskBitmaps.Cropped {
        val alpha = ByteArray(64)
        for (y in 2..5) for (x in 2..5) alpha[y * 8 + x] = 0xFF.toByte()
        return MaskBitmaps.crop(alpha, 8, 8)!!
    }

    @Test
    fun `a fill row keeps its colour and its geometry`() {
        val row = OpRows.fillRow(0xFF1B1BB3.toInt(), cropped(), downsample = 2f)

        assertEquals(SoilType.FILL, row.type)
        assertEquals("#FF1B1BB3", row.color)
        assertEquals(2f, row.x)
        assertEquals(2f, row.y)
        assertEquals(8f, row.width)
        assertEquals(8f, row.height)
        assertEquals("the capture resolution travels with the mask", 2f, row.amount)

        val back = OpRows.readMask(row)!!
        assertEquals(cropped().mask, back.mask)
        assertEquals(8, back.fullWidth)
    }

    @Test
    fun `an erase row carries only the mask`() {
        val row = OpRows.eraseRow(cropped(), 2f)
        assertEquals(SoilType.ERASE, row.type)
        assertNull(row.color)
        assertNotNull(OpRows.readMask(row))
    }

    /** A transform that drifts re-lays the lifted paint slightly off, every replay. */
    @Test
    fun `a move row keeps both the mask and an exact transform`() {
        val matrix = floatArrayOf(0.70710678f, -0.70710678f, 1919.9999f, 0.70710678f, 0.70710678f, -0.00001f, 0f, 0f, 1f)
        val row = OpRows.moveRow(matrix, cropped(), 2f)

        assertEquals(SoilType.MOVE, row.type)
        assertTrue(matrix.contentEquals(OpRows.readMatrix(row)))
        assertEquals(cropped().mask, OpRows.readMask(row)!!.mask)
    }

    @Test
    fun `a clip row is a stroke's frisket, not an op`() {
        val row = OpRows.clipRow(cropped(), 2f)
        assertEquals(SoilType.STROKE_CLIP, row.type)
        assertTrue(SoilType.STROKE_CLIP !in SoilType.OPS)
        assertNotNull(OpRows.readMask(row))
    }

    @Test
    fun `an unreadable mask row is skipped rather than thrown`() {
        assertNull(OpRows.readMask(OpRows.fillRow(0, cropped(), 2f).copy(blob = null)))
        assertNull(OpRows.readMask(OpRows.fillRow(0, cropped(), 2f).copy(blob = byteArrayOf(1, 2))))
    }

    // --- Wet state ----------------------------------------------------------

    @Test
    fun `a wash carries its tick schedule, crop and freeze`() {
        val stroke = Stroke(Tool.WATERCOLOR, 0xFF1B1BB3.toInt())
        stroke.wetSchedule.addAll(listOf(0, 4, 9))
        // The stubbed Rect's constructor is a no-op off-device; its fields are not.
        stroke.wetCrop = android.graphics.Rect().apply {
            left = 10; top = 20; right = 300; bottom = 400
        }
        stroke.dryFreeze = floatArrayOf(0.25f, 0.5f)

        val row = OpRows.wetStateRow(stroke)!!
        val back = OpRows.readWetState(row)!!

        assertEquals(SoilType.WET_STATE, row.type)
        assertArrayEquals(intArrayOf(0, 4, 9), back.schedule)
        assertArrayEquals(intArrayOf(10, 20, 300, 400), back.crop)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f), back.dryFreeze, 0f)
    }

    /** A dry tool has no wet state, and writing an empty one would be noise. */
    @Test
    fun `a dry stroke has no wet state row`() {
        assertNull(OpRows.wetStateRow(Stroke(Tool.PENCIL, 0)))
    }

    /** A wash that dried fully has a schedule but nothing frozen. */
    @Test
    fun `a fully dried wash keeps its schedule and no freeze`() {
        val stroke = Stroke(Tool.WATERCOLOR, 0)
        stroke.wetSchedule.addAll(listOf(0, 3))
        val back = OpRows.readWetState(OpRows.wetStateRow(stroke)!!)!!
        assertArrayEquals(intArrayOf(0, 3), back.schedule)
        assertNull(back.dryFreeze)
        assertNull(back.crop)
    }

    // --- Recipes and the tray -----------------------------------------------

    @Test
    fun `a recipe round-trips through text`() {
        val recipe = Recipe.of(0xFF1B1BB3.toInt(), 0.5f).plus(0xFFFFD300.toInt(), 0.25f)
        val back = RecipeCodec.decode(RecipeCodec.encode(recipe))

        assertEquals(2, back.pigmentCount)
        assertEquals(0.5f, back.amountOf(0xFF1B1BB3.toInt()), 1e-6f)
        assertEquals(0.25f, back.amountOf(0xFFFFD300.toInt()), 1e-6f)
        assertEquals("the colour is what the ratios make", recipe.color, back.color)
    }

    @Test
    fun `an empty recipe round-trips`() {
        assertTrue(RecipeCodec.decode(RecipeCodec.encode(Recipe.EMPTY)).isEmpty)
        assertTrue(RecipeCodec.decode(null).isEmpty)
        assertTrue(RecipeCodec.decode("").isEmpty)
    }

    /** A palette missing one pigment beats a document that will not open. */
    @Test
    fun `malformed recipe entries are skipped, not thrown`() {
        val recipe = RecipeCodec.decode("FF1B1BB3:0.5,rubbish,FFFFD300:notanumber,:,FF00FF00:0.25")
        assertEquals(2, recipe.pigmentCount)
        assertEquals(0.25f, recipe.amountOf(0xFF00FF00.toInt()), 1e-6f)
    }

    @Test
    fun `the mixing well and the brush load both survive`() {
        val mixture = Recipe.of(0xFF8E3A59.toInt(), 2f)
        val load = BrushLoad(Recipe.of(0xFF507D2A.toInt(), 0.4f), capacity = 1f)

        val params = Params.decode(OpRows.paletteParams(mixture, load).encode())

        assertEquals(mixture.color, OpRows.readMixture(params).color)
        assertEquals(load.color, OpRows.readLoad(params).color)
        assertEquals(0.4f, OpRows.readLoad(params).volume, 1e-6f)
        assertEquals(1f, OpRows.readLoad(params).capacity, 0f)
    }

    @Test
    fun `a pot remembers its name and whether it was mixed by hand`() {
        val standard = OpRows.potRow("Ultramarine Blue", 0xFF1B1BB3.toInt(), custom = false, order = 0)
        val custom = OpRows.potRow("A green", 0xFF3F7A2A.toInt(), custom = true, order = 1)

        assertEquals("Ultramarine Blue", standard.text)
        assertEquals("#FF1B1BB3", standard.color)
        assertTrue(!standard.hasFlag(SoilFlags.POT_CUSTOM))
        assertTrue(custom.hasFlag(SoilFlags.POT_CUSTOM))
        assertEquals(1, custom.order)
    }

    /** An empty palette reads as an empty tray rather than as damage. */
    @Test
    fun `an absent palette payload reads as empty`() {
        assertTrue(OpRows.readMixture(Params.EMPTY).isEmpty)
        assertTrue(OpRows.readLoad(Params.EMPTY).recipe.isEmpty)
        assertEquals(BrushLoad.DEFAULT_CAPACITY, OpRows.readLoad(Params.EMPTY).capacity, 0f)
    }
}
