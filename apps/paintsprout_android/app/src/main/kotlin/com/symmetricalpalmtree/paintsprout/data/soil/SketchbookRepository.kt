package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import com.symmetricalpalmtree.paintsprout.paint.LayerStack
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

    /**
     * The page to reopen on, if it is still there.
     *
     * Resolved **and filtered** at read, exactly like list membership: the pointer
     * is a `refId` to a row that may since have been deleted — here, or in another
     * session — and a reference is not permission to resurrect what it points at.
     * A stale pointer means "no particular page", which the caller answers with
     * the first one.
     */
    fun lastOpenedPage(): SoilObject? =
        root()?.refId?.let { store.byId(it) }?.takeIf { it.isAlive && it.type == SoilType.PAGE }

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
        // A copy belongs beside the page it came from. Appending it — which is
        // what the subtree copy does on its own, having nowhere else to put it —
        // means hunting for your duplicate at the back of a fifty-page book.
        val index = pages().indexOfFirst { it.id == pageId }
        if (index >= 0) movePage(copy.first().id, index + 1)
        store.byId(copy.first().id) ?: copy.first()
    }

    /**
     * A page and everything under it — layers, ops, attachments, cache.
     *
     * The read half of a transfer. It is deliberately the *whole* subtree rather
     * than a curated selection: whatever the page is made of travels, including
     * the layer's `undoDepth`, so the page arrives somewhere else with its undo
     * history intact rather than as a flattened result.
     */
    fun pageSubtree(pageId: String): List<SoilObject> {
        // Alive, and actually a page. `byId` answers for tombstones too — that is
        // what makes undelete possible — so a deleted page would otherwise travel
        // as a deleted page and arrive invisible.
        val page = store.byId(pageId)
            ?.takeIf { it.type == SoilType.PAGE && it.isAlive }
            ?: return emptyList()
        return listOf(page) + Subtrees.collect(pageId, store::childrenOf)
    }

    /**
     * The write half: takes a subtree read from *another* document and lands it
     * here as a new last page.
     *
     * Fresh ids for everything, through the one shared helper — [rows] carry the
     * ids they had in the document they came from, and sending the same page
     * twice, or sending it back where it came from, is otherwise a `UNIQUE`
     * failure in the middle of somebody's work.
     */
    fun insertPage(rows: List<SoilObject>, sourcePageId: String): SoilObject? = store.transaction {
        if (rows.none { it.id == sourcePageId }) return@transaction null
        val copy = Subtrees.copyInto(
            rows = rows,
            rootId = sourcePageId,
            newParentId = rootId,
            order = Subtrees.nextOrder(store.childrenOfType(rootId, SoilType.PAGE)),
            newId = newId,
        )
        val at = now()
        // createdAt is stamped fresh — this page arrived here now, whatever age
        // its source is — while updatedAt would be a lie either way; it is the
        // same content.
        store.upsertAll(copy.map { it.copy(createdAt = at, updatedAt = at) })
        store.byId(copy.first().id) ?: copy.first()
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

    /**
     * Every layer and folder on the page, at whatever depth, in no order.
     *
     * Walked a level at a time rather than through [Subtrees.collect], which
     * would bring back every stroke on the page as well: a stack has a handful
     * of rows and a page's history has thousands, and the panel wants the first
     * of those without paying for the second.
     */
    fun stackRows(pageId: String): List<SoilObject> {
        val out = mutableListOf<SoilObject>()
        val seen = hashSetOf(pageId)
        var frontier = setOf(pageId)
        var depth = 0
        while (frontier.isNotEmpty() && depth <= LayerStack.MAX_NESTING) {
            val next = store.childrenOf(frontier)
                .filter { (it.type == SoilType.LAYER || it.type == SoilType.GROUP) && seen.add(it.id) }
            out += next
            frontier = next.filter { it.type == SoilType.GROUP }.mapTo(hashSetOf()) { it.id }
            depth++
        }
        return out
    }

    /** The page's stack top-down, as the panel reads it: folders and layers both. */
    fun stack(pageId: String): List<SoilObject> = Stacks.topDown(pageId, stackRows(pageId))

    /**
     * Every layer on the page, bottom-first — the order they are painted in.
     *
     * Folders drop out entirely: they hold layers, they are not layers, and
     * nothing that paints or folds ops has any use for them. That a layer may
     * now live one or six levels down is invisible from here, which is the
     * point — a folder passes its contents through, so the paint order is the
     * whole stack's layers flattened and nothing more.
     */
    fun layers(pageId: String): List<SoilObject> =
        stack(pageId).filter { it.type == SoilType.LAYER }.asReversed()

    /** The bottom layer of the page, wherever in the stack it has been filed. */
    fun contentLayer(pageId: String): SoilObject? = layers(pageId).firstOrNull()

    /**
     * [parentId] is the page, or a folder on it. The caller has already decided
     * which; a layer does not care what holds it.
     */
    fun addLayer(parentId: String, label: String = "Paint"): SoilObject {
        val at = now()
        val layer = SoilObject(
            id = newId(),
            parentId = parentId,
            type = SoilType.LAYER,
            order = Subtrees.nextOrder(stackSiblings(parentId)),
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

    /**
     * A folder, holding nothing yet.
     *
     * It carries the same composition columns a layer does — the eye and the
     * dial — because a folder has both, and they mean the same thing there:
     * something multiplied onto what is inside rather than applied to pixels of
     * its own. It has no `undoDepth` because it has no ops; the steps that make,
     * move and dim a folder are filed on layers, where the timeline already is.
     */
    fun addFolder(parentId: String, label: String = "Folder"): SoilObject {
        val at = now()
        val folder = SoilObject(
            id = newId(),
            parentId = parentId,
            type = SoilType.GROUP,
            order = Subtrees.nextOrder(stackSiblings(parentId)),
            createdAt = at,
            updatedAt = at,
            text = label,
            flags = SoilFlags.LAYER_DEFAULT,
            opacity = 1f,
        )
        store.insert(folder)
        return folder
    }

    /**
     * Layers and folders share one sequence within whatever holds them, so a new
     * one of either goes above both.
     */
    private fun stackSiblings(parentId: String): List<SoilObject> =
        store.children(parentId).filter { it.type == SoilType.LAYER || it.type == SoilType.GROUP }

    /** Every folder alive on the page, at whatever depth. */
    fun folders(pageId: String): List<SoilObject> =
        stackRows(pageId).filter { it.type == SoilType.GROUP }

    /**
     * A folder row with this id, whether or not there was one a moment ago.
     *
     * Undo brings folders back, and it brings back *the same folder* — every step
     * still on the timeline names it by the id it had, so a new id would leave
     * them all pointing at nothing. A tombstoned row is revived rather than
     * replaced for the same reason the rest of this format soft-deletes: the row
     * is still the record of that folder, and the delete was only ever a mark
     * saying it had gone.
     */
    fun ensureFolder(id: String, parentId: String, label: String): SoilObject {
        val at = now()
        val existing = store.byId(id)
        val row = existing?.copy(
            parentId = parentId,
            text = label,
            deletedAt = null,
            updatedAt = at,
        ) ?: SoilObject(
            id = id,
            parentId = parentId,
            type = SoilType.GROUP,
            order = Subtrees.nextOrder(stackSiblings(parentId)),
            createdAt = at,
            updatedAt = at,
            text = label,
            flags = SoilFlags.LAYER_DEFAULT,
            opacity = 1f,
        )
        store.upsert(row)
        return row
    }

    fun renameStackEntry(id: String, label: String) {
        val row = store.byId(id) ?: return
        if (row.type != SoilType.LAYER && row.type != SoilType.GROUP) return
        store.upsert(row.copy(text = label, updatedAt = now()))
    }

    /**
     * Drops a folder. What was inside it is *not* dropped with it.
     *
     * The caller has already lifted the contents out and back into the stack,
     * and written where they went — a folder is a place to keep layers, so
     * throwing one away is throwing away the place, never the work. That is also
     * why this is the one structural delete with no undo step of its own: by the
     * time it runs, the folder is empty, and an empty folder holds nothing that
     * could be lost.
     */
    fun removeFolder(folderId: String) = store.transaction {
        val row = store.byId(folderId) ?: return@transaction
        if (row.type != SoilType.GROUP) return@transaction
        // Anything still filed in it comes out first, into whatever held the
        // folder. Normally the caller has already emptied it — but a *deleted
        // layer* keeps its row exactly where it was, on purpose, so that the step
        // that removed it stays undoable, and that row is invisible to the caller.
        // Tombstoning over the top of one would strand it: no walk from the page
        // would reach it again, and the undo that was supposed to bring the layer
        // back with all its paint would find nothing to bring.
        val at = now()
        for (child in store.children(folderId)) {
            if (child.type != SoilType.LAYER && child.type != SoilType.GROUP) continue
            store.upsert(child.copy(parentId = row.parentId, updatedAt = at))
        }
        store.softDelete(folderId, at)
    }

    /**
     * Drops a layer and, with it, every op filed beneath it.
     *
     * Soft, like a page: the same delete a sketchbook uses everywhere else, so a
     * layer removed by mistake is recoverable by the same means as anything else.
     */
    fun removeLayer(layerId: String) {
        val row = store.byId(layerId) ?: return
        if (row.type != SoilType.LAYER) return
        store.softDelete(layerId, now())
    }

    /**
     * Writes the whole arrangement: what holds what, and in what order.
     *
     * One call for both, because with folders they are one fact. A layer dragged
     * out of a folder changes its parent *and* its place among its new siblings
     * *and* the numbering of the siblings it left, and writing those separately
     * is three chances to leave the file describing a stack that never existed.
     *
     * Renumbered wholesale rather than shuffled, for the reason pages are: a
     * sequence rewritten from 0 cannot end up with two rows claiming one place.
     */
    fun setStackOrder(pageId: String, stack: LayerStack) = store.transaction {
        val at = now()
        val rows = stackRows(pageId).associateBy { it.id }
        for ((id, place) in Stacks.placements(pageId, stack)) {
            val row = rows[id] ?: continue
            if (row.parentId == place.parentId && row.order == place.order) continue
            store.upsert(row.copy(parentId = place.parentId, order = place.order, updatedAt = at))
        }
    }

    /**
     * How a layer or a folder composites: whether it shows, and how strongly.
     *
     * Not an op. These change the way something is drawn, never what is on it,
     * so they are written straight to the row rather than onto the timeline.
     * A folder takes the same two because it means the same two by them — its
     * eye and its dial reach through onto everything it holds.
     */
    fun setLayerState(layerId: String, visible: Boolean, opacity: Float) {
        val row = store.byId(layerId) ?: return
        val flags = (row.flags ?: SoilFlags.LAYER_DEFAULT).let {
            if (visible) it or SoilFlags.LAYER_VISIBLE else it and SoilFlags.LAYER_VISIBLE.inv()
        }
        store.upsert(row.copy(flags = flags, opacity = opacity, updatedAt = now()))
    }

    /**
     * Whether a folder is folded shut.
     *
     * Off the timeline, unlike the eye beside it, and the difference is what the
     * two do. Hiding a folder changes the picture; shutting one changes only how
     * much of the panel it takes up. Undo retraces what you did to the drawing,
     * and folding a list is not something you did to the drawing.
     */
    fun setFolderCollapsed(folderId: String, collapsed: Boolean) {
        val row = store.byId(folderId) ?: return
        if (row.type != SoilType.GROUP) return
        store.upsert(row.withFlag(SoilFlags.FOLDER_COLLAPSED, collapsed).copy(updatedAt = now()))
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
