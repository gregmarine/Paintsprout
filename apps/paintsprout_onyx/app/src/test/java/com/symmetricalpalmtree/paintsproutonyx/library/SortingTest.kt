package com.symmetricalpalmtree.paintsproutonyx.library

import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectSummary
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order of the shelf, checked here because the failure it guards against is not a crash and does
 * not look like a bug: a library that comes back in a slightly different order each time it is looked
 * at. Nothing errors, nothing is lost, and the artist stops trusting where their work is.
 */
class SortingTest {

    private fun book(name: String, updatedAt: Long) = ObjectSummary(
        id = "id-$name-$updatedAt",
        type = ObjectType.SKETCHBOOK,
        name = name,
        parentId = null,
        createdAt = 0,
        updatedAt = updatedAt,
        pageCount = 1,
        flags = null,
        paperKind = null,
    )

    private fun names(items: List<ObjectSummary>) = items.map { it.name }

    @Test
    fun `names sort as a person reads them, not as bytes`() {
        val items = listOf(book("zoo", 1), book("Apple", 2), book("banana", 3))
        assertEquals(
            "a shelf that files lower case after Z is sorted by a rule nobody is thinking in",
            listOf("Apple", "banana", "zoo"),
            names(Sorting.sort(items, SortField.NAME, SortOrder.ASC)),
        )
        assertEquals(
            listOf("zoo", "banana", "Apple"),
            names(Sorting.sort(items, SortField.NAME, SortOrder.DESC)),
        )
    }

    @Test
    fun `last worked on puts the newest first`() {
        val items = listOf(book("old", 100), book("new", 300), book("middle", 200))
        assertEquals(
            listOf("new", "middle", "old"),
            names(Sorting.sort(items, SortField.MODIFIED, SortOrder.DESC)),
        )
        assertEquals(
            listOf("old", "middle", "new"),
            names(Sorting.sort(items, SortField.MODIFIED, SortOrder.ASC)),
        )
    }

    @Test
    fun `sketchbooks sharing a timestamp always come back in the same order`() {
        // A folder filled in one sitting, or restored in one go, is full of these.
        val items = listOf(book("charlie", 500), book("alpha", 500), book("bravo", 500))
        val once = names(Sorting.sort(items, SortField.MODIFIED, SortOrder.DESC))
        val again = names(Sorting.sort(items.reversed(), SortField.MODIFIED, SortOrder.DESC))
        assertEquals("the tie-break must not depend on the order the rows arrived in", once, again)
        assertEquals(listOf("alpha", "bravo", "charlie"), once)
    }

    @Test
    fun `reversing the order does not reverse the tie-break`() {
        val items = listOf(book("bravo", 500), book("alpha", 500))
        assertEquals(
            "both directions of a date sort read the same shelf from either end",
            listOf("alpha", "bravo"),
            names(Sorting.sort(items, SortField.MODIFIED, SortOrder.ASC)),
        )
        assertEquals(
            listOf("alpha", "bravo"),
            names(Sorting.sort(items, SortField.MODIFIED, SortOrder.DESC)),
        )
    }

    @Test
    fun `an empty shelf sorts to an empty shelf`() {
        assertEquals(emptyList<String>(), names(Sorting.sort(emptyList(), SortField.NAME, SortOrder.ASC)))
    }
}
