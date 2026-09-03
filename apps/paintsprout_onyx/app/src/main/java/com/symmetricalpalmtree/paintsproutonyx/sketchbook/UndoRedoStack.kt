package com.symmetricalpalmtree.paintsproutonyx.sketchbook

/**
 * What the hand has done to this sketchbook since it was opened, and what has been taken back.
 *
 * g-paper keeps no history by design — the host records what happened and replays it — so this is
 * the record. It knows nothing about paper, files or pages: it is ordering and nothing else, which
 * is exactly why it can be proved on a laptop with no tablet in the room. Turning an [Edit] back
 * into rows and pixels lives in the screen, where the paper, the session and the store are all in
 * reach.
 *
 * **Screen-level, not page-level.** Each [Edit] carries the page it happened on, so the history
 * survives a page turn — and adding or deleting a page *is* a page turn, so undoing one has to
 * reverse that turn as well.
 *
 * **Cleared when the sketchbook closes, and never on a flip.** Undo is a memory of this sitting,
 * not a second copy of the file. What is on disk is the drawing; what is here is how the artist got
 * to it, and that is a thing they stop needing when they put the book down.
 *
 * **Bounded at [MAX], oldest dropped.** A hundred is far past anything a hand undoes in one sitting
 * and cheap to hold, because an entry is ids rather than geometry — the rows are all still in the
 * file, stamped rather than deleted. Dropping from the old end means the thing lost is always the
 * thing furthest from what is being worked on now.
 *
 * ## Why there is a generation counter
 *
 * A replay is not instantaneous. It writes to the file and waits, then loads a page and waits, and
 * across those waits the pen can land and finish a mark — the artist drawing while an undo is still
 * catching up. That fresh edit goes through [record], which clears the redo side, because a new
 * mark makes every taken-back edit unreachable: redoing one now would replay it on top of a drawing
 * that has moved on.
 *
 * The replayer, meanwhile, is holding an entry it popped and is about to push onto redo. Pushing it
 * would put back the one thing [record] had just decided must go, and record-clears-redo would be
 * quietly broken by a race nobody can see. So the replayer snapshots [generation] before it starts
 * and compares afterwards: a changed count means an edit landed mid-replay and the entry is
 * dropped rather than pushed.
 */
class UndoRedoStack {

    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()

    /** Bumped by every [record], and by nothing else. See the class note on why it exists. */
    var generation: Int = 0
        private set

    /** An edit that just happened. Clears the redo side — there is no going forward from here now. */
    fun record(edit: Edit) {
        undo.addLast(edit)
        while (undo.size > MAX) undo.removeFirst()
        redo.clear()
        generation++
    }

    fun canUndo(): Boolean = undo.isNotEmpty()

    fun canRedo(): Boolean = redo.isNotEmpty()

    /** Take the newest edit off the undo side; the caller reverses it, then [pushRedo]s it. */
    fun popUndo(): Edit? = undo.removeLastOrNull()

    fun pushRedo(edit: Edit) {
        redo.addLast(edit)
    }

    /** Take the newest taken-back edit off the redo side; the caller re-applies it, then [pushUndo]s it. */
    fun popRedo(): Edit? = redo.removeLastOrNull()

    fun pushUndo(edit: Edit) {
        undo.addLast(edit)
    }

    /** Forget the sitting. Called when the sketchbook closes, and nowhere else. */
    fun clear() {
        undo.clear()
        redo.clear()
    }

    companion object {
        const val MAX = 100
    }
}
