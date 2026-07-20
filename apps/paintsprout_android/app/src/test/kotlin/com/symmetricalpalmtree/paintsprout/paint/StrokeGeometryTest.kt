package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeGeometryTest {

    private val eps = 1e-4f

    // --- resolveWidth --------------------------------------------------------

    @Test
    fun nonDynamicToolsIgnorePressureAndTilt() {
        // Pen and eraser strictly honor the base size regardless of stylus data.
        for (tool in listOf(Tool.PEN, Tool.ERASER)) {
            assertEquals(3f, resolveWidth(tool, 3f, pressureNorm = 0f, tiltRadians = 0f), 0f)
            assertEquals(3f, resolveWidth(tool, 3f, pressureNorm = 1f, tiltRadians = 1.2f), 0f)
        }
    }

    @Test
    fun brushWidthScalesWithPressureAtFlatTilt() {
        // tilt 0 is below the lo cutoff -> tiltFactor 1, so only pressure applies.
        // min/max pressure factors are 0.35 / 2.2 (base 10).
        assertEquals(3.5f, resolveWidth(Tool.BRUSH, 10f, pressureNorm = 0f, tiltRadians = 0f), eps)
        assertEquals(22f, resolveWidth(Tool.BRUSH, 10f, pressureNorm = 1f, tiltRadians = 0f), eps)
    }

    @Test
    fun widthNeverDropsBelowHalfPixel() {
        // Tiny base at minimum pressure would compute 0.35 -> clamped up to 0.5.
        assertEquals(0.5f, resolveWidth(Tool.BRUSH, 1f, pressureNorm = 0f, tiltRadians = 0f), eps)
    }

    @Test
    fun pencilWidthMostlyTiltSlightPressure() {
        // Feel phase: pressure now nudges pencil width a little (a pressed
        // tip flattens), 0.9x..1.12x, while tilt stays the dominant axis.
        val base = 4f
        assertEquals(base * 0.9f, resolveWidth(Tool.PENCIL, base, pressureNorm = 0f, tiltRadians = 0f), eps)
        assertEquals(base * 1.12f, resolveWidth(Tool.PENCIL, base, pressureNorm = 1f, tiltRadians = 0f), eps)

        // Full tilt: tiltFactor = 1 + tiltGain(16) * 1 = 17, times pressure.
        assertEquals(base * 0.9f * 17f, resolveWidth(Tool.PENCIL, base, 0f, TILT_HI_RAD), eps)
    }

    @Test
    fun pencilWidthIsMonotonicInTilt() {
        val base = 4f
        var prev = resolveWidth(Tool.PENCIL, base, 0f, TILT_LO_RAD)
        var t = TILT_LO_RAD + 0.05f
        while (t <= TILT_HI_RAD) {
            val w = resolveWidth(Tool.PENCIL, base, 0f, t)
            assertTrue("width should not decrease as tilt grows (t=$t)", w >= prev - eps)
            prev = w
            t += 0.05f
        }
    }

    // --- resolveDensity ------------------------------------------------------

    @Test
    fun nonDensityToolsStayFullyDense() {
        assertEquals(1f, resolveDensity(Tool.PEN, 0f), 0f)
        assertEquals(1f, resolveDensity(Tool.BRUSH, 0.5f), 0f)
    }

    @Test
    fun pencilDensityMapsPressure() {
        // minDensity 0.1, maxDensity 0.95; pressure is scaled *1.3 then clamped.
        assertEquals(0.1f, resolveDensity(Tool.PENCIL, 0f), eps)
        assertEquals(0.95f, resolveDensity(Tool.PENCIL, 1f), eps)
        // pressureNorm 0.5 -> p' = 0.65 -> lerp(0.1, 0.95, 0.65) = 0.6525
        assertEquals(0.6525f, resolveDensity(Tool.PENCIL, 0.5f), eps)
    }

    // --- strokeNormals -------------------------------------------------------

    @Test
    fun normalsOfHorizontalLinePointDown() {
        val pts = listOf(
            StrokePoint(Vec2(0f, 0f), 2f),
            StrokePoint(Vec2(10f, 0f), 2f),
            StrokePoint(Vec2(20f, 0f), 2f),
        )
        val normals = strokeNormals(pts)
        for (n in normals) {
            assertEquals(0f, n.x, eps)
            assertEquals(1f, n.y, eps)
        }
    }

    @Test
    fun normalsOfVerticalLinePointLeft() {
        val pts = listOf(
            StrokePoint(Vec2(0f, 0f), 2f),
            StrokePoint(Vec2(0f, 10f), 2f),
        )
        val normals = strokeNormals(pts)
        for (n in normals) {
            assertEquals(-1f, n.x, eps)
            assertEquals(0f, n.y, eps)
        }
    }

    @Test
    fun strokeNormalsRequiresTwoPoints() {
        assertThrows(IllegalArgumentException::class.java) {
            strokeNormals(listOf(StrokePoint(Vec2(0f, 0f), 2f)))
        }
    }

    // --- ribbonOutline -------------------------------------------------------

    @Test
    fun ribbonOutlineOffsetsByHalfWidthAlongNormals() {
        val pts = listOf(
            StrokePoint(Vec2(0f, 0f), 2f),
            StrokePoint(Vec2(10f, 0f), 2f),
            StrokePoint(Vec2(20f, 0f), 2f),
        )
        val normals = strokeNormals(pts) // all (0, 1); half-width = 1
        val outline = ribbonOutline(pts, normals)

        // Down the left edge (y = +1), then back up the right edge (y = -1).
        val expected = listOf(
            Vec2(0f, 1f), Vec2(10f, 1f), Vec2(20f, 1f),
            Vec2(20f, -1f), Vec2(10f, -1f), Vec2(0f, -1f),
        )
        assertEquals(expected.size, outline.size)
        for (i in expected.indices) {
            assertEquals("x[$i]", expected[i].x, outline[i].x, eps)
            assertEquals("y[$i]", expected[i].y, outline[i].y, eps)
        }
    }

    @Test
    fun strokeNormalsRangeMatchesWholeStrokeComputation() {
        // A wiggly stroke; every interior sub-range must agree with the full
        // computation (the incremental append relies on this).
        val pts = listOf(
            StrokePoint(Vec2(0f, 0f), 2f),
            StrokePoint(Vec2(10f, 4f), 2f),
            StrokePoint(Vec2(18f, -3f), 2f),
            StrokePoint(Vec2(30f, 5f), 2f),
            StrokePoint(Vec2(41f, 2f), 2f),
        )
        val full = strokeNormals(pts)
        for (from in pts.indices) {
            for (to in from until pts.size) {
                val range = strokeNormalsRange(pts, from, to)
                for (i in from..to) {
                    assertEquals("x[$from..$to @$i]", full[i].x, range[i - from].x, eps)
                    assertEquals("y[$from..$to @$i]", full[i].y, range[i - from].y, eps)
                }
            }
        }
    }

    @Test
    fun ribbonOutlineClampsHalfWidthToMinimum() {
        // width 0.4 -> half-width 0.2 -> clamped up to 0.5.
        val pts = listOf(
            StrokePoint(Vec2(0f, 0f), 0.4f),
            StrokePoint(Vec2(10f, 0f), 0.4f),
        )
        val normals = strokeNormals(pts)
        val outline = ribbonOutline(pts, normals)
        assertEquals(0.5f, outline.first().y, eps)
    }

    @Test
    fun windowedNormalsSteadyThroughLandingClusterNoise() {
        // A horizontal line whose points carry ~±1px of irregular vertical
        // sensor noise at ~1.3px spacing — a pen landing. The windowed
        // chord bounds the residual tilt at noise/window (here 2/16), so
        // every normal stays near vertical — and strictly steadier than the
        // raw central-difference normals, which swing with each sample.
        val noise = floatArrayOf(1f, -0.7f, 0.3f, -1f, 0.8f, -0.2f, 0.6f, -0.9f)
        val pts = ArrayList<StrokePoint>()
        var x = 0f
        var i = 0
        while (x <= 40f) {
            pts.add(StrokePoint(Vec2(x, 100f + noise[i % noise.size]), width = 16f))
            i++
            x += 1.3f
        }
        val stroke = Stroke(Tool.MARKER, 0xFF000000.toInt(), baseWidth = 16f)
        pts.forEach(stroke::add)
        val windowed = windowedNormals(stroke.points, stroke.arcLengths())
        val raw = strokeNormals(stroke.points)
        for ((k, nrm) in windowed.withIndex()) {
            assertTrue(
                "normal $k should be near vertical, was (${nrm.x}, ${nrm.y})",
                kotlin.math.abs(nrm.y) > 0.95f,
            )
        }
        val worstWindowed = windowed.minOf { kotlin.math.abs(it.y) }
        val worstRaw = raw.minOf { kotlin.math.abs(it.y) }
        assertTrue(
            "windowed ($worstWindowed) must beat raw ($worstRaw)",
            worstWindowed > worstRaw,
        )
    }

    @Test
    fun chiselNibWidthFollowsTravelDirection() {
        val base = 16f
        // Across the edge (travel perpendicular to the nib angle): full width.
        val across = NIB_ANGLE_RAD + (Math.PI / 2).toFloat()
        assertEquals(base, chiselNibWidth(base, 0f, across), 0.01f)
        // Along the edge: only the nib's own thickness.
        assertEquals(base * NIB_FLOOR, chiselNibWidth(base, 0f, NIB_ANGLE_RAD), 0.01f)
        // Unknown direction (first point): mid engagement, between the two.
        val unknown = chiselNibWidth(base, 0f, null)
        assertTrue(unknown > base * NIB_FLOOR && unknown < base)
        // Full tilt engages more edge.
        assertEquals(
            base * (1f + NIB_TILT_ENGAGE),
            chiselNibWidth(base, TILT_HI_RAD, across), 0.01f,
        )
    }
}
