package com.symmetricalpalmtree.paintsprout.data.soil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtreesTest {

    private val rows = mutableListOf<SoilObject>()
    private var minted = 0

    private fun row(
        id: String,
        parentId: String,
        type: String = SoilType.STROKE,
        order: Int = 0,
        refId: String? = null,
        deletedAt: Long? = null,
    ) = SoilObject(
        id = id, parentId = parentId, type = type, order = order,
        refId = refId, deletedAt = deletedAt, createdAt = 1, updatedAt = 1,
    ).also { rows += it }

    private val childrenOf: (Set<String>) -> List<SoilObject> = { parents ->
        rows.filter { it.parentId in parents }
    }

    /** Deterministic ids, so a remap can be asserted rather than sampled. */
    private val newId: () -> String = { "new-${minted++}" }

    // --- Collecting ---------------------------------------------------------

    @Test
    fun `collect walks the whole subtree and leaves the root out`() {
        row("book", "", SoilType.SKETCHBOOK)
        row("page", "book", SoilType.PAGE)
        row("layer", "page", SoilType.LAYER)
        row("stroke", "layer")
        row("clip", "stroke", SoilType.STROKE_CLIP)

        val found = Subtrees.collect("book", childrenOf).map { it.id }
        assertEquals(listOf("page", "layer", "stroke", "clip"), found)
    }

    @Test
    fun `a leaf has no descendants`() {
        row("lonely", "layer")
        assertTrue(Subtrees.collect("lonely", childrenOf).isEmpty())
    }

    /** Batched by level: a page's twelve layers cost one lookup, not twelve. */
    @Test
    fun `collect asks once per level`() {
        row("page", "book", SoilType.PAGE)
        repeat(12) { row("layer$it", "page", SoilType.LAYER) }
        repeat(12) { row("op$it", "layer$it") }

        var lookups = 0
        val counted: (Set<String>) -> List<SoilObject> = { parents ->
            lookups++
            childrenOf(parents)
        }
        assertEquals(24, Subtrees.collect("page", counted).size)
        assertEquals("one per level, plus the empty probe that ends it", 3, lookups)
    }

    /** A parent cycle in a file we did not write must not become a hang. */
    @Test
    fun `a cycle terminates`() {
        row("a", "b")
        row("b", "a")
        val found = Subtrees.collect("a", childrenOf)
        assertTrue(found.size <= 2)
    }

    @Test
    fun `depth is capped`() {
        repeat(50) { row("n$it", if (it == 0) "root" else "n${it - 1}") }
        assertEquals(5, Subtrees.collect("root", childrenOf, maxDepth = 5).size)
    }

    // --- Remapping ----------------------------------------------------------

    @Test
    fun `every row gets a fresh id`() {
        val subtree = listOf(row("page", "book", SoilType.PAGE), row("layer", "page", SoilType.LAYER))
        val copy = Subtrees.remapIds(subtree, newId)

        assertEquals(listOf("new-0", "new-1"), copy.map { it.id })
        assertTrue(copy.none { it.id in setOf("page", "layer") })
    }

    @Test
    fun `parent links are rewired inside the copy`() {
        val subtree = listOf(
            row("page", "book", SoilType.PAGE),
            row("layer", "page", SoilType.LAYER),
            row("stroke", "layer"),
        )
        val copy = Subtrees.remapIds(subtree, newId)

        assertEquals(copy[0].id, copy[1].parentId)
        assertEquals(copy[1].id, copy[2].parentId)
    }

    /** The root's parent lives outside the copy and belongs to the caller. */
    @Test
    fun `the root keeps its parent until the caller says otherwise`() {
        val copy = Subtrees.remapIds(listOf(row("page", "book", SoilType.PAGE)), newId)
        assertEquals("book", copy.single().parentId)

        val pasted = Subtrees.copyInto(
            listOf(row("page2", "book", SoilType.PAGE)), "page2", "other-book", order = 3, newId = newId,
        )
        assertEquals("other-book", pasted.single().parentId)
        assertEquals(3, pasted.single().order)
    }

    /** A reference to something inside the copy has to follow it. */
    @Test
    fun `intra-subtree references follow the copy`() {
        val subtree = listOf(
            row("book", "", SoilType.SKETCHBOOK, refId = "page"),
            row("page", "book", SoilType.PAGE),
        )
        val copy = Subtrees.remapIds(subtree, newId)
        assertEquals(copy[1].id, copy[0].refId)
    }

    /** A reference to something outside it still points there. */
    @Test
    fun `outward references are left alone`() {
        val subtree = listOf(row("mask", "layer", refId = "some-other-layer"))
        assertEquals("some-other-layer", Subtrees.remapIds(subtree, newId).single().refId)
    }

    /**
     * The bug this whole function exists for: paste the same clipboard twice and
     * the second insert must not collide with the first.
     */
    @Test
    fun `pasting the same subtree twice yields two disjoint sets of ids`() {
        val subtree = listOf(
            row("page", "book", SoilType.PAGE),
            row("layer", "page", SoilType.LAYER),
            row("stroke", "layer"),
        )
        val first = Subtrees.remapIds(subtree, newId)
        val second = Subtrees.remapIds(subtree, newId)

        val a = first.map { it.id }.toSet()
        val b = second.map { it.id }.toSet()
        assertTrue("the copies collide", a.intersect(b).isEmpty())
        assertTrue("and neither reuses the source", (a + b).intersect(setOf("page", "layer", "stroke")).isEmpty())
        // Each copy is internally consistent on its own terms.
        assertEquals(first[0].id, first[1].parentId)
        assertEquals(second[0].id, second[1].parentId)
    }

    /** Insert and replace must both go through this; both get the same answer. */
    @Test
    fun `remapping is what makes a copy safe to write anywhere`() {
        val subtree = listOf(row("page", "book", SoilType.PAGE), row("layer", "page", SoilType.LAYER))
        val intoSameParent = Subtrees.copyInto(subtree, "page", "book", newId = newId)
        val intoAnother = Subtrees.copyInto(subtree, "page", "elsewhere", newId = newId)

        assertNotEquals(intoSameParent[0].id, intoAnother[0].id)
        assertEquals("book", intoSameParent[0].parentId)
        assertEquals("elsewhere", intoAnother[0].parentId)
    }

    @Test
    fun `remapping nothing is nothing`() {
        assertTrue(Subtrees.remapIds(emptyList(), newId).isEmpty())
    }

    @Test
    fun `copyInto refuses a subtree that does not contain its root`() {
        var threw = false
        try {
            Subtrees.copyInto(listOf(row("a", "x")), "not-in-here", "dest", newId = newId)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // --- Order and soft delete ----------------------------------------------

    @Test
    fun `the next order goes after the last live sibling`() {
        val siblings = listOf(row("a", "l", order = 0), row("b", "l", order = 1))
        assertEquals(2, Subtrees.nextOrder(siblings))
        assertEquals(0, Subtrees.nextOrder(emptyList()))
    }

    /**
     * Delete a page, add a page, undo the delete: if the new one had reused the
     * tombstone's position, two pages would claim it and the order between them
     * would be whatever the query planner felt like.
     */
    @Test
    fun `a tombstoned sibling still holds its place`() {
        val siblings = listOf(
            row("a", "l", order = 0),
            row("b", "l", order = 1, deletedAt = 99),
        )
        assertEquals(2, Subtrees.nextOrder(siblings))
    }

    /** The gap that leaves is free: `order` sorts siblings, it does not index them. */
    @Test
    fun `gaps in the sequence are harmless`() {
        val siblings = listOf(row("a", "l", order = 0), row("c", "l", order = 40))
        assertEquals(41, Subtrees.nextOrder(siblings))
        assertEquals(listOf(0, 1), Subtrees.renumber(siblings).map { it.order })
    }

    @Test
    fun `renumber compacts order without reordering`() {
        val siblings = listOf(row("a", "l", order = 5), row("b", "l", order = 2), row("c", "l", order = 9))
        assertEquals(listOf("b" to 0, "a" to 1, "c" to 2), Subtrees.renumber(siblings).map { it.id to it.order })
    }

    @Test
    fun `soft delete stamps the whole subtree and leaves the already-dead alone`() {
        val subtree = listOf(row("a", "l"), row("b", "a", deletedAt = 5))
        val deleted = Subtrees.softDelete(subtree, 100)

        assertEquals(100L, deleted[0].deletedAt)
        assertEquals("an earlier deletion keeps its own timestamp", 5L, deleted[1].deletedAt)
        assertFalse(deleted.any { it.isAlive })
    }
}
