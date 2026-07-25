package com.symmetricalpalmtree.paintsprout.data.soil

import android.graphics.Bitmap
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate

/**
 * Where a document lives, from the editor's point of view.
 *
 * A [DocumentSession] is the same machinery either way — pages, ops, undo, the
 * raster cache — and the only thing that differs between a sketchbook and the
 * scratchpad is what has to happen at the edges: **who the library is told
 * about, and what gets closed.**
 *
 * That distinction is worth a type rather than a null check, because getting it
 * backwards is not a cosmetic bug. Sealing the scratchpad would close the *index*
 * — the one database the app keeps open for its whole life — out from under every
 * screen that reads it.
 */
interface DocumentHome {

    /** The id the session reports. A sketchbook's file id; a sentinel for the scratchpad. */
    val documentId: String

    /**
     * Everything that has to happen before this document goes cold, called with
     * the lock held and on a scope that outlives the screen.
     *
     * [cover] and [pageCount] are offered rather than demanded: a home with no
     * library card ignores both.
     */
    suspend fun close(changed: Boolean, cover: Bitmap?, pageCount: Int)
}

/**
 * A sketchbook: its own encrypted file, and a card in the library.
 *
 * Each step guards itself. A disk-full failure seconds after the user left the
 * page must not crash the app and — more importantly — must not stop the steps
 * after it from running: skipping the checkpoint because the cover failed to
 * write would leave a `-wal` beside the document forever.
 */
class SoilHome(override val documentId: String, private val soil: SoilDatabase) : DocumentHome {

    override suspend fun close(changed: Boolean, cover: Bitmap?, pageCount: Int) {
        if (changed) runCatching { refreshIndexRow(cover, pageCount) }
        // What the library knows about this document, baked into the document —
        // fetched before the seal, because the seal is where the file goes cold
        // and a suspending index read inside it would be a read on a closing
        // database. Null when the index is unavailable, which leaves the embedded
        // record as it was rather than overwriting it with guesses.
        val upkeep = runCatching { MetaUpkeep.from(documentId) }.getOrNull()
        // The seal proper: refresh the embedded identity record, vacuum, truncate
        // the WAL, close, and leave no sidecars behind.
        runCatching {
            soil.seal { meta -> upkeep?.invoke(meta) ?: meta.copy(updatedAt = System.currentTimeMillis()) }
        }
    }

    /**
     * What the library needs to know about a closed document.
     *
     * The cover is offered rather than stored: [com.symmetricalpalmtree.paintsprout.data.index.IndexRepository.setCover]
     * refuses it for a document with its own passphrase, because the index opens
     * with the *global* key and that is a key boundary a picture of the contents
     * must not cross.
     */
    private suspend fun refreshIndexRow(cover: Bitmap?, pageCount: Int) {
        val index = IndexGate.awaitReady()
        index.setPageCount(documentId, pageCount)
        index.recordEdited(documentId)
        if (cover != null) {
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                cover.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                out.toByteArray()
            }
            index.setCover(documentId, bytes)
        }
    }
}

/**
 * The scratchpad: rows in the index's own `scratchpad` table.
 *
 * Closing it does nothing at all, and that is the whole point. There is no
 * library card to refresh — a scratch page is not a document the user filed
 * anywhere — and the database it lives in is the index, which stays open for as
 * long as the app runs and is sealed once, by [IndexGate], on the way out.
 *
 * The ops are already on disk regardless: the session flushes before it calls
 * this, and the index is in WAL mode.
 */
class ScratchpadHome(override val documentId: String) : DocumentHome {
    override suspend fun close(changed: Boolean, cover: Bitmap?, pageCount: Int) = Unit
}
