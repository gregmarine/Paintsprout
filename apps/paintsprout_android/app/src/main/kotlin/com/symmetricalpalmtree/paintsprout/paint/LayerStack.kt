package com.symmetricalpalmtree.paintsprout.paint

/**
 * One thing in a page's stack: a layer to paint on, or a folder to file them in.
 *
 * [parentId] says which folder holds it; the empty string means it sits loose at
 * the top of the stack rather than in anything.
 */
data class StackEntry(
    val id: String,
    val isFolder: Boolean = false,
    val parentId: String = LOOSE,
) {
    companion object {
        /** What a thing's parent is when no folder holds it. */
        const val LOOSE = ""
    }
}

/**
 * The shape of a page's stack — which things are in it, in what order, and inside
 * what — and nothing at all about what is drawn on them.
 *
 * Kept as a flat list in the order the panel reads it: **top-down**, the thing
 * nearest the viewer first, with a folder immediately followed by everything
 * filed inside it. That contiguity is the whole representation. [StackEntry.parentId]
 * says which folder a thing belongs to and its position says where it sits among
 * its siblings; together they are a tree, without a tree's bookkeeping.
 *
 * Two things fall out of holding it this way, and both are why it is held this
 * way. Drawing order is the layers in list order, reversed — a folder passes its
 * contents through rather than compositing them, so no folder changes what is
 * drawn when, and the reversal needs no walk. And a drag is a splice: a run of
 * adjacent entries lifted out and put back somewhere else, which is exactly what
 * moving a folder *means* when its contents are the rows beneath it.
 *
 * Structure only, held by id, so the whole of it can be tested without a screen.
 */
class LayerStack(entries: List<StackEntry> = emptyList()) {

    private val list = entries.toMutableList()

    /** The stack top-down, a folder immediately followed by what it holds. */
    val entries: List<StackEntry> get() = list

    val size: Int get() = list.size

    fun indexOf(id: String): Int = list.indexOfFirst { it.id == id }

    fun entry(id: String): StackEntry? = list.firstOrNull { it.id == id }

    fun contains(id: String): Boolean = indexOf(id) >= 0

    /**
     * How deep in folders the entry at [index] sits: 0 when nothing holds it.
     *
     * Walked up the parent chain rather than read off the row, so there is one
     * account of where a thing is and not two to disagree. Bounded by the list's
     * own length, because a parent cycle — from a file we did not write, or from
     * a bug in here — would otherwise turn a repaint into a hang.
     */
    fun depth(index: Int): Int = list.getOrNull(index)?.let { chainFrom(it.parentId).size } ?: 0

    /** The folders holding [id], nearest first. Empty when nothing holds it. */
    fun ancestors(id: String): List<String> =
        entry(id)?.let { chainFrom(it.parentId) } ?: emptyList()

    private fun chainFrom(first: String): List<String> {
        val out = mutableListOf<String>()
        var parent = first
        var hops = 0
        while (parent != StackEntry.LOOSE && hops <= list.size) {
            out += parent
            hops++
            parent = list.firstOrNull { it.id == parent }?.parentId ?: break
        }
        return out
    }

    /** True when [id] is inside [folder], at any depth. */
    fun isInside(id: String, folder: String): Boolean = folder in ancestors(id)

    /**
     * The entry at [index] together with everything filed inside it.
     *
     * A folder's contents are exactly the run of entries after it that sit deeper
     * than it does, which holds because the list keeps them adjacent — so a
     * folder and its contents can be lifted, dropped or deleted as one block
     * without ever assembling the tree.
     */
    fun span(index: Int): IntRange {
        if (index !in list.indices) return IntRange.EMPTY
        if (!list[index].isFolder) return index..index
        val floor = depth(index)
        var last = index
        var j = index + 1
        while (j < list.size && depth(j) > floor) {
            last = j
            j++
        }
        return index..last
    }

