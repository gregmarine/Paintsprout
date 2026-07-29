package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.index.IndexObject
import com.symmetricalpalmtree.paintsprout.paint.CanvasSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canvas-size round trip a library card depends on.
 *
 * A card has to know a book's proportions without opening it, so the size lives
 * on the index row — and the two directions have to agree, or every thumbnail
 * would be drawn at the wrong shape.
 */
class SketchbooksTest {

    private fun row(kind: String?, w: Float?, h: Float?) = IndexObject(
        id = "b", type = "sketchbook", name = "Harbour", parentId = null,
        createdAt = 0, updatedAt = 0, canvasKind = kind, canvasW = w, canvasH = h,
    )

    @Test
    fun `a print size round-trips through the index row`() {
        val original = CanvasSize.Print(7f, 5f, "5 × 7 in")
        assertEquals("PRINT", Sketchbooks.canvasKindOf(original))

        val back = Sketchbooks.canvasSizeOf(row("PRINT", 7f, 5f))
        assertTrue(back is CanvasSize.Print)
        assertEquals(7f, (back as CanvasSize.Print).wIn)
        assertEquals(5f, back.hIn)
        assertEquals("a known preset keeps its label", "5 × 7 in", back.label)
    }

    @Test
    fun `full screen round-trips`() {
        assertEquals("FULL_SCREEN", Sketchbooks.canvasKindOf(CanvasSize.FullScreen))
        assertEquals(CanvasSize.FullScreen, Sketchbooks.canvasSizeOf(row("FULL_SCREEN", null, null)))
    }

    /** A custom size the presets do not know still describes itself. */
    @Test
    fun `an unrecognised print size gets a made-up label rather than none`() {
        val back = Sketchbooks.canvasSizeOf(row("PRINT", 3.5f, 2f)) as CanvasSize.Print
        assertEquals(3.5f, back.wIn)
        assertTrue(back.label.isNotEmpty())
    }

    /**
     * A row that is missing its dimensions cannot be a print size, whatever it
     * claims — falling back to full screen beats drawing a card 0 × 0.
     */
    @Test
    fun `an incoherent row falls back to full screen`() {
        assertEquals(CanvasSize.FullScreen, Sketchbooks.canvasSizeOf(row("PRINT", null, null)))
        assertEquals(CanvasSize.FullScreen, Sketchbooks.canvasSizeOf(row("PRINT", 7f, null)))
        assertEquals(CanvasSize.FullScreen, Sketchbooks.canvasSizeOf(row(null, 7f, 5f)))
    }

    /** Every preset survives being written to a row and read back. */
    @Test
    fun `every canvas preset round-trips`() {
        for (preset in CanvasSize.PRESETS) {
            val back = Sketchbooks.canvasSizeOf(row("PRINT", preset.wIn, preset.hIn)) as CanvasSize.Print
            assertEquals(preset.wIn, back.wIn)
            assertEquals(preset.hIn, back.hIn)
            assertEquals(preset.label, back.label)
        }
    }

    /**
     * A frame stores pixels where a print stores inches, which is why the kind is
     * read before the numbers are. Reading 480 × 800 as inches would make a card
     * forty feet wide, and reading a 7 × 5 print as pixels would make it a speck.
     */
    @Test
    fun `every frame round-trips as its pixel grid`() {
        for (frame in CanvasSize.FRAMES) {
            assertEquals("FRAME", Sketchbooks.canvasKindOf(frame))
            val (w, h) = Sketchbooks.canvasDimsOf(frame)
            assertEquals(frame.pxW.toFloat(), w)
            assertEquals(frame.pxH.toFloat(), h)
            assertEquals(frame, Sketchbooks.canvasSizeOf(row("FRAME", w, h)))
        }
    }

    @Test
    fun `a print stores inches and a full screen stores nothing`() {
        assertEquals(7f to 5f, Sketchbooks.canvasDimsOf(CanvasSize.Print(7f, 5f, "5 × 7 in")))
        assertEquals(null to null, Sketchbooks.canvasDimsOf(CanvasSize.FullScreen))
    }
}
