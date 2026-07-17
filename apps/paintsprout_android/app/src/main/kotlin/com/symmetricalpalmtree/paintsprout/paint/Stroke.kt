package com.symmetricalpalmtree.paintsprout.paint

import androidx.annotation.ColorInt

/** [StrokePoint.color] sentinel: take the colour from the stroke instead. */
const val INHERIT_COLOR = 0

/**
 * One captured sample along a stroke. Width and density are resolved at capture
 * time from the tool profile + stylus pressure/tilt (see [resolveWidth] /
 * [resolveDensity]), so rendering never needs to look at pressure again.
 *
 * [color] and [load] are resolved at capture time for the same reason: they are
 * what the brush happened to be carrying here. Baking them into the point (rather
 * than re-deriving them at paint time) keeps replay honest — undo/redo refolds the
 * op history without having to re-simulate the brush and arrive at the same
 * answer.
 *
 * @param density per-point opacity/darkness in [0, 1]. Pencil maps pressure to
 *   this so lighter pressure = fainter marks; other tools leave it at 1.
 * @param color ARGB the brush carried at this point, or [INHERIT_COLOR] to use
 *   the stroke's own colour. Only wet media (which pick pigment up as they go)
 *   vary this along a stroke. Must be opaque: the renderer draws segments
 *   inside an isolated layer and relies on opaque overlaps not accumulating.
 * @param load how full the brush was here, in [0, 1]. 1 is a fully charged
 *   brush; as it drains the mark fades out. Non-wet tools leave it at 1.
 */
data class StrokePoint(
    val position: Vec2,
    val width: Float,
    val density: Float = 1.0f,
    @param:ColorInt val color: Int = INHERIT_COLOR,
    val load: Float = 1.0f,
)

/**
 * A single continuous stroke (pointer-down to pointer-up). Ported from `Stroke`
 * in the Flutter `stroke.dart`.
 *
 * @param color the color the stroke was drawn with (ARGB). Eraser strokes use
 *   the paper color.
 * @param seed stable per-stroke seed for any randomized texture (brush
 *   bristles), so the live preview and the baked result look identical.
 */
class Stroke(
    val tool: Tool,
    @param:ColorInt val color: Int,
    val seed: Int = 0,
) {
    val points: MutableList<StrokePoint> = mutableListOf()

    fun add(p: StrokePoint) {
        points.add(p)
    }

    val isEmpty: Boolean get() = points.isEmpty()

    /** True if the brush ran down at any point — the renderer then fades the mark. */
    val varies: Boolean get() = points.any { it.load < 1f }

    /** True if the brush changed colour mid-stroke (it dragged through wet paint). */
    val dirty: Boolean get() = points.any { it.color != INHERIT_COLOR }
}
