package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap

/**
 * One sheet of the stack.
 *
 * A page is a surface with layers laid over it, each holding its own paint and
 * transparent everywhere it has none. They composite bottom-first, so the last in
 * the list is the one nearest the viewer.
 *
 * A layer owns its pixels and nothing else. What is *on* it lives in the page's
 * op history, tagged with this layer's [id]: the bitmap is only ever the folded
 * result of those ops, which is why it can be thrown away and rebuilt.
 *
 * [opacity] and [visible] are properties of the layer rather than of its paint —
 * they change how it is composited, never what it holds — so turning a layer off
 * and on again is not an edit and costs nothing to undo.
 */
class Layer(
    val id: String,
    var name: String,
    var visible: Boolean = true,
    var opacity: Float = 1f,
) {
    /** The folded paint, at buffer resolution. Null until the view is laid out. */
    var bmp: Bitmap? = null

    /** Alpha to composite with, 0–255. A hidden layer never gets this far. */
    val alpha: Int get() = (opacity.coerceIn(0f, 1f) * 255f).toInt()

    fun recycle() {
        bmp?.recycle()
        bmp = null
    }

    companion object {
        /** What the first layer of a page is called when nothing named it. */
        const val DEFAULT_NAME = "Paint"

        /** Ceiling on layers per page, pending the memory measurement. */
        const val MAX_PER_PAGE = 8
    }
}
