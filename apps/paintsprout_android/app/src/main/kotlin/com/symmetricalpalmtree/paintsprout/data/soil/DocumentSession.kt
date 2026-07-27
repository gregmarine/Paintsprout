package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.Sentinels
import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.symmetricalpalmtree.paintsprout.paint.BrushLoad
import com.symmetricalpalmtree.paintsprout.paint.CanvasSize
import com.symmetricalpalmtree.paintsprout.paint.EraseOp
import com.symmetricalpalmtree.paintsprout.paint.Layer
import com.symmetricalpalmtree.paintsprout.paint.LayerAddOp
import com.symmetricalpalmtree.paintsprout.paint.LayerDeleteOp
import com.symmetricalpalmtree.paintsprout.paint.LayerOpacityOp
import com.symmetricalpalmtree.paintsprout.paint.LayerOrderOp
import com.symmetricalpalmtree.paintsprout.paint.LayerVisibilityOp
import com.symmetricalpalmtree.paintsprout.paint.FillOp
import com.symmetricalpalmtree.paintsprout.paint.MoveOp
import com.symmetricalpalmtree.paintsprout.paint.PaintOp
import com.symmetricalpalmtree.paintsprout.paint.PasteOp
import com.symmetricalpalmtree.paintsprout.paint.Pot
import com.symmetricalpalmtree.paintsprout.paint.Recipe
import com.symmetricalpalmtree.paintsprout.paint.StrokeOp
import com.symmetricalpalmtree.paintsprout.paint.WandFloodFill
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.SurfaceOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The document the editor is currently painting into.
 *
 * The editor hands it ops as they are committed; it turns them into rows. Writes
 * are debounced and batched, because a fast pen commits strokes faster than a
 * transaction wants to run, and none of it happens on the UI thread.
 *
 * The debounce is a *floor*, not a cancel-and-reschedule: a flush is scheduled
 * when the queue goes from empty to non-empty and is not pushed back by later
 * ops. Continuous painting therefore still lands within one window, where
 * rescheduling would mean an unbroken stroke sequence never writes at all.
 */
