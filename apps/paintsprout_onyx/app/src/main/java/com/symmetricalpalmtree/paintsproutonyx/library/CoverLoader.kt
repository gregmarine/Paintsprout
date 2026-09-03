package com.symmetricalpalmtree.paintsproutonyx.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.symmetricalpalmtree.paintsproutonyx.data.index.IndexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CoverLoader"

/**
 * Fetching the pictures on the cards, kept out of [LibraryActivity].
 *
 * **Its own file because it is its own discipline, and because the shelf screen is at the line.**
 * `LibraryActivity` is the one screen that does everything a library does — modes, folders, naming,
 * moving, deleting, paging — and this repo does not let a file past about eight hundred lines
 * without saying why. Covers are the piece that comes out cleanly: nothing here needs the Activity,
 * a view, a string or a dialog, and everything here is a rule about *which thread* and *how much
 * memory*, which is exactly the kind of rule that gets quietly broken when it is a few lines in the
 * middle of a screen that is mostly about something else.
 *
 * Three decisions live here.
 *
 * **The listing never reads a cover; a card does, one at a time.** The index's listing queries are
 * cover-free on purpose — see `SUMMARY_COLS` in `ObjectDao` — because a whole-row listing would pull
 * every cover in the library out of the encrypted file to lay out six cards, and the library would
 * get slower with every sketchbook added to it. So the covers for the six cards actually about to be
 * drawn are read after the listing, by id.
 *
 * **The cache holds the stored bytes, not the decoded picture.** A cover is a few tens of kilobytes
 * of WEBP and about two megabytes once it is a bitmap; caching bitmaps would mean a library paged
 * from end to end holding a bitmap per sketchbook, which is how a shelf ends up being the thing that
 * runs the device out of memory. Keeping the bytes makes paging back to a page already seen free of
 * the expensive half — the encrypted read — and pays the cheap half, a decode of at most six small
 * images, again. Both halves happen off the main thread either way.
 *
 * **A blob is data from a file, not a promise.** The dimensions in the header are whatever is
 * actually written there, and this app is not the only thing that will ever have written to a
 * `.soil` or an index — a backup restored from another build, a file edited from outside. So the
 * decode is bounded: anything claiming to be bigger than [MAX_EDGE] on its long side is sampled
 * down on the way in rather than allocated at its stated size and taking the app with it.
 */
class CoverLoader(private val repo: IndexRepository) {

    /**
     * Sketchbook id → the stored cover bytes, or null for "asked, and there is none".
     *
     * A plain `HashMap` with no lock, because **[load] only ever touches it from the caller's own
     * thread**, which is the main thread; the reads and the decodes are the only things that go to
     * IO, and they hand their answers back before this map is written. Two refreshes overlapping is
     * ordinary here — a tap landing while `onResume`'s listing is still running — and a map written
     * from two IO threads at once is the kind of corruption that shows up months later as a shelf
     * that hangs on one particular library.
     */
    private val bytes = HashMap<String, ByteArray?>()

    /**
     * Throw away what was read, because it may not be true any more.
     *
     * Called at the top of every listing. A cover changes whenever a sketchbook is closed, which is
     * to say between one visit to this screen and the next, and a cache kept across a refresh is a
     * shelf showing the artist the page they were on *before* the session they just finished.
     */
    fun forget() {
        bytes.clear()
    }

    /**
     * The covers for [ids], decoded and ready to hand to [LibraryGrid.bind].
     *
     * **Call it from the main thread.** It takes itself to IO for the parts that belong there and
     * comes back to the caller's thread to touch the cache — see [bytes].
     *
     * Ids with no stored cover are simply absent from the answer, and so are ids whose cover would
     * not decode. Both mean the same thing to a card: the frame stays the empty white page it
     * already is, which is a picture the shelf is entitled to show and a good deal better than a
     * gap where a card should be.
     */
    suspend fun load(ids: List<String>): Map<String, Bitmap> {
        val missing = ids.filter { !bytes.containsKey(it) }
        if (missing.isNotEmpty()) {
            val read = withContext(Dispatchers.IO) {
                // A cover that will not come out of the index is a card without a picture, never a
                // shelf that fails to draw. The index is encrypted and opened per read; a single
                // unreadable row must not take the listing with it.
                missing.associateWith { id -> runCatching { repo.cover(id) }.getOrNull() }
            }
            bytes.putAll(read)
        }
        val blobs = ids.mapNotNull { id -> bytes[id]?.let { id to it } }
        if (blobs.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            blobs.mapNotNull { (id, blob) -> decode(blob)?.let { id to it } }.toMap()
        }
    }

    /**
     * One stored cover into a bitmap no larger than [MAX_EDGE] on its long side.
     *
     * The bounds pass first, then the real decode, because `inSampleSize` has to be chosen from
     * dimensions and the header is the only place they are written. A cover this app made is
     * 620 × 827 and comes back untouched at sample 1 — the sampling is there for the cover this app
     * did not make.
     */
    private fun decode(blob: ByteArray): Bitmap? {
        if (blob.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(blob, 0, blob.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null
        var sample = 1
        while (longEdge / sample > MAX_EDGE) sample *= 2
        return try {
            BitmapFactory.decodeByteArray(
                blob, 0, blob.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (e: Throwable) {
            // OutOfMemoryError included, which is why this catches Throwable rather than Exception:
            // the whole point of the bound above is that a cover is never worth the library.
            Log.w(TAG, "a cover would not decode and the card will show a blank page", e)
            null
        }
    }

    private companion object {
        /**
         * A cover is drawn into about a third of the panel's width. Anything past a thousand pixels
         * on its long side is detail the card cannot show, so a blob claiming more is sampled down
         * rather than believed.
         */
        const val MAX_EDGE = 1024
    }
}
