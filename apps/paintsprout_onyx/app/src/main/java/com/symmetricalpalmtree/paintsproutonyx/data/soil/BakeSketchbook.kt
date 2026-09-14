package com.symmetricalpalmtree.paintsproutonyx.data.soil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeySession
import com.symmetricalpalmtree.paintsproutonyx.data.index.IndexRepository
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectType
import com.symmetricalpalmtree.paintsproutonyx.data.soilFile
import com.symmetricalpalmtree.paintsproutonyx.sketchbook.CoverSnapshot
import com.symmetricalpalmtree.paintsproutonyx.sketchbook.MarkRows
import com.symmetricalpalmtree.paintsproutonyx.sketchbook.RasterBake
import com.symmetricalpalmtree.paintsproutonyx.sketchbook.RasterImage
import com.symmetricalpalmtree.paintsproutonyx.sketchbook.RasterRows
import com.symmetricalpalmtree.paintsproutonyx.sketchbook.SketchbookSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "BakeSketchbook"

/** What asking for a bake came back with. */
sealed class BakeOutcome {

    /** A new sketchbook is on the shelf. [id] is its file and [name] is what the card says. */
    class Made(val id: String, val name: String) : BakeOutcome()

    /**
     * The book the artist long-pressed already keeps pixels, so there was nothing to do — and
     * **nothing was made**: no file, no card, no name taken.
     */
    object AlreadyRaster : BakeOutcome()
}

/**
 * Bake a stroke sketchbook into a raster one: every live page drawn out as graphite, in a **new
 * sketchbook beside the old one**, and the old one not touched at all.
 *
 * The argument for a copy rather than a conversion is [RasterBake]'s and is written out there. What
 * belongs here is the promise that makes the copy safe to try, and the discipline that keeps it:
 * **the source file is opened, read, and sealed, and nothing in this file writes to it.** Not a
 * mark, not the open-page pointer, not `updatedAt` — baking a book is not working in it, and a
 * sketchbook that jumped to the top of "last worked on" because somebody made a copy of it would be
 * the shelf lying about what the artist did with their afternoon. The source's index row is not
 * touched either, for the same reason. The read finishes and the file closes before a single bitmap
 * is asked for, so for the twenty seconds the drawing takes, the artist's original is shut.
 *
 * **The order is the whole thing**, exactly as in [createSketchbook], and for the same reason: the
 * index row goes last, after the `.soil` has been made, filled and closed. A card written first
 * would, on any failure in between, be a card that opens onto nothing — and there is no worse thing
 * a library can do than lie about what it holds. A file with no card is the survivable failure, and
 * [discardHalfMadeSketchbook] clears it up when it can.
 *
 * **One page bitmap is alive at a time.** A page of this panel is about eighteen megabytes decoded,
 * which is the memory failure G6 met head-on; two of them alive while the third is encoding is a
 * device that kills the process in the middle of the artist's copy. Each leaf allocates, draws,
 * encodes and recycles before the next is asked for.
 *
 * [copyName] is the caller's resource pattern and [onProgress] is how the screen keeps its dialog
 * honest — 1-based, called before each leaf is drawn, and free to hop to whatever thread it needs.
 * A ten-second silence on an e-ink panel reads as an app that has stopped.
 *
 * IO throughout.
 */