    /**
     * How many folders deep the deepest *folder* inside [id] sits below it, or
     * -1 when the block holds no folders at all.
     *
     * Only folders are counted because only folders are what the nesting cap is
     * about: the chain a walk has to climb is made of folders, and a layer at the
     * bottom of the deepest allowed one adds nothing to it. Counting layers would
     * mean the deepest folder you are allowed to make is one you cannot put
     * anything in.
     */
    fun folderHeight(id: String): Int {
        val index = indexOf(id)
        if (index < 0) return -1
        val floor = depth(index)
        return span(index).filter { list[it].isFolder }.maxOfOrNull { depth(it) - floor } ?: -1
    }

    /** The layers top-down, as the panel reads them. */
    fun layerIds(): List<String> = list.filter { !it.isFolder }.map { it.id }

    /**
     * The layers bottom-first, as they are painted.
     *
     * A plain reversal, and it is allowed to be one: a folder passes its contents
     * through rather than compositing them, so nesting never reorders the paint.
     */
    fun drawOrder(): List<String> = layerIds().asReversed()

    fun folderIds(): List<String> = list.filter { it.isFolder }.map { it.id }

    fun layerCount(): Int = list.count { !it.isFolder }

    /** What is directly inside [folder], top-down — not what is inside those. */
    fun childrenOf(folder: String): List<StackEntry> = list.filter { it.parentId == folder }

    fun isEmptyFolder(id: String): Boolean =
        entry(id)?.isFolder == true && span(indexOf(id)).let { it.first == it.last }

    // --- Changing the shape --------------------------------------------------

    /** Takes the whole shape from somewhere else — a page arriving off the disk. */
    fun replaceWith(entries: List<StackEntry>) {
        list.clear()
        list.addAll(entries)
    }

    /** Puts [entry] at [at], clamped into the list. Its own parentId is honoured. */
    fun insert(entry: StackEntry, at: Int) {
        list.add(at.coerceIn(0, list.size), entry)
    }

    /** Puts a lifted block back, in the order it was lifted. */
    fun insertAll(block: List<StackEntry>, at: Int) {
        list.addAll(at.coerceIn(0, list.size), block)
    }

    /**
     * Lifts [id] and everything inside it out of the stack, and hands it back.
     *
     * The block returned is a whole stack in miniature — the entry first, its
     * contents after — so putting it back is [insertAll] and nothing more.
     */
    fun remove(id: String): List<StackEntry> {
        val index = indexOf(id)
        if (index < 0) return emptyList()
        val span = span(index)
        val block = list.subList(span.first, span.last + 1).toList()
        repeat(block.size) { list.removeAt(span.first) }
        return block
    }

    /**
     * Moves [id] to sit at [to] in the list, held by [into].
     *
     * Refused when [into] is the thing being moved or something inside it: a
     * folder dropped into its own contents is a folder that contains itself, and
     * every walk over the stack would then run to its cycle guard rather than to
     * an answer. Refused too when the landing would file things deeper than
     * [MAX_NESTING].
     *
     * [to] is read against the list *before* the lift, which is how the caller
     * saw it, and corrected here for the hole the lift leaves behind.
     */
    fun move(id: String, to: Int, into: String = StackEntry.LOOSE): Boolean {
        val from = indexOf(id)
        if (from < 0) return false
        if (into != StackEntry.LOOSE && (into == id || isInside(into, id) || !contains(into))) return false
        val span = span(from)
        // Landing inside its own block is not a place; landing at either end of
        // it is where it already is, unless the folder holding it is changing.
        if (to in (span.first + 1)..span.last) return false
        if (to in span.first..(span.last + 1) && into == list[from].parentId) return false
        val height = folderHeight(id)
        if (height >= 0) {
            val landing = if (into == StackEntry.LOOSE) 0 else depth(indexOf(into)) + 1
            if (landing + height > MAX_NESTING - 1) return false
        }

        val block = remove(id)
        if (block.isEmpty()) return false
        val shift = if (to > span.last) block.size else 0
        val rehomed = listOf(block.first().copy(parentId = into)) + block.drop(1)
        insertAll(rehomed, (to - shift).coerceIn(0, list.size))
        return true
    }

