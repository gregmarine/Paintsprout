package com.symmetricalpalmtree.paintsproutonyx.sketchbook

/**
 * Where the screen lands when the pages move under it.
 *
 * Two questions, both about a list of page ids in page order, and both answered here rather than in
 * the screen because they are the sort of thing that is wrong by one and stays wrong: an
 * off-by-one in "which page now" is not a crash and not visibly a fault — it is a book that lands
 * on the wrong leaf after a delete, once, and looks like the artist mis-swiped.
 */
object PageMath {

    /**
     * The page to show once [removed] is gone: **the next one, else the previous one, else nothing.**
     *
     * Forward first because that is what a hand expects. Tearing a leaf out of a real book leaves
     * you looking at what was behind it, and what was behind it is the next page — the drawing
     * continues in the direction it was going. Falling back to the previous page is for the last
     * leaf, where there is no forward left; and null is the empty book, which the screen answers by
     * putting a fresh blank page in rather than showing nothing at all.
     *
     * A page that is not in the list is not a page that left, so there is no answer to give and the
     * caller is asking about a book it has misread.
     */
    fun neighbourAfterRemoving(pageIds: List<String>, removed: String): String? {
        val at = pageIds.indexOf(removed)
        if (at < 0) return null
        if (at + 1 <= pageIds.lastIndex) return pageIds[at + 1]
        if (at - 1 >= 0) return pageIds[at - 1]
        return null
    }

    /**
     * Which leaf this is, counted the way a person counts pages: the first one is page one.
     *
     * Zero for a page that is not in the book, which is the honest answer for a page that has just
     * been thrown away or was never in this file — and reads, in "0 / 7", as obviously wrong rather
     * than as a plausible page number nobody will question.
     */
    fun positionOf(pageIds: List<String>, id: String): Int = pageIds.indexOf(id) + 1
}
