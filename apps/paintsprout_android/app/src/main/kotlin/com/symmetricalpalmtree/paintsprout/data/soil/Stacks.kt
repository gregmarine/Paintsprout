package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.paint.LayerStack
import com.symmetricalpalmtree.paintsprout.paint.StackEntry

/**
 * Turning a page's layer and folder rows into a stack, and a stack back into rows.
 *
 * The two ends disagree about direction, deliberately, and this is where that is
 * reconciled. On disk `order` counts **up from the bottom**, the way it has since
 * before folders existed and the way every other sequence in the format does. In
 * the panel a stack reads **top-down**, nearest the viewer first, because that is
 * how anyone looks at one. Neither is worth changing to match the other, so the
 * flip lives here and nowhere else.
 *
 * Rows are read as a tree by `parentId` — a folder's children name it, the ones
 * at the top of the stack name the page — and the walk is bounded by the same
 * nesting cap the editor enforces, so a file we did not write cannot spend a page
 * load climbing a cycle.
 */
object Stacks {

    /**
     * The page's stack, top-down, a folder immediately followed by what it holds.
     *
     * [rows] may arrive in any order and may contain anything; only layers and
     * folders reachable from [pageId] come out. A row whose parent is missing —
     * a folder deleted while something was still inside it, in a file that let
     * that happen — is simply not reached, which is the same answer the panel
     * would give and a better one than a crash.
     */
    fun topDown(pageId: String, rows: List<SoilObject>): List<SoilObject> {
        val stackRows = rows.filter { it.type == SoilType.LAYER || it.type == SoilType.GROUP }
        val byParent = stackRows.groupBy { it.parentId }
        val out = mutableListOf<SoilObject>()
        val seen = hashSetOf(pageId)

        fun walk(parent: String, depth: Int) {
            if (depth > LayerStack.MAX_NESTING) return
            // Reversed on the way out: stored bottom-first, read top-down.
            for (row in byParent[parent].orEmpty().sortedBy { it.order }.asReversed()) {
                if (!seen.add(row.id)) continue
                out += row
                if (row.type == SoilType.GROUP) walk(row.id, depth + 1)
            }
        }
        walk(pageId, 0)
        return out
    }

    /** The stack's shape alone, with the page itself standing in for "loose". */
    fun stackOf(pageId: String, rows: List<SoilObject>): LayerStack = LayerStack(
        topDown(pageId, rows).map {
            StackEntry(
                id = it.id,
                isFolder = it.type == SoilType.GROUP,
                parentId = if (it.parentId == pageId) StackEntry.LOOSE else it.parentId,
            )
        },
    )

    /**
     * Where every entry belongs on disk, given the stack as it now reads.
     *
     * Numbered per folder rather than across the page, because `order` has only
     * ever meant "among your siblings" and a folder's contents are siblings of
     * each other and of nothing else. Numbered from 0 upward each time rather
     * than nudged, for the reason pages are: a sequence that is rewritten whole
     * cannot end up with two rows claiming the same place.
     */
    fun placements(pageId: String, stack: LayerStack): Map<String, Placement> {
        val out = LinkedHashMap<String, Placement>()
        val byParent = stack.entries.groupBy { it.parentId }
        for ((parent, siblings) in byParent) {
            val home = if (parent == StackEntry.LOOSE) pageId else parent
            // The panel handed them down; disk counts up.
            siblings.asReversed().forEachIndexed { i, entry ->
                out[entry.id] = Placement(parentId = home, order = i)
            }
        }
        return out
    }

    /** Where one row sits: inside what, and how far up from the bottom. */
    data class Placement(val parentId: String, val order: Int)
}
