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
    return lerp(profile.minDensity, profile.maxDensity, p)
}

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
