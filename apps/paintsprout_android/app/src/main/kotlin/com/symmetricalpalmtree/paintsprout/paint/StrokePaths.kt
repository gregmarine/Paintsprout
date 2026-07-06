package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Path
import kotlin.math.max

/**
 * `android.graphics.Path` builders that consume the pure geometry from
 * [StrokeGeometry]. Kept separate from that (JVM-testable) file because these
 * touch the Android framework. Ported from `smoothPath` in the Flutter
 * `stroke.dart`.
 */

/**
 * A smooth path through the sample points using quadratic béziers whose
 * endpoints are the segment midpoints and whose control points are the raw
 * samples. This both curves through the points and damps sample jitter.
 */
fun smoothPath(pts: List<StrokePoint>): Path {
    val path = Path()
    path.moveTo(pts.first().position.x, pts.first().position.y)
    if (pts.size == 2) {
        path.lineTo(pts[1].position.x, pts[1].position.y)
        return path
    }
    for (i in 1 until pts.size - 1) {
        val c = pts[i].position
        val next = pts[i + 1].position
        path.quadTo(c.x, c.y, (c.x + next.x) / 2f, (c.y + next.y) / 2f)
    }
    val last = pts.last().position
    path.lineTo(last.x, last.y)
    return path
}

/** Builds a closed `Path` from a ribbon outline (see [ribbonOutline]). */
fun ribbonPath(outline: List<Vec2>): Path {
    val path = Path()
    outline.forEachIndexed { i, v ->
        if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y)
    }
    path.close()
    return path
}

/**
 * The filled outline of a whole stroke (variable-width ribbon, or a disc for a
 * single dab) in the stroke's own coordinates. The watercolor wet interaction
 * uses this to mask the region of existing paint it re-wets. Ported from
 * `strokeRibbon` in the Flutter `stroke.dart`.
 */
fun strokeRegionPath(stroke: Stroke): Path {
    val pts = stroke.points
    if (pts.size == 1) {
        val p = pts.first()
        return Path().apply {
            addCircle(p.position.x, p.position.y, max(0.5f, p.width / 2f), Path.Direction.CW)
        }
    }
    return ribbonPath(ribbonOutline(pts, strokeNormals(pts)))
}
