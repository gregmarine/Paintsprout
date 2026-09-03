package com.symmetricalpalmtree.paintsproutonyx.library

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the "recently opened" shelf.
 *
 * Every failure this guards against is a quiet one. A duplicate entry, an entry that never falls off
 * the end, an order that drifts — none of them crash, none of them lose a drawing, and all of them
 * end with a shelf called "Recent" that the artist stops trusting to hold the thing they were just
 * in. The JSON round-trip is here for the same reason: this list is written to a file that outlives
 * the build that wrote it.
 */
class RecentsListTest {

    private fun ids(entries: List<RecentEntry>) = entries.map { it.id }

    @Test
    fun `the newest open goes to the front`() {
        val list = RecentsList.record(RecentsList.record(emptyList(), "a", 100), "b", 200)
        assertEquals(listOf("b", "a"), ids(list))
        assertEquals(200L, list[0].at)
    }

    @Test
    fun `opening something again moves it rather than listing it twice`() {
        var list = RecentsList.record(emptyList(), "a", 100)
        list = RecentsList.record(list, "b", 200)
        list = RecentsList.record(list, "c", 300)
        list = RecentsList.record(list, "a", 400)
        assertEquals(
            "one sketchbook opened four times would otherwise be the whole shelf",
            listOf("a", "c", "b"),
            ids(list),
        )
        assertEquals(400L, list[0].at)
    }

    @Test
    fun `the list stops at its cap and the oldest is what goes`() {
        var list = emptyList<RecentEntry>()
        for (i in 1..RecentsList.MAX + 5) list = RecentsList.record(list, "book-$i", i.toLong())
        assertEquals(RecentsList.MAX, list.size)
        assertEquals("book-${RecentsList.MAX + 5}", list.first().id)
        assertEquals("book-6", list.last().id)
        assertTrue("the oldest entries are the ones that fall off", list.none { it.id == "book-1" })
    }

    @Test
    fun `a smaller cap truncates a list that is already over it`() {
        // The cap is a constant now, but a list written by a build with a bigger one must not come
        // back longer than this build's shelf can show.
        val long = (1..10).map { RecentEntry("book-$it", it.toLong()) }
        val list = RecentsList.record(long, "new", 99, max = 3)
        assertEquals(listOf("new", "book-1", "book-2"), ids(list))
    }

    @Test
    fun `pruning drops what is gone and leaves the order of the rest`() {
        val list = listOf(
            RecentEntry("a", 300),
            RecentEntry("b", 200),
            RecentEntry("c", 100),
        )
        assertEquals(listOf("a", "c"), ids(RecentsList.prune(list, setOf("a", "c", "unrelated"))))
        assertEquals(emptyList<String>(), ids(RecentsList.prune(list, emptySet())))
    }

    @Test
    fun `the list survives a trip through the pref file`() {
        val list = RecentsList.record(RecentsList.record(emptyList(), "a", 100), "b", 200)
        val json = Json.encodeToString(list)
        assertEquals(list, Json.decodeFromString<List<RecentEntry>>(json))
    }
}
