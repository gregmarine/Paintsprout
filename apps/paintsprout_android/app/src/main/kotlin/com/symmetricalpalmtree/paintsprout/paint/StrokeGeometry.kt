package com.symmetricalpalmtree.paintsprout.paint

import kotlin.math.max
import kotlin.math.pow

/**
 * Pure stroke geometry and stylus-response math, ported from the Flutter
 * `stroke.dart`. Everything here is framework-free so it can be unit-tested on
 * the plain JVM.
 *
 * The `Path`-building helpers from the Flutter file (`smoothPath`,
 * `strokeRibbon`) are deliberately *not* here — they produce `android.graphics.
 * Path` objects and belong with the renderer (Stage 2). Their pure inputs
 * ([strokeNormals], [ribbonOutline]) live here and are tested.
 */

// A held stylus never reaches 0 (perpendicular) or pi/2 (flat) tilt in practice
// — the reachable range sits in a compressed middle band. Remap that band to
// [0, 1] so an upright pen reads as a fine tip and laying it onto its side reads
// as the full width, with everything in between proportional.
const val TILT_LO_RAD = 0.42f // ~24 deg: "upright" hold -> thin
const val TILT_HI_RAD = 1.05f // ~60 deg: on its side -> full width

// Ease-in exponent (>1) so the first few degrees off upright widen gently
// instead of jumping, while full tilt still reaches the same max width.
const val TILT_EASE = 2.1f

/** Linear interpolation, matching Flutter's `lerpDouble(a, b, t) = a + (b-a)*t`. */
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Resolves the width of a mark for the given tool, base size and stylus data.
 *
 * [pressureNorm] is expected in [0, 1]; [tiltRadians] in [0, pi/2] where 0 is
 * perpendicular to the surface and pi/2 is flat against it.
 */
fun resolveWidth(
    tool: Tool,
    baseSize: Float,
    pressureNorm: Float,
    tiltRadians: Float,
): Float {
    val profile = ToolProfile.of(tool)
    if (!tool.isDynamic) return baseSize

    val p = pressureNorm.coerceIn(0.0f, 1.0f)
    val pressureFactor = if (profile.pressureAffectsWidth) {
        lerp(profile.minPressureFactor, profile.maxPressureFactor, p)
    } else {
        1.0f
    }

    val rawTilt = ((tiltRadians - TILT_LO_RAD) / (TILT_HI_RAD - TILT_LO_RAD))
        .coerceIn(0.0f, 1.0f)
    val tiltNorm = rawTilt.pow(TILT_EASE)
    val tiltFactor = 1.0f + profile.tiltGain * tiltNorm

    return max(0.5f, baseSize * pressureFactor * tiltFactor)
}

// The marker's chisel nib: a flat edge held at a fixed angle in hand space.
// Dragging ALONG the edge lays only its thickness; dragging ACROSS it lays
// the full engaged edge. Tilt engages more edge (pressing the face down)
// instead of multiplying an isotropic ribbon — the old tiltGain look the
// user rejected.
const val NIB_ANGLE_RAD = 0.6f // ~34°, the classic right-hand chisel hold
const val NIB_FLOOR = 0.3f // the edge's own thickness, as a fraction
const val NIB_TILT_ENGAGE = 2.5f // full tilt engages 3.5x the upright edge
const val NIB_DEFAULT_CROSS = 0.7f // no direction yet (first point): mid engagement

/**
 * The chisel-nib mark width for a marker travelling at [travelAngle]
 * (radians, canvas space; null = direction unknown), holding [tiltRadians]
 * of tilt over a [base] px edge.
 */
fun chiselNibWidth(base: Float, tiltRadians: Float, travelAngle: Float?): Float {
    val rawTilt = ((tiltRadians - TILT_LO_RAD) / (TILT_HI_RAD - TILT_LO_RAD))
        .coerceIn(0.0f, 1.0f)
    val engaged = base * (1f + NIB_TILT_ENGAGE * rawTilt.pow(TILT_EASE))
    val cross = if (travelAngle == null) {
        NIB_DEFAULT_CROSS
    } else {
        kotlin.math.abs(kotlin.math.sin(travelAngle - NIB_ANGLE_RAD))
    }
    return max(0.5f, engaged * (NIB_FLOOR + (1f - NIB_FLOOR) * cross))
}

/**
 * Resolves per-point density (darkness) from pressure. Pencil maps light
 * pressure to faint marks; other tools stay fully dense.
 */
fun resolveDensity(tool: Tool, pressureNorm: Float): Float {
    val profile = ToolProfile.of(tool)
    if (!profile.pressureAffectsDensity) return 1.0f
    // Real strokes rarely exceed ~0.8 raw pressure, so scale up to use the full
    // density range without the artist having to bear down to the stops.
    val p = (pressureNorm * 1.3f).coerceIn(0.0f, 1.0f)
    if (tool == Tool.ERASER) {
        // The eraser's ramp saturates early: a normal erasing hand must
        // remove paint FULLY (a linear ramp left ghosts on every ordinary
        // pass) — only a genuine graze lifts partially.
        val t = (p / ERASER_FULL_AT).coerceIn(0.0f, 1.0f)
        return lerp(profile.minDensity, profile.maxDensity, t)
    }
    return lerp(profile.minDensity, profile.maxDensity, p)
}

/** Scaled pressure at which the eraser reaches full lift. */
const val ERASER_FULL_AT = 0.55f

/**
 * Per-point unit normals (perpendicular to the local tangent) for a stroke.
 * Requires at least two points (single-dab strokes are rendered as a disc, which
 * never needs normals).
 */
