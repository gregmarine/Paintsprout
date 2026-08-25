package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStackTest {

    private fun layer(id: String, parent: String = "") = StackEntry(id, isFolder = false, parentId = parent)
    private fun folder(id: String, parent: String = "") = StackEntry(id, isFolder = true, parentId = parent)

    /**
     *  Figure        (folder)
     *    Line
     *    Shadow
     *  Sky
     */
    private fun figureOverSky() = LayerStack(
        listOf(folder("F"), layer("line", "F"), layer("shadow", "F"), layer("sky")),
    )

    // --- Reading the shape ---------------------------------------------------

    @Test
    fun `a folder's contents sit deeper than it does`() {
        val stack = figureOverSky()
        assertEquals(0, stack.depth(0))
        assertEquals(1, stack.depth(1))
        assertEquals(1, stack.depth(2))
        assertEquals(0, stack.depth(3))
    }

    @Test
    fun `a folder's span reaches everything inside it and stops`() {
        assertEquals(0..2, figureOverSky().span(0))
        assertEquals(3..3, figureOverSky().span(3))
    }

    @Test
    fun `a layer's span is itself`() {
        assertEquals(1..1, figureOverSky().span(1))
    }

    /** The panel reads top-down; the paint goes down bottom-first. */
    @Test
    fun `paint order is the layers, reversed, and folders are not in it`() {
        assertEquals(listOf("line", "shadow", "sky"), figureOverSky().layerIds())
        assertEquals(listOf("sky", "shadow", "line"), figureOverSky().drawOrder())
    }

    /**
     * The point of a shelf: a layer inside a folder paints exactly where it
     * painted before, because nesting is filing and filing is not compositing.
     */
    @Test
    fun `filing a layer away does not change where it paints`() {
        val stack = LayerStack(listOf(folder("F"), layer("a"), layer("b"), layer("c")))
        val before = stack.drawOrder()
        assertTrue(stack.move("a", to = 1, into = "F"))
        assertEquals(before, stack.drawOrder())
    }

    @Test
    fun `ancestors run nearest first`() {
        val stack = LayerStack(
            listOf(folder("outer"), folder("inner", "outer"), layer("deep", "inner")),
        )
        assertEquals(listOf("inner", "outer"), stack.ancestors("deep"))
        assertTrue(stack.isInside("deep", "outer"))
        assertFalse(stack.isInside("outer", "deep"))
    }

    @Test
    fun `an empty folder knows it is empty`() {
        val stack = LayerStack(listOf(folder("F"), layer("sky")))
        assertTrue(stack.isEmptyFolder("F"))
        assertFalse(stack.isEmptyFolder("sky"))
    }

    // --- Moving --------------------------------------------------------------

    @Test
    fun `a folder travels with what it holds`() {
        val stack = figureOverSky()
        assertTrue(stack.move("F", to = 4))
        assertEquals(listOf("sky", "F", "line", "shadow"), stack.entries.map { it.id })
        // And still holds them.
        assertEquals(1, stack.depth(2))
        assertEquals(1, stack.depth(3))
    }

    @Test
    fun `a layer dragged out of a folder comes loose`() {
        val stack = figureOverSky()
        assertTrue(stack.move("line", to = 4))
        assertEquals("", stack.entry("line")!!.parentId)
        assertEquals(listOf("F", "shadow", "sky", "line"), stack.entries.map { it.id })
    }

    /** Contiguity is the representation; a move that broke it would break reading. */
    @Test
    fun `a layer dropped into a folder lands adjacent to it`() {
        val stack = figureOverSky()
        assertTrue(stack.move("sky", to = 1, into = "F"))
        assertEquals(listOf("F", "sky", "line", "shadow"), stack.entries.map { it.id })
        assertEquals(0..3, stack.span(0))
    }

    @Test
    fun `a folder cannot be put inside itself`() {
        val stack = figureOverSky()
        assertFalse(stack.move("F", to = 1, into = "F"))
    }

    /** The one that makes the walk hang if it gets through. */
    @Test
    fun `a folder cannot be put inside its own contents`() {
        val stack = LayerStack(listOf(folder("outer"), folder("inner", "outer"), layer("deep", "inner")))
        assertFalse(stack.move("outer", to = 2, into = "inner"))
        assertEquals(listOf("outer", "inner", "deep"), stack.entries.map { it.id })
    }

    @Test
    fun `a move that lands where it started changes nothing`() {
        val stack = figureOverSky()
        assertFalse(stack.move("sky", to = 3))
        assertFalse(stack.move("sky", to = 4))
    }

    /** A chain of [LayerStack.MAX_NESTING] folders, deepest last, plus loose odds. */
    private fun fullDepthChain(): LayerStack {
        val entries = mutableListOf<StackEntry>()
        var parent = ""
        repeat(LayerStack.MAX_NESTING) { i ->
            entries += folder("f$i", parent)
            parent = "f$i"
        }
        entries += folder("spare")
        entries += layer("sky")
        return LayerStack(entries)
    }

    @Test
    fun `folders stop nesting at the cap`() {
        val stack = fullDepthChain()
        val deepest = "f${LayerStack.MAX_NESTING - 1}"
        assertFalse("a folder inside the deepest folder", stack.move("spare", to = 1, into = deepest))
        assertTrue("a folder one shelf up", stack.move("spare", to = 1, into = "f${LayerStack.MAX_NESTING - 2}"))
    }

    /**
     * The cap counts folders, not things. Otherwise the deepest folder you are
     * allowed to make is one you cannot put a layer in, which is not a shelf.
     */
    @Test
    fun `a layer may go in the deepest folder there is`() {
        val stack = fullDepthChain()
        assertTrue(stack.move("sky", to = 1, into = "f${LayerStack.MAX_NESTING - 1}"))
    }

    /** A tall folder moving needs room for everything under it, not just itself. */
    @Test
    fun `a folder too tall for its landing is refused`() {
        val entries = mutableListOf<StackEntry>(folder("home"))
        var parent = ""
        repeat(LayerStack.MAX_NESTING) { i ->
            entries += folder("t$i", parent)
            parent = "t$i"
        }
        entries += layer("leaf", parent)
        val stack = LayerStack(entries)

        assertEquals(LayerStack.MAX_NESTING - 1, stack.folderHeight("t0"))
        // Already exactly as deep as folders go, so it fits nowhere but the top.
        assertFalse(stack.move("t0", to = 1, into = "home"))
        assertEquals(-1, stack.folderHeight("leaf"))
    }

    // --- Removing ------------------------------------------------------------

    @Test
    fun `removing a folder lifts its contents with it`() {
        val stack = figureOverSky()
        val block = stack.remove("F")
        assertEquals(listOf("F", "line", "shadow"), block.map { it.id })
        assertEquals(listOf("sky"), stack.entries.map { it.id })
    }

    @Test
    fun `a lifted block goes back the way it came`() {
        val stack = figureOverSky()
        val block = stack.remove("F")
        stack.insertAll(block, 1)
        assertEquals(listOf("sky", "F", "line", "shadow"), stack.entries.map { it.id })
        assertEquals(1..3, stack.span(1))
    }

    // --- Folding shut --------------------------------------------------------

    @Test
    fun `a shut folder shows, and what it holds does not`() {
        val stack = figureOverSky()
        assertEquals(listOf(0, 1, 2, 3), stack.visibleRows(emptySet()))
        assertEquals(listOf(0, 3), stack.visibleRows(setOf("F")))
    }

    @Test
    fun `shutting the outer folder hides the inner one too`() {
        val stack = LayerStack(
            listOf(folder("outer"), folder("inner", "outer"), layer("deep", "inner"), layer("sky")),
        )
        assertEquals(listOf(0, 3), stack.visibleRows(setOf("outer")))
    }

    // --- Where a drop lands --------------------------------------------------

    @Test
    fun `the gap under a folder title belongs to the folder`() {
        val stack = figureOverSky()
        assertEquals("F", stack.dropInto(above = 0, below = 1))
    }

    @Test
    fun `a gap between two loose layers is loose`() {
        val stack = LayerStack(listOf(layer("a"), layer("b")))
        assertEquals("", stack.dropInto(above = 0, below = 1))
    }

    /** Below the last row of a folder there is nothing, so the folder keeps it. */
    @Test
    fun `the gap at the very bottom belongs to whatever holds the row above`() {
        val stack = LayerStack(listOf(folder("F"), layer("line", "F")))
        assertEquals("F", stack.dropInto(above = 1, below = null))
    }

    @Test
    fun `the gap above the first row is loose`() {
        assertEquals("", figureOverSky().dropInto(above = null, below = 0))
    }

    /**
     * The seam at a folder's bottom edge means two things, and it offers both.
     * Answering with only one made whichever it dropped somewhere a layer could
     * not be put at all — which is exactly what happened to the bottom of a
     * folder.
     */
    @Test
    fun `the gap under a folder's last layer offers the folder, then beside it`() {
        val stack = figureOverSky()
        assertEquals(listOf("F", ""), stack.dropChain(above = 2, below = 3))
        assertEquals("staying in is the default", "F", stack.dropInto(2, 3, stepsOut = 0))
        assertEquals("one step out", "", stack.dropInto(2, 3, stepsOut = 1))
    }

    /** Reaching past the shallowest reading is still the shallowest reading. */
    @Test
    fun `stepping out further than there is stops at the outside`() {
        val stack = figureOverSky()
        assertEquals("", stack.dropInto(2, 3, stepsOut = 9))
    }

    /** The whole point, end to end: a layer can reach the bottom of a folder. */
    @Test
    fun `a layer can land at the bottom of a folder`() {
        val stack = figureOverSky()
        // Dragging "line" down one row: the seam above "sky", which is the gap
        // under the folder's last layer.
        assertTrue(stack.move("line", to = 3, into = stack.dropInto(2, 3, stepsOut = 0)))
        assertEquals(listOf("F", "shadow", "line", "sky"), stack.entries.map { it.id })
        assertEquals("F", stack.entry("line")!!.parentId)
        assertEquals("still inside, and last", 0..2, stack.span(0))
    }

    @Test
    fun `the same seam, one step out, lands beside the folder instead`() {
        val stack = figureOverSky()
        assertTrue(stack.move("line", to = 3, into = stack.dropInto(2, 3, stepsOut = 1)))
        assertEquals(listOf("F", "shadow", "line", "sky"), stack.entries.map { it.id })
        assertEquals("", stack.entry("line")!!.parentId)
        assertEquals("the folder ends before it", 0..1, stack.span(0))
    }

    /** Where two folders meet, the seam means three things, deepest first. */
    @Test
    fun `a seam between two folders offers every depth it sits on`() {
        val stack = LayerStack(
            listOf(
                folder("outer"), folder("inner", "outer"), layer("deep", "inner"),
                folder("next"), layer("after", "next"),
            ),
        )
        // The gap between "deep" (two folders in) and the "next" folder's title.
        assertEquals(listOf("inner", "outer", ""), stack.dropChain(above = 2, below = 3))
    }

    /** Under a folder's own title is inside it, however little it holds. */
    @Test
    fun `the gap under an empty folder's title is inside that folder`() {
        val stack = LayerStack(listOf(folder("F"), layer("sky")))
        assertEquals(listOf("F", ""), stack.dropChain(above = 0, below = 1))
    }

    @Test
    fun `depth inside says where a thing filed there would sit`() {
        val stack = LayerStack(
            listOf(folder("outer"), folder("inner", "outer"), layer("deep", "inner")),
        )
        assertEquals(0, stack.depthInside(StackEntry.LOOSE))
        assertEquals(1, stack.depthInside("outer"))
        assertEquals(2, stack.depthInside("inner"))
    }
}
