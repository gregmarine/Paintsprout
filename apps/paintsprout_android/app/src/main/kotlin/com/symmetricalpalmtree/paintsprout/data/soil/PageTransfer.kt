package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.IndexObject
import com.symmetricalpalmtree.paintsprout.paint.CanvasSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Sending a whole page somewhere else.
 *
 * A page is a subtree, and both ends of every transfer are a
 * [SketchbookRepository] — so this is a read from one and a write to another,
 * and nothing more. The scratchpad is a repository over the index's own table
 * and a sketchbook is one over its file; the direction of travel changes which
 * is which and changes nothing else. That is the payoff for Phase 9's decision
 * to parameterise the repository rather than write a second one.
 *
 * What travels is everything: the layers, the ops, their attachments, the
 * layer's undo frontier, and the page's own paper — the surface a page was
 * created on is a column on the page row, and every later surface change is an
 * op in the sequence, so both come along without being handled.
 *
 * What does *not* travel is size. Marks keep their coordinates, because those
 * coordinates are millimetres on a calibrated screen; a page sent into a smaller
 * book keeps the size it was drawn at and may run off the sheet, which is the
 * honest outcome rather than silently rescaling somebody's drawing.
 */
object PageTransfer {

    /** Copies [pageId] out of [from] and onto the end of the scratchpad. */
    suspend fun toScratchpad(from: SketchbookRepository, pageId: String): Boolean =
        withContext(Dispatchers.IO) {
            val rows = from.pageSubtree(pageId)
            if (rows.isEmpty()) return@withContext false
            Scratchpad.repository().insertPage(rows, pageId) != null
        }

    /**
     * Copies [pageId] out of [from] and onto the end of the sketchbook [targetId].
     *
     * The target's file is opened, written and **sealed here** — it is not the
     * document the editor has open, and a file left with a `-wal` beside it is a
     * file the user sees in their file browser as three.
     */
    suspend fun toSketchbook(
        context: Context,
        from: SketchbookRepository,
        pageId: String,
        targetId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val rows = from.pageSubtree(pageId)
        if (rows.isEmpty()) return@withContext false
        // Refused rather than risked: writing into a document somebody else has
        // open means two connections disagreeing about what is in it.
        if (OpenDocuments.isOpen(targetId)) {
            throw IOException("Refusing to send a page into a sketchbook that is open: $targetId")
        }
        val soil = SketchbookStore.open(context, targetId)
        val pages = try {
            val target = Sketchbooks.repositoryFor(soil, targetId)
            if (target.insertPage(rows, pageId) == null) return@withContext false
            target.pageCount()
        } finally {
            runCatching { soil.seal() }
        }
        IndexGate.awaitReady().setPageCount(targetId, pages)
        true
    }

    /**
     * A new sketchbook made to hold this page, and holding *only* it.
     *
     * Creating a book mints a first page, which here is an empty sheet nobody
     * asked for — so it is removed once the sent page has landed beside it. The
     * new book takes the source's canvas size, because a page arriving at a
     * different size than it was drawn is the one thing a transfer must not do.
     */
    suspend fun toNewSketchbook(
        context: Context,
        from: SketchbookRepository,
        pageId: String,
        name: String,
        canvasSize: CanvasSize,
    ): IndexObject? = withContext(Dispatchers.IO) {
        val rows = from.pageSubtree(pageId)
        if (rows.isEmpty()) return@withContext null
        val book = Sketchbooks.create(context, name, canvasSize = canvasSize)
        val soil = SketchbookStore.open(context, book.id)
        val pages = try {
            val target = Sketchbooks.repositoryFor(soil, book.id)
            val blank = target.pages().singleOrNull()?.id
            val landed = target.insertPage(rows, pageId) ?: return@withContext null
            // Only once the page is safely in: a book that lost its only page to
            // a transfer that then failed would have nothing to open.
            blank?.let { target.deletePage(it) }
            target.setLastOpenedPage(landed.id)
            target.pageCount()
        } finally {
            runCatching { soil.seal() }
        }
        IndexGate.awaitReady().setPageCount(book.id, pages)
        book
    }
}
