package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import java.util.UUID

/**
 * The structure of a document: pages, layers, the ops on them, and the tray.
 *
 * It works over an [ObjectStore], so the same repository drives a sketchbook file
 * and the scratchpad table inside the index. Only the store and the [rootId]
 * differ, which is what will make "send this page to the scratchpad" a copy
 * between two of these rather than a special case.
 *
 * ### The undo model
 *
 * A layer carries one integer, `undoDepth`, and it is the whole thing:
 *
 * ```
 * order:      0    1    2    3    4
 * ops:       [A]  [B]  [C]  [D]  [E]
 *                       ↑
 *                  undoDepth = 3      A B C are committed; D E are the redo stack
 * ```
 *
 * Undo decrements it, redo increments it, and neither touches a row. Because the
 * ops are still there, undo history survives closing the document — reopen a page
 * days later and you can still step backwards through it.
 *
 * The price is that `order` must stay **dense** for a layer's ops, which is why
 * appending truncates the redo tail with a *hard* delete rather than a tombstone.
 * (Everywhere else in this format, deletion is soft. This is the deliberate
 * exception, and it is safe because a truncated redo tail is unreachable: the
 * user has already replaced that future with a different one.)
 */
class SketchbookRepository(
    private val store: ObjectStore,
    val rootId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    // --- The document root --------------------------------------------------

    /**
     * Creates the root row if it is absent, and returns it either way.
     *
     * Idempotent on purpose: the scratchpad's root is a sentinel that has to be
     * ensured at every launch, and a sketchbook's is created exactly once. Same
     * call, both cases.
     */
    fun ensureRoot(
        title: String = "",
        canvasKind: String? = null,
        canvasWidthInches: Float? = null,
        canvasHeightInches: Float? = null,
    ): SoilObject {
        store.byId(rootId)?.let { return it }
        val at = now()
        val root = SoilObject(
            id = rootId,
            parentId = "",
            type = SoilType.SKETCHBOOK,
            createdAt = at,
            updatedAt = at,
            text = title,
            kind = canvasKind,
            width = canvasWidthInches,
            height = canvasHeightInches,
        )
        store.insert(root)
        return root
    }

    fun root(): SoilObject? = store.byId(rootId)

    /** Runs [body] atomically — several rows that must land together, or not. */
    fun <T> transaction(body: () -> T): T = store.transaction(body)

    /** Everything a new document needs: a root, a first page, its layer, a palette. */
    fun createDocument(
        title: String,
        canvasKind: String? = null,
        canvasWidthInches: Float? = null,
        canvasHeightInches: Float? = null,
        surfaceKind: String? = null,
        surfaceSeed: Long? = null,
    ): SoilObject = store.transaction {
        ensureRoot(title, canvasKind, canvasWidthInches, canvasHeightInches)
        ensurePalette()
        addPage(surfaceKind = surfaceKind, surfaceSeed = surfaceSeed)
    }

    fun rename(title: String) = touchRoot { it.copy(text = title) }

    /** The page to reopen on. Nullable, and never trusted without a lookup. */
    fun lastOpenedPage(): SoilObject? = root()?.refId?.let { store.byId(it) }

    fun setLastOpenedPage(pageId: String?) = touchRoot { it.copy(refId = pageId) }

    private fun touchRoot(change: (SoilObject) -> SoilObject) {
        val root = root() ?: return
        store.upsert(change(root).copy(updatedAt = now()))
    }

    // --- Pages --------------------------------------------------------------

    fun pages(): List<SoilObject> = store.childrenOfType(rootId, SoilType.PAGE)

    fun pageCount(): Int = pages().size

    fun addPage(
        surfaceKind: String? = null,
        plainColor: String? = null,
        surfaceSeed: Long? = null,
        surfaceParams: Params = Params.EMPTY,
        bufferWidth: Float? = null,
        bufferHeight: Float? = null,
        atIndex: Int? = null,
    ): SoilObject = store.transaction {
        val at = now()
        val page = SoilObject(
            id = newId(),
            parentId = rootId,
            type = SoilType.PAGE,
            order = Subtrees.nextOrder(store.childrenOfType(rootId, SoilType.PAGE)),
            createdAt = at,
            updatedAt = at,
            kind = surfaceKind,
            color = plainColor,
            seed = surfaceSeed,
            params = surfaceParams.encode(),
            width = bufferWidth,
            height = bufferHeight,
        )
        store.insert(page)
        addLayer(page.id)
        if (atIndex != null) movePage(page.id, atIndex)
        store.byId(page.id) ?: page
    }

    /**
     * The surface a page was *created* on.
     *
     * Deliberately not "the surface it is on now". The current surface is whatever
     * the last committed `surface_op` says, and undo moves that — so a page row
     * that tried to cache the answer would be wrong the moment someone undid a
     * surface change, and would then render the wrong paper on reload. (It did,
     * on device, which is how this ended up written down.)
     *
     * So the page keeps the one fact that never changes, [resolvedSurface] derives
     * the one that does, and there is nothing to keep in sync.
     */
    fun setInitialSurface(
        pageId: String,
        surfaceKind: String?,
        plainColor: String?,
        surfaceSeed: Long?,
        surfaceParams: Params,
    ) {
        val page = store.byId(pageId) ?: return
        store.upsert(
            page.copy(
                kind = surfaceKind,
                color = plainColor,
                seed = surfaceSeed,
                params = surfaceParams.encode(),
                updatedAt = now(),
            ),
        )
    }

    /**
     * What the paper actually looks like right now: the last surface change at or
     * below the undo frontier, or the page's own surface when there has been none.
     *
     * Returns the row to read `kind` / `color` / `params` from — a `surface_op` or
     * the `page` itself. The per-artwork seed always comes from the page, because
     * it belongs to the sheet rather than to a moment in its history.
     */
    fun resolvedSurface(pageId: String): SoilObject? {
        val page = store.byId(pageId) ?: return null
        val layer = contentLayer(pageId) ?: return page
        return committedOps(layer.id).lastOrNull { it.type == SoilType.SURFACE_OP } ?: page
    }

    fun setPageSize(pageId: String, bufferWidth: Float, bufferHeight: Float) {
        val page = store.byId(pageId) ?: return
        store.upsert(page.copy(width = bufferWidth, height = bufferHeight, updatedAt = now()))
    }

    /**
     * Soft-deletes the page only.
     *
     * Its layers and their ops are left exactly where they are: every read filters
     * by parent, so they become invisible without being touched, undoing the
     * delete is a single stamp, and the compactor reclaims the subtree once the
     * page itself is purged.
     */
    fun deletePage(pageId: String) = store.softDelete(pageId, now())

    fun restorePage(pageId: String) {
        val page = store.byId(pageId) ?: return
        store.upsert(page.copy(deletedAt = null, updatedAt = now()))
    }

    fun duplicatePage(pageId: String): SoilObject? = store.transaction {
        val page = store.byId(pageId) ?: return@transaction null
        val subtree = listOf(page) + Subtrees.collect(pageId, store::childrenOf)
        val copy = Subtrees.copyInto(
            rows = subtree,
            rootId = pageId,
            newParentId = rootId,
            order = Subtrees.nextOrder(store.childrenOfType(rootId, SoilType.PAGE)),
            newId = newId,
        )
        val at = now()
        store.upsertAll(copy.map { it.copy(createdAt = at, updatedAt = at) })
        copy.first()
    }

    /** Moves a page to [toIndex] among its live siblings, renumbering the rest. */
    fun movePage(pageId: String, toIndex: Int) = store.transaction {
        val ordered = pages().toMutableList()
        val from = ordered.indexOfFirst { it.id == pageId }
        if (from < 0) return@transaction
        val target = toIndex.coerceIn(0, ordered.size - 1)
        if (from == target) return@transaction
        ordered.add(target, ordered.removeAt(from))
        val at = now()
        Subtrees.renumber(ordered.mapIndexed { i, row -> row.copy(order = i) })
            .forEach { store.upsert(it.copy(updatedAt = at)) }
    }

    // --- Layers -------------------------------------------------------------

    fun layers(pageId: String): List<SoilObject> = store.childrenOfType(pageId, SoilType.LAYER)

    /** Today every page has exactly one; the schema is ready for more. */
    fun contentLayer(pageId: String): SoilObject? = layers(pageId).firstOrNull()

    fun addLayer(pageId: String, label: String = "Paint"): SoilObject {
        val at = now()
        val layer = SoilObject(
            id = newId(),
            parentId = pageId,
            type = SoilType.LAYER,
            order = Subtrees.nextOrder(store.childrenOfType(pageId, SoilType.LAYER)),
            createdAt = at,
            updatedAt = at,
            text = label,
            flags = SoilFlags.LAYER_DEFAULT,
            opacity = 1f,
            blendMode = "NORMAL",
            undoDepth = 0,
        )
        store.insert(layer)
        return layer
    }

    // --- Ops ----------------------------------------------------------------

    fun undoDepth(layerId: String): Int = store.byId(layerId)?.undoDepth ?: 0

    /** The ops that make up what is currently on the layer, in order. */
    fun committedOps(layerId: String): List<SoilObject> =
        store.opsBelow(layerId, undoDepth(layerId))

    /** The undone ops still available to redo, oldest first. */
    fun redoableOps(layerId: String): List<SoilObject> =
        store.opsAtOrAbove(layerId, undoDepth(layerId))

    fun canUndo(layerId: String): Boolean = undoDepth(layerId) > 0

    fun canRedo(layerId: String): Boolean = undoDepth(layerId) < store.countOps(layerId)

    /**
     * Commits an op to the end of a layer's history.
     *
     * Three writes, atomically, and the first one is the interesting one: a new op
     * **replaces the future**, so anything that was undone is hard-deleted before
     * this lands. That is what keeps `order` dense, which is what lets a single
     * integer be the undo frontier.
     *
     * [op]'s `id`, `parentId` and `order` are assigned here; everything else — the
     * type, the blob, the columns — comes from the caller.
     */
    fun appendOp(layerId: String, op: SoilObject): SoilObject = store.transaction {
        val layer = store.byId(layerId) ?: error("No such layer: $layerId")
        val depth = layer.undoDepth ?: 0

        store.hardDeleteOpsAtOrAbove(layerId, depth)

        val at = now()
        val row = op.copy(
            id = op.id.ifEmpty { newId() },
            parentId = layerId,
            order = depth,
            createdAt = if (op.createdAt == 0L) at else op.createdAt,
            updatedAt = at,
        )
        store.insert(row)
        store.upsert(layer.copy(undoDepth = depth + 1, updatedAt = at))
        row
    }

    /** Attaches a child to an op — a frisket clip, a stroke's wet state. */
    fun attach(opId: String, attachment: SoilObject): SoilObject {
        val at = now()
        val row = attachment.copy(
            id = attachment.id.ifEmpty { newId() },
            parentId = opId,
            createdAt = if (attachment.createdAt == 0L) at else attachment.createdAt,
            updatedAt = at,
        )
        store.insert(row)
        return row
    }

    fun attachmentsOf(opIds: Collection<String>): List<SoilObject> = store.childrenOf(opIds)

    /** Steps the frontier back one. Nothing is deleted; the op is still there. */
    fun undo(layerId: String): Boolean = store.transaction {
        val layer = store.byId(layerId) ?: return@transaction false
        val depth = layer.undoDepth ?: 0
        if (depth <= 0) return@transaction false
        store.upsert(layer.copy(undoDepth = depth - 1, updatedAt = now()))
        true
    }

    fun redo(layerId: String): Boolean = store.transaction {
        val layer = store.byId(layerId) ?: return@transaction false
        val depth = layer.undoDepth ?: 0
        if (depth >= store.countOps(layerId)) return@transaction false
        store.upsert(layer.copy(undoDepth = depth + 1, updatedAt = now()))
        true
    }

    // --- The raster cache ---------------------------------------------------

    /**
     * The composited pixels for a layer, if they are still current.
     *
     * Returns null when the cache describes a different number of ops than the
     * layer currently shows — after an undo, say. That is a **degradation, never a
     * failure**: the caller replays the ops instead and pays some milliseconds.
     */
    fun cache(layerId: String): SoilObject? {
        val row = cacheRow(layerId) ?: return null
        return if (row.opCount == undoDepth(layerId)) row else null
    }

    /** The cache row whatever its state — for invalidation and for the seal. */
    fun cacheRow(layerId: String): SoilObject? =
        store.childrenOfType(layerId, SoilType.RASTER_CACHE).firstOrNull()

    fun writeCache(layerId: String, pixels: ByteArray, width: Float, height: Float) {
        val at = now()
        val existing = cacheRow(layerId)
        store.upsert(
            SoilObject(
                id = existing?.id ?: newId(),
                parentId = layerId,
                type = SoilType.RASTER_CACHE,
                // Out of `order` space entirely, so no history read can see it.
                order = CACHE_ORDER,
                createdAt = existing?.createdAt ?: at,
                updatedAt = at,
                width = width,
                height = height,
                opCount = undoDepth(layerId),
                blob = pixels,
            ),
        )
    }

    fun invalidateCache(layerId: String) {
        cacheRow(layerId)?.let { store.hardDelete(listOf(it.id)) }
    }

    // --- The tray -----------------------------------------------------------

    fun palette(): SoilObject? = store.childrenOfType(rootId, SoilType.PALETTE).firstOrNull()

    fun ensurePalette(): SoilObject {
        palette()?.let { return it }
        val at = now()
        val row = SoilObject(
            id = newId(),
            parentId = rootId,
            type = SoilType.PALETTE,
            createdAt = at,
            updatedAt = at,
            params = Params.EMPTY.encode(),
        )
        store.insert(row)
        return row
    }

    /** The mixing well and what the brush is carrying, as recipes. */
    fun writePaletteState(params: Params) {
        val row = ensurePalette()
        store.upsert(row.copy(params = params.encode(), updatedAt = now()))
    }

    fun paletteState(): Params = Params.decode(palette()?.params)

    fun pots(): List<SoilObject> = store.childrenOfType(ensurePalette().id, SoilType.POT)

    fun addPot(name: String, color: String, custom: Boolean): SoilObject {
        val paletteId = ensurePalette().id
        val at = now()
        val pot = SoilObject(
            id = newId(),
            parentId = paletteId,
            type = SoilType.POT,
            order = Subtrees.nextOrder(store.childrenOfType(paletteId, SoilType.POT)),
            createdAt = at,
            updatedAt = at,
            text = name,
            color = color,
            flags = if (custom) SoilFlags.POT_CUSTOM else 0,
        )
        store.insert(pot)
        return pot
    }

    fun removePot(potId: String) = store.softDelete(potId, now())
}
