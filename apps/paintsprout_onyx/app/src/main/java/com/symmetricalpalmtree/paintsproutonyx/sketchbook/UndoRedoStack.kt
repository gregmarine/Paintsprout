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
 * **And bounded again at [BUDGET_BYTES], because a raster page's entry is not ids.** The sentence
 * above stopped being the whole truth in R3. A raster book has no rows to un-stamp: the only record
 * of what was on the page before a mark or a rub is the pixels that were there, so an entry carries
 * them ([Edit.RasterChanged]), and a page-wide erase's before-image is the size of the page —
 * eighteen megabytes on this panel. A hundred of those is not a history, it is a dead process. So
 * the undo side also keeps a running total of [Edit.bytes] and evicts while it is over budget, and
 * what it evicts is always the **oldest entry that actually costs something**: an id-only edit is
 * free and dropping one would shorten a stroke book's history to pay for a raster book's, which is
 * a bill the wrong hand receives.
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
class UndoRedoStack(private val budgetBytes: Long = BUDGET_BYTES) {

    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()

    /**
     * What the undo side is holding in pixels, kept as it goes rather than counted when it is
     * asked for. Adding an entry is one addition and evicting one is one subtraction; adding up a
     * hundred entries on every mark would put a walk of the whole history in the path of the pen.
     *
     * The redo side is deliberately not counted. A fresh [record] clears it outright, so the only
     * way pixels sit there at all is between an undo and the artist's next mark — a moment, and one
     * where the alternative is throwing away the thing they are about to ask for again.
     */
    var undoBytes: Long = 0L
        private set

    /** Bumped by every [record], and by nothing else. See the class note on why it exists. */
    var generation: Int = 0
        private set

    /** An edit that just happened. Clears the redo side — there is no going forward from here now. */
    fun record(edit: Edit) {
        undo.addLast(edit)
        undoBytes += edit.bytes
        while (undo.size > MAX) undoBytes -= undo.removeFirst().bytes
        evictForBudget()
        redo.clear()
        generation++
    }

    /**
     * Make room for what was just recorded by letting go of the oldest pixels held.
     *
     * The search for "the oldest entry that costs something" walks from the old end, which is a
     * walk of at most [MAX] entries and only on the rare mark that actually overflows the budget —
     * the total itself is never recounted, which is the part that would be in the path of every
     * stroke.
     *
     * **The newest entry is never the one dropped.** It is the thing the artist is about to reach
     * for, and an undo arrow that does nothing for the mark just made reads as broken. A single
     * entry cannot exceed the budget on its own anyway (a contact's before-image is bounded by the
     * page — see [RasterTiles]), so this only ever decides the case where old entries have all gone
     * already.
     */
    private fun evictForBudget() {
        while (undoBytes > budgetBytes) {
            val oldest = undo.indexOfFirst { it.bytes > 0 }
            if (oldest < 0 || oldest == undo.lastIndex) return
            undoBytes -= undo.removeAt(oldest).bytes
        }
    }

    fun canUndo(): Boolean = undo.isNotEmpty()

    fun canRedo(): Boolean = redo.isNotEmpty()

    /** Take the newest edit off the undo side; the caller reverses it, then [pushRedo]s it. */
    fun popUndo(): Edit? = undo.removeLastOrNull()?.also { undoBytes -= it.bytes }

    fun pushRedo(edit: Edit) {
        redo.addLast(edit)
    }

    /** Take the newest taken-back edit off the redo side; the caller re-applies it, then [pushUndo]s it. */
    fun popRedo(): Edit? = redo.removeLastOrNull()

    /**
     * An edit coming back from the redo side. Its pixels count again the moment it does — it is on
     * the undo side now and is holding exactly what it was holding before, so a total that did not
     * move would drift further from the truth with every undo the artist changed their mind about.
     */
    fun pushUndo(edit: Edit) {
        undo.addLast(edit)
        undoBytes += edit.bytes
    }

    /** Forget the sitting. Called when the sketchbook closes, and nowhere else. */
    fun clear() {
        undo.clear()
        redo.clear()
        undoBytes = 0L
    }

    companion object {
        const val MAX = 100

        /**
         * How many bytes of before-image the undo side may hold. Forty-eight megabytes, Greg's
         * answer at R3's phase start.
         *
         * Two and a half page-wide erases, or hundreds of ordinary marks, on a panel whose page is
         * about eighteen megabytes. Large enough that nothing a hand does in a sitting reaches it
         * by drawing; small enough that the history cannot be the reason a device that kills
         * processes for memory kills this one.
         *
         * It is a constructor parameter with this as its default for one reason only: the eviction
         * rule is arithmetic and deserves to be proved on a laptop, and proving it at the real
         * number would mean allocating forty-eight megabytes of pixels per assertion. Nothing in
         * the app ever passes anything else.
         */
        const val BUDGET_BYTES = 48L shl 20
    }
}
