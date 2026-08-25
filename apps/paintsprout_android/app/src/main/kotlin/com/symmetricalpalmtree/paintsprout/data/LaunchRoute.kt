package com.symmetricalpalmtree.paintsprout.data

/** The screen the app opens on, once the index is ready. */
enum class LaunchTarget { EDITOR, LIBRARY }

/**
 * Where a stored pointer sends the user.
 *
 * A function of its own because the obvious version of it was wrong on device:
 * "route to the editor if the pointer names a document" was written when a
 * sketchbook was the only thing the editor could host, and it quietly sent every
 * scratchpad session to the library — the scratchpad *has* no document id.
 *
 * Whether the thing pointed at still exists is a separate question, answered
 * later and much closer to the file. This decides which screen, not whether the
 * pointer is any good.
 */
object LaunchRoute {

    fun of(pointer: LastOpen.Pointer?): LaunchTarget = when {
        pointer == null -> LaunchTarget.LIBRARY
        pointer.kind == LastOpen.Kind.SCRATCHPAD -> LaunchTarget.EDITOR
        pointer.documentId != null -> LaunchTarget.EDITOR
        else -> LaunchTarget.LIBRARY
    }
}