suspend fun bakeSketchbook(
    context: Context,
    sourceId: String,
    repo: IndexRepository,
    copyName: (String) -> String,
    onProgress: suspend (page: Int, of: Int) -> Unit,
): BakeOutcome = withContext(Dispatchers.IO) {
    val passphrase = KeySession.get() ?: error("no key session — nothing may be baked before bootstrap")
    val summary = repo.summary(sourceId)
        ?: error("there is no card on the shelf for $sourceId, so there is nothing to bake")

    // Everything the copy is made of, read out of the source and the source closed again. Null is
    // the one honest non-failure: the book already keeps pixels and nothing at all has been made.
    val source = readSource(context, sourceId, passphrase)
        ?: return@withContext BakeOutcome.AlreadyRaster
    // Where the copy will sit, asked of the index and not of the source's meta row. The meta row's
    // path is written once, when a book is made, and nothing refreshes it — a book moved to another
    // folder since still says where it *was*, and a copy that inherited that would describe itself
    // as being somewhere it never was. The index is the only thing that knows where the card is.
    val folderPath = repo.ancestry(summary.parentId)

    val name = RasterBake.copyName(summary.name, copyName) {
        repo.nameTaken(summary.parentId, ObjectType.SKETCHBOOK, it)
    }
    val newId = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    val plan = RasterBake.plan(
        source = source,
        name = name,
        newBookId = newId,
        newIds = { UUID.randomUUID().toString() },
        now = now,
    )
    val versionCode = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrNull()

    val file = soilFile(context, newId)
    var opened: SoilDatabase? = null
    try {
        val db = SoilDatabase.create(context, newId, file, passphrase)
        opened = db
        val dao = db.dao()
        dao.upsert(plan.book)
        dao.upsertAll(plan.pages.map { it.row })

        var cover: CoverSnapshot.Cover = CoverSnapshot.Cover.Blank
        val leaves = plan.pages.size
        for ((index, page) in plan.pages.withIndex()) {
            // Before the draw, and for every leaf including the ones with nothing on them: the
            // number on the dialog is how many pages of the book have been dealt with, not how many
            // happened to have marks, and a count that skipped the blanks would stall visibly on a
            // book with a few empty leaves in the middle of it.
            onProgress(index + 1, leaves)
            val width = SketchbookSession.pageDimension(page.row.width)
            val height = SketchbookSession.pageDimension(page.row.height)
            // A blank leaf gets no picture — see [RasterBake]'s KDoc. Silent, because it is the
            // ordinary state of an untouched page rather than anything having gone wrong.
            if (page.marks.isEmpty()) continue
            if (width <= 0 || height <= 0) {
                // A page that never recorded a size is a page from a file this app did not write.
                // There is no rectangle to draw the marks in, and inventing one would put the
                // artist's drawing in a frame nobody chose — the same answer `CoverSnapshot` gives
                // to the same question. The leaf is copied; only its picture is not.
                Log.w(TAG, "page ${page.sourcePageId} recorded no size; its copy is a blank leaf")
                continue
            }
            var bitmap: Bitmap? = null
            try {
                // Born transparent, and left that way. The page image is a **layer over the paper**
                // — `RasterImage.decode`'s KDoc has the whole argument — so erasing clears back to
                // nothing rather than painting white, and a page filled with white here would turn
                // every future rubbed-out patch into a white hole over whatever paper a later arc
                // puts underneath. The cover is the one place white belongs, and `CoverSnapshot`
                // composites it there.
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                StrokeRasterizer.draw(Canvas(bitmap), page.marks)
                val png = RasterImage.encode(bitmap, page.row.id)
                dao.upsert(RasterRows.toRow(page.row.id, png, UUID.randomUUID().toString(), now))
                Log.i(
                    TAG,
                    "baked page ${index + 1}/$leaves of $sourceId: ${png.size} bytes, " +
                        RasterBake.digest(png),
                )
                if (page.row.id == plan.book.refId) {
                    // The card for the copy, taken from the one leaf the copy will open on, so the
                    // two books can be told apart **on the shelf** — which is how this phase is
                    // judged. A copy whose card is a white frame beside the original's drawn card
                    // would look like a bake that had done nothing.
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    // Freed before the cover asks for anything. The pixel array is already another
                    // eighteen megabytes and the cover bitmap is a third allocation.
                    bitmap.recycle()
                    bitmap = null
                    cover = CoverSnapshot.render(pixels, width, height)
                }
            } finally {
                bitmap?.recycle()
            }
        }

        SketchbookMetaStore.write(
            db.raw(),
            SketchbookMeta(
                sketchbookId = newId,
                name = name,
                createdAt = now,
                updatedAt = now,
                folderPath = folderPath,
                appVersionCode = versionCode,
            ),
        )
        db.seal(file)
        repo.createSketchbook(
            id = newId,
            name = name,
            parentId = summary.parentId,
            paperKind = summary.paperKind ?: SoilSchema.PAPER_BLANK,
            pageCount = plan.pages.size,
            now = now,
        )
        // A Blank or a Failed cover stores nothing at all, exactly as the sketchbook screen does it:
        // the card's white frame is the honest picture of a book with nothing drawn in it, and a
        // render that fell over is not a reason to put a wrong picture on the shelf.
        val image = cover as? CoverSnapshot.Cover.Image
        if (image != null) repo.setCover(newId, image.bytes)
        BakeOutcome.Made(newId, name)
    } catch (t: Throwable) {
        // Throwable rather than Exception, and the difference is the one failure with a real chance
        // of happening: an OutOfMemoryError on a page-sized bitmap. `CoverSnapshot` catches it for
        // the same reason. Letting an Error past here would leave a `.soil` in the Garden that no
        // card points at and nothing will ever clear up — the tidying has to run for the failure
        // that is actually likely, not only for the ones that arrive politely as exceptions.
        runCatching { opened?.seal(file) }
        discardHalfMadeSketchbook(context, newId, file)
        throw t
    }
}

