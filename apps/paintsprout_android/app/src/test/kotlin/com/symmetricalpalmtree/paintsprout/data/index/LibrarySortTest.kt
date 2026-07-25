package com.symmetricalpalmtree.paintsprout.data.index

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {

    private fun row(name: String, created: Long, updated: Long) = IndexObject(
        id = name, type = IndexType.SKETCHBOOK, name = name, parentId = null,
        createdAt = created, updatedAt = updated,
    )

    private val rows = listOf(
        row("banana", created = 300, updated = 100),
        row("Apple", created = 100, updated = 300),
        row("cherry", created = 200, updated = 200),
    )

    /**
     * Case-insensitive, so "apple" and "Apple" sit together rather than in two
     * blocks divided by every capital letter in between.
     */
    @Test
    fun `by name, ignoring case`() {
        assertEquals(listOf("Apple", "banana", "cherry"), LibrarySort.NAME.applyTo(rows).map { it.name })
    }

    @Test
    fun `by created, newest first`() {
        assertEquals(listOf("banana", "cherry", "Apple"), LibrarySort.CREATED.applyTo(rows).map { it.name })
    }

    @Test
    fun `by updated, newest first`() {
        assertEquals(listOf("Apple", "cherry", "banana"), LibrarySort.UPDATED.applyTo(rows).map { it.name })
    }

    @Test
    fun `sorting nothing is nothing`() {
        for (order in LibrarySort.entries) assertEquals(emptyList<IndexObject>(), order.applyTo(emptyList()))
    }

    @Test
    fun `a stored preference round-trips, and junk falls back to name`() {
        for (order in LibrarySort.entries) assertEquals(order, LibrarySort.parse(order.name))
        assertEquals(LibrarySort.NAME, LibrarySort.parse(null))
        assertEquals(LibrarySort.NAME, LibrarySort.parse("BY_VIBES"))
    }

    /** Sorting never invents or loses a row. */
    @Test
    fun `every ordering is a permutation of the input`() {
        for (order in LibrarySort.entries) {
            assertEquals(rows.map { it.id }.toSet(), order.applyTo(rows).map { it.id }.toSet())
            assertEquals(rows.size, order.applyTo(rows).size)
        }
    }
}
