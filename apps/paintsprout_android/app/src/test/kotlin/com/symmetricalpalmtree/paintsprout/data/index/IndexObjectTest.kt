package com.symmetricalpalmtree.paintsprout.data.index

import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexObjectTest {

    private fun book(flags: Int?, keyScope: String?) = IndexObject(
        id = "id", type = IndexType.SKETCHBOOK, name = "Harbour", parentId = null,
        createdAt = 0, updatedAt = 0, flags = flags, keyScope = keyScope,
    )

    /**
     * These four fields exist so that every card, list and picker knows a
     * sketchbook is locked **without opening it**. Deciding whether to prompt for
     * a key must not require the key.
     */
    @Test
    fun `encryption state is readable from the row alone`() {
        assertFalse(book(null, null).isEncrypted)
        assertFalse(book(0, null).isEncrypted)
        assertTrue(book(IndexObject.FLAG_ENCRYPTED, KeyScope.GLOBAL.name).isEncrypted)
    }

    /** The case the cover rule exists for. */
    @Test
    fun `only a private-passphrase book is private scope`() {
        assertFalse("unencrypted", book(0, null).isPrivateScope)
        assertFalse(
            "global scope is protected by the same key as the index",
            book(IndexObject.FLAG_ENCRYPTED, KeyScope.GLOBAL.name).isPrivateScope,
        )
        assertTrue(
            "its own passphrase",
            book(IndexObject.FLAG_ENCRYPTED, KeyScope.NOTEBOOK.name).isPrivateScope,
        )
    }

    /** Rows are identified by id; the generated equals would compare blobs by reference. */
    @Test
    fun `identity is the id, not the cover bytes`() {
        val a = book(0, null).copy(blob = byteArrayOf(1, 2, 3))
        val b = book(0, null).copy(blob = byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == b.copy(id = "other"))
    }

    /**
     * Bump too eagerly and every bookkeeping pass re-flags the library for backup;
     * bump too rarely and a renamed sketchbook is never backed up again.
     */
    @Test
    fun `only real modifications move updatedAt`() {
        assertTrue(IndexEdit.RENAME.bumpsUpdatedAt)
        assertTrue(IndexEdit.MOVE.bumpsUpdatedAt)
        assertTrue(IndexEdit.COVER_REFRESH.bumpsUpdatedAt)
        assertTrue(IndexEdit.PAGE_COUNT_REFRESH.bumpsUpdatedAt)
        assertTrue(IndexEdit.ENCRYPTION_CHANGE.bumpsUpdatedAt)

        assertFalse(IndexEdit.PIN_TOGGLE.bumpsUpdatedAt)
        assertFalse(IndexEdit.ACTIVITY_LOG.bumpsUpdatedAt)
        assertFalse(IndexEdit.FORMAT_CONVERSION.bumpsUpdatedAt)
    }

    // --- Sentinels ----------------------------------------------------------

    @Test
    fun `sentinel ids spell their purpose in hex`() {
        assertEquals("pinned", Sentinels.wordOf(Sentinels.PINNED_LIST_ID))
        assertEquals("clipbd", Sentinels.wordOf(Sentinels.CLIPBOARD_ID))
        assertEquals("scrtch", Sentinels.wordOf(Sentinels.SCRATCHPAD_ROOT_ID))
        assertEquals("clipbr", Sentinels.wordOf(Sentinels.CLIPBOARD_ROOT_ID))
    }

    @Test
    fun `sentinels are distinct, UUID-shaped, and all-zero above the last group`() {
        assertEquals(Sentinels.ALL.size, Sentinels.ALL.toSet().size)
        for (id in Sentinels.ALL) {
            assertTrue("$id is not UUID-shaped", SoilFiles.isDocumentId(id))
            assertTrue(id.startsWith("00000000-0000-0000-0000-"))
        }
    }

    /**
     * A real UUIDv4 always has `4` as its version nibble, so an all-zero prefix
     * cannot be minted by accident.
     */
    @Test
    fun `no random UUID could collide with a sentinel`() {
        repeat(500) {
            val random = java.util.UUID.randomUUID().toString()
            assertFalse(random in Sentinels.ALL)
        }
    }
}
