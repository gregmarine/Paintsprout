package com.symmetricalpalmtree.paintsproutonyx.sketchbook

/**
 * One thing the artist did that can be taken back, written down at the moment it happened.
 *
 * g-paper keeps no history of its own — that is stated in its host responsibilities and it is the
 * right call, because the component has no idea what a page is or what a file is. So the record is
 * ours, and this is the vocabulary of it: four things a hand can do to a sketchbook, each carrying
 * only what taking it back needs.
 *
 * **Every edit knows which page it happened on, and that is the whole design.** The history is
 * screen-level, not page-level: one stack for the sketchbook, not one per leaf. An artist who draws
 * on page three, turns to page four and taps undo means "take back the last thing I did", and the
 * last thing they did was on page three — so undoing it has to turn back to page three and show it.
 * A per-page stack would silently do nothing on page four, which reads as a broken button rather
 * than as a rule nobody explained.
 *
 * Nothing here holds geometry. A mark is an id, because the row is still in the file with its
 * points and its stacking position intact — an erase is a stamp, never a deletion, so undo is a
 * matter of un-stamping the row rather than rebuilding something that resembles what was there.
 * That is what keeps a bounded hundred entries cheap enough to keep.
 */
sealed class Edit {

    /** The page the edit happened on, and the page undo turns back to unless it says otherwise. */
    abstract val pageId: String

    /** A mark the pen finished. */
    data class Drew(override val pageId: String, val markId: String) : Edit()

    /** Marks the eraser swept, in one sweep. One sweep is one undo — it was one movement of the hand. */
    data class Erased(override val pageId: String, val markIds: List<String>) : Edit()

    /**
     * A page appended past the end by a swipe.
     *
     * [shownAfterUndo] is the page to land on when it is taken away again — the last living page
     * before it, captured at the moment of the swipe rather than worked out later. Recomputing it
     * on the way back would be asking a different question of a book that has changed since.
     *
     * [hiddenMarkIds] is empty when the edit is recorded and filled in by the undo: whatever marks
     * were still alive on the leaf when it was taken away go down with it, and the redo has to bring
     * exactly those back. Ordinarily there are none — anything drawn on the leaf sits above this
     * entry on the stack and was undone first — but a history that overflowed past those marks
     * reaches here with them still on the page, and a redo that put the leaf back blank would have
     * turned an undo into a delete.
     */
    data class AddedPage(
        override val pageId: String,
        val shownAfterUndo: String,
        val hiddenMarkIds: List<String> = emptyList(),
    ) : Edit()

    /**
     * A page thrown away.
     *
     * [markIds] are the marks that went with it, read once so undo restores exactly that set —
     * counting them again afterwards would find whatever the page holds *now*, which after a redo
     * or a second delete is not the same list. [replacementPageId] is non-null only when the page
     * deleted was the last living one and a fresh blank leaf was made in its place, because a
     * sketchbook with no pages in it is not a sketchbook; undoing the delete has to take that
     * stand-in away again. [shownAfterDelete] is where the screen landed afterwards, which is where
     * redoing the delete has to land it again. [replacementMarkIds] is what the undo found drawn on
     * that stand-in when it took it away, so the redo can put the stand-in back as it was — the same
     * overflow argument as [AddedPage.hiddenMarkIds].
     */
    data class DeletedPage(
        override val pageId: String,
        val markIds: List<String>,
        val replacementPageId: String?,
        val shownAfterDelete: String,
        val replacementMarkIds: List<String> = emptyList(),
    ) : Edit()
}
