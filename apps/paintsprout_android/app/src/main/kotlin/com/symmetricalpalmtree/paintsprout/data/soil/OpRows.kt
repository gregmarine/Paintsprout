package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.StrokeCodec
import com.symmetricalpalmtree.paintsprout.paint.Stroke
import com.symmetricalpalmtree.paintsprout.paint.SurfaceOp
import com.symmetricalpalmtree.paintsprout.paint.Tool

/**
 * Paint ops, as rows.
 *
 * The division of labour is the one the format asks for: **the blob is pure
 * geometry**, and everything scalar about a stroke — which tool, what colour, how
 * wide, which seed — is a column. So changing a stroke's colour is a scalar
 * update rather than a re-encode, and a query can ask "which tools were used on
 * this page" without decompressing anything.
 *
 * Reading is deliberately forgiving. A row naming a tool this build has never
 * heard of still becomes a stroke — drawn with the pen — because a page that
 * renders imperfectly beats a page that refuses to open.
 */
object OpRows {

    // --- Strokes ------------------------------------------------------------

    fun strokeRow(stroke: Stroke): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.STROKE,
        tool = stroke.tool.name,
        color = ArgbHex.encode(stroke.color),
        strokeWidth = stroke.baseWidth,
        seed = stroke.seed.toLong(),
        flags = if (stroke.water) SoilFlags.STROKE_WATER else 0,
        blob = StrokeCodec.encode(stroke.points),
    )

    /**
     * Rebuilds a stroke, or returns null when its geometry is unreadable.
     *
     * Null here means one stroke does not render. It must never mean the page
     * fails — the caller drops it and carries on with the rest.
     */
    fun readStroke(row: SoilObject): Stroke? {
        val points = StrokeCodec.decode(row.blob) ?: return null
        val stroke = Stroke(
            tool = toolOf(row.tool),
            color = ArgbHex.decode(row.color, DEFAULT_COLOR),
            seed = (row.seed ?: 0L).toInt(),
            baseWidth = row.strokeWidth ?: 0f,
            water = row.hasFlag(SoilFlags.STROKE_WATER),
        )
        points.forEach(stroke::add)
        return stroke
    }

    /** An unknown tool name draws as a pen rather than not at all. */
    fun toolOf(name: String?): Tool =
        Tool.entries.firstOrNull { it.name == name } ?: Tool.PEN

    // --- Surface changes ----------------------------------------------------

    /**
     * A surface change on the undo timeline.
     *
     * Paint-neutral — replaying it touches no pixels — but it has to sit in the
     * op sequence, because undoing back past a surface change must put the old
     * paper back. The per-artwork seed is *not* here: it belongs to the page, not
     * to a moment in its history.
     */
    fun surfaceRow(op: SurfaceOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.SURFACE_OP,
        kind = op.kind.name,
        color = ArgbHex.encode(op.plainColor),
        params = SurfaceParamsCodec.encode(
            canvas = op.canvas,
            watercolor = op.watercolor,
            wood = op.wood,
            stone = op.stone,
            concrete = op.concrete,
            metal = op.metal,
            chalkboard = op.chalkboard,
        ).encode(),
    )

    private const val DEFAULT_COLOR = 0xFF000000.toInt()
}
