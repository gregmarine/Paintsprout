package com.symmetricalpalmtree.paintsproutonyx.library

/**
 * How many cards fit on one page of the shelf, and how big each one is.
 *
 * Kept apart from the view, and pure, because it is the one piece of the library that is a decision
 * about this panel rather than a piece of Android. A shelf that silently fits five cards where six
 * were meant to go is not a crash and not a visible fault — it is a library that paginates a beat
 * early forever, and the only way to catch that is to be able to ask the question without a device
 * in the room.
 *
 * The shape of the answer, measured on the NA5C (1860 × 2480 px at density 1.875 — 992 dp across):
 *
 *  - **Three columns.** Not two, which wastes half the width of a ten-inch sheet, and not four, which
 *    buys twelve cards at the cost of covers too small to recognise a drawing in. A sketchbook is
 *    found by looking at it.
 *  - **Cards keep their proportions.** [CARD_ASPECT] is a page (3:4) with a two-line label under it.
 *    Rows are counted at that proportion and the cards are then left at it rather than stretched to
 *    swallow the leftover height, because a card that grows to fill a gap is a card whose cover no
 *    longer has a page's shape — and from G5 on that cover is a photograph of a real page.
 *  - **The leftover height stays at the foot**, where the caller puts it. A shelf fills from the top
 *    down, so the first card sits in the top-left corner of the grid area on every page, however few
 *    cards that page holds. Sharing the slack out top and bottom instead makes the first row sit
 *    lower on a half-full page than on a full one, and the shelf appears to move as it is paged
 *    through.
 *
 * [columns] falls back to two below [WIDE_DP]. Nothing this app installs to is that narrow — the NA5C
 * is the only device — but the value is here rather than hard-coded at three so that a desk-testing
 * run in a small window shows a usable grid instead of one card cropped in half.
 */
data class GridGeometry(
    val columns: Int,
    val rows: Int,
    val cardWidthPx: Int,
    val cardHeightPx: Int,
    val gapPx: Int,
) {
    val cardsPerPage: Int get() = columns * rows

    companion object {
        /** A page (3:4) with room for a name and a line of detail beneath it. */
        const val CARD_ASPECT = 1.4f

        /** Below this the panel is not a sheet of paper and three columns stop making sense. */
        const val WIDE_DP = 720f

        /**
         * [cardWidthPx] and [cardHeightPx] are the **cell** a card occupies, gutter included — the
         * card view itself is laid out one gutter smaller inside it, with half a gutter of margin on
         * every side. Counting it that way is what makes the sums here honest: three cells and their
         * gutters add up to the container rather than overflowing it by two gutters, and on a grid
         * that does not scroll, overflowing by two gutters means the third column is simply not
         * there and nobody can tell why.
         */
        fun measure(containerWidthPx: Int, containerHeightPx: Int, density: Float, gapPx: Int): GridGeometry {
            val columns = if (containerWidthPx / density >= WIDE_DP) 3 else 2
            val cardWidth = (containerWidthPx / columns).coerceAtLeast(1)
            val cardHeight = (cardWidth * CARD_ASPECT).toInt().coerceAtLeast(1)
            // At least one row even on a container too short for a whole card: a page showing one
            // clipped card is still a library that can be navigated, and zero rows is a division by
            // zero in every pagination sum downstream.
            val rows = (containerHeightPx / cardHeight).coerceAtLeast(1)
            return GridGeometry(columns, rows, cardWidth, cardHeight, gapPx)
        }

        /**
         * How many pages [itemCount] items take at [perPage]. Always at least one: an empty shelf is
         * page 1 of 1, not page 1 of 0, and the pager reads "1 / 1" rather than dividing by nothing.
         */
        fun pageCount(itemCount: Int, perPage: Int): Int =
            if (itemCount <= 0 || perPage <= 0) 1 else (itemCount - 1) / perPage + 1
    }
}
