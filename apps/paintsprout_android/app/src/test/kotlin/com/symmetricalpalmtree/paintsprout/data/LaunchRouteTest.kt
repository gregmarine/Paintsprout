package com.symmetricalpalmtree.paintsprout.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class LaunchRouteTest {

    private val book = UUID.randomUUID().toString()
    private val page = UUID.randomUUID().toString()

    @Test
    fun `a sketchbook pointer opens the editor`() {
        assertEquals(
            LaunchTarget.EDITOR,
            LaunchRoute.of(LastOpen.Pointer(LastOpen.Kind.SKETCHBOOK, book, page)),
        )
    }

    /**
     * The bug this exists for: the scratchpad has no document id, and a rule that
     * demanded one sent every scratch session to the library instead of back to
     * the page it was left on.
     */
    @Test
    fun `a scratchpad pointer opens the editor, with no document to name`() {
        assertEquals(
            LaunchTarget.EDITOR,
            LaunchRoute.of(LastOpen.Pointer(LastOpen.Kind.SCRATCHPAD, null, page)),
        )
        assertEquals(
            LaunchTarget.EDITOR,
            LaunchRoute.of(LastOpen.Pointer(LastOpen.Kind.SCRATCHPAD, null, null)),
        )
    }

    /** Nothing stored — a fresh install — starts at the shelf. */
    @Test
    fun `no pointer opens the library`() {
        assertEquals(LaunchTarget.LIBRARY, LaunchRoute.of(null))
    }

    /** A pointer that decoding refused is a null pointer, and lands the same way. */
    @Test
    fun `a rejected pointer opens the library`() {
        assertEquals(LaunchTarget.LIBRARY, LaunchRoute.of(LastOpen.decode("SKETCHBOOK||")))
    }
}
