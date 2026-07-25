package com.symmetricalpalmtree.paintsprout.data.soil

/**
 * Reads one row by column name.
 *
 * An interface rather than a `Cursor` so the mapping — the part that can silently
 * lose a column — is testable against a real SQLite engine on the JVM, where
 * `Cursor` doesn't exist. The Android side is a four-line adapter.
 */
interface RowReader {
    fun getStringOrNull(column: String): String?
    fun getIntOrNull(column: String): Int?
    fun getLongOrNull(column: String): Long?
    fun getFloatOrNull(column: String): Float?
    fun getBlobOrNull(column: String): ByteArray?
}

/**
 * The one translation between a [SoilObject] and a row of a document object
 * table.
 *
 * There is exactly one of these for all three tables — the sketchbook file, the
 * scratchpad and the clipboard — which is what makes "copy a page into the
 * scratchpad" or "paste into another book" ordinary code instead of three
 * conversions that drift.
 *
 * > ⚠️ **A columnar table needs a columnar writer.** In the app this format comes
 * > from, a generic "update the JSON payload" helper survived a migration to
 * > columnar storage and became a **dead write**: it reported success and did
 * > nothing, and the symptom — "my moved objects snap back on reload" — read as a
 * > UI bug for weeks. There is no JSON write path here at all, which is the only
 * > reliable way to not have that one.
 */
object SoilObjectMapper {

    /** Every column of the document row, in DDL order. */
    val COLUMNS: List<String> = listOf(
        "id", "parentId", "type", "order", "createdAt", "updatedAt", "deletedAt",
        "x", "y", "width", "height",
        "text", "color", "refId", "flags", "seed", "kind", "params",
        "tool", "strokeWidth", "opacity", "blendMode", "undoDepth", "opCount", "amount",
        "blob",
    )

    fun read(reader: RowReader): SoilObject = SoilObject(
        id = reader.getStringOrNull("id").orEmpty(),
        parentId = reader.getStringOrNull("parentId").orEmpty(),
        type = reader.getStringOrNull("type").orEmpty(),
        order = reader.getIntOrNull("order") ?: 0,
        createdAt = reader.getLongOrNull("createdAt") ?: 0,
        updatedAt = reader.getLongOrNull("updatedAt") ?: 0,
        deletedAt = reader.getLongOrNull("deletedAt"),
        x = reader.getFloatOrNull("x"),
        y = reader.getFloatOrNull("y"),
        width = reader.getFloatOrNull("width"),
        height = reader.getFloatOrNull("height"),
        text = reader.getStringOrNull("text"),
        color = reader.getStringOrNull("color"),
        refId = reader.getStringOrNull("refId"),
        flags = reader.getIntOrNull("flags"),
        seed = reader.getLongOrNull("seed"),
        kind = reader.getStringOrNull("kind"),
        params = reader.getStringOrNull("params"),
        tool = reader.getStringOrNull("tool"),
        strokeWidth = reader.getFloatOrNull("strokeWidth"),
        opacity = reader.getFloatOrNull("opacity"),
        blendMode = reader.getStringOrNull("blendMode"),
        undoDepth = reader.getIntOrNull("undoDepth"),
        opCount = reader.getIntOrNull("opCount"),
        amount = reader.getFloatOrNull("amount"),
        blob = reader.getBlobOrNull("blob"),
    )

    /**
     * Column → value, nulls included.
     *
     * Nulls are written rather than omitted so an update genuinely clears a
     * column: a writer that skips its nulls can only ever add information, and
     * "the eraser mask is gone but its bounds are still there" is the kind of
     * ghost that takes a day to find.
     */
    fun write(row: SoilObject): Map<String, Any?> = linkedMapOf(
        "id" to row.id,
        "parentId" to row.parentId,
        "type" to row.type,
        "order" to row.order,
        "createdAt" to row.createdAt,
        "updatedAt" to row.updatedAt,
        "deletedAt" to row.deletedAt,
        "x" to row.x,
        "y" to row.y,
        "width" to row.width,
        "height" to row.height,
        "text" to row.text,
        "color" to row.color,
        "refId" to row.refId,
        "flags" to row.flags,
        "seed" to row.seed,
        "kind" to row.kind,
        "params" to row.params,
        "tool" to row.tool,
        "strokeWidth" to row.strokeWidth,
        "opacity" to row.opacity,
        "blendMode" to row.blendMode,
        "undoDepth" to row.undoDepth,
        "opCount" to row.opCount,
        "amount" to row.amount,
        "blob" to row.blob,
    )
}
