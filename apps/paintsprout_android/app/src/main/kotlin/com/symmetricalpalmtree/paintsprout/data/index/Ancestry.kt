package com.symmetricalpalmtree.paintsprout.data.index

/**
 * Walks a row's folder ancestry, root first.
 *
 * A pure function over a lookup, both because it is then testable without a
 * database and because the cycle guard is the only interesting thing here and
 * deserves to be looked at directly.
 *
 * A cycle in a user's folder tree should be impossible. An unguarded walk turns
 * "impossible" into a hang, on the library screen, with no way out — so the walk
 * is bounded twice: by a set of ids already seen, and by a hop cap. Whichever
 * trips first, the walk returns what it has rather than throwing; a breadcrumb
 * that is missing its top is better than a screen that never draws.
 */
object Ancestry {

    /** Deep enough for any real library; shallow enough that a loop cannot hide. */
    const val MAX_HOPS = 50

    /**
     * The folders containing [id], **ordered root → immediate parent**. Does not
     * include the row itself. The same order `folderPath` uses in the portable
     * `sketchbook_meta` record, so an importer can walk it forwards and recreate
     * missing folders as it goes.
     */
    fun pathTo(id: String, lookup: (String) -> IndexObject?): List<IndexObject> {
        val start = lookup(id) ?: return emptyList()
        val path = ArrayList<IndexObject>()
        val seen = HashSet<String>().apply { add(start.id) }

        var parentId = start.parentId
        var hops = 0
        while (parentId != null && hops < MAX_HOPS) {
            if (!seen.add(parentId)) break // a cycle; stop where it closes
            val parent = lookup(parentId) ?: break
            path += parent
            parentId = parent.parentId
            hops++
        }
        return path.asReversed()
    }

    /** `Sketches / Studies / …`, for a breadcrumb. */
    fun breadcrumb(id: String, lookup: (String) -> IndexObject?, separator: String = " / "): String =
        pathTo(id, lookup).joinToString(separator) { it.name }

    /**
     * Whether [candidateParent] sits inside [id]'s own subtree — the check a move
     * has to pass, since dragging a folder into its own descendant is what creates
     * the cycle the walk above has to survive.
     */
    fun wouldCycle(id: String, candidateParent: String?, lookup: (String) -> IndexObject?): Boolean {
        if (candidateParent == null) return false
        if (candidateParent == id) return true
        var cursor: String? = candidateParent
        val seen = HashSet<String>()
        var hops = 0
        while (cursor != null && hops < MAX_HOPS) {
            if (cursor == id) return true
            if (!seen.add(cursor)) return true // already looped: refuse the move
            cursor = lookup(cursor)?.parentId
            hops++
        }
        return false
    }
}
