package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.symmetricalpalmtree.paintsprout.paint.BrushLoad
import com.symmetricalpalmtree.paintsprout.paint.CanvasSize
import com.symmetricalpalmtree.paintsprout.paint.EraseOp
import com.symmetricalpalmtree.paintsprout.paint.FillOp
import com.symmetricalpalmtree.paintsprout.paint.MoveOp
import com.symmetricalpalmtree.paintsprout.paint.PaintOp
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
    val documentId: String,
    private val soil: SoilDatabase,
    val repo: SketchbookRepository,
    val pageId: String,
    val layerId: String,
) {

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

    fun recordUndo() = scope.launch {
        isDirty = true
        lock.withLock { flushNow(); repo.undo(layerId) }
    }

    fun recordRedo() = scope.launch {
        isDirty = true
        lock.withLock { flushNow(); repo.redo(layerId) }
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

    private fun write(op: PaintOp) {
        when (op) {
            is StrokeOp -> {
                val row = repo.appendOp(layerId, OpRows.strokeRow(op.stroke))
                // The frisket and the wet state are properties of this one stroke,
                // not steps in the history, so they hang off it as children and
                // replay with it.
                op.clip?.let { clip ->
                    maskOf(clip)?.let { repo.attach(row.id, OpRows.clipRow(it, DOWNSAMPLE)) }
                }
                OpRows.wetStateRow(op.stroke)?.let { repo.attach(row.id, it) }
            }

            is FillOp -> maskOf(op.mask)?.let {
                repo.appendOp(layerId, OpRows.fillRow(op.color, it, DOWNSAMPLE))
            }

            is EraseOp -> maskOf(op.mask)?.let {
                repo.appendOp(layerId, OpRows.eraseRow(it, DOWNSAMPLE))
            }

            is MoveOp -> maskOf(op.sourceMask)?.let {
                val matrix = FloatArray(9).also { m -> op.transform.getValues(m) }
                repo.appendOp(layerId, OpRows.moveRow(matrix, it, DOWNSAMPLE))
            }

            // Only the op. The page row holds the surface the page was *created*
            // on and is never rewritten — see SketchbookRepository.resolvedSurface.
            // Caching the current surface there instead looked obviously right and
            // was wrong: undoing a surface change moved the history and left the
            // cached answer behind, so the page reloaded on the wrong paper.
            is SurfaceOp -> repo.appendOp(layerId, OpRows.surfaceRow(op))
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

            val committedRows = repo.committedOps(layerId)
            val undoneRows = repo.redoableOps(layerId)
            val attachments = repo.attachmentsOf((committedRows + undoneRows).map { it.id })
                .groupBy { it.parentId }

            fun rebuild(rows: List<SoilObject>) =
                rows.mapNotNull { OpRows.readOp(it, attachments[it.id].orEmpty()) }

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
            )
        }
    }

    /** The cache, only when it describes the history as it currently stands. */
    private fun decodeCache(): Bitmap? {
        val row = repo.cache(layerId) ?: return null
        val bytes = row.blob ?: return null
        // Bounded decode: these bytes are as much "a file on disk" as any other,
        // and a hostile or merely enormous one must not be an OOM on open.
        val options = BitmapFactory.Options().apply { inMutable = true }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
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
     * Everything that has to happen before the file goes cold.
     *
     * **Each step is guarded on its own.** A disk-full failure seconds after the
     * user left the page must not crash the app, and — more importantly — must not
     * stop the steps after it from running. Skipping the checkpoint because the
     * cover failed to write would leave a `-wal` beside the document forever.
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
                if (isDirty) {
                    paint?.let { p -> runCatching { writeCacheLocked(p) } }
                    runCatching { refreshIndexRow(cover) }
                }
                // The seal proper: refresh the embedded identity record, vacuum,
                // truncate the WAL, close, and leave no sidecars behind.
                runCatching { soil.seal { it.copy(updatedAt = System.currentTimeMillis()) } }
            }
            isDirty = false
        }
    }

    /**
     * What the library needs to know about a closed document.
     *
     * Only when something changed — see [isDirty]. The cover is offered rather
     * than stored: [IndexRepository.setCover] refuses it for a document with its
     * own passphrase, because the index opens with the *global* key and that is a
     * key boundary a picture of the contents must not cross.
     */
    private suspend fun refreshIndexRow(cover: Bitmap?) {
        val index = IndexGate.awaitReady()
        index.setPageCount(documentId, repo.pageCount())
        index.recordEdited(documentId)
        if (cover != null) {
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                cover.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                out.toByteArray()
            }
            index.setCover(documentId, bytes)
        }
    }

    companion object {

        /** Long enough to batch a burst of strokes; short enough to be invisible. */
        const val DEBOUNCE_MS = 300L

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
        suspend fun openOrCreate(
            context: Context,
            documentId: String?,
            defaultName: String = "Sketchbook",
            surface: SurfaceKind = SurfaceKind.PAPER,
        ): DocumentSession = withContext(Dispatchers.IO) {
            val index = IndexGate.awaitReady()
            val existing = documentId
                ?.takeIf { SoilFiles.isDocumentId(it) }
                // Both the row and the file, before anything opens either: a stale
                // pointer handed to a create-capable open mints an empty ghost.
                ?.takeIf { index.byId(it) != null && SoilFiles.soilFile(context, it).exists() }

            if (existing != null) open(context, existing) else create(context, defaultName, surface)
        }

        /** Creates the sketchbook, then opens it — one construction path, not two. */
        private suspend fun create(
            context: Context,
            name: String,
            surface: SurfaceKind,
        ): DocumentSession = open(context, Sketchbooks.create(context, name, surface = surface).id)

        private suspend fun open(context: Context, id: String): DocumentSession {
            val soil = SketchbookStore.open(context, id)
            val repo = Sketchbooks.repositoryFor(soil, id)
            // A document that somehow has no page is still openable; give it one
            // rather than failing in front of the user.
            if (repo.pages().isEmpty()) repo.createDocument(title = repo.root()?.text ?: "")
            IndexGate.awaitReady().recordOpened(id)
            return sessionFor(id, soil, repo)
        }

        private fun sessionFor(
            id: String,
            soil: SoilDatabase,
            repo: SketchbookRepository,
        ): DocumentSession {
            val page = repo.lastOpenedPage() ?: repo.pages().first()
            val layer = repo.contentLayer(page.id) ?: repo.addLayer(page.id)
            repo.setLastOpenedPage(page.id)
            return DocumentSession(id, soil, repo, page.id, layer.id)
        }
    }
}
