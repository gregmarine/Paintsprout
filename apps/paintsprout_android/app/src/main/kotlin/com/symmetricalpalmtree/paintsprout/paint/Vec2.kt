package com.symmetricalpalmtree.paintsprout.paint

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A 2D point/vector in canvas coordinates. The native counterpart of Flutter's
 * `Offset` — an immutable value type with the vector operators the stroke
 * geometry relies on. Kept framework-free (no `android.graphics.PointF`) so the
 * geometry that uses it stays unit-testable on the plain JVM.
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2): Vec2 = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2): Vec2 = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float): Vec2 = Vec2(x * s, y * s)
    operator fun div(s: Float): Vec2 = Vec2(x / s, y / s)

    /** Euclidean length. Mirrors `Offset.distance`. */
    val distance: Float get() = hypot(x, y)

    /** Angle of the vector in radians. Mirrors `Offset.direction` (atan2(dy, dx)). */
    val direction: Float get() = atan2(y, x)

    companion object {
        val Zero = Vec2(0f, 0f)
    }
}