class DocumentSession private constructor(
    private val home: DocumentHome,
    val repo: SketchbookRepository,
    pageId: String,
    layerId: String,
) {

    /** A sketchbook's file id, or the scratchpad's sentinel. */
    val documentId: String get() = home.documentId

    /** The page being painted. Changes with [switchTo]. */
    var pageId: String = pageId
        private set

    var layerId: String = layerId
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val pending = ArrayDeque<PaintOp>()
    private var flushJob: Job? = null

    /** The tray, captured on change and written with the next batch. */
    private var pendingPalette: PaletteSnapshot? = null

    /**
     * Whether anything has actually changed since this document was opened.
     *
     * The seal consults it for a reason beyond saving work: refreshing the index
     * row bumps `updatedAt`, and `updatedAt` is the input to the backup predicate.
     * Opening a sketchbook to look at it, and re-flagging it for backup as a
     * result, is exactly the "no" case the discipline exists for.
     */
    @Volatile
    var isDirty: Boolean = false
        private set

    // --- Recording ----------------------------------------------------------

    fun record(op: PaintOp) {
        isDirty = true
        synchronized(pending) { pending.addLast(op) }
        scheduleFlush()
    }

    /**
     * The palette, as it stands.
     *
     * Snapshotted rather than queued: the brush's load changes on every stylus
     * sample as it drains and picks up colour, and only its latest value is worth
     * anything. Coalescing it into the same debounce as the ops means a dirty
     * brush costs one write per batch instead of hundreds.
     */
    fun recordPalette(pots: List<Pot>, mixture: Recipe, load: BrushLoad) {
        isDirty = true
        pendingPalette = PaletteSnapshot(pots.toList(), mixture, load)
        scheduleFlush()
    }

    private class PaletteSnapshot(val pots: List<Pot>, val mixture: Recipe, val load: BrushLoad)

    /**
     * [onLayer] is the layer the undone step was on — each layer keeps its own
     * boundary, so moving the wrong one would leave that layer's paint and its
     * count disagreeing.
     */
    fun recordUndo(onLayer: String) = scope.launch {
        isDirty = true
        lock.withLock { flushNow(); repo.undo(onLayer.ifEmpty { layerId }) }
    }

    fun recordRedo(onLayer: String) = scope.launch {
        isDirty = true
        lock.withLock { flushNow(); repo.redo(onLayer.ifEmpty { layerId }) }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(DEBOUNCE_MS)
            lock.withLock { flushNow() }
        }
    }

    /** Writes everything queued. Safe to call when there is nothing to do. */
    suspend fun flush() = lock.withLock { flushNow() }

    private suspend fun flushNow() {
        val batch = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        val palette = pendingPalette.also { pendingPalette = null }
        if (batch.isEmpty() && palette == null) return

        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                repo.transaction {
                    batch.forEach(::write)
                    palette?.let(::writePalette)
                }
            }
        }
    }

    private fun writePalette(snapshot: PaletteSnapshot) {
        repo.writePaletteState(OpRows.paletteParams(snapshot.mixture, snapshot.load))
        // The rim is small and changes rarely; rewriting it wholesale is simpler
        // than diffing, and a pot is identified by what it is rather than by an id.
        val existing = repo.pots()
        val wanted = snapshot.pots
        if (existing.size == wanted.size &&
            existing.zip(wanted).all { (row, pot) ->
                row.text == pot.name && row.color == ArgbHex.encode(pot.color)
            }
        ) {
            return
        }
        existing.forEach { repo.removePot(it.id) }
        wanted.forEachIndexed { i, pot -> repo.addPot(pot.name, ArgbHex.encode(pot.color), pot.custom) }
    }

    /**
     * The layer an op is filed under.
     *
     * The op names its own, because by the time this runs the pen may well have
     * moved to a different layer — writes are debounced, and the selection is
     * not. An op from before layers existed names none, and belongs to the one
     * layer such a page has.
     */
    private fun target(op: PaintOp): String = op.layerId.ifEmpty { layerId }

    private fun write(op: PaintOp) {
        when (op) {
            is StrokeOp -> {
                val row = repo.appendOp(target(op), OpRows.strokeRow(op.stroke))
                // The frisket and the wet state are properties of this one stroke,
                // not steps in the history, so they hang off it as children and
                // replay with it.
                op.clip?.let { clip ->
                    maskOf(clip)?.let { repo.attach(row.id, OpRows.clipRow(it, DOWNSAMPLE)) }
                }
                OpRows.wetStateRow(op.stroke)?.let { repo.attach(row.id, it) }
            }

            is FillOp -> maskOf(op.mask)?.let {
                repo.appendOp(target(op), OpRows.fillRow(op.color, it, DOWNSAMPLE))
            }

            is EraseOp -> maskOf(op.mask)?.let {
                repo.appendOp(target(op), OpRows.eraseRow(it, DOWNSAMPLE))
            }

            is MoveOp -> maskOf(op.sourceMask)?.let {
                val matrix = FloatArray(9).also { m -> op.transform.getValues(m) }
                repo.appendOp(target(op), OpRows.moveRow(matrix, it, DOWNSAMPLE))
            }

            // One row on the timeline, with the pasted ops beneath it — so undo
            // takes the whole paste back, which is what a paste is.
            is PasteOp -> {
                val parent = repo.appendOp(target(op), OpRows.pasteRow(op.ops.size))
                op.ops.forEachIndexed { i, child -> writeUnder(parent.id, child, i) }
            }

            // Only the op. The page row holds the surface the page was *created*
            // on and is never rewritten — see SketchbookRepository.resolvedSurface.
            // Caching the current surface there instead looked obviously right and
            // was wrong: undoing a surface change moved the history and left the
            // cached answer behind, so the page reloaded on the wrong paper.
            is SurfaceOp -> repo.appendOp(target(op), OpRows.surfaceRow(op))

            // Filed under the layer they describe, which is also how they find
            // their way back to it on load — an op's parent is its layer.
            is LayerOpacityOp -> repo.appendOp(target(op), OpRows.layerOpacityRow(op))
            is LayerVisibilityOp -> repo.appendOp(target(op), OpRows.layerVisibilityRow(op))
            is LayerAddOp -> repo.appendOp(target(op), OpRows.layerAddRow(op))
            is LayerDeleteOp -> repo.appendOp(target(op), OpRows.layerDeleteRow(op))
            is LayerOrderOp -> repo.appendOp(target(op), OpRows.layerOrderRow(op))
        }
    }

    /**
     * An op stored as somebody else's child rather than as a step of its own.
     *
     * Only a paste does this. A paste of a paste is refused rather than nested:
     * the clipboard flattens on copy, so this cannot arise from the app, and a
     * file that claims otherwise gets one level and no recursion.
     */
    private fun writeUnder(parentId: String, op: PaintOp, order: Int) {
        when (op) {
            is StrokeOp -> {
                val row = repo.attach(parentId, OpRows.strokeRow(op.stroke).copy(order = order))
                op.clip?.let { clip ->
                    maskOf(clip)?.let { repo.attach(row.id, OpRows.clipRow(it, DOWNSAMPLE)) }
                }
                OpRows.wetStateRow(op.stroke)?.let { repo.attach(row.id, it) }
            }

            is FillOp -> maskOf(op.mask)?.let {
                repo.attach(parentId, OpRows.fillRow(op.color, it, DOWNSAMPLE).copy(order = order))
            }

            is EraseOp -> maskOf(op.mask)?.let {
                repo.attach(parentId, OpRows.eraseRow(it, DOWNSAMPLE).copy(order = order))
            }

            // Nothing a paste can contain. The clipboard holds marks, and how a
            // layer composites is not a mark.
            is MoveOp, is PasteOp, is SurfaceOp,
            is LayerOpacityOp, is LayerVisibilityOp,
            is LayerAddOp, is LayerDeleteOp, is LayerOrderOp -> Unit
        }
    }

    /**
     * An empty mask yields null and the op is skipped.
     *
     * That is the right answer rather than a lost edit: a fill or erase over
     * nothing changed nothing, so there is nothing for a replay to do.
     */
    private fun maskOf(bitmap: Bitmap): MaskBitmaps.Cropped? =
        runCatching { MaskBitmaps.encode(bitmap) }.getOrNull()

    /**
     * Page-wide order, rebuilt from what the rows remember.
     *
     * When it was made comes first, because that is the order it was worked in.
     * The rest is only there so the sort is total: two ops from different layers
     * cannot share a moment while there is one pen, and two from the same layer
     * already have a stored sequence.
     */
    private val byWhenMade =
        compareBy<SoilObject>({ it.createdAt }, { it.parentId }, { it.order })

    // --- Layers ---------------------------------------------------------------

    /**
     * Writes a new layer and hands back its id, or null at the ceiling.
     *
     * The row is written before the canvas makes room for it, so a layer that
     * exists on screen always exists on disk — the other way round leaves paint
     * with nowhere to be filed.
     */
    suspend fun addLayer(name: String): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            flushNow()
            if (repo.layers(pageId).size >= Layer.MAX_PER_PAGE) return@withLock null
            isDirty = true
            repo.addLayer(pageId, name).id
        }
    }

    /** Removes a layer and everything filed under it. */
    suspend fun deleteLayer(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            flushNow()
            isDirty = true
            repo.removeLayer(id)
        }
    }

    /** Persists the stack's order, bottom-first. */
    suspend fun recordLayerOrder(bottomFirst: List<String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            isDirty = true
            repo.setLayerOrder(pageId, bottomFirst)
        }
    }

    /** Persists a layer's opacity and visibility — how it composites, not what it holds. */
    suspend fun recordLayerState(id: String, visible: Boolean, opacity: Float) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                isDirty = true
                repo.setLayerState(id, visible, opacity)
            }
        }

    // --- Loading ------------------------------------------------------------

    /**
     * Everything needed to put this page back on the canvas.
     *
     * One read of the layer's ops, one batched read of their children, and — if
     * it is still current — the composited raster, so the common case is a decode
     * rather than a replay.
     */
    class PageSnapshot(
        val canvasSize: CanvasSize,
        val surface: SurfaceOp,
        val surfaceSeed: Long?,
        val committed: List<PaintOp>,
        val undone: List<PaintOp>,
        val cachedPaint: Bitmap?,
        val pots: List<Pot>,
        val mixture: Recipe,
        val load: BrushLoad,
        /** The stack, bottom-first. Never empty. */
        val layers: List<Layer> = emptyList(),
        /** Index into [layers] of the one to paint on. */
        val activeLayer: Int = 0,
    )

    suspend fun load(): PageSnapshot = withContext(Dispatchers.IO) {
        lock.withLock {
            val page = repo.pages().firstOrNull { it.id == pageId }

            // The surface a page is *on* is the last committed surface change,
            // falling back to the one it was created with. Never a cached answer —
            // an undo moves the history and would leave a cache behind.
            val resolved = repo.resolvedSurface(pageId)
            val surface = resolved?.let(OpRows::readSurfaceOp)
                ?: SurfaceOp(SurfaceKind.PAPER, 0xFFFFFFFF.toInt())

            // The whole stack, bottom-first, and every layer's timeline with it.
            val layerRows = repo.layers(pageId)
            val stack = layerRows.map { row ->
                Layer(
                    id = row.id,
                    name = row.text ?: Layer.DEFAULT_NAME,
                    visible = (row.flags ?: SoilFlags.LAYER_DEFAULT) and SoilFlags.LAYER_VISIBLE != 0,
                    opacity = row.opacity ?: 1f,
                )
            }

            // Each layer keeps its own sequence; the page has none. Ordering every
            // layer's ops by when they were made puts the timeline back the way it
            // was worked, and since one pen can only be on one layer at a time,
            // two ops from different layers cannot share a moment. Within a layer
            // the stored order settles any that do.
            val perLayer = layerRows.associate { it.id to repo.committedOps(it.id) }
            val perLayerUndone = layerRows.associate { it.id to repo.redoableOps(it.id) }
            val committedRows = perLayer.values.flatten().sortedWith(byWhenMade)
            val undoneRows = perLayerUndone.values.flatten().sortedWith(byWhenMade)

            val attachments = repo.attachmentsOf((committedRows + undoneRows).map { it.id })
            // A paste's children are ops, which have children of their own — one
            // level deeper than anything else on the timeline. Fetched only when
            // there is a paste to fetch it for, so an ordinary page still loads in
            // two queries.
            val nested = if (attachments.any { it.type in SoilType.OPS }) {
                repo.attachmentsOf(attachments.map { it.id })
            } else {
                emptyList()
            }
            val children = (attachments + nested).groupBy { it.parentId }

            // An op's layer is its parent. Tagged on the way in, because from here
            // on the canvas folds each layer from its own ops and nothing else can
            // say which those are.
            fun rebuild(rows: List<SoilObject>) =
                rows.mapNotNull { row ->
                    OpRows.readOp(row) { id -> children[id].orEmpty() }
                        ?.also { it.layerId = row.parentId }
                }

            PageSnapshot(
                // The size is the book's, not the page's: a sketchbook is bought
                // at one size and every page in it shares that.
                canvasSize = repo.root()?.let(Sketchbooks::canvasSizeOfRoot) ?: CanvasSize.FullScreen,
                surface = surface,
                surfaceSeed = page?.seed,
                committed = rebuild(committedRows),
                undone = rebuild(undoneRows),
                cachedPaint = decodeCache(),
                pots = repo.pots().map {
                    Pot(
                        name = it.text.orEmpty(),
                        color = ArgbHex.decode(it.color, 0),
                        custom = it.hasFlag(SoilFlags.POT_CUSTOM),
                    )
                },
                mixture = OpRows.readMixture(repo.paletteState()),
                load = OpRows.readLoad(repo.paletteState()),
                layers = stack,
                // Which layer was last painted on is not something the file
                // records, so the topmost one that can be painted on wins — the
                // nearest sheet is the one a hand reaches for.
                activeLayer = stack.indexOfLast { it.visible }.coerceAtLeast(0),
            )
        }
    }

    /** The cache, only when it describes the history as it currently stands. */
    private fun decodeCache(): Bitmap? {
        val row = repo.cache(layerId) ?: return null
        val bytes = row.blob ?: return null
        // Bounded, and genuinely so — see [BoundedDecode]. These bytes are as
        // much "a file somebody sent" as any other since import shipped, and null
        // here costs a replay rather than an out-of-memory kill on page open.
        return BoundedDecode.full(bytes, mutable = true)
    }

    /**
     * Stores the composited paint so the next open is a decode.
     *
     * Written at the frontier it was composited at, and read back only while that
     * still matches — so an undo makes it stale rather than wrong.
     */
    suspend fun writeCache(paint: Bitmap) = lock.withLock { writeCacheLocked(paint) }

    private suspend fun writeCacheLocked(paint: Bitmap) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    // Lossless: paint pixels are dense and user-authored, and a
                    // lossy round-trip on every save is not something to do to
                    // somebody's painting. Phase 25 measures PNG against raw+zlib.
                    paint.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                repo.writeCache(layerId, bytes, paint.width.toFloat(), paint.height.toFloat())
            }
        }
    }

    // --- The clipboard ------------------------------------------------------

    /**
     * Copies the ops wholly inside [selection] onto the clipboard.
     *
     * Whole ops, not pixels — see [Clipboard]. [selection] is the live selection
     * mask, and [maskScale] how many buffer pixels one of its pixels covers; the
     * enclosure test happens in buffer coordinates because that is what the rows
     * are in.
     *
     * Returns how many marks were taken, so the caller can say so: a copy that
     * silently took nothing (because every stroke crossed the edge) is otherwise
     * indistinguishable from one that worked.
     */
    suspend fun copySelection(selection: Bitmap, maskScale: Float): Int =
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching { flushNow() }
                val bounds = maskBounds(selection, maskScale) ?: return@withLock 0
                val inside = sampler(selection, maskScale)

                val rows = repo.committedOps(layerId)
                val children = repo.attachmentsOf(rows.map { it.id })
                // An earlier paste is opened up rather than treated as one mark:
                // the clipboard stays one level deep, and lassoing half of what
                // you pasted copies that half.
                val candidates = rows.flatMap { row ->
                    if (row.type == SoilType.PASTE) children.filter { it.parentId == row.id } else listOf(row)
                }
                val taken = candidates.filter { row ->
                    // A move is deliberately not copyable: it lifts whatever paint
                    // is under it *at replay time*, so pasted into another book it
                    // would move that book's paint, not the mark it was copied for.
                    row.type != SoilType.MOVE &&
                        Enclosure.shapeOf(row)?.let { Enclosure.encloses(it, bounds, inside) } == true
                }
                if (taken.isEmpty()) return@withLock 0

                val subtree = taken + repo.attachmentsOf(taken.map { it.id })
                Clipboard.replace(subtree, taken.map { it.id }, documentId)
                taken.size
            }
        }

    /**
     * The clipboard's contents, as ops this page can replay. Empty when there is
     * nothing to paste, or when nothing on it could be read.
     */
    suspend fun clipboardOps(): List<PaintOp> = withContext(Dispatchers.IO) {
        val rows = Clipboard.contents()
        val children = rows.groupBy { it.parentId }
        rows.filter { it.parentId == Sentinels.CLIPBOARD_ROOT_ID }
            .sortedBy { it.order }
            .mapNotNull { row -> OpRows.readOp(row) { id -> children[id].orEmpty() } }
    }

    suspend fun clipboardCount(): Int = runCatching { Clipboard.summary().count }.getOrDefault(0)

    /** The selection's extent in buffer pixels, or null when nothing is selected. */
    private fun maskBounds(mask: Bitmap, scale: Float): Enclosure.Box? {
        val cropped = runCatching { MaskBitmaps.encode(mask) }.getOrNull() ?: return null
        return Enclosure.Box(
            cropped.left * scale,
            cropped.top * scale,
            (cropped.left + cropped.mask.width) * scale,
            (cropped.top + cropped.mask.height) * scale,
        )
    }

    /** Reads the mask once, so the enclosure test is not a bitmap call per point. */
    private fun sampler(mask: Bitmap, scale: Float): (Float, Float) -> Boolean {
        val w = mask.width
        val h = mask.height
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        return { x, y ->
            val mx = (x / scale).toInt()
            val my = (y / scale).toInt()
            mx in 0 until w && my in 0 until h && (pixels[my * w + mx] ushr 24) > COVERAGE_FLOOR
        }
    }

    // --- Pages --------------------------------------------------------------

    /** One page, as the page strip needs it. */
    class PageInfo(val id: String, val index: Int, val isCurrent: Boolean, val thumbnail: Bitmap?)

    suspend fun pages(): List<PageInfo> = withContext(Dispatchers.IO) {
        lock.withLock {
            repo.pages().mapIndexed { i, page ->
                val layer = repo.contentLayer(page.id)
                PageInfo(
                    id = page.id,
                    index = i,
                    isCurrent = page.id == pageId,
                    thumbnail = layer?.let { decodeThumbnail(repo.cacheRow(it.id)?.blob, THUMBNAIL_EDGE) },
                )
            }
        }
    }

    /**
     * Puts the current page down and picks another one up.
     *
     * The order matters: everything pending is written and the composited pixels
     * are cached *before* the page changes, because after it changes there is no
     * longer anything holding the old page's paint.
     */
    suspend fun switchTo(newPageId: String, paint: Bitmap?): PageSnapshot? {
        if (newPageId == pageId) return null
        withContext(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                runCatching { flushNow() }
                paint?.let { runCatching { writeCacheLocked(it) } }
                val layer = repo.contentLayer(newPageId) ?: repo.addLayer(newPageId)
                pageId = newPageId
                layerId = layer.id
                repo.setLastOpenedPage(newPageId)
            }
        }
        return load()
    }

    suspend fun addPage(surface: SurfaceOp, seed: Long, paint: Bitmap?): PageSnapshot? {
        val created = withContext(Dispatchers.IO) {
            lock.withLock {
                isDirty = true
                repo.addPage(
                    surfaceKind = surface.kind.name,
                    plainColor = ArgbHex.encode(surface.plainColor),
                    surfaceSeed = seed,
                    surfaceParams = SurfaceParamsCodec.encode(
                        canvas = surface.canvas, watercolor = surface.watercolor, wood = surface.wood,
                        stone = surface.stone, concrete = surface.concrete, metal = surface.metal,
                        chalkboard = surface.chalkboard,
                    ),
                ).id
            }
        }
        return switchTo(created, paint)
    }

    suspend fun duplicatePage(id: String, paint: Bitmap?): PageSnapshot? {
        val copy = withContext(Dispatchers.IO) {
            lock.withLock {
                isDirty = true
                runCatching { flushNow() }
                paint?.let { runCatching { writeCacheLocked(it) } }
                repo.duplicatePage(id)?.id
            }
        } ?: return null
        return switchTo(copy, paint = null)
    }

    /**
     * Deletes a page and lands on a neighbour.
     *
     * The last page is never deleted — a sketchbook with no pages has nothing to
     * open, and "delete then immediately create a blank one" is a worse answer
     * than simply refusing.
     */
    suspend fun deletePage(id: String): PageSnapshot? {
        val next = withContext(Dispatchers.IO) {
            lock.withLock {
                val pages = repo.pages()
                if (pages.size <= 1) return@withLock null
                val index = pages.indexOfFirst { it.id == id }
                if (index < 0) return@withLock null
                isDirty = true
                repo.deletePage(id)
                (pages.getOrNull(index + 1) ?: pages.getOrNull(index - 1))?.id
            }
        } ?: return null
        // The current page may have been the one removed, in which case the
        // canvas has to be handed its neighbour rather than left showing a ghost.
        return if (id == pageId) switchTo(next, paint = null) else load()
    }

    suspend fun movePage(id: String, toIndex: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            isDirty = true
            repo.movePage(id, toIndex)
        }
    }

    /**
     * A cached page, small enough for a strip of thumbnails.
     *
     * Decoded full size and reduced here, rather than by `inSampleSize`.
     * `inSampleSize` **subsamples**: it keeps one row in every *n* and discards
     * the rest, so a pen line one pixel wide survives only if it happens to fall
     * on a kept row. Measured on device with a seven-inch page: at the sample
     * factor a 240-pixel thumbnail wants, alternate pages of identical line art
     * came back completely empty. A page strip that shows blank paper for a page
     * you drew on is worse than no strip at all.
     *
     * Halving with filtering averages four pixels into one at every pass, so a
     * hairline arrives faint instead of missing — see [ThumbnailPlan], which owns
     * that schedule. One full-size bitmap exists at a time and is recycled before
     * the next page is read; the reason the cheap path was chosen originally was
     * memory, and this keeps that.
     */
    private fun decodeThumbnail(bytes: ByteArray?, maxEdge: Int): Bitmap? {
        if (bytes == null) return null
        return runCatching {
            var current = BoundedDecode.full(bytes) ?: return@runCatching null
            for (step in ThumbnailPlan.steps(current.width, current.height, maxEdge)) {
                current = current.scaledTo(step.width, step.height)
            }
            current
        }.getOrNull()
    }

    /** Scales and releases the source, which is always an intermediate here. */
    private fun Bitmap.scaledTo(width: Int, height: Int): Bitmap =
        Bitmap.createScaledBitmap(this, width, height, true).also { if (it !== this) recycle() }

    // --- Page bookkeeping ---------------------------------------------------

    fun setPageSize(width: Float, height: Float) = scope.launch {
        lock.withLock { repo.setPageSize(pageId, width, height) }
    }

    fun setSurfaceSeed(seed: Long) = scope.launch {
        lock.withLock {
            val page = repo.pages().firstOrNull { it.id == pageId } ?: return@withLock
            repo.setInitialSurface(pageId, page.kind, page.color, seed, Params.decode(page.params))
        }
    }

    // --- Closing ------------------------------------------------------------

    /**
     * Everything that has to happen before the document goes cold.
     *
     * The parts that are the same wherever it lives are here — cancel the
     * debounce, flush what is queued, cache the composited pixels — and the parts
     * that are not are [DocumentHome]'s: a sketchbook seals its file and refreshes
     * its library card, the scratchpad does neither.
     *
     * Run this from an application-scoped, non-cancellable coroutine: the screen
     * going away is precisely when it needs to survive.
     *
     * [cover] and [paint] are captured by the caller on the main thread, while
     * those bitmaps are still guaranteed to be alive.
     */
    suspend fun close(paint: Bitmap? = null, cover: Bitmap? = null) {
        flushJob?.cancel()
        lock.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { flushNow() }
                if (isDirty) paint?.let { p -> runCatching { writeCacheLocked(p) } }
                runCatching { home.close(isDirty, cover, repo.pageCount()) }
            }
            isDirty = false
        }
    }

    companion object {

        /** Long enough to batch a burst of strokes; short enough to be invisible. */
        const val DEBOUNCE_MS = 300L

        /** Page-strip thumbnails; small enough that ten of them cost nothing. */
        const val THUMBNAIL_EDGE = 240

        /**
         * How much of a mask pixel counts as selected, when deciding whether a
         * mark is inside it. Above zero because a lasso's edge is antialiased and
         * its outermost pixels are a few percent covered — a stroke sitting on
         * that fringe is on the line the user drew, not inside it.
         */
        const val COVERAGE_FLOOR = 24

        /**
         * Masks are captured at half resolution and stretched at paint time, so
         * the factor travels with each one rather than being assumed by whoever
         * reads it back.
         */
        private val DOWNSAMPLE = WandFloodFill.DOWNSAMPLE.toFloat()

        /**
         * Opens the given document, or creates one when there is nothing to open.
         *
         * The default-document path is temporary scaffolding: until the library
         * screen exists, the editor still has to be editing *something*, and a
         * document that creates itself on first paint is better than an editor
         * that silently discards work.
         */
        /**
         * Opens what the pointer names, or **nothing**.
         *
         * Null means "there is nothing to edit", and the caller's answer to that
         * is the library — not a new document. It used to be a new document: the
         * editor predated the library and had to be editing *something*, so a
         * pointer that no longer resolved created a book. Found on device long
         * after that stopped being true, by deleting the book that was last open:
         * the next launch minted an empty "Sketchbook" nobody asked for.
         *
         * Both halves are checked before anything opens either — the index row
         * *and* the file — because the open underneath is create-capable, and a
         * stale pointer handed to it fabricates the ghost it was looking for.
         */
        suspend fun openExisting(context: Context, documentId: String?): DocumentSession? =
            withContext(Dispatchers.IO) {
                val index = IndexGate.awaitReady()
                val existing = documentId
                    ?.takeIf { SoilFiles.isDocumentId(it) }
                    ?.takeIf {
                        index.byId(it)?.isAlive == true && SoilFiles.soilFile(context, it).exists()
                    }
                existing?.let { open(context, it) }
            }

        private suspend fun open(context: Context, id: String): DocumentSession {
            // A private-passphrase document is opened under its own scope, which
            // means "use the key already derived in RAM, and never a cached one
            // from disk". The library primes that when it takes the passphrase;
            // arriving here without it simply fails to open, which is the correct
            // outcome for a locked document nobody has unlocked.
            val scope = if (IndexGate.awaitReady().byId(id)?.isPrivateScope == true) {
                KeyScope.SKETCHBOOK
            } else {
                KeyScope.GLOBAL
            }
            val soil = SketchbookStore.open(context, id, keyScope = scope)
            val repo = Sketchbooks.repositoryFor(soil, id)
            // A document that somehow has no page is still openable; give it one
            // rather than failing in front of the user.
            if (repo.pages().isEmpty()) repo.createDocument(title = repo.root()?.text ?: "")
            IndexGate.awaitReady().recordOpened(id)
            // Upkeep on the way in as well as on the way out. A document renamed
            // or moved in the library carries a stale record until the file is
            // next open, and this is that moment — so a crash before the seal
            // still leaves the embedded name current.
            runCatching { soil.writeMeta(MetaUpkeep.from(id).invoke(soil.readMeta() ?: return@runCatching)) }
            return on(SoilHome(id, soil), repo)
        }

        /**
         * A session over an already-prepared repository, landing on the page it
         * was left on. The scratchpad builds itself this way too — same page
         * resolution, same "a stale pointer means the first page" rule.
         */
        internal fun on(home: DocumentHome, repo: SketchbookRepository): DocumentSession {
            val page = repo.lastOpenedPage() ?: repo.pages().first()
            val layer = repo.contentLayer(page.id) ?: repo.addLayer(page.id)
            repo.setLastOpenedPage(page.id)
            return DocumentSession(home, repo, page.id, layer.id)
        }
    }
}
