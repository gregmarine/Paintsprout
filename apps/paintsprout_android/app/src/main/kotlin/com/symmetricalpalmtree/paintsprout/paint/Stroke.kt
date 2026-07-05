package com.symmetricalpalmtree.paintsprout.paint

import androidx.annotation.ColorInt

/**
 * One captured sample along a stroke. Width and density are resolved at capture
 * time from the tool profile + stylus pressure/tilt (see [resolveWidth] /
 * [resolveDensity]), so rendering never needs to look at pressure again.
 *
 * @param density per-point opacity/darkness in [0, 1]. Pencil maps pressure to
 *   this so lighter pressure = fainter marks; other tools leave it at 1.
 */
data class StrokePoint(
    val position: Vec2,
    val width: Float,
    val density: Float = 1.0f,
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
}
