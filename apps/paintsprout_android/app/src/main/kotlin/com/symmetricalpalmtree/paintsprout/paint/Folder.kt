package com.symmetricalpalmtree.paintsprout.paint

/**
 * Somewhere to keep layers.
 *
 * A folder holds no pixels and paints nothing. It is a place in the stack with a
 * name, and what it does to the layers filed in it is reach through them: its
 * [opacity] multiplies onto theirs and its [visible] gates theirs, one level at
 * a time, all the way down. So a folder never composites its contents together
 * first — two half-opaque layers inside a folder at half are two quarter-opaque
 * layers, still overlapping each other, and the seam where they cross is still
 * darker than either. Turning a shelf down does not merge what is on it.
 *
 * That is also why nesting is free here. Since a folder changes nothing about
 * the *order* paint goes down in, and only scales what is already there, folders
 * inside folders need no buffer of their own at any depth — the effective
 * opacity of a layer is its own times its ancestors', and that is the whole
 * calculation.
 *
 * [collapsed] is the odd one out and stays off the undo timeline: shutting a
 * folder changes how much of a list you are looking at, not the picture.
 */
class Folder(
    val id: String,
    var name: String,
    var visible: Boolean = true,
    var opacity: Float = 1f,
    var collapsed: Boolean = false,
) {
    /**
     * What a folder is before anything is done to it, for the same reason a
     * layer has one: the row on disk holds the state as it stood when the page
     * last closed, which is the end of the timeline rather than its beginning.
     * See [Layer.baseVisible].
     */
    var baseVisible: Boolean = true
    var baseOpacity: Float = 1f

    companion object {
        /** What a folder is called when nothing named it. */
        const val DEFAULT_NAME = "Folder"
    }
}