fun strokeNormals(pts: List<StrokePoint>): List<Vec2> {
    require(pts.size >= 2) { "strokeNormals needs at least 2 points" }
    val normals = ArrayList<Vec2>(pts.size)
    for (i in pts.indices) {
        val tangent = when (i) {
            0 -> pts[1].position - pts[0].position
            pts.size - 1 -> pts[i].position - pts[i - 1].position
            else -> pts[i + 1].position - pts[i - 1].position
        }
        val len = tangent.distance
        val dir = if (len < 1e-3f) Vec2(1f, 0f) else tangent / len
        normals.add(Vec2(-dir.y, dir.x))
    }
    return normals
}

/**
 * Unit normals for just the points [from..to] (inclusive), reading only the
 * neighbours the formula needs — so an incremental append costs the range, not
 * the stroke. Entry k corresponds to point from+k, and matches what
 * [strokeNormals] would produce at the same index PROVIDED the points after
 * [to] existed when [strokeNormals] ran (interior normals are final once both
 * neighbours exist; only the last point's normal is provisional).
 */
fun strokeNormalsRange(pts: List<StrokePoint>, from: Int, to: Int): List<Vec2> {
    require(pts.size >= 2) { "strokeNormalsRange needs at least 2 points" }
    require(from in 0..to && to < pts.size) { "bad range $from..$to for ${pts.size} points" }
    val normals = ArrayList<Vec2>(to - from + 1)
    for (i in from..to) {
        val tangent = when (i) {
            0 -> pts[1].position - pts[0].position
            pts.size - 1 -> pts[i].position - pts[i - 1].position
            else -> pts[i + 1].position - pts[i - 1].position
        }
        val len = tangent.distance
        val dir = if (len < 1e-3f) Vec2(1f, 0f) else tangent / len
        normals.add(Vec2(-dir.y, dir.x))
    }
    return normals
}

/**
 * Per-point unit normals measured over an arc-length window instead of
 * adjacent samples. A slow pen landing lays a cluster of points whose
 * spacing is the size of the sensor noise, and central-difference normals
 * swing wildly through it — the visible jag at stroke starts. At the scale
 * of its own footprint the tool is rigid (the wash geometry's
 * surface-tension lesson), so each tangent is the chord across [window] of
 * arc length centred on the point; a collapsed window carries the previous
 * direction rather than inventing one from noise. [arcs] is
 * `Stroke.arcLengths()` (entries valid for `pts.indices`).
 */
fun windowedNormals(pts: List<StrokePoint>, arcs: FloatArray, minWindow: Float = 4f): List<Vec2> {
    require(pts.size >= 2) { "windowedNormals needs at least 2 points" }
    val n = pts.size
    val out = ArrayList<Vec2>(n)
    var j = 0
    var k = 0
    var prevDir = Vec2(1f, 0f)
    for (i in 0 until n) {
        // Window = the LOCAL footprint: a thin pencil stays near-raw, a
        // broad tilted marker steadies hard. The pointers stay monotone
        // under a varying window; the mild chord asymmetry that allows is
        // harmless for a smoothing measurement.
        val half = maxOf(minWindow, pts[i].width) / 2f
        while (j < i && arcs[j + 1] <= arcs[i] - half) j++
        if (k < i) k = i
        while (k < n - 1 && arcs[k] < arcs[i] + half) k++
        val chord = pts[k].position - pts[j].position
        val len = chord.distance
        val dir = if (len < 1e-3f) prevDir else chord / len
        prevDir = dir
        out.add(Vec2(-dir.y, dir.x))
    }
    return out
}

/**
 * The unit normal at a stroke's last point, measured over an arc-length
 * [window] instead of the final segment. The raw tail normal follows every
 * sensor wobble of the newest sample, and a bristle fan pivots laterally
 * around the tail by half the brush width times that swing — visible jitter.
 * A window about a brush-width long is the same steadying the wash geometry
 * uses: at the scale of its own footprint the tool is rigid. Returns null
 * when the chord collapses (coincident points), meaning: keep the segment
 * normal.
 */
fun windowedTailNormal(pts: List<StrokePoint>, window: Float): Vec2? {
    if (pts.size < 2) return null
    var j = pts.size - 1
    var acc = 0f
    while (j > 0 && acc < window) {
        acc += (pts[j].position - pts[j - 1].position).distance
        j--
    }
    val chord = pts.last().position - pts[j].position
    val len = chord.distance
    if (len < 1e-3f) return null
    val dir = chord / len
    return Vec2(-dir.y, dir.x)
}

/**
 * A single closed polygon covering the whole variable-width stroke: down the
 * left edge (position + normal*halfWidth) and back up the right edge. Returns
 * the ordered polygon vertices; the renderer turns them into a filled `Path`
 * (or strokes the outline for the pooled watercolor rim).
 *
 * Ported from `_ribbonPath` in `stroke.dart`, minus the `Path` construction.
 */
fun ribbonOutline(pts: List<StrokePoint>, normals: List<Vec2>): List<Vec2> {
    require(pts.size == normals.size) { "pts and normals must be the same length" }
    val out = ArrayList<Vec2>(pts.size * 2)
    for (i in pts.indices) {
        val hw = max(0.5f, pts[i].width / 2f)
        out.add(pts[i].position + normals[i] * hw)
    }
    for (i in pts.indices.reversed()) {
        val hw = max(0.5f, pts[i].width / 2f)
        out.add(pts[i].position - normals[i] * hw)
    }
    return out
}
