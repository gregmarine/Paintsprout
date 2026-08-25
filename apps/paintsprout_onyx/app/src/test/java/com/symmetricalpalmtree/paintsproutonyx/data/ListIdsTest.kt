package com.symmetricalpalmtree.paintsproutonyx.data

import com.symmetricalpalmtree.paintsproutonyx.data.index.ListIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The pinned shelf's id is a constant, which means nothing at runtime ever checks it — the row is
 * found by it or it is not found at all. A typo here would not fail; it would quietly give the shelf a
 * second, empty pinned list, and everything the artist had pinned would still be in the file, still
 * intact, and no longer anywhere on screen.
 *
 * So it is checked here instead, in the only place it can be: that it is a real UUID, and that it
 * still spells what it is meant to spell.
 */
class ListIdsTest {

    @Test
    fun `pinned list id is a well-formed uuid`() {
        val parsed = UUID.fromString(ListIds.PINNED_LIST_ID)
        assertEquals(
            "the constant must round-trip through UUID unchanged, or it is not the id it looks like",
            ListIds.PINNED_LIST_ID,
            parsed.toString(),
        )
        assertTrue(
            "a UUID is 8-4-4-4-12 lower-case hex",
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                .matches(ListIds.PINNED_LIST_ID),
        )
    }

    @Test
    fun `the last group spells pinned in hex`() {
        val groups = ListIds.PINNED_LIST_ID.split("-")
        assertEquals(5, groups.size)

        val spelled = groups.last()
            .chunked(2)
            .map { it.toInt(16).toChar() }
            .joinToString("")
        assertEquals("pinned", spelled)
    }

    @Test
    fun `everything before the last group is zeroes`() {
        val groups = ListIds.PINNED_LIST_ID.split("-")
        // No generator hands out a UUID of zeroes, which is exactly why the sentinel can never
        // collide with a sketchbook or a folder.
        assertTrue(groups.dropLast(1).all { group -> group.all { it == '0' } })
    }
}
