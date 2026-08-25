package com.symmetricalpalmtree.paintsprout.data.soil

import java.util.UUID

/**
 * Walking and copying object subtrees.
 *
 * A composite — a page with its layers and their ops, a group with its
 * contents — is a parent row plus child rows, never a nested serialized
 * document. Everything here follows from that one decision, and so does the trap
 * below.
 */
object Subtrees {

    /**
     * How deep a walk will go before it gives up.
     *
     * Today's hierarchy is four levels (sketchbook → page → layer → op → attachment),
     * and the bound is a convention in the readers rather than a schema constraint —
     * so it needs an explicit cap here, where nested groups will eventually push
     * against it.
     */
    const val MAX_DEPTH = 12

    /**
     * Everything under [rootId], breadth-first, excluding the root itself.
     *
     * Batched by level: one lookup per depth, not one per row. [childrenOf] takes
     * a *set* of parents precisely so a page's twelve layers cost one query.
     *
     * Bounded twice — by depth and by a seen-set — because a parent cycle in a
     * file we did not write turns a walk into a hang on the page-load path.
     */
    fun collect(
        rootId: String,
        childrenOf: (Set<String>) -> List<SoilObject>,
        maxDepth: Int = MAX_DEPTH,
    ): List<SoilObject> {
        val out = mutableListOf<SoilObject>()
        val seen = hashSetOf(rootId)
        var frontier = setOf(rootId)
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            val next = childrenOf(frontier).filter { seen.add(it.id) }
            out += next
            frontier = next.mapTo(hashSetOf()) { it.id }
            depth++
        }
        return out
    }

    /**
     * Fresh ids for an entire subtree, with every reference inside it rewired.
     *
     * > ⚠️ **Call this from every path that writes a subtree — insert *and*
     * > replace — and have both call this same function.**
     * >
     * > Child row ids are the primary key, and a copied or cut subtree carries its
     * > *source's* live child ids. Paste onto the same page, paste the same
     * > clipboard twice, or send the same selection across surfaces twice, and the
     * > second insert is a hard `UNIQUE` failure — a crash, in the middle of the
     * > user's work. Notesprout shipped this bug twice, once on each side, which is
     * > the actual lesson: it is not that the rule is hard, it is that having two
     * > copies of the rule means fixing it once isn't enough.
     *
     * Remapping is always safe because a composite's child ids are private to its
     * subtree and never referenced from outside it. References that point *out* of
     * the subtree are left exactly as they are — only ids we are actually
     * reassigning get rewritten.
     *
     * [rows] must be the whole subtree. The root's own [SoilObject.parentId] is
     * untouched; the caller decides where the copy lands.
     */
    fun remapIds(
        rows: List<SoilObject>,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<SoilObject> {
        if (rows.isEmpty()) return rows
        val fresh = rows.associate { it.id to newId() }
        return rows.map { row ->
            row.copy(
                id = fresh.getValue(row.id),
                // Only rewrite a parent that is itself part of the copy. The root's
                // parent lives outside it and belongs to the caller.
                parentId = fresh[row.parentId] ?: row.parentId,
                // An intra-subtree reference — a sketchbook's lastOpenedPage, a
                // mask's target — has to follow its subject. One pointing outside
                // still points there.
                refId = row.refId?.let { fresh[it] ?: it },
            )
        }
    }

    /** [remapIds] plus a new home for the root. The paste operation, in one call. */
    fun copyInto(
        rows: List<SoilObject>,
        rootId: String,
        newParentId: String,
        order: Int? = null,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<SoilObject> {
        val remapped = remapIds(rows, newId)
        val rootIndex = rows.indexOfFirst { it.id == rootId }
        require(rootIndex >= 0) { "The subtree does not contain its own root" }
        return remapped.mapIndexed { i, row ->
            if (i == rootIndex) row.copy(parentId = newParentId, order = order ?: row.order) else row
        }
    }

    /** Soft-deletes a whole subtree in one stamp. */
    fun softDelete(rows: List<SoilObject>, at: Long): List<SoilObject> =
        rows.map { if (it.isAlive) it.copy(deletedAt = at) else it }

    /**
     * Where a new sibling goes: after the last existing one, **tombstones
     * included**.
     *
     * Skipping the dead is the tempting version and it is wrong. Delete page 2 of
     * three, add a page, then undo the delete: the resurrected page and the new one
     * both claim the same position, and which one comes first is whatever the
     * query planner felt like. Counting them costs a gap in the sequence, and a
     * gap is free — `order` is a sort key among siblings, not an index into
     * anything.
     *
     * Ops under a layer are the exception, and they do not use this: an op appends
     * at the layer's `undoDepth`, because there `order` genuinely *is* the index
     * the undo frontier is compared against, and it has to stay dense. That works
     * because a truncated redo tail is **hard**-deleted — an op is never a
     * tombstone.
     */
    fun nextOrder(siblings: List<SoilObject>): Int =
        (siblings.maxOfOrNull { it.order } ?: -1) + 1

    /** Compacts sibling order to 0..n-1, preserving the current sequence. */
    fun renumber(siblings: List<SoilObject>): List<SoilObject> =
        siblings.sortedBy { it.order }.mapIndexed { i, row ->
            if (row.order == i) row else row.copy(order = i)
        }
}
