package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import com.symmetricalpalmtree.paintsprout.paint.PaintOp
import com.symmetricalpalmtree.paintsprout.paint.StrokeOp
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

    /**
     * Set when an op arrives that this build cannot yet persist.
     *
     * Once true, nothing further is written for this document. A file that is
     * *visibly* missing its tail is recoverable; a file whose op sequence has a
     * hole in the middle claims to be a painting it is not, and would replay as
     * one. Phase 11 handles the remaining op types and this stops being reachable.
     */
    var desynced: Boolean = false
        private set

    // --- Recording ----------------------------------------------------------

    fun record(op: PaintOp) {
        if (desynced) return
        if (!canPersist(op)) {
            desynced = true
            return
        }
        synchronized(pending) { pending.addLast(op) }
        scheduleFlush()
    }

    fun recordUndo() = scope.launch { lock.withLock { flushNow(); repo.undo(layerId) } }

    fun recordRedo() = scope.launch { lock.withLock { flushNow(); repo.redo(layerId) } }

    private fun canPersist(op: PaintOp): Boolean = op is StrokeOp || op is SurfaceOp

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
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                repo.transaction {
                    batch.forEach(::write)
                }
            }
        }
    }

    private fun write(op: PaintOp) {
        when (op) {
            is StrokeOp -> repo.appendOp(layerId, OpRows.strokeRow(op.stroke))
            // Only the op. The page row holds the surface the page was *created*
            // on and is never rewritten — see SketchbookRepository.resolvedSurface.
            // Caching the current surface there instead looked obviously right and
            // was wrong: undoing a surface change moved the history and left the
            // cached answer behind, so the page reloaded on the wrong paper.
            is SurfaceOp -> repo.appendOp(layerId, OpRows.surfaceRow(op))
            else -> Unit // unreachable: canPersist gates this
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

    /** Flushes, seals the file, and lets go of it. */
    suspend fun close() {
        flushJob?.cancel()
        lock.withLock {
            flushNow()
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { soil.seal { it.copy(updatedAt = System.currentTimeMillis()) } }
            }
        }
    }

    companion object {

        /** Long enough to batch a burst of strokes; short enough to be invisible. */
        const val DEBOUNCE_MS = 300L

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

        private suspend fun create(
            context: Context,
            name: String,
            surface: SurfaceKind,
        ): DocumentSession {
            val index = IndexGate.awaitReady()
            val id = UUID.randomUUID().toString()
            val soil = SketchbookStore.create(context, name, documentId = id)
            val repo = repositoryFor(soil, id)
            repo.createDocument(
                title = name,
                surfaceKind = surface.name,
                surfaceSeed = java.util.Random().nextLong(),
            )
            index.createSketchbook(name = name, id = id, keyScope = KeyScope.GLOBAL)
            index.setPageCount(id, repo.pageCount())
            return sessionFor(id, soil, repo)
        }

        private suspend fun open(context: Context, id: String): DocumentSession {
            val soil = SketchbookStore.open(context, id)
            val repo = repositoryFor(soil, id)
            // A document that somehow has no page is still openable; give it one
            // rather than failing in front of the user.
            if (repo.pages().isEmpty()) repo.createDocument(title = repo.root()?.text ?: "")
            IndexGate.awaitReady().recordOpened(id)
            return sessionFor(id, soil, repo)
        }

        private fun repositoryFor(soil: SoilDatabase, id: String) = SketchbookRepository(
            store = ObjectTable(soil.db, SchemaSql.SKETCHBOOK_TABLE),
            rootId = id,
        )

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
