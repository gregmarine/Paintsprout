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

    /**
     * What a layer is before anything is done to it: wholly there.
     *
     * This is the floor an undo unwinds towards, and it is a constant rather than
     * something read off the file. The row on disk holds the state as it stood
     * when the page last closed, which is the *end* of the timeline, not its
     * beginning — rewinding towards it would leave undo with nothing to undo. An
     * op records only the value it set, never the one it replaced, so the start
     * cannot be recovered from the ops either. It does not need to be: from here
     * on every change is an op, so base-plus-ops reproduces the stored state
     * exactly, and a page written before any of this had no way to be anything
     * other than visible and opaque.
     */
    var baseVisible: Boolean = true
    var baseOpacity: Float = 1f

    /** What it was called before the timeline renamed it. See [baseVisible]. */
    var baseName: String = name

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
