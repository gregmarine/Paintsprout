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
import java.util.UUID

private const val TAG = "SketchbookSession"

/**
 * What the shelf's card was made from: the page that was on the glass, and how many edits had
 * landed in the file by then. Two cards with the same key are the same card.
 *
 * The edit count rather than the file's `updatedAt`, because two edits can share a millisecond and
 * a count cannot be shared. A page turn is not an edit and does not move the count — it moves the
 * page id, which is the other half of the key, since the cover is a picture of the last page shown.
 */
data class CardKey(val pageId: String, val edits: Long)

/** What asking for the shelf's card came back with. */
sealed class CardOutcome {
    /** Nothing has happened since the card was last written. There is nothing to write. */
    object Unchanged : CardOutcome()

    /** A cover to store, and the key to record once the index actually holds it. */
    class Fresh(val cover: CoverSnapshot.Cover, val key: CardKey) : CardOutcome()
}

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
 * ## Which page is open, and who gets to say
 *
 * [currentPageId] is the one piece of screen state that lives down here, and it is written by
 * exactly one thing: the Activity, at the instant a page is actually on the glass. Not when a flip
 * is decided, not when the marks have been read — when the panel is showing it. That matters
 * because a mark can commit while a page swap is in flight: the pen finishes a stroke, the callback
 * arrives, and if this said "the new page" a beat too early the mark would be filed on a leaf the
 * hand never touched. The screen captures the page id it means and hands it to [recordMark], so the
 * mark lands where it was drawn whatever the book is doing.
 *
 * ## Reads and writes
 *
 * The reads ([livePages], [page], [loadMarks], [livePageCount], [lastEditAt]) are ordinary IO. The
 * writes are all queued, and they split in two: the ones the hand makes ([recordMark],
 * [recordErase], [rememberOpenPage]) are fired and forgotten, because there is nothing honest to say
 * to an artist mid-stroke about a write that failed; the ones an undo or a page turn depends on are
 * *awaited* through [SoilWriter.perform], because the screen is about to show a page built from what
 * they did and must not show it before it is true.
 *
 * [renderCover] is the one read that goes through the queue anyway, because what it wants to see is
 * the writes that have not landed yet. See its own comment.
 */
