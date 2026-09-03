package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Undo is a promise about order, and order is the one thing about it that can be checked without a
 * tablet in the room. Whether an undo *looks* right is the artist's eye; whether the right edit
 * comes back, once, in the order it was made, is arithmetic — and every way it can go wrong is a
 * silent one, because a history that hands back the wrong entry still hands back *an* entry.
 */
class UndoRedoStackTest {

    private fun drew(page: String, mark: String) = Edit.Drew(page, mark)

    @Test
    fun `a fresh stack has nothing to give in either direction`() {
        val stack = UndoRedoStack()
        assertFalse(stack.canUndo())
        assertFalse(stack.canRedo())
        assertNull(stack.popUndo())
        assertNull(stack.popRedo())
    }

    @Test
    fun `the newest edit is the first one taken back`() {
        val stack = UndoRedoStack()
        stack.record(drew("p1", "a"))
        stack.record(drew("p1", "b"))
        assertEquals(drew("p1", "b"), stack.popUndo())
        assertEquals(drew("p1", "a"), stack.popUndo())
        assertNull(stack.popUndo())
    }

    @Test
    fun `an edit goes out one side and comes back the other`() {
        val stack = UndoRedoStack()
        stack.record(drew("p1", "a"))
        val undone = stack.popUndo()!!
        stack.pushRedo(undone)
        assertFalse(stack.canUndo())
        assertTrue(stack.canRedo())
        val redone = stack.popRedo()!!
        stack.pushUndo(redone)
        assertTrue(stack.canUndo())
        assertFalse(stack.canRedo())
        assertEquals(drew("p1", "a"), stack.popUndo())
    }

    @Test
    fun `a fresh edit makes everything that was taken back unreachable`() {
        val stack = UndoRedoStack()
        stack.record(drew("p1", "a"))
        stack.pushRedo(stack.popUndo()!!)
        assertTrue(stack.canRedo())
        stack.record(drew("p1", "b"))
        assertFalse(
            "redoing onto a drawing that has moved on would replay an edit into a page that no " +
                "longer has room for it",
            stack.canRedo(),
        )
    }

    @Test
    fun `a hundred edits deep the oldest is the one that falls off`() {
        val stack = UndoRedoStack()
        for (i in 0 until UndoRedoStack.MAX + 5) stack.record(drew("p1", "mark$i"))
        val held = generateSequence { stack.popUndo() }.toList()
        assertEquals(UndoRedoStack.MAX, held.size)
        assertEquals(
            "the newest is always kept",
            drew("p1", "mark${UndoRedoStack.MAX + 4}"),
            held.first(),
        )
        assertEquals(
            "what is dropped is always the furthest from the hand",
            drew("p1", "mark5"),
            held.last(),
        )
    }

    @Test
    fun `only recording moves the generation`() {
        val stack = UndoRedoStack()
        val start = stack.generation
        stack.record(drew("p1", "a"))
        assertEquals(start + 1, stack.generation)

        val settled = stack.generation
        stack.popUndo()
        stack.pushRedo(drew("p1", "a"))
        stack.popRedo()
        stack.pushUndo(drew("p1", "a"))
        stack.canUndo()
        stack.canRedo()
        assertEquals(
            "a replay must be able to tell 'a fresh edit landed' from 'I moved entries about'",
            settled,
            stack.generation,
        )

        stack.record(drew("p1", "b"))
        assertEquals(settled + 1, stack.generation)
    }

    @Test
    fun `closing the sketchbook forgets the sitting`() {
        val stack = UndoRedoStack()
        stack.record(drew("p1", "a"))
        stack.pushRedo(drew("p1", "b"))
        stack.clear()
        assertFalse(stack.canUndo())
        assertFalse(stack.canRedo())
    }

    @Test
    fun `an edit remembers the page it happened on across a page turn`() {
        val stack = UndoRedoStack()
        stack.record(drew("p1", "a"))
        stack.record(drew("p2", "b"))
        // The artist is looking at p2; taking back the second mark and then the first has to walk
        // the history back to p1, not do nothing because the current page has no more edits on it.
        assertEquals("p2", stack.popUndo()!!.pageId)
        assertEquals("p1", stack.popUndo()!!.pageId)
    }
}
