package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeySession
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilDatabase
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilSchema
import com.symmetricalpalmtree.paintsproutonyx.data.soilFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SketchbookSession"

/**
 * One open sketchbook, for as long as its screen is on top.
 *
 * The screen above it knows about marks and buttons; this knows about the file. Everything that
 * touches the `.soil` goes through here, and everything that *writes* to it goes through the one
 * [SoilWriter] this owns — see that file for why the order of writes is the thing that matters.
 *
 * **Opened once, closed once.** The database is expensive to open (a quarter of a million rounds of
 * key derivation sit behind it, cached but not free) and holding one open across a screen is what
 * lets a mark reach the disk in the time between two pen strokes. The cost of that is that closing
 * has to be deliberate: [close] drains the queue before it seals the file, because the marks still
 * in that queue are the last ones the artist drew and the ones they will most expect to find.
 *
 * Arc 1 has exactly one page per sketchbook — [pageId] is read once at open and does not move.
 * Turning pages is G4, and this is where the page id it turns will live.
 */
class SketchbookSession private constructor(
    private val db: SoilDatabase,
    private val file: File,
    val sketchbookId: String,
    val title: String,
    val pageId: String,
    val pageWidth: Int,
    val pageHeight: Int,
    val pageCount: Int,
    scope: CoroutineScope,
) {

    private val writer = SoilWriter(scope)
    private val dao = db.dao()

    /**
     * Every mark on the page, in the order it was laid down.
     *
     * Order is not decoration: marks are opaque and stack, so reading them out of order puts an
     * older mark on top of a newer one. A row that will not decode is dropped with a line in the log
     * and the rest of the page still opens — a mark that cannot be read is one lost mark, and
     * refusing to open the page over it would be losing all of them.
     */
    suspend fun loadMarks(): List<Stroke> = withContext(Dispatchers.IO) {
        val rows = dao.childrenOfType(pageId, SoilSchema.TYPE_MARK)
        val out = ArrayList<Stroke>(rows.size)
        var dropped = 0
        for (row in rows) {
            val stroke = try {
                MarkRows.toStroke(row)
            } catch (e: Exception) {
                Log.e(TAG, "mark ${row.id} could not be read and was left off the page", e)
                null
            }
            if (stroke == null) dropped++ else out.add(stroke)
        }
        if (dropped > 0) Log.w(TAG, "$dropped of ${rows.size} marks on page $pageId did not open")
        out
    }

    /**
     * A mark the pen just finished, on its way to the file.
     *
     * The stacking position is read from the table at write time rather than counted in memory,
     * because the table is what the next reload will trust. It counts erased rows too, so a mark
     * drawn after an erase can never be handed the position the erased one still holds — two marks
     * claiming one position is a stacking order decided by whichever way SQLite broke the tie.
     */
    fun recordMark(stroke: Stroke) {
        writer.submit {
            val order = dao.maxOrder(pageId, SoilSchema.TYPE_MARK) + 1
            val now = System.currentTimeMillis()
            dao.upsert(MarkRows.toRow(stroke, pageId, order, now))
            touchSketchbook(now)
        }
    }

    /** Marks the eraser swept. Stamped, never removed — see `SoilDao`. */
    fun recordErase(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.submit {
            val now = System.currentTimeMillis()
            dao.softDelete(ids, now)
            touchSketchbook(now)
        }
    }

    private suspend fun touchSketchbook(now: Long) {
        dao.touch(sketchbookId, now)
    }

    /**
     * Close the file, and do not lose the last thing that was drawn on it.
     *
     * The drain is the whole method. Everything else here is sealing a database; the drain is the
     * difference between a page that reopens as it was left and one that is missing whatever the
     * hand did in the last second before the screen went away.
     */
    suspend fun close() {
        writer.drain()
        writer.close()
        withContext(Dispatchers.IO) {
            runCatching { db.seal(file) }
                .onFailure { Log.w(TAG, "sealing ${file.name} failed", it) }
        }
    }

    companion object {

        /**
         * Open a sketchbook by id, or return null when there is nothing openable there.
         *
         * Null rather than an exception for the ordinary misses — a card pointing at a file that is
         * not there, a file with no sketchbook row — because those are things the screen has to say
         * something calm about, not crash over. A wrong key or a damaged file throws from inside
         * [SoilDatabase.open], which is the layer that refuses to delete anything it cannot read.
         */
        /**
         * [scope] owns the write queue, and it must **outlive the screen**. Handed an Activity's
         * own scope, the queue's pump is cancelled the instant `onDestroy` returns — which is
         * exactly when [close] is trying to drain it, so the last marks drawn would be thrown away
         * by the very call written to save them. The application scope is the only correct answer.
         */
        suspend fun open(
            context: Context,
            sketchbookId: String,
            scope: CoroutineScope,
        ): SketchbookSession? = withContext(Dispatchers.IO) {
            val passphrase = KeySession.get() ?: return@withContext null
            val file = soilFile(context, sketchbookId)
            if (!file.exists()) {
                Log.w(TAG, "no file in the Garden for $sketchbookId")
                return@withContext null
            }
            val db = SoilDatabase.open(context, sketchbookId, file, passphrase)
            val dao = db.dao()
            val book = dao.sketchbookRow()
            if (book == null) {
                Log.e(TAG, "$sketchbookId has no sketchbook row — not a sketchbook this app made")
                runCatching { db.seal(file) }
                return@withContext null
            }
            val pages = dao.childrenOfType(sketchbookId, SoilSchema.TYPE_PAGE)
            // The sketchbook row points at the page that was open when it was last closed; if that
            // page is gone — or this is a file from before there was a pointer — fall to the first
            // one rather than opening onto nothing.
            val page = pages.firstOrNull { it.id == book.refId } ?: pages.firstOrNull()
            if (page == null) {
                Log.e(TAG, "$sketchbookId holds no pages")
                runCatching { db.seal(file) }
                return@withContext null
            }
            SketchbookSession(
                db = db,
                file = file,
                sketchbookId = sketchbookId,
                title = book.text.orEmpty(),
                pageId = page.id,
                pageWidth = pageDimension(page.width),
                pageHeight = pageDimension(page.height),
                pageCount = pages.size,
                scope = scope,
            )
        }

        /**
         * A page's recorded size, or zero.
         *
         * Zero is g-paper's own "stretch to the view" and is the right answer for a page that never
         * recorded one: a sketchbook made on this panel is a sketchbook of this panel's pages, so a
         * page with no size is a page from a file this app did not write, and fitting it to the
         * screen is friendlier than drawing it at a size nobody chose.
         */
        private fun pageDimension(value: Float?): Int {
            val v = value ?: return 0
            return if (v > 0f) v.toInt() else 0
        }
    }
}
