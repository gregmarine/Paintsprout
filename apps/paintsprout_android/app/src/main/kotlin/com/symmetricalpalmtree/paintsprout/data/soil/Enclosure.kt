package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.MaskCodec
import com.symmetricalpalmtree.paintsprout.data.soil.codec.StrokeCodec

/**
 * Which ops a selection has actually got hold of.
 *
 * Copy takes **whole ops**, so the question is not "which pixels are selected"
 * but "which marks are entirely inside" — and getting it wrong in either
 * direction is a bad day: too strict and a stroke the user obviously enclosed is
 * silently missing from the paste; too loose and they get a mark they never
 * selected, pasted into another book.
 *
 * The row is the source of truth here rather than the rendered pixels, because
 * that is what actually gets copied. Kept free of Android types so the rule can
 * be tested without a canvas — the caller supplies [inside] as a plain sampler
 * over whatever it has.
 */
object Enclosure {

    /** A box in buffer pixels. Right and bottom are exclusive-ish; nothing here cares. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun within(outer: Box): Boolean =
            left >= outer.left && top >= outer.top && right <= outer.right && bottom <= outer.bottom
    }

    /**
     * What an op occupies: its box, plus the points that have to test inside.
     *
     * A stroke supplies its own points, so a lasso around a curve is judged
     * against the curve rather than against the rectangle it happens to span — an
     * S drawn corner to corner has a box far larger than the mark. A region op
     * has no path, so its box corners stand in for it, which is exact for a
     * convex selection and generous for a concave one. Generous is the side to
     * err on: a mark that came along is visible and can be undone, where a mark
     * that stayed behind is discovered later, in another document.
     */
    class Shape(val box: Box, val samples: FloatArray)

    /**
     * The area an op covers, or null when it has none — a surface change is not
     * a mark, and an unreadable blob is a row we decline to guess about.
     */
    fun shapeOf(row: SoilObject): Shape? = when (row.type) {
        SoilType.STROKE -> strokeShape(row)
        SoilType.FILL, SoilType.ERASE, SoilType.MOVE -> maskShape(row)
        else -> null
    }

    private fun strokeShape(row: SoilObject): Shape? {
        val points = StrokeCodec.decode(row.blob) ?: return null
        if (points.isEmpty()) return null
        val samples = FloatArray(points.size * 2)
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var widest = row.strokeWidth ?: 0f
        points.forEachIndexed { i, p ->
            samples[2 * i] = p.position.x
            samples[2 * i + 1] = p.position.y
            if (p.position.x < l) l = p.position.x
            if (p.position.x > r) r = p.position.x
            if (p.position.y < t) t = p.position.y
            if (p.position.y > b) b = p.position.y
            if (p.width > widest) widest = p.width
        }
        // The box carries the mark's *width*: a stroke whose spine is inside the
        // loop but whose edge spills over it was not wholly enclosed.
        val half = widest / 2f
        return Shape(Box(l - half, t - half, r + half, b + half), samples)
    }

    /**
     * A mask op's extent comes from where its crop sits, scaled back up: masks
     * are stored at half resolution with the factor in the row, and a box in the
     * wrong units would be silently half the size it should be.
     */
    private fun maskShape(row: SoilObject): Shape? {
        val mask = MaskCodec.decode(row.blob) ?: return null
        val scale = row.amount ?: 1f
        val l = (row.x ?: 0f) * scale
        val t = (row.y ?: 0f) * scale
        val r = l + mask.width * scale
        val b = t + mask.height * scale
        return Shape(Box(l, t, r, b), floatArrayOf(l, t, r, t, r, b, l, b))
    }

    /**
     * Whether [shape] lies wholly within a selection whose bounding box is
     * [bounds] and whose coverage is [inside].
     *
     * Both tests, not either: the box catches a mark that runs outside the
     * selection entirely, and the samples catch one that stays within its box but
     * wanders out of the shape the user actually drew.
     */
    fun encloses(shape: Shape, bounds: Box, inside: (Float, Float) -> Boolean): Boolean {
        if (!shape.box.within(bounds)) return false
        for (i in 0 until shape.samples.size / 2) {
            if (!inside(shape.samples[2 * i], shape.samples[2 * i + 1])) return false
        }
        return true
    }
}
