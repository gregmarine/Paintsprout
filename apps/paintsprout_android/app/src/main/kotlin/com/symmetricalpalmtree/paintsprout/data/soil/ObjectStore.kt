package com.symmetricalpalmtree.paintsprout.data.soil

/**
 * Everything the document repository needs from a table of objects.
 *
 * An interface for two reasons. The repository's actual content — the undo
 * frontier, redo truncation, page ordering — is logic worth testing without a
 * device, and this is what lets that happen. And the *same* repository runs over
 * a sketchbook file and over the scratchpad table inside the index, which are two
 * different databases; the only thing that varies is which store it was handed.
 */
interface ObjectStore {

    val table: String

    fun byId(id: String): SoilObject?

    /** Live children in sibling order. Includes non-op rows such as a raster cache. */
    fun children(parentId: String): List<SoilObject>

    fun childrenOfType(parentId: String, type: String): List<SoilObject>

    fun childrenOf(parentIds: Collection<String>): List<SoilObject>

    /** A layer's committed ops: `0 <= order < undoDepth`. */
    fun opsBelow(layerId: String, undoDepth: Int): List<SoilObject>

    /** A layer's undone ops — the redo stack, oldest first. */
    fun opsAtOrAbove(layerId: String, undoDepth: Int): List<SoilObject>

    /** How many ops the layer holds in total, undone ones included. */
    fun countOps(layerId: String): Int

    fun count(parentId: String): Int

    fun insert(row: SoilObject)

    fun upsert(row: SoilObject)

    fun upsertAll(rows: Iterable<SoilObject>)

    fun softDelete(id: String, at: Long)

    fun hardDelete(ids: Collection<String>)

    /** Drops a layer's redo tail. The one routine hard delete on the content path. */
    fun hardDeleteOpsAtOrAbove(layerId: String, undoDepth: Int)

    /**
     * Runs [body] atomically.
     *
     * Appending an op is three writes — truncate the redo tail, insert, move the
     * frontier — and a crash between any two of them leaves a layer whose
     * `undoDepth` disagrees with the ops it has.
     */
    fun <T> transaction(body: () -> T): T
}
