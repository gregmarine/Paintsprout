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






    // --- Spans (how a fading / contaminated stroke gets drawn) --------------

    private val black = 0xFF000000.toInt()
    private fun linear(load: Float) = load

    /** The perf guard: an ordinary stroke must still be one path per bristle. */
    @Test
    fun anOrdinaryStrokeIsASingleSpan() {
        val stroke = Stroke(Tool.BRUSH, color = black)
        repeat(200) { i -> stroke.add(StrokePoint(Vec2(i * 3f, 0f), 4f)) }

        assertEquals(1, strokeRuns(stroke, black, ::linear).size)
    }

    /** A loaded-but-not-yet-fading brush is likewise one span. */
    @Test
    fun aFullBrushHoldingOneColourIsASingleSpan() {
        val green = 0xFF6A8A42.toInt()
        val stroke = Stroke(Tool.BRUSH, color = green)
        repeat(200) { i -> stroke.add(StrokePoint(Vec2(i * 3f, 0f), 4f, color = green, load = 1f)) }

        val runs = strokeRuns(stroke, green, ::linear)
        assertEquals(1, runs.size)
        assertEquals(green, runs[0].color)
        assertEquals(1f, runs[0].alpha, 1e-4f)
    }

    /** Spans are bounded no matter how many points a draining stroke has. */
    @Test
    fun aDrainingStrokeSplitsIntoBoundedSpans() {
        val stroke = Stroke(Tool.BRUSH, color = black)
        repeat(500) { i -> stroke.add(StrokePoint(Vec2(i * 3f, 0f), 4f, load = 1f - i / 500f)) }

        val runs = strokeRuns(stroke, black, ::linear)
        assertTrue("should split as it fades", runs.size > 1)
        assertTrue("but stay bounded by ALPHA_STEP, was ${runs.size}", runs.size <= (1f / ALPHA_STEP).toInt() + 2)
    }

    /** Spans must tile the stroke and touch, or the mark would show gaps. */
    @Test
    fun spansCoverTheWholeStrokeAndJoinUp() {
        val stroke = Stroke(Tool.BRUSH, color = black)
        repeat(120) { i -> stroke.add(StrokePoint(Vec2(i * 3f, 0f), 4f, load = 1f - i / 120f)) }

        val runs = strokeRuns(stroke, black, ::linear)
        assertEquals("starts at the first point", 0, runs.first().from)
        assertEquals("ends at the last point", stroke.points.size - 1, runs.last().to)
        for (i in 1 until runs.size) {
            assertEquals("span $i must start where span ${i - 1} ended", runs[i - 1].to, runs[i].from)
        }
        for (r in runs) assertTrue("every span spans at least a segment", r.to > r.from)
    }

    /** A colour change splits a span even when the strength hasn't moved. */
    @Test
    fun pickingUpPigmentSplitsASpan() {
        val stroke = Stroke(Tool.BRUSH, color = black)
        val blue = 0xFF0000FF.toInt()
        val green = 0xFF00FF00.toInt()
        repeat(5) { stroke.add(StrokePoint(Vec2(it * 3f, 0f), 4f, color = blue)) }
        repeat(5) { stroke.add(StrokePoint(Vec2((5 + it) * 3f, 0f), 4f, color = green)) }

        val runs = strokeRuns(stroke, black, ::linear)
        assertEquals(2, runs.size)
        assertEquals(blue, runs[0].color)
        assertEquals(green, runs[1].color)
    }

    /** A point with no colour of its own takes the stroke's, already opaque. */
    @Test
    fun spansInheritTheStrokesColourOpaque() {
        val stroke = Stroke(Tool.BRUSH, color = black)
        repeat(4) { stroke.add(StrokePoint(Vec2(it * 3f, 0f), 4f)) }

        val runs = strokeRuns(stroke, black, ::linear)
        assertEquals(black, runs[0].color)
        assertEquals(0xFF, (runs[0].color ushr 24) and 0xFF)
    }

    @Test
    fun aStrokeTooShortToSpanYieldsNothing() {
        val stroke = Stroke(Tool.BRUSH, color = black)
        assertTrue(strokeRuns(stroke, black, ::linear).isEmpty())
        stroke.add(StrokePoint(Vec2(0f, 0f), 4f))
        assertTrue("a single dab is drawn directly, not as a span", strokeRuns(stroke, black, ::linear).isEmpty())
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
