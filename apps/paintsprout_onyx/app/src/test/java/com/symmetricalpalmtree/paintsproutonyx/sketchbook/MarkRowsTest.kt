package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.paintsproutonyx.core.MarkCodec
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mark put down and picked back up.
 *
 * This is the only part of the page that can be checked without a tablet, and it is the part that
 * decides whether a sketchbook reopens as the sketchbook that was closed. Everything either side of
 * it — the pen, the panel, how graphite reads — needs a hand and an eye.
 */
class MarkRowsTest {

    private val pageId = "page-1"

    private fun stroke(
        id: String = "mark-1",
        n: Int = 12,
        width: Float = Lead.MEDIUM.widthPx,
        style: StrokeStyle = StrokeStyle.PENCIL,
        color: Int = 0xFF000000.toInt(),
    ) = Stroke(
        id = id,
        points = (0 until n).map {
            StrokePoint(x = 10f + it * 3.5f, y = 20f - it * 1.25f, pressure = 0.2f + it * 0.05f)
        },
        color = color,
        width = width,
        style = style,
    )

    @Test
    fun `a mark comes back the mark that went down`() {
        val original = stroke()
        val back = MarkRows.toStroke(MarkRows.toRow(original, pageId, order = 3, now = 1_000L))
        assertNotNull(back)
        back!!
        assertEquals(original.id, back.id)
        assertEquals(original.color, back.color)
        assertEquals(original.width, back.width, 0f)
        assertEquals(original.style, back.style)
        assertEquals(original.points.size, back.points.size)
        for (i in original.points.indices) {
            assertEquals(original.points[i].x, back.points[i].x, 0f)
            assertEquals(original.points[i].y, back.points[i].y, 0f)
            assertEquals(original.points[i].pressure, back.points[i].pressure, 0f)
        }
    }

    @Test
    fun `the row is a mark of its page, in its place in the stack`() {
        val row = MarkRows.toRow(stroke(), pageId, order = 7, now = 4_242L)
        assertEquals(SoilSchema.TYPE_MARK, row.type)
        assertEquals(pageId, row.parentId)
        assertEquals(7, row.order)
        assertEquals(4_242L, row.createdAt)
        assertEquals(4_242L, row.updatedAt)
        assertNull("a fresh mark is not erased", row.deletedAt)
    }

    @Test
    fun `tilt is not written, so a reader is never told the pen was upright`() {
        // The engine reports tilt as zero on every sample here, and the codec distinguishes a
        // channel that is absent from one recorded as zeroes. Writing zeroes would claim this app
        // measured the pen's angle. It did not.
        val row = MarkRows.toRow(stroke(), pageId, order = 0, now = 0L)
        val decoded = MarkCodec.decode(row.blob!!)
        assertNull("tilt channel must be absent", decoded.tilt)
        assertNotNull("pressure channel must be present", decoded.pressure)
    }

    @Test
    fun `a mark with no pressure recorded draws at full weight, never at none`() {
        // Zero pressure is an invisible mark, which looks exactly like a mark nobody made.
        val blob = MarkCodec.encode(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f))
        val row = SoilObjectEntity(
            id = "no-pressure",
            parentId = pageId,
            type = SoilSchema.TYPE_MARK,
            createdAt = 0L,
            updatedAt = 0L,
            color = "#000000",
            strokeWidth = 6f,
            style = StrokeStyle.PENCIL.name,
            blob = blob,
        )
        val back = MarkRows.toStroke(row)!!
        assertTrue(back.points.all { it.pressure == 1f })
    }

    @Test
    fun `every lead survives the round trip`() {
        for (lead in Lead.entries) {
            val back = MarkRows.toStroke(
                MarkRows.toRow(stroke(width = lead.widthPx), pageId, 0, 0L)
            )!!
            assertEquals(lead.name, lead.widthPx, back.width, 0f)
        }
    }

    @Test
    fun `an unknown style reopens as a pencil, not as a pen`() {
        // Everything this arc draws is graphite, so a name from a build that knew about some other
        // tool is far likelier to be a pencil variant — and a graphite drawing that reopens with one
        // inked line through it is worse than one drawn slightly wrong.
        val row = MarkRows.toRow(stroke(), pageId, 0, 0L).copy(style = "SOMETHING_LATER")
        assertEquals(StrokeStyle.PENCIL, MarkRows.toStroke(row)!!.style)
    }

    @Test
    fun `a row that is not a mark is not a mark`() {
        val page = MarkRows.toRow(stroke(), pageId, 0, 0L).copy(type = SoilSchema.TYPE_PAGE)
        assertNull(MarkRows.toStroke(page))
    }

    @Test
    fun `a mark with no geometry at all is skipped rather than drawn as nothing`() {
        val row = MarkRows.toRow(stroke(), pageId, 0, 0L).copy(blob = null)
        assertNull(MarkRows.toStroke(row))
    }

    @Test
    fun `a single-sample tap is a mark`() {
        val tap = stroke(n = 1)
        val back = MarkRows.toStroke(MarkRows.toRow(tap, pageId, 0, 0L))!!
        assertEquals(1, back.points.size)
        assertEquals(tap.points[0].x, back.points[0].x, 0f)
    }

    @Test
    fun `colour survives as the colour, not as a shade of something`() {
        val grey = 0xFF444444.toInt()
        val row = MarkRows.toRow(stroke(color = grey), pageId, 0, 0L)
        assertEquals(grey, MarkRows.toStroke(row)!!.color)
    }
}