/**
 * Open the source, read everything the copy needs, and close it again. Null means the book already
 * keeps pixels — asked here rather than by the shelf because it is the `.soil` row that knows, and
 * the shelf would have to open an encrypted file to find out.
 *
 * **The seal is in a `finally`, and the early answer goes through it.** The source is the artist's
 * work and this function is the only thing in the bake that ever has it open; a path out of here
 * that left it open — the already-raster answer, a page that would not read, anything — would hold
 * the file for the life of the process, because nothing downstream knows there is a database to
 * close.
 *
 * A mark row that will not decode is dropped with a line in the log and the rest of the page is
 * still copied, which is `SketchbookSession.readMarks`' discipline exactly: a mark that cannot be
 * read is one lost mark, and refusing the whole bake over it would lose every other mark in the book.
 */
private suspend fun readSource(
    context: Context,
    sourceId: String,
    passphrase: String,
): RasterBake.Source? {
    val file = soilFile(context, sourceId)
    val db = SoilDatabase.open(context, sourceId, file, passphrase)
    try {
        val dao = db.dao()
        val book = dao.sketchbookRow()
            ?: error("$sourceId has no sketchbook row — not a sketchbook this app made")
        if (RasterRows.isRaster(book.flags)) return null
        val pages = dao.childrenOfType(sourceId, SoilSchema.TYPE_PAGE)
        val marksByPage = LinkedHashMap<String, List<Stroke>>(pages.size)
        for (page in pages) marksByPage[page.id] = readMarks(dao, page.id)
        return RasterBake.Source(book = book, pages = pages, marksByPage = marksByPage)
    } finally {
        runCatching { db.seal(file) }
    }
}

/** One page's marks, in the order they were laid down, a row that will not open left off. */
private suspend fun readMarks(dao: SoilDao, pageId: String): List<Stroke> {
    val rows = dao.childrenOfType(pageId, SoilSchema.TYPE_MARK)
    val out = ArrayList<Stroke>(rows.size)
    var dropped = 0
    for (row in rows) {
        val stroke = try {
            MarkRows.toStroke(row)
        } catch (e: Exception) {
            Log.e(TAG, "mark ${row.id} could not be read and is not in the copy", e)
            null
        }
        if (stroke == null) dropped++ else out.add(stroke)
    }
    if (dropped > 0) Log.w(TAG, "$dropped of ${rows.size} marks on page $pageId did not open")
    return out
}
