package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class StrokeTest {

    private val eps = 1e-4f

    @Test
    fun strokeStartsEmptyThenAccumulates() {
        val stroke = Stroke(Tool.PEN, color = 0xFF112233.toInt(), seed = 7)
        assertTrue(stroke.isEmpty)
        assertEquals(0, stroke.points.size)

        stroke.add(StrokePoint(Vec2(1f, 2f), 3f))
        stroke.add(StrokePoint(Vec2(4f, 5f), 3f))

        assertFalse(stroke.isEmpty)
        assertEquals(2, stroke.points.size)
        assertEquals(Tool.PEN, stroke.tool)
        assertEquals(0xFF112233.toInt(), stroke.color)
        assertEquals(7, stroke.seed)
    }

    @Test
    fun strokePointDefaultsToFullDensity() {
        val p = StrokePoint(Vec2(0f, 0f), 5f)
        assertEquals(1.0f, p.density, 0f)
    }

    /** Every existing caller builds points without a load; they must not fade. */
    @Test
    fun strokePointDefaultsToAFullBrushAndTheStrokesOwnColour() {
        val p = StrokePoint(Vec2(0f, 0f), 5f)
        assertEquals(1.0f, p.load, 0f)
        assertEquals(INHERIT_COLOR, p.color)
    }

    /** Dry tools must render on exactly the path they always did. */
    @Test
    fun anOrdinaryStrokeNeitherVariesNorIsDirty() {
        val stroke = Stroke(Tool.PENCIL, color = 0xFF000000.toInt())
        stroke.add(StrokePoint(Vec2(0f, 0f), 2f))
        stroke.add(StrokePoint(Vec2(5f, 0f), 2f))

        assertFalse(stroke.varies)
        assertFalse(stroke.dirty)
    }

    @Test
    fun aDrainingStrokeVaries() {
        val stroke = Stroke(Tool.BRUSH, color = 0xFF000000.toInt())
        stroke.add(StrokePoint(Vec2(0f, 0f), 2f, load = 1f))
        stroke.add(StrokePoint(Vec2(5f, 0f), 2f, load = 0.4f))

        assertTrue(stroke.varies)
        assertFalse("load alone isn't contamination", stroke.dirty)
    }

    /**
     * A loaded brush stamps its colour on every point, but a constant colour is
     * not contamination. Calling that "dirty" forces the renderer down the
     * per-segment path — ~150x the draw calls per bristle — and drags the live
     * preview from 24ms a frame to 250ms.
     */
    @Test
    fun aLoadedBrushHoldingOneColourIsNotDirty() {
        val green = 0xFF6A8A42.toInt()
        val stroke = Stroke(Tool.BRUSH, color = green)
        repeat(10) { i ->
            stroke.add(StrokePoint(Vec2(i * 5f, 0f), 4f, color = green, load = 1f - i * 0.05f))
        }

        assertFalse("a constant colour is not a colour change", stroke.dirty)
        assertTrue("but it is draining", stroke.varies)
    }

    /** A point with no colour of its own means the stroke's — still no change. */
    @Test
    fun inheritedColourIsNotAColourChange() {
        val stroke = Stroke(Tool.BRUSH, color = 0xFF112233.toInt())
        stroke.add(StrokePoint(Vec2(0f, 0f), 4f, color = 0xFF112233.toInt()))
        stroke.add(StrokePoint(Vec2(5f, 0f), 4f)) // INHERIT — resolves to the same colour

        assertFalse(stroke.dirty)
    }

    @Test
    fun aStrokeThatChangedColourIsDirty() {
        val stroke = Stroke(Tool.BRUSH, color = 0xFF000000.toInt())
        stroke.add(StrokePoint(Vec2(0f, 0f), 2f, color = 0xFF0000FF.toInt()))
        stroke.add(StrokePoint(Vec2(5f, 0f), 2f, color = 0xFF00FF00.toInt()))

        assertTrue(stroke.dirty)
        assertFalse("colour alone isn't draining", stroke.varies)
    }

    /** A pencil dragged through wet paint must not load up with it. */
    @Test
    fun onlyWetMediaCarryALoad() {
        assertTrue(Tool.BRUSH.usesLoad)
        assertTrue(Tool.WATERCOLOR.usesLoad)

        for (dry in listOf(Tool.PENCIL, Tool.PEN, Tool.MARKER, Tool.SPRAY, Tool.ERASER, Tool.WAND, Tool.LINE)) {
            assertFalse("$dry must not carry paint", dry.usesLoad)
        }
    }

    @Test
    fun vec2VectorOperators() {
        val a = Vec2(3f, 4f)
        val b = Vec2(1f, 2f)
        assertEquals(Vec2(4f, 6f), a + b)
        assertEquals(Vec2(2f, 2f), a - b)
        assertEquals(Vec2(6f, 8f), a * 2f)
        assertEquals(Vec2(1.5f, 2f), a / 2f)
        assertEquals(5f, a.distance, eps)
    }

    @Test
    fun vec2Direction() {
        assertEquals(0f, Vec2(1f, 0f).direction, eps)
        assertEquals((PI / 2).toFloat(), Vec2(0f, 1f).direction, eps)
    }
}
