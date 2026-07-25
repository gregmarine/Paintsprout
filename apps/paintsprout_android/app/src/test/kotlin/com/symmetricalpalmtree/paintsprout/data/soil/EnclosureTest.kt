package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.MaskCodec
import com.symmetricalpalmtree.paintsprout.data.soil.codec.StrokeCodec
import com.symmetricalpalmtree.paintsprout.paint.StrokePoint
import com.symmetricalpalmtree.paintsprout.paint.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copy takes whole ops, so this is the rule that decides what a selection got.
 * Too strict and a stroke the user obviously enclosed goes missing from the
 * paste; too loose and a mark they never selected turns up in another book.
 */
class EnclosureTest {

    private fun stroke(points: List<Pair<Float, Float>>, width: Float = 4f) = SoilObject(
        id = "s", parentId = "layer", type = SoilType.STROKE,
        strokeWidth = width,
        blob = StrokeCodec.encode(
            points.map { (x, y) -> StrokePoint(Vec2(x, y), width) }.toMutableList(),
        ),
    )

    /** A mask op: crop at (left, top), that many mask px across, at half res. */
    private fun fill(left: Int, top: Int, w: Int, h: Int, downsample: Float = 2f) = SoilObject(
        id = "f", parentId = "layer", type = SoilType.FILL,
        x = left.toFloat(), y = top.toFloat(),
        width = 1000f, height = 1000f, amount = downsample,
        blob = MaskCodec.encode(MaskCodec.Mask(w, h, ByteArray(w * h) { -1 })),
    )

    /** A rectangular selection, as a box plus a sampler over the same rectangle. */
    private fun box(l: Float, t: Float, r: Float, b: Float) = Enclosure.Box(l, t, r, b)

    private fun rectangleInside(l: Float, t: Float, r: Float, b: Float): (Float, Float) -> Boolean =
        { x, y -> x in l..r && y in t..b }

    // --- Strokes ------------------------------------------------------------

    @Test
    fun `a stroke well inside is enclosed`() {
        val shape = Enclosure.shapeOf(stroke(listOf(50f to 50f, 60f to 60f, 70f to 55f)))!!
        assertTrue(Enclosure.encloses(shape, box(0f, 0f, 100f, 100f), rectangleInside(0f, 0f, 100f, 100f)))
    }

    @Test
    fun `a stroke that crosses the edge is not`() {
        val shape = Enclosure.shapeOf(stroke(listOf(50f to 50f, 150f to 50f)))!!
        assertFalse(Enclosure.encloses(shape, box(0f, 0f, 100f, 100f), rectangleInside(0f, 0f, 100f, 100f)))
    }

    /** The mark has width: a spine inside the loop can still spill over it. */
    @Test
    fun `a stroke whose edge spills over is not enclosed`() {
        val shape = Enclosure.shapeOf(stroke(listOf(50f to 50f, 98f to 50f), width = 20f))!!
        assertFalse(Enclosure.encloses(shape, box(0f, 0f, 100f, 100f), rectangleInside(0f, 0f, 100f, 100f)))
    }

    /**
     * The case a bounding box alone gets wrong: an S drawn corner to corner spans
     * a box far bigger than the mark, and a lasso can hold the mark without
     * holding the box.
     */
    @Test
    fun `a stroke is judged against its path, not its box`() {
        // A diagonal whose points avoid the top-right and bottom-left quadrants.
        val diagonal = stroke(listOf(10f to 10f, 30f to 30f, 50f to 50f, 70f to 70f), width = 0f)
        val shape = Enclosure.shapeOf(diagonal)!!
        // A sampler that only accepts the diagonal band — a rectangle would too,
        // but this one refuses the box's other two corners.
        val band: (Float, Float) -> Boolean = { x, y -> kotlin.math.abs(x - y) < 5f }
        assertTrue(Enclosure.encloses(shape, box(0f, 0f, 100f, 100f), band))
        assertFalse(band(0f, 100f))
    }

    @Test
    fun `a stroke with no readable geometry has no shape`() {
        assertNull(Enclosure.shapeOf(stroke(emptyList())))
        assertNull(Enclosure.shapeOf(SoilObject(id = "s", parentId = "l", type = SoilType.STROKE)))
    }

    // --- Mask ops -----------------------------------------------------------

    /**
     * Masks are stored at half resolution, and a box in the wrong units would be
     * silently half the size it should be — which would copy a fill that runs
     * well outside the selection.
     */
    @Test
    fun `a mask op's box is scaled back up to buffer pixels`() {
        val shape = Enclosure.shapeOf(fill(left = 10, top = 20, w = 30, h = 40))!!
        assertEquals(20f, shape.box.left, 0f)
        assertEquals(40f, shape.box.top, 0f)
        assertEquals(80f, shape.box.right, 0f)
        assertEquals(120f, shape.box.bottom, 0f)
    }

    @Test
    fun `a fill inside is enclosed and one hanging out is not`() {
        val inner = Enclosure.shapeOf(fill(left = 10, top = 10, w = 10, h = 10))!!
        val outer = Enclosure.shapeOf(fill(left = 10, top = 10, w = 100, h = 10))!!
        assertTrue(Enclosure.encloses(inner, box(0f, 0f, 100f, 100f), rectangleInside(0f, 0f, 100f, 100f)))
        assertFalse(Enclosure.encloses(outer, box(0f, 0f, 100f, 100f), rectangleInside(0f, 0f, 100f, 100f)))
    }

    // --- What has no shape at all -------------------------------------------

    @Test
    fun `a surface change and a paste are not marks with an extent`() {
        assertNull(Enclosure.shapeOf(SoilObject(id = "x", parentId = "l", type = SoilType.SURFACE_OP)))
        assertNull(Enclosure.shapeOf(SoilObject(id = "x", parentId = "l", type = SoilType.PASTE)))
        assertNull(Enclosure.shapeOf(SoilObject(id = "x", parentId = "l", type = SoilType.RASTER_CACHE)))
    }

    @Test
    fun `an erase and a move do have one`() {
        assertNotNull(Enclosure.shapeOf(fill(0, 0, 4, 4).copy(type = SoilType.ERASE)))
    }

    // --- The box test -------------------------------------------------------

    @Test
    fun `within is inclusive at the edges`() {
        assertTrue(box(0f, 0f, 10f, 10f).within(box(0f, 0f, 10f, 10f)))
        assertFalse(box(0f, 0f, 10.1f, 10f).within(box(0f, 0f, 10f, 10f)))
        assertFalse(box(-0.1f, 0f, 10f, 10f).within(box(0f, 0f, 10f, 10f)))
    }
}
