package com.symmetricalpalmtree.paintsproutonyx.library

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectSummary

/** One thing on the shelf. Folders come first wherever both appear — see [Sorting]. */
sealed class CardItem(val summary: ObjectSummary) {
    class Folder(s: ObjectSummary) : CardItem(s)
    /** [meta] is the card's second line: "6 pages · 25 Aug 2026", already formatted by the caller. */
    class Sketchbook(s: ObjectSummary, val meta: String) : CardItem(s)
}

/**
 * The shelf itself: one page of cards, laid out and handed back.
 *
 * **It does not scroll, and that is the design.** Every scroll on an EPD panel is a full-screen
 * repaint chasing a finger, and what the artist gets is a grey blur that settles somewhere they did
 * not aim for. A page of cards that simply replaces the last page costs one refresh and lands
 * exactly where it was going. The price is that the grid has to know how many cards fit before it
 * draws any of them, which is what [GridGeometry] is for.
 *
 * The grid is torn down and rebuilt on every bind rather than recycled. Six views is not a number
 * worth a recycler, and the alternative — views held across a listing that has changed underneath
 * them — is how a card ends up showing the name of a sketchbook that was deleted two taps ago.
 * [container]'s other children (the empty-state label) are left alone, so only the grid this class
 * added last is removed.
 */
class LibraryGrid(
    private val container: ViewGroup,
    private val onTap: (CardItem) -> Unit,
    private val onLongPress: (CardItem) -> Unit,
) {
    var geometry: GridGeometry = GridGeometry(1, 1, 1, 1, 0)
        private set

    val cardsPerPage: Int get() = geometry.cardsPerPage

    private var currentGrid: View? = null

    /**
     * Work out the page's shape from the container's **usable** area.
     *
     * The padding is subtracted here, inside this class, rather than left to each caller. A view's
     * `width` is its whole width, padding included, and the shelf's grid area carries a screen margin
     * either side — so measuring against `width` alone hands the third column ninety pixels that do
     * not exist and it is drawn half off the edge of the panel. It looks like a card that is simply
     * too big, which is the wrong thing to go and fix. Two screens ask this question; asking it in one
     * place means it can only be got wrong once.
     */
    fun measure(density: Float, gapPx: Int) {
        val usableWidth = container.width - container.paddingLeft - container.paddingRight
        val usableHeight = container.height - container.paddingTop - container.paddingBottom
        geometry = GridGeometry.measure(usableWidth, usableHeight, density, gapPx)
    }

    /** Render the [pageIndex] slice of [items]. An empty or out-of-range page leaves the shelf bare. */
    fun bind(items: List<CardItem>, pageIndex: Int) {
        currentGrid?.let { container.removeView(it) }
        currentGrid = null

        val perPage = geometry.cardsPerPage
        val start = pageIndex * perPage
        if (items.isEmpty() || start >= items.size) return
        val end = minOf(start + perPage, items.size)

        val context = container.context
        val inflater = LayoutInflater.from(context)
        val grid = GridLayout(context).apply {
            columnCount = geometry.columns
            // Full width, anchored to the top, and the leftover height left at the foot.
            //
            // Centring the block was tried first and reads wrong on the panel. A shelf is filled from
            // the top down — the first card belongs at the top-left corner of the grid area and stays
            // there whether the page holds one card or six. Split the slack top and bottom instead
            // and the first row sinks by a different amount on every page, so the shelf appears to
            // shift under the artist as they page through it, and a half-full last page floats in the
            // middle of the screen with nothing holding it up. Empty space at the bottom is just room
            // left on the shelf.
            //
            // The width is deliberately not wrapped to fit, for the same reason turned sideways: a
            // wrapped grid centres what is inside it, so a folder holding one sketchbook would put
            // that card in the middle of the screen and a folder holding four would put the first one
            // somewhere else again. Filling the width pins every card to the column it belongs in.
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
        }

        val half = geometry.gapPx / 2
        for (i in start until end) {
            val item = items[i]
            val view = when (item) {
                is CardItem.Folder -> folderCard(inflater, grid, item)
                is CardItem.Sketchbook -> sketchbookCard(inflater, grid, item)
            }
            view.layoutParams = GridLayout.LayoutParams().apply {
                width = geometry.cardWidthPx - geometry.gapPx
                height = geometry.cardHeightPx - geometry.gapPx
                setMargins(half, half, half, half)
            }
            view.setOnClickListener { onTap(item) }
            view.setOnLongClickListener { onLongPress(item); true }
            grid.addView(view)
        }
        container.addView(grid)
        currentGrid = grid
    }

    private fun folderCard(inflater: LayoutInflater, parent: ViewGroup, item: CardItem.Folder): View =
        inflater.inflate(R.layout.card_folder, parent, false).apply {
            findViewById<TextView>(R.id.folderName).text = item.summary.name
            contentDescription = item.summary.name
        }

    private fun sketchbookCard(inflater: LayoutInflater, parent: ViewGroup, item: CardItem.Sketchbook): View =
        inflater.inflate(R.layout.card_sketchbook, parent, false).apply {
            findViewById<TextView>(R.id.cardName).text = item.summary.name
            findViewById<TextView>(R.id.cardMeta).text = item.meta
            contentDescription = item.summary.name
            // The cover frame stays empty until G5 takes a snapshot of a page. A blank frame is not a
            // placeholder waiting for a picture — it is the true picture of a sketchbook nobody has
            // drawn in yet.
        }
}
