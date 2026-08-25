package com.symmetricalpalmtree.paintsproutonyx.data.soil

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.paintsproutonyx.core.PanelSize
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeyMaterial
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeySession
import com.symmetricalpalmtree.paintsproutonyx.data.index.IndexRepository
import com.symmetricalpalmtree.paintsproutonyx.data.sidecarsOf
import com.symmetricalpalmtree.paintsproutonyx.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "NewSketchbook"

/**
 * Bring a new sketchbook into the world: the file first, then the card on the shelf.
 *
 * **The order is the whole thing.** The index row is written last, after the `.soil` has been made,
 * filled in and closed. The other order — claim it on the shelf, then try to write it — leaves a card
 * that opens onto nothing if anything at all goes wrong in between, and there is no worse thing a
 * library can do than lie about what it holds. A file with no card is the other failure and it is
 * survivable: an orphan in `Garden/` costs disk and nothing else, nothing enumerates that directory,
 * and the artist simply never made a sketchbook. So a half-finished create is cleaned up if it can be
 * and abandoned quietly if it cannot.
 *
 * What is inside a brand-new sketchbook:
 *
 *  - the **sketchbook row** — the root of the file, carrying the title and pointing at the page that
 *    was last open, which on day one is the only page there is;
 *  - **one page row**, at the panel's own size, because in arc 1 the page *is* the panel;
 *  - **no paper row.** Arc 1's paper is plain white and all the tooth lives in the pencil's grain, so
 *    there is nothing to store. The index's `paperKind` says [SoilSchema.PAPER_BLANK] out loud rather
 *    than leaving the column null, so a later arc that adds a real paper can tell "plain, on purpose"
 *    from "written before this app knew about paper";
 *  - the **meta row**, which is the part a person could read with a stock `sqlcipher` CLI if this file
 *    ever turned up on its own with no index to explain it.
 *
 * The page size comes from [PanelSize] and is never written again. Nothing here rescales a page: a
 * sketchbook made on this panel is a sketchbook of this panel's pages, for good.
 *
 * IO throughout. Returns the new sketchbook's id, which is also its filename.
 */
suspend fun createSketchbook(
    context: Context,
    name: String,
    parentFolderId: String?,
    panel: PanelSize,
    repo: IndexRepository,
): String = withContext(Dispatchers.IO) {
    val sketchbookId = UUID.randomUUID().toString()
    val passphrase = KeySession.get() ?: error("no key session — nothing may be created before bootstrap")
    val file = soilFile(context, sketchbookId)
    val now = System.currentTimeMillis()
    val pageId = UUID.randomUUID().toString()
    // Read before the file exists: a failure here is a create that never started, which is much
    // easier to be honest about than one that has already put a file in the Garden.
    val folderPath = repo.ancestry(parentFolderId)
    val versionCode = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrNull()

    // One try around the whole create, and not just the writing of the rows. A sketchbook is not
    // made until its card is on the shelf, so every step up to and including the index row is part of
    // the same act: if any of them fails, none of it happened and the file goes.
    var opened: SoilDatabase? = null
    try {
        val db = SoilDatabase.create(context, sketchbookId, file, passphrase)
        opened = db
        val dao = db.dao()
        dao.upsert(
            SoilObjectEntity(
                id = sketchbookId,
                parentId = SoilSchema.ROOT_PARENT,
                type = SoilSchema.TYPE_SKETCHBOOK,
                createdAt = now,
                updatedAt = now,
                text = name,
                refId = pageId,
            )
        )
        dao.upsert(
            SoilObjectEntity(
                id = pageId,
                parentId = sketchbookId,
                type = SoilSchema.TYPE_PAGE,
                order = 0,
                createdAt = now,
                updatedAt = now,
                // Empty rather than null: the column is the id of this page's paper, and arc 1's
                // paper is nothing at all. "" says the question was asked and answered.
                refId = "",
                width = panel.width.toFloat(),
                height = panel.height.toFloat(),
            )
        )
        SketchbookMetaStore.write(
            db.raw(),
            SketchbookMeta(
                sketchbookId = sketchbookId,
                name = name,
                createdAt = now,
                updatedAt = now,
                folderPath = folderPath,
                appVersionCode = versionCode,
            ),
        )
        db.seal(file)
        repo.createSketchbook(
            id = sketchbookId,
            name = name,
            parentId = parentFolderId,
            paperKind = SoilSchema.PAPER_BLANK,
            pageCount = 1,
            now = now,
        )
    } catch (e: Exception) {
        runCatching { opened?.seal(file) }
        discardHalfMadeSketchbook(context, sketchbookId, file)
        throw e
    }
    sketchbookId
}

/**
 * Undo a create that did not finish.
 *
 * Safe to do exactly here and nowhere else: this file was made moments ago by this call and no card
 * on the shelf has ever pointed at it, so there is nothing in it that anybody drew. The cached raw
 * key goes with it — from both RAM and the Keystore — because the next sketchbook is a different file
 * with a different salt, and a key left behind under a dead id is one that will one day be tried
 * against a file it does not open and reported as corruption.
 *
 * Failures here are logged and swallowed. The caller is already carrying a real error to the artist,
 * and "and also the tidying up failed" is not a second thing they can act on.
 */
private fun discardHalfMadeSketchbook(context: Context, sketchbookId: String, file: java.io.File) {
    try {
        file.delete()
        sidecarsOf(file).forEach { it.delete() }
        // Best-effort on the key, and knowingly so: the raw key for a new file is derived on a
        // background scope, so a slow derivation can finish after this and put the entry back. It
        // cannot do any harm — this id is a UUID and no file will ever carry it again, so the key is
        // never tried against anything. What is left behind is one stale Keystore entry, which is a
        // better outcome than blocking a failed create on a quarter of a million hash rounds.
        KeyMaterial.invalidate(context, sketchbookId)
    } catch (e: Exception) {
        Log.w(TAG, "could not clear up the half-made sketchbook ${file.name}", e)
    }
}
