package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.paintsproutonyx.core.InkColorCodec
import com.symmetricalpalmtree.paintsproutonyx.core.MarkCodec
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilSchema

/**
 * The border between a mark on the page and a row in the file, crossed in both directions.
 *
 * It lives on its own, away from the session and away from the engine, because it is the one piece
 * of this screen that can be *proved* rather than looked at: a mark that goes down and comes back
 * unchanged is a page that reopens as the page that was closed, and that is testable on a laptop
 * with no tablet in the room. Everything either side of it needs a panel and a hand.
 *
 * Two decisions are worth stating.
 *
 * **Tilt is written, and it has to be.** It was left out at first, when the engine reported zero on
 * every sample. It does not any more: the NoteAir5C's digitizer turns out to report the pen's lean
 * in degrees from vertical, and **tilt is what decides how wide a mark is** — a pencil laid over
 * draws with the flank of the lead instead of its point. Drop the channel and a page would reopen
 * with every shading stroke narrowed to a line, which is not a mark drawn slightly wrong but a
 * different drawing. The blob format reserved a flag for it from the start, so carrying it costs no
 * version bump and no migration; files written before this simply have no tilt channel and reopen
 * as the upright marks they were recorded as.
 *
 * **A row that cannot be read is skipped, not guessed at.** A mark whose blob is damaged has no
 * honest fallback: an empty mark drawn on a page is indistinguishable from a mark nobody ever made,
 * so a page of unreadable rows would open as a blank sheet and read as lost work with nothing to
 * explain it. The caller logs what it dropped and shows the rest.
 */
object MarkRows {

    /**
     * A committed stroke as a row of the sketchbook.
     *
     * [order] is the stacking position, counted over erased rows as well as living ones — see
     * `SoilDao.maxOrder` for why the dead have to be counted.
     */
    fun toRow(stroke: Stroke, pageId: String, order: Int, now: Long): SoilObjectEntity {
        val n = stroke.points.size
        val x = FloatArray(n)
        val y = FloatArray(n)
        val pressure = FloatArray(n)
        val tilt = FloatArray(n)
        for (i in 0 until n) {
            val p = stroke.points[i]
            x[i] = p.x
            y[i] = p.y
            pressure[i] = p.pressure
            tilt[i] = p.tilt
        }
        return SoilObjectEntity(
            id = stroke.id,
            parentId = pageId,
            type = SoilSchema.TYPE_MARK,
            order = order,
            createdAt = now,
            updatedAt = now,
            color = InkColorCodec.encode(stroke.color),
            strokeWidth = stroke.width,
            style = stroke.style.name,
            blob = MarkCodec.encode(x, y, pressure = pressure, tilt = tilt),
        )
    }

    /**
     * A row as a mark the engine can draw, or null when the row is not one.
     *
     * The id comes straight back off the row, which is what makes an erase, a reload and an undo all
     * talk about the same mark. A pressure channel that was never recorded becomes g-paper's own
     * default of full pressure rather than zero — a mark drawn with no weight at all is invisible,
     * and "we did not record this" must never render as "the artist pressed with nothing".
     *
     * A missing **tilt** channel takes the opposite default, zero, and for the same kind of reason:
     * zero tilt is a pencil held upright, which is an ordinary way to hold one. Marks from a file
     * written before this app read the pen's angle therefore reopen at the width they were drawn at,
     * which is exactly what they were.
     *
     * An unknown style name falls back to `PENCIL` rather than to the engine's `PEN`: everything
     * this arc can draw is a pencil, so a name from a build that knew about some other tool is far
     * more likely to be a pencil variant than a pen, and a graphite drawing that reopens with one
     * inked line through it is worse than one drawn slightly wrong.
     */
    fun toStroke(row: SoilObjectEntity): Stroke? {
        if (row.type != SoilSchema.TYPE_MARK) return null
        val blob = row.blob ?: return null
        val pts = MarkCodec.decode(blob)
        if (pts.size == 0) return null
        val points = ArrayList<StrokePoint>(pts.size)
        for (i in 0 until pts.size) {
            points.add(
                StrokePoint(
                    x = pts.x[i],
                    y = pts.y[i],
                    pressure = pts.pressure?.get(i) ?: 1f,
                    tilt = pts.tilt?.get(i) ?: 0f,
                )
            )
        }
        return Stroke(
            id = row.id,
            points = points,
            color = InkColorCodec.decode(row.color),
            width = row.strokeWidth ?: Lead.DEFAULT.widthPx,
            style = styleOf(row.style),
        )
    }

    private fun styleOf(name: String?): StrokeStyle =
        StrokeStyle.entries.firstOrNull { it.name == name } ?: StrokeStyle.PENCIL
}
