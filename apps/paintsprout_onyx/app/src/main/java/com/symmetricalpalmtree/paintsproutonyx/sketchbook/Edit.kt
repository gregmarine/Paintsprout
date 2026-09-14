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
 * Nothing here holds geometry **except on a raster page**. In a stroke book a mark is an id,
 * because the row is still in the file with its points and its stacking position intact — an erase
 * is a stamp, never a deletion, so undo is a matter of un-stamping the row rather than rebuilding
 * something that resembles what was there. That is what keeps a bounded hundred entries cheap.
 *
 * A raster page has no rows. The graphite went into one page image at pen-up and the rubber took
 * pixels off it, and there is nothing in the file to un-stamp: the only record of what was there a
 * moment ago is the pixels themselves. So [RasterChanged] carries them, and [bytes] is what lets the
 * stack bound a history that is no longer free to hold. See [RasterTiles] for why those pixels are
 * kept on a grid of cells rather than as the rectangles the engine reported.
 */
sealed class Edit {

    /** The page the edit happened on, and the page undo turns back to unless it says otherwise. */
    abstract val pageId: String

    /**
     * What keeping this entry costs, for the stack's byte budget. Zero for everything that is ids
     * and nothing else — which is every variant but [RasterChanged], and which is why a stroke
     * book's history is bounded by count alone exactly as it always was.
     */
    open val bytes: Long get() = 0L

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

    /**
     * The page image as it was, over the patch of page one contact changed — a mark composited at
     * pen-up, or a whole eraser sweep from the moment the rubber touched down to the moment it
     * lifted.
     *
     * **One contact is one entry, because it was one movement of the hand.** The engine reports an
     * erase once per batch and there are dozens of batches in a second of scrubbing; an entry each
     * would make taking back a rub a matter of tapping the arrow until it stopped.
     *
     * **It is its own inverse.** The tiles go onto the page and come back holding what the page was
     * holding (`swapPageRaster`), so the entry that undid a change is the entry that redoes it, with
     * no second copy of the pixels and no second shape of call. That is the whole reason this is a
     * swap in the engine rather than a load: a page-wide erase's before-image is the page, and a
     * second one of those is eighteen megabytes the device does not have to spare.
     *
     * [tiles] are disjoint — see [RasterTiles] — so they can be swapped in any order, which is what
     * spares the replayer from having to remember which order they were read in.
     */
    data class RasterChanged(override val pageId: String, val tiles: List<RasterTile>) : Edit() {
        override val bytes: Long get() = tiles.sumOf { it.bytes }
    }
}
