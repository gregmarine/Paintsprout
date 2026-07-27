package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.MaskCodec
import com.symmetricalpalmtree.paintsprout.data.soil.codec.MoveCodec
import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import com.symmetricalpalmtree.paintsprout.data.soil.codec.StrokeCodec
import com.symmetricalpalmtree.paintsprout.data.soil.codec.WetStateCodec
import android.graphics.Matrix
import com.symmetricalpalmtree.paintsprout.paint.BrushLoad
import com.symmetricalpalmtree.paintsprout.paint.EraseOp
import com.symmetricalpalmtree.paintsprout.paint.FillOp
import com.symmetricalpalmtree.paintsprout.paint.FolderAddOp
import com.symmetricalpalmtree.paintsprout.paint.FolderDeleteOp
import com.symmetricalpalmtree.paintsprout.paint.FolderOpacityOp
import com.symmetricalpalmtree.paintsprout.paint.FolderVisibilityOp
import com.symmetricalpalmtree.paintsprout.paint.StackSpot
import com.symmetricalpalmtree.paintsprout.paint.LayerAddOp
import com.symmetricalpalmtree.paintsprout.paint.LayerDeleteOp
import com.symmetricalpalmtree.paintsprout.paint.LayerOpacityOp
import com.symmetricalpalmtree.paintsprout.paint.LayerOrderOp
import com.symmetricalpalmtree.paintsprout.paint.LayerVisibilityOp
import com.symmetricalpalmtree.paintsprout.paint.MoveOp
import com.symmetricalpalmtree.paintsprout.paint.PaintOp
import com.symmetricalpalmtree.paintsprout.paint.PasteOp
import com.symmetricalpalmtree.paintsprout.paint.StrokeOp
import com.symmetricalpalmtree.paintsprout.paint.Recipe
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

    // --- Layer composition ---------------------------------------------------

    /**
     * How a layer composites, as a step.
     *
     * Each rides in the column the layer row already uses for the same thing —
     * `opacity`, and the visibility bit of `flags` — so nothing new is stored,
     * only stored somewhere else: on the timeline rather than on the layer. Like
     * a surface change these are paint-neutral, but they have to sit in the op
     * sequence, because undoing back past one must put the old state back.
     */
    fun layerOpacityRow(op: LayerOpacityOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.LAYER_OPACITY,
        opacity = op.opacity,
    )

    fun layerVisibilityRow(op: LayerVisibilityOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.LAYER_VISIBILITY,
        flags = if (op.visible) SoilFlags.LAYER_VISIBLE else 0,
    )

    /**
     * A layer arriving and a layer going away.
     *
     * `opCount` carries how far up from the bottom of its folder's contents it
     * sat, and `refId` which folder that was — empty for the ones nothing holds.
     * Undoing a deletion therefore puts the layer back where it was rather than
     * on top, and back inside rather than beside. The `opCount` column belongs to
     * the raster cache elsewhere and means nothing to a step in a timeline, which
     * is why it was free to mean this.
     *
     * A step written before folders existed carries no `refId`, and reads back as
     * a layer nothing holds — which is what it was.
     */
    fun layerAddRow(op: LayerAddOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.LAYER_ADD,
        text = op.name,
        refId = op.spot.folder,
        opCount = op.spot.at,
    )

    fun layerDeleteRow(op: LayerDeleteOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.LAYER_DELETE,
        refId = op.spot.folder,
        opCount = op.spot.at,
    )

    /**
     * A move, as where it went and where it came from — both of them a folder and
     * a place in it.
     *
     * `opCount`/`refId` are the destination, matching the other two structure
     * steps; `amount`/`kind` are the origin. `amount` is the format's
     * general-purpose scalar already — a pigment quantity on one row type, a
     * mask's downsample factor on another — and the columns here are shared by
     * role rather than owned by a type. A stack index is small and whole, so a
     * REAL holds it exactly.
     */
    fun layerOrderRow(op: LayerOrderOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.LAYER_ORDER,
        refId = op.to.folder,
        opCount = op.to.at,
        kind = op.from.folder,
        amount = op.from.at.toFloat(),
        // Empty for a layer, which is filed under itself. A folder's move is
        // filed under the working layer, so it has to say what it was about.
        text = op.subject,
    )

    // --- Folders --------------------------------------------------------------

    /**
     * A folder arriving and a folder going away.
     *
     * `refId` is the folder itself, because these are filed under whichever layer
     * was being worked on and the row is the only thing that says which folder it
     * is about. `kind` is the folder that held it and `opCount` where in that
     * folder it sat — the same pair the layer steps use, in the columns left over
     * once `refId` is spoken for.
     */
    fun folderAddRow(op: FolderAddOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.FOLDER_ADD,
        text = op.name,
        refId = op.folderId,
        kind = op.spot.folder,
        opCount = op.spot.at,
    )

    /** `amount` counts what spilled out of it, which is what undo has to take back. */
    fun folderDeleteRow(op: FolderDeleteOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.FOLDER_DELETE,
        text = op.name,
        refId = op.folderId,
        kind = op.spot.folder,
        opCount = op.spot.at,
        amount = op.held.toFloat(),
    )

    /** How a folder composites, as a step. The layer pair, one level up. */
    fun folderOpacityRow(op: FolderOpacityOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.FOLDER_OPACITY,
        refId = op.folderId,
        opacity = op.opacity,
    )

    fun folderVisibilityRow(op: FolderVisibilityOp): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.FOLDER_VISIBILITY,
        refId = op.folderId,
        flags = if (op.visible) SoilFlags.LAYER_VISIBLE else 0,
    )

    // --- Selection ops ------------------------------------------------------

    /**
     * The three ops that act on a wand or lasso region.
     *
     * They share a shape: a mask in the blob, and the geometry needed to put it
     * back — the crop's origin in `x`/`y`, the full field it belongs in as
     * `width`/`height`, and the resolution it was captured at in `amount`. The
     * mask is captured at half resolution and stretched at paint time, so that
     * factor has to travel with it rather than being assumed.
     */
    fun fillRow(color: Int, mask: MaskBitmaps.Cropped, downsample: Float): SoilObject =
        maskRow(SoilType.FILL, mask, downsample).copy(
            color = ArgbHex.encode(color),
            blob = MaskCodec.encode(mask.mask),
        )

    fun eraseRow(mask: MaskBitmaps.Cropped, downsample: Float): SoilObject =
        maskRow(SoilType.ERASE, mask, downsample).copy(blob = MaskCodec.encode(mask.mask))

    /**
     * A move carries its transform in the blob alongside the mask. Nine floats
     * through decimal text would drift, and a transform that drifts re-lays the
     * lifted paint slightly off on every replay.
     */
    fun moveRow(matrix: FloatArray, mask: MaskBitmaps.Cropped, downsample: Float): SoilObject =
        maskRow(SoilType.MOVE, mask, downsample).copy(
            blob = MoveCodec.encode(MoveCodec.Move(matrix, mask.mask)),
        )

    /**
     * The frisket a stroke was drawn inside.
     *
     * A child of the stroke rather than an op of its own: it is not a step in the
     * history, it is a property of that one stroke, and it has to replay with it
     * so the constraint survives undo and a surface change.
     */
    fun clipRow(mask: MaskBitmaps.Cropped, downsample: Float): SoilObject =
        maskRow(SoilType.STROKE_CLIP, mask, downsample).copy(blob = MaskCodec.encode(mask.mask))

    private fun maskRow(type: String, mask: MaskBitmaps.Cropped, downsample: Float) = SoilObject(
        id = "",
        parentId = "",
        type = type,
        x = mask.left.toFloat(),
        y = mask.top.toFloat(),
        width = mask.fullWidth.toFloat(),
        height = mask.fullHeight.toFloat(),
        amount = downsample,
    )

    fun readMask(row: SoilObject): MaskBitmaps.Cropped? {
        val mask = when (row.type) {
            SoilType.MOVE -> MoveCodec.decode(row.blob)?.mask
            else -> MaskCodec.decode(row.blob)
        } ?: return null
        return MaskBitmaps.Cropped(
            mask = mask,
            left = (row.x ?: 0f).toInt(),
            top = (row.y ?: 0f).toInt(),
            fullWidth = (row.width ?: 0f).toInt(),
            fullHeight = (row.height ?: 0f).toInt(),
        )
    }

    fun readMatrix(row: SoilObject): FloatArray? = MoveCodec.decode(row.blob)?.matrix

    // --- Watercolour ---------------------------------------------------------

    /**
     * The wall-clock state a wash needs to replay as what the user watched.
     *
     * A child of the stroke, and only written for a stroke that actually went
     * wet — a dry tool has none, and a wash that dried fully has no freeze.
     */
    fun wetStateRow(stroke: Stroke): SoilObject? {
        val crop = stroke.wetCrop
        val schedule = stroke.wetSchedule
        val freeze = stroke.dryFreeze
        if (schedule.isEmpty() && crop == null && freeze == null) return null
        return SoilObject(
            id = "",
            parentId = "",
            type = SoilType.WET_STATE,
            blob = WetStateCodec.encode(
                WetStateCodec.WetState(
                    schedule = schedule.toIntArray(),
                    crop = crop?.let { intArrayOf(it.left, it.top, it.right, it.bottom) },
                    dryFreeze = freeze,
                ),
            ),
        )
    }

    fun readWetState(row: SoilObject): WetStateCodec.WetState? = WetStateCodec.decode(row.blob)

    // --- The tray ------------------------------------------------------------

    fun potRow(name: String, color: Int, custom: Boolean, order: Int): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.POT,
        order = order,
        text = name,
        color = ArgbHex.encode(color),
        flags = if (custom) SoilFlags.POT_CUSTOM else 0,
    )

    /** What is in the mixing well and what the brush is carrying. */
    fun paletteParams(mixture: Recipe, load: BrushLoad): Params = Params.of(
        "mixture" to RecipeCodec.encode(mixture),
        "load" to RecipeCodec.encode(load.recipe),
        "capacity" to load.capacity,
    )

    fun readMixture(params: Params): Recipe = RecipeCodec.decode(params.string("mixture", ""))

    fun readLoad(params: Params): BrushLoad = BrushLoad(
        recipe = RecipeCodec.decode(params.string("load", "")),
        capacity = params.float("capacity", BrushLoad.DEFAULT_CAPACITY),
    )

    // --- Ops, back into the objects the canvas replays ----------------------

    /**
     * Rebuilds one op, or returns null if its payload is unreadable.
     *
     * Null is a dropped op, never a failed page: one damaged blob costs the mark
     * it describes and nothing else. [childrenOf] answers with an op's own child
     * rows and is expected to be backed by a batch the caller already fetched,
     * rather than a query per op.
     */
    fun readOp(row: SoilObject, childrenOf: (String) -> List<SoilObject>): PaintOp? =
        readOp(row, childrenOf(row.id), childrenOf)

    /** The flat case: an op whose children are attachments, never other ops. */
    fun readOp(row: SoilObject, attachments: List<SoilObject>): PaintOp? =
        readOp(row, attachments) { emptyList() }

    private fun readOp(
        row: SoilObject,
        attachments: List<SoilObject>,
        childrenOf: (String) -> List<SoilObject>,
    ): PaintOp? = when (row.type) {
        SoilType.STROKE -> readStroke(row)?.let { stroke ->
            attachments.firstOrNull { it.type == SoilType.WET_STATE }
                ?.let(::readWetState)
                ?.let { wet ->
                    stroke.wetSchedule.addAll(wet.schedule.toList())
                    stroke.wetCrop = wet.crop?.let { c ->
                        android.graphics.Rect(c[0], c[1], c[2], c[3])
                    }
                    stroke.dryFreeze = wet.dryFreeze
                }
            val clip = attachments.firstOrNull { it.type == SoilType.STROKE_CLIP }
                ?.let(::readMask)
                ?.let(MaskBitmaps::decode)
            StrokeOp(stroke, clip)
        }

        SoilType.FILL -> readMask(row)?.let {
            FillOp(MaskBitmaps.decode(it), ArgbHex.decode(row.color, DEFAULT_COLOR))
        }

        SoilType.ERASE -> readMask(row)?.let { EraseOp(MaskBitmaps.decode(it)) }

        SoilType.MOVE -> readMask(row)?.let { mask ->
            val values = readMatrix(row) ?: return null
            MoveOp(MaskBitmaps.decode(mask), Matrix().apply { setValues(values) })
        }

        SoilType.SURFACE_OP -> readSurfaceOp(row)

        // A missing value would be a step that changed nothing, which is worse
        // than a step that is not there: it would sit in the timeline swallowing
        // an undo. Dropped instead, by the same rule as any unreadable op.
        SoilType.LAYER_OPACITY -> row.opacity?.let { LayerOpacityOp(it.coerceIn(0f, 1f)) }

        SoilType.LAYER_VISIBILITY ->
            row.flags?.let { LayerVisibilityOp(it and SoilFlags.LAYER_VISIBLE != 0) }

        SoilType.LAYER_ADD -> LayerAddOp(row.text.orEmpty(), destinationOf(row))
        SoilType.LAYER_DELETE -> LayerDeleteOp(destinationOf(row))

        SoilType.LAYER_ORDER -> LayerOrderOp(
            from = originOf(row),
            to = destinationOf(row),
            subject = row.text.orEmpty(),
        )

        // A folder step with no folder to name is a step about nothing, and a
        // step about nothing in the middle of a timeline swallows an undo.
        SoilType.FOLDER_ADD -> row.refId?.takeIf { it.isNotEmpty() }?.let {
            FolderAddOp(it, row.text.orEmpty(), spotOf(row.kind, row.opCount))
        }

        SoilType.FOLDER_DELETE -> row.refId?.takeIf { it.isNotEmpty() }?.let {
            FolderDeleteOp(
                folderId = it,
                name = row.text.orEmpty(),
                spot = spotOf(row.kind, row.opCount),
                held = (row.amount ?: 0f).toInt().coerceAtLeast(0),
            )
        }

        SoilType.FOLDER_OPACITY -> row.refId?.takeIf { it.isNotEmpty() }?.let { folder ->
            row.opacity?.let { FolderOpacityOp(folder, it.coerceIn(0f, 1f)) }
        }

        SoilType.FOLDER_VISIBILITY -> row.refId?.takeIf { it.isNotEmpty() }?.let { folder ->
            row.flags?.let { FolderVisibilityOp(folder, it and SoilFlags.LAYER_VISIBLE != 0) }
        }

        // The one op with ops beneath it. Each child is read on its own terms and
        // a damaged one is dropped, so a paste of thirty marks survives losing
        // one of them — the same rule as a page, one level down.
        SoilType.PASTE -> PasteOp(
            attachments
                .sortedBy { it.order }
                .mapNotNull { child -> readOp(child, childrenOf(child.id), childrenOf) },
        )

        // A raster cache is not an op, and neither is anything a future build
        // adds that this one has not heard of. Skipping beats guessing.
        else -> null
    }

    /**
     * The place a structural step points at, and the place it came from.
     *
     * A missing folder reads as no folder, which is the right answer twice over:
     * it is what a step written before folders existed meant, and it is what a
     * step written since means when nothing held the thing it moved.
     */
    private fun destinationOf(row: SoilObject) = spotOf(row.refId, row.opCount)

    private fun originOf(row: SoilObject) = spotOf(row.kind, row.amount?.toInt())

    private fun spotOf(folder: String?, at: Int?) =
        StackSpot(folder.orEmpty(), (at ?: 0).coerceAtLeast(0))

    /** The parent row of a paste. It carries no payload of its own. */
    fun pasteRow(count: Int): SoilObject = SoilObject(
        id = "",
        parentId = "",
        type = SoilType.PASTE,
        opCount = count,
    )

    fun readSurfaceOp(row: SoilObject): SurfaceOp {
        val params = Params.decode(row.params)
        return SurfaceOp(
            kind = SurfaceParamsCodec.kindOf(row.kind),
            plainColor = ArgbHex.decode(row.color, 0xFFFFFFFF.toInt()),
            canvas = SurfaceParamsCodec.canvas(params),
            watercolor = SurfaceParamsCodec.watercolor(params),
            wood = SurfaceParamsCodec.wood(params),
            stone = SurfaceParamsCodec.stone(params),
            concrete = SurfaceParamsCodec.concrete(params),
            metal = SurfaceParamsCodec.metal(params),
            chalkboard = SurfaceParamsCodec.chalkboard(params),
        )
    }

    private const val DEFAULT_COLOR = 0xFF000000.toInt()
}
