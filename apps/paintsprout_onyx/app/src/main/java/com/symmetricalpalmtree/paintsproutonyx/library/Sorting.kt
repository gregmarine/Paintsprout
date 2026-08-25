package com.symmetricalpalmtree.paintsproutonyx.library

import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectSummary

/** What the shelf is ordered by. Persisted by name, so do not rename these. */
enum class SortField { NAME, MODIFIED }

/** Which end it starts from. Persisted by name, so do not rename these. */
enum class SortOrder { ASC, DESC }

/**
 * The order of the shelf.
 *
 * Two things here are decisions rather than mechanics.
 *
 * **Names sort case-insensitively.** A shelf that files "studies" after "Zoo" is sorted by a rule
 * about bytes, and nobody looking for a sketchbook is thinking in bytes.
 *
 * **The tie-break is always the name, never the id.** Two sketchbooks worked on in the same
 * millisecond — a folder full of them made in one sitting, or restored together — would otherwise
 * come back in whatever order SQLite happened to walk the rows, and *change* order between two
 * refreshes of a screen that has not changed. A shelf that reshuffles while the artist is looking at
 * it is the single most unsettling thing a library can do.
 *
 * Folders and sketchbooks are sorted separately by the caller and folders come first. Interleaving
 * them by name would be defensible on a list; on a grid of cards it means hunting for a folder among
 * things that are not folders.
 */
object Sorting {

    fun sort(items: List<ObjectSummary>, field: SortField, order: SortOrder): List<ObjectSummary> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { it: ObjectSummary -> it.name }
        val primary: Comparator<ObjectSummary> = when (field) {
            SortField.NAME -> byName
            SortField.MODIFIED -> compareBy { it.updatedAt }
        }
        // Only the chosen field turns around. The name tie-break stays A-Z in both directions, so
        // "last worked on" and "least recently worked on" are not two different orderings of the
        // sketchbooks that share a timestamp — they are the same shelf read from either end.
        val directed = if (order == SortOrder.DESC) primary.reversed() else primary
        return items.sortedWith(directed.then(byName))
    }
}
