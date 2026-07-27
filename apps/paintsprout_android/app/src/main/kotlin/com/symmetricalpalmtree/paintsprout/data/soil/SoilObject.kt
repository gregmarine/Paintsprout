package com.symmetricalpalmtree.paintsprout.data.soil

/**
 * One row of a document object table — a page, a layer, a stroke, a palette pot.
 *
 * The same shape serves the sketchbook file, the scratchpad and the clipboard,
 * which is the whole reason the columns are shared *by role* rather than owned by
 * a type: [text] is a sketchbook's title, a layer's label and a pigment's name.
 * Read [type] first, then interpret.
 *
 * Almost everything is nullable, and that costs nothing worth counting — a NULL
 * column is about a byte of record header, and trailing NULLs are truncated away
 * entirely. A stroke row populates [tool], [color], [strokeWidth], [seed] and
 * [blob]; the other eighteen never reach the disk. What it buys is that a new
 * object type — a raster tile, a group, a text block — costs no migration, no
 * join and no per-type reader.
 */
data class SoilObject(
    val id: String,
    /** `""` on the root meta row; otherwise the containing object. */
    val parentId: String,
    val type: String,
    /** Sort among siblings, and the op index under a layer. */
    val order: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** NULL = alive. Soft delete is the only delete. */
    val deletedAt: Long? = null,

    // Geometry, buffer px. Top-left plus extents — never right/bottom.
    val x: Float? = null,
    val y: Float? = null,
    val width: Float? = null,
    val height: Float? = null,

    // Shared scalars.
    val text: String? = null,
    val color: String? = null,
    val refId: String? = null,
    val flags: Int? = null,
    val seed: Long? = null,
    val kind: String? = null,
    val params: String? = null,

    // Paint-specific.
    val tool: String? = null,
    val strokeWidth: Float? = null,
    val opacity: Float? = null,
    val blendMode: String? = null,
    val undoDepth: Int? = null,
    val opCount: Int? = null,
    val amount: Float? = null,

    val blob: ByteArray? = null,
) {
    val isAlive: Boolean get() = deletedAt == null

    fun hasFlag(bit: Int): Boolean = (flags ?: 0) and bit != 0

    fun withFlag(bit: Int, on: Boolean): SoilObject =
        copy(flags = if (on) (flags ?: 0) or bit else (flags ?: 0) and bit.inv())

    /**
     * The generated equals would compare [blob] by reference, so two reads of the
     * same stroke would be unequal — which quietly breaks every round-trip test
     * and every "did this actually change?" check.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoilObject) return false
        return id == other.id && parentId == other.parentId && type == other.type &&
            order == other.order && createdAt == other.createdAt && updatedAt == other.updatedAt &&
            deletedAt == other.deletedAt && x == other.x && y == other.y &&
            width == other.width && height == other.height && text == other.text &&
            color == other.color && refId == other.refId && flags == other.flags &&
            seed == other.seed && kind == other.kind && params == other.params &&
            tool == other.tool && strokeWidth == other.strokeWidth && opacity == other.opacity &&
            blendMode == other.blendMode && undoDepth == other.undoDepth &&
            opCount == other.opCount && amount == other.amount &&
            (blob?.contentEquals(other.blob) ?: (other.blob == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + parentId.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + order
        result = 31 * result + (blob?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * The document type catalog.
 *
 * Adding to it costs nothing — [SoilObject.type] is a plain string and the table
 * has no idea what any of these mean. The reserved entries are here so the names
 * are settled before two phases pick different ones for the same thing.
 */
object SoilType {
    // Structure
    const val SKETCHBOOK = "sketchbook"
    const val PAGE = "page"
    const val LAYER = "layer"

    // Ops, in `order` sequence under a layer
    const val STROKE = "stroke"
    const val FILL = "fill"
    const val ERASE = "erase"
    const val MOVE = "move"
    const val SURFACE_OP = "surface_op"

    /**
     * How a layer composites, as steps in the timeline.
     *
     * On the timeline because undo retraces what was done and turning a layer
     * down is something done. They lay no pixels: the fold skips them, and the
     * state they describe is re-derived from history on the way back.
     */
    const val LAYER_OPACITY = "layer_opacity"
    const val LAYER_VISIBILITY = "layer_visibility"

    /**
     * A layer arriving and a layer going away.
     *
     * A deleted layer keeps its row and every op beneath it; this step is the
     * only record that it is gone, which is what lets an undo bring it back
     * whole. Both carry the stack position in `opCount` — an integer column that
     * has meant nothing to a step in a timeline until now.
     */
    const val LAYER_ADD = "layer_add"
    const val LAYER_DELETE = "layer_delete"

    /**
     * A layer moving in the stack.
     *
     * Kept for undo only. The layer rows carry the order themselves, so a page
     * loads already in its final arrangement and replaying these would move
     * everything a second time.
     */
    const val LAYER_ORDER = "layer_order"

    /**
     * A clipboard paste: one step in the timeline, holding the pasted ops as
     * child rows. The only op type with ops beneath it.
     */
    const val PASTE = "paste"

    // Attached to a stroke
    const val STROKE_CLIP = "stroke_clip"
    const val WET_STATE = "wet_state"

    /** Not an op: composited pixels, kept out of `order` space (see [CACHE_ORDER]). */
    const val RASTER_CACHE = "raster_cache"

    // The tray, travelling with the artwork
    const val PALETTE = "palette"
    const val POT = "pot"

    /** Reserved. Named now so two phases don't invent different spellings. */
    const val GROUP = "group"
    const val RASTER = "raster"
    const val TEXT = "text"
    const val SHAPE = "shape"

    /** Every op type, in the sense of "appears in a layer's undo timeline". */
    val OPS = setOf(
        STROKE, FILL, ERASE, MOVE, SURFACE_OP, PASTE,
        LAYER_OPACITY, LAYER_VISIBILITY, LAYER_ADD, LAYER_DELETE, LAYER_ORDER,
    )
}

/** Per-type bitfields. */
object SoilFlags {
    const val LAYER_LOCKED = 1
    const val LAYER_VISIBLE = 2

    /** A clean-water stroke: deposits no pigment, only re-wets. */
    const val STROKE_WATER = 1

    /** A pigment the user added from the wheel rather than a standard pot. */
    const val POT_CUSTOM = 1

    /** What a new content layer gets: visible, unlocked. */
    const val LAYER_DEFAULT = LAYER_VISIBLE
}

/**
 * A raster cache sits at `order = -1` so it never collides with an op index, and
 * every history read can simply take `order >= 0`.
 */
const val CACHE_ORDER = -1
