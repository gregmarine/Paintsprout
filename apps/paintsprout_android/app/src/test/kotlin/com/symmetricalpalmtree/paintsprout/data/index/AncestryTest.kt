package com.symmetricalpalmtree.paintsprout.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AncestryTest {

    private val rows = mutableMapOf<String, IndexObject>()

    private fun row(id: String, name: String, parentId: String?, type: String = IndexType.FOLDER) {
        rows[id] = IndexObject(
            id = id, type = type, name = name, parentId = parentId, createdAt = 0, updatedAt = 0,
        )
    }

    private val lookup: (String) -> IndexObject? = { rows[it] }

    @Test
    fun `the path runs root first, and excludes the row itself`() {
        row("a", "Sketches", null)
        row("b", "Studies", "a")
        row("c", "Harbour", "b", IndexType.SKETCHBOOK)

        assertEquals(listOf("Sketches", "Studies"), Ancestry.pathTo("c", lookup).map { it.name })
        assertEquals("Sketches / Studies", Ancestry.breadcrumb("c", lookup))
    }

    @Test
    fun `a row at the root has no ancestry`() {
        row("a", "Loose", null, IndexType.SKETCHBOOK)
        assertTrue(Ancestry.pathTo("a", lookup).isEmpty())
    }

    @Test
    fun `an unknown row has no ancestry rather than an error`() {
        assertTrue(Ancestry.pathTo("nobody", lookup).isEmpty())
    }

    /** A parent that has been deleted out from under a row stops the walk, quietly. */
    @Test
    fun `a missing parent truncates the path instead of failing`() {
        row("b", "Orphan", "gone", IndexType.SKETCHBOOK)
        assertTrue(Ancestry.pathTo("b", lookup).isEmpty())
    }

    /**
     * A cycle should be impossible. An unguarded walk turns "impossible" into a
     * hang on the library screen, so it is bounded and returns what it has.
     */
    @Test
    fun `a cycle terminates rather than hanging`() {
        row("a", "A", "b")
        row("b", "B", "a")

        // The walk stops where the cycle closes: B is a genuine parent of A, and
        // A is then already seen. What matters is that it returns at all.
        assertEquals(listOf("B"), Ancestry.pathTo("a", lookup).map { it.name })
    }

    @Test
    fun `a self-parented row terminates`() {
        row("a", "A", "a")
        assertTrue(Ancestry.pathTo("a", lookup).isEmpty())
    }

    @Test
    fun `the hop cap bounds an absurdly deep tree`() {
        row("leaf", "leaf", "f0", IndexType.SKETCHBOOK)
        repeat(200) { i -> row("f$i", "f$i", "f${i + 1}") }
        row("f200", "top", null)

        assertEquals(Ancestry.MAX_HOPS, Ancestry.pathTo("leaf", lookup).size)
    }

    // --- Move validation ----------------------------------------------------

    @Test
    fun `moving a folder into its own descendant is refused`() {
        row("a", "A", null)
        row("b", "B", "a")
        row("c", "C", "b")

        assertTrue(Ancestry.wouldCycle("a", "c", lookup))
        assertTrue(Ancestry.wouldCycle("a", "a", lookup))
    }

    @Test
    fun `ordinary moves are allowed`() {
        row("a", "A", null)
        row("b", "B", null)
        row("c", "C", "b")

        assertFalse(Ancestry.wouldCycle("a", "b", lookup))
        assertFalse(Ancestry.wouldCycle("a", "c", lookup))
        assertFalse("to the root is always fine", Ancestry.wouldCycle("c", null, lookup))
    }

    /** An already-broken tree must not make the check itself hang. */
    @Test
    fun `an existing cycle in the target chain is refused, not followed forever`() {
        row("a", "A", null)
        row("x", "X", "y")
        row("y", "Y", "x")

        assertTrue(Ancestry.wouldCycle("a", "x", lookup))
    }
}