    // --- Places, as the timeline records them --------------------------------

    /**
     * Where [id] sits: which folder holds it, and how far up from the bottom of
     * what that folder holds.
     *
     * The form every structural step is written in. A position counted among
     * siblings survives things happening elsewhere in the stack in a way a
     * position counted across the whole of it does not — and on a page with no
     * folders it is the same number the stack was recorded with before folders
     * existed, which is why none of those steps needed rewriting.
     */
    fun spotOf(id: String): Pair<String, Int>? {
        val entry = entry(id) ?: return null
        val siblings = childrenOf(entry.parentId)
        val fromTop = siblings.indexOfFirst { it.id == id }
        if (fromTop < 0) return null
        return entry.parentId to (siblings.size - 1 - fromTop)
    }

    /**
     * The place in the list a thing goes to end up [at] steps up from the bottom
     * of [folder]'s contents.
     *
     * Landing *below* an existing sibling means landing below everything that
     * sibling holds too, which is the only reason this is not arithmetic.
     */
    fun flatIndexFor(folder: String, at: Int): Int {
        val siblings = childrenOf(folder)
        val fromTop = (siblings.size - at).coerceIn(0, siblings.size)
        if (fromTop < siblings.size) return indexOf(siblings[fromTop].id)
        // Below the last sibling — or, with no siblings at all, just inside the
        // folder's own row, and at the very bottom of the stack when nothing
        // holds it.
        val last = siblings.lastOrNull() ?: return if (folder == StackEntry.LOOSE) {
            list.size
        } else {
            indexOf(folder) + 1
        }
        return span(indexOf(last.id)).last + 1
    }

    /** Moves [id] to sit [at] steps up from the bottom of [folder]'s contents. */
    fun placeAt(id: String, folder: String, at: Int): Boolean =
        move(id, flatIndexFor(folder, at), folder)

    /** Puts a new [entry] [at] steps up from the bottom of [folder]'s contents. */
    fun insertAt(entry: StackEntry, folder: String, at: Int) {
        insert(entry.copy(parentId = folder), flatIndexFor(folder, at))
    }

    /**
     * The rows a panel should draw, as indices, top-down.
     *
     * A shut folder still shows; what it holds does not.
     */
    fun visibleRows(collapsed: Set<String>): List<Int> {
        val out = mutableListOf<Int>()
        var skipUntil = -1
        for (i in list.indices) {
            if (i <= skipUntil) continue
            out += i
            if (list[i].isFolder && list[i].id in collapsed) skipUntil = span(i).last
        }
        return out
    }

    /**
     * Which folder catches a drop made in the gap between two panel rows.
     *
     * [above] and [below] are the entries the gap sits between, either absent at
     * the ends of the list. A gap just under a folder's title belongs to that
     * folder — there is no other reading of that gesture. Otherwise it belongs to
     * whatever is below it, and at the very bottom, where nothing is, to whatever
     * holds the thing above.
     */
    fun dropInto(above: Int?, below: Int?): String {
        val over = above?.let { list.getOrNull(it) }
        if (over != null && over.isFolder) return over.id
        val under = below?.let { list.getOrNull(it) }
        if (under != null) return under.parentId
        return over?.parentId ?: StackEntry.LOOSE
    }

    companion object {
        /**
         * How many folders may be stacked inside one another.
         *
         * Not a limit anyone should meet while working. It is a floor under the
         * cap that bounds every walk over a document's rows (`Subtrees.MAX_DEPTH`):
         * a page's rows already run sketchbook → page → layer → op → attachment,
         * and the folders in between have what is left. Without it a stack could
         * be built here that cannot be read back off the disk.
         */
        const val MAX_NESTING = 6
    }
}