class SketchbookSession private constructor(
    private val db: SoilDatabase,
    private val file: File,
    val sketchbookId: String,
    val title: String,
    initialPageId: String,
    scope: CoroutineScope,
) {

    private val writer = SoilWriter(scope)
    private val dao = db.dao()

    /** The page on the glass. Set by the Activity when a page is shown, and by nothing else. */
    var currentPageId: String = initialPageId

    /**
     * How many edits have landed in the file this sitting. Bumped on the write queue by
     * [touchSketchbook], which every edit goes through, and read on the queue by [renderCover] —
     * so a count read there has seen every write that was ahead of it.
     */
    @Volatile
    private var edits = 0L

    /** The key of the last card the index actually took. Null until it has taken one this sitting. */
    @Volatile
    private var cardWritten: CardKey? = null

    // ── Reading the book ─────────────────────────────────────────────────────

    /**
     * The living pages, in page order — the book as it stands.
     *
     * Deleted pages are not in it, and that is what makes it the right list to count positions and
     * neighbours against: a page the artist threw away is not a page they can swipe to, so it is
     * not page four of seven either.
     */
    suspend fun livePages(): List<SoilObjectEntity> = withContext(Dispatchers.IO) {
        dao.childrenOfType(sketchbookId, SoilSchema.TYPE_PAGE)
    }

    /** One page's row, alive or not — the size of a page is a fact about it even after it is gone. */
    suspend fun page(id: String): SoilObjectEntity? = withContext(Dispatchers.IO) { dao.byId(id) }

    /** How many leaves the book has now. What the shelf's card shows. */
    suspend fun livePageCount(): Int = withContext(Dispatchers.IO) { dao.livePageCount() }

    /**
     * Every mark on one page, in the order it was laid down.
     *
     * Order is not decoration: marks are opaque and stack, so reading them out of order puts an
     * older mark on top of a newer one. A row that will not decode is dropped with a line in the log
     * and the rest of the page still opens — a mark that cannot be read is one lost mark, and
     * refusing to open the page over it would be losing all of them.
     */
    suspend fun loadMarks(pageId: String): List<Stroke> = withContext(Dispatchers.IO) {
        readMarks(pageId)
    }

    /**
     * The same read, without choosing a thread.
     *
     * [loadMarks] is called from a screen and takes itself to IO; [renderCover] is called from
     * inside the write queue, which is already there, and wrapping a second `withContext` around a
     * read that is running on the queue's own dispatcher only makes it harder to see that it is.
     * Both are the same query and the same dropped-row discipline, so they are the same code.
     */
    private suspend fun readMarks(pageId: String): List<Stroke> {
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
        return out
    }

    /**
     * When this file was last actually drawn in, by its own reckoning.
     *
     * The sketchbook row's `updatedAt`, which `touchSketchbook` moves for a mark, an erase, a page
     * added, thrown away or brought back — and for nothing else, now that a page turn no longer
     * touches it. This is what the close carries over to the index so that "last worked on" on the
     * shelf means the last time work was done, rather than the last time the cover was opened.
     *
     * Zero for a file with no sketchbook row, which cannot happen to a session that opened — and if
     * it somehow did, zero is the number that moves nothing, since the index only ever takes a stamp
     * that is newer than the one it holds.
     */
    suspend fun lastEditAt(): Long = withContext(Dispatchers.IO) {
        dao.sketchbookRow()?.updatedAt ?: 0L
    }

    /**
     * Bake the page that is on the glass into a cover for the shelf — or say that the card the
     * shelf already holds is still the right one. See [CoverSnapshot] for the bake itself.
     *
     * **On the write queue, and that is the point.** The marks a cover most needs are the ones still
     * sitting in the queue: the cover is taken at the moment the artist puts the sketchbook down,
     * which is a second or two after the last stroke they drew, which is exactly the stroke least
     * likely to have reached the table yet. [SoilWriter.perform] puts this read behind every write
     * already waiting, so the picture is of the page as it will reopen rather than as it was a
     * moment before the last thing on it happened.
     *
     * **The "nothing changed" answer is decided on the queue too, for the same reason.** G5 left a
     * watch item: every press of Home rendered a full page — an 18 MB bitmap and every mark on it —
     * whether or not anything had been drawn, at the exact moment this device is deciding what to
     * kill. The [CardKey] is the fix, and it has to be read *behind* the pending writes or it would
     * lie in precisely the case that matters most: a stroke just drawn and still in the queue is an
     * edit the count has not seen yet, and a key read from the screen's thread would say the card
     * was current while the last stroke was missing from it. Read here, the count has seen
     * everything that was ahead of it, and "unchanged" means unchanged.
     *
     * The cost is that this is queued behind the artist's ink and has to be treated as a write that
     * might not land — the caller wraps it, and a cover that fails is a cover that stays stale, never
     * a crash on the way out of a screen.
     */
    suspend fun renderCover(): CardOutcome = writer.perform {
        val pageId = currentPageId
        val key = CardKey(pageId, edits)
        if (key == cardWritten) return@perform CardOutcome.Unchanged
        val page = dao.byId(pageId)
        val cover = CoverSnapshot.render(
            marks = readMarks(pageId),
            pageWidth = pageDimension(page?.width),
            pageHeight = pageDimension(page?.height),
        )
        CardOutcome.Fresh(cover, key)
    }

    /**
     * The index now holds the card made from [key]. Called by the screen only after every part of
     * the card — cover, page count, stamp — has actually been written, so a card the index refused
     * is a card still owed and the next departure makes it again.
     */
    fun cardWritten(key: CardKey) {
        cardWritten = key
    }

    // ── Writing, the fire-and-forget half ────────────────────────────────────

    /**
     * A mark the pen just finished, on its way to the file.
     *
     * [pageId] is passed in rather than read from [currentPageId] on purpose. The caller captured
     * it at the moment the stroke committed; by the time this write reaches the front of the queue
     * a page turn may have moved the book on, and a mark filed against the page the artist happens
     * to be looking at now is a mark on the wrong leaf.
     *
     * The stacking position is read from the table at write time rather than counted in memory,
     * because the table is what the next reload will trust. It counts erased rows too, so a mark
     * drawn after an erase can never be handed the position the erased one still holds — two marks
     * claiming one position is a stacking order decided by whichever way SQLite broke the tie.
     */
    fun recordMark(stroke: Stroke, pageId: String) {
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

    /**
     * Point the sketchbook row at the page now open, so a kill mid-session reopens where the hand
     * was rather than back at page one.
     *
     * Fired and forgotten, and called on every single page shown, because it is the one write here
     * whose failure costs nothing worth reporting: the fallback is the first page, which is where a
     * sketchbook opened before there was a pointer at all.
     *
     * It is also the one write here that is **not** an edit, which is why it goes through
     * `setOpenPage` and leaves the row's `updatedAt` where it was. Turning a page is not work — a
     * sketchbook flipped through and put down again has not been drawn in, and a stamp that moved
     * anyway would file it as the newest thing in the library.
     */
    fun rememberOpenPage(pageId: String) {
        writer.submit {
            dao.setOpenPage(sketchbookId, pageId)
        }
    }

    // ── Writing, the half an undo depends on ─────────────────────────────────

    /**
     * Take marks off the page without taking them out of the file — the store side of an undo of a
     * drawn mark, and of a redo of an erase.
     *
     * Awaited, unlike [recordErase], because the screen reloads the page from the file the instant
     * this returns. Reloading before the stamp landed would show the mark still there and leave an
     * undo button that visibly did nothing.
     */
    suspend fun hideMarks(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.perform {
            val now = System.currentTimeMillis()
            dao.softDelete(ids, now)
            touchSketchbook(now)
        }
    }

    /** The other direction: put erased marks back, with their ids and stacking positions intact. */
    suspend fun restoreMarks(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.perform {
            val now = System.currentTimeMillis()
            dao.restore(ids, now)
            touchSketchbook(now)
        }
    }

    /**
     * A new leaf at the end of the book, and the row that describes it.
     *
     * Pages are only ever added here, at the end, which is what keeps page order a thing nobody has
     * to maintain. The position counts the **dead pages too**, for exactly the reason marks do: a
     * page thrown away still holds its number, and handing that number to a new page means two
     * pages claim one position the moment the delete is undone.
     *
     * The size is copied from the page the artist is on rather than read from the panel. A
     * sketchbook made on this panel is a sketchbook of this panel's pages, and a book whose leaves
     * were different sizes would be a book that cannot be flipped through — so the new leaf is the
     * same leaf as the one before it, whatever screen it is being made on. Falling back to the
     * first living page covers a delete-then-append, where the current row is one that just left;
     * falling back to zero after that is g-paper's own "fit the view", which is the honest answer
     * for a file that never recorded a size.
     */
    suspend fun appendPage(): SoilObjectEntity = writer.perform {
        val now = System.currentTimeMillis()
        val model = dao.byId(currentPageId)
            ?: dao.childrenOfType(sketchbookId, SoilSchema.TYPE_PAGE).firstOrNull()
        val row = SoilObjectEntity(
            id = UUID.randomUUID().toString(),
            parentId = sketchbookId,
            type = SoilSchema.TYPE_PAGE,
            order = dao.maxOrder(sketchbookId, SoilSchema.TYPE_PAGE) + 1,
            createdAt = now,
            updatedAt = now,
            // Empty rather than null, as NewSketchbook writes it: the column is the id of this
            // page's paper, and arc 1's paper is nothing at all. "" says the question was answered.
            refId = "",
            width = model?.width ?: 0f,
            height = model?.height ?: 0f,
        )
        dao.upsert(row)
        touchSketchbook(now)
        row
    }

    /**
     * Throw a page away, and its marks with it. Returns the marks that went.
     *
     * The set of marks is read once, here, and handed back so undo can restore exactly it. Asking
     * the table again on the way back would be asking a different question of a page that has
     * changed since — see `SoilDao.liveChildIds`, where the same argument is made from the other
     * side.
     */
    suspend fun deletePage(pageId: String): List<String> = writer.perform {
        val now = System.currentTimeMillis()
        val markIds = dao.liveChildIds(pageId)
        dao.softDelete(markIds + pageId, now)
        touchSketchbook(now)
        markIds
    }

    /** Bring a thrown-away page back, with exactly the marks that went down with it. */
    suspend fun restorePage(pageId: String, markIds: List<String>) {
        writer.perform {
            val now = System.currentTimeMillis()
            dao.restore(markIds + pageId, now)
            touchSketchbook(now)
        }
    }

    /**
     * Every edit ends here: the file's own "last worked on" moves, and the sitting's edit count with
     * it. The count is what tells the shelf's card whether anything has happened since it was last
     * made — see [renderCover]. Runs on the write queue, which is the only writer of either.
     */
    private suspend fun touchSketchbook(now: Long) {
        dao.touch(sketchbookId, now)
        edits++
    }

    /**
     * Close the file, and do not lose the last thing that was drawn on it.
     *
     * The writer's close is the whole method. Everything else here is sealing a database; waiting
     * for the queue to empty is the difference between a page that reopens as it was left and one
     * that is missing whatever the hand did in the last second before the screen went away.
     */
    suspend fun close() {
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
                initialPageId = page.id,
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
        fun pageDimension(value: Float?): Int {
            val v = value ?: return 0
            return if (v > 0f) v.toInt() else 0
        }
    }
}
