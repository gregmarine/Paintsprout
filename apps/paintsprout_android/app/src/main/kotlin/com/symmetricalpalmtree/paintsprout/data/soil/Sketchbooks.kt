package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import com.symmetricalpalmtree.paintsprout.crypto.CryptoStores
import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.crypto.RawKeyCache
import com.symmetricalpalmtree.paintsprout.crypto.SoilCrypto
import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.IndexObject
import com.symmetricalpalmtree.paintsprout.paint.CanvasSize
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Random
import java.util.UUID

/**
 * A sketchbook as the user means it: a file *and* a row in the library.
 *
 * Neither half is a sketchbook on its own. A file with no row is invisible; a row
 * with no file is a card that opens onto nothing — and, if something then opens
 * it with a create-capable helper, an empty ghost that masquerades as the real
 * thing. So the two are always made and unmade together, here.
 */
object Sketchbooks {

    suspend fun create(
        context: Context,
        name: String,
        canvasSize: CanvasSize = CanvasSize.FullScreen,
        surface: SurfaceKind = SurfaceKind.PAPER,
        parentId: String? = null,
    ): IndexObject = withContext(Dispatchers.IO) {
        val index = IndexGate.awaitReady()
        val id = UUID.randomUUID().toString()
        val print = canvasSize as? CanvasSize.Print

        // The file first: a row pointing at a document that failed to be created
        // is the worse of the two half-states.
        val soil = SketchbookStore.create(context, name, documentId = id)
        try {
            repositoryFor(soil, id).createDocument(
                title = name,
                canvasKind = canvasKindOf(canvasSize),
                canvasWidthInches = print?.wIn,
                canvasHeightInches = print?.hIn,
                surfaceKind = surface.name,
                surfaceSeed = Random().nextLong(),
            )
        } finally {
            soil.seal()
        }

        index.createSketchbook(
            name = name,
            parentId = parentId,
            id = id,
            canvasKind = canvasKindOf(canvasSize),
            canvasW = print?.wIn,
            canvasH = print?.hIn,
            keyScope = KeyScope.GLOBAL,
        ).also {
            index.setPageCount(id, 1)
            // Where it was filed, into the file, at the moment it is filed. A book
            // created inside a folder and exported before it is ever opened still
            // carries the ancestry an importer needs to put it back.
            if (parentId != null) runCatching { stampFolderPath(context, id) }
        }
    }

    /**
     * A copy that is genuinely its own document.
     *
     * The bytes are copied, then the copy's root row is re-identified — the root
     * carries the document's own id, so without that step the duplicate would
     * insist it was the original. Its pages, layers and ops keep their ids, which
     * is safe: they are private to this file and nothing outside it refers to them.
     */
    suspend fun duplicate(context: Context, sourceId: String, name: String): IndexObject? =
        withContext(Dispatchers.IO) {
            val index = IndexGate.awaitReady()
            val source = index.byId(sourceId) ?: return@withContext null
            val sourceFile = SoilFiles.soilFile(context, sourceId)
            if (!sourceFile.exists()) return@withContext null
            if (OpenDocuments.isOpen(sourceId)) {
                throw IOException("Refusing to duplicate a sketchbook that is open")
            }

            val newId = UUID.randomUUID().toString()
            val target = SoilFiles.soilFile(context, newId)
            sourceFile.copyTo(target, overwrite = false)

            val soil = SketchbookStore.open(context, newId)
            try {
                reidentify(soil, sourceId, newId)
                soil.writeMeta(
                    (soil.readMeta() ?: return@withContext null).copy(
                        sketchbookId = newId,
                        name = name,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } finally {
                soil.seal()
            }

            index.createSketchbook(
                name = name,
                parentId = source.parentId,
                id = newId,
                canvasKind = source.canvasKind,
                canvasW = source.canvasW,
                canvasH = source.canvasH,
                keyScope = KeyScope.GLOBAL,
            ).also { index.setPageCount(newId, source.pageCount ?: 1) }
        }

    /**
     * Removes both halves.
     *
     * The row goes **after** the file, and soft: the index's delete also scrubs
     * every list edge pointing at this book and drops its activity log, so a
     * pinned card cannot outlive the thing it points at.
     */
    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        SketchbookStore.delete(context, id)
        IndexGate.awaitReady().delete(id)
    }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        IndexGate.awaitReady().rename(id, name)
        // The embedded copy of the name catches up on the document's next open;
        // Phase 22's meta upkeep is what makes export prompt-free.
    }

    fun canvasKindOf(size: CanvasSize): String = when (size) {
        is CanvasSize.Print -> "PRINT"
        else -> "FULL_SCREEN"
    }

    fun canvasSizeOf(row: IndexObject): CanvasSize {
        val w = row.canvasW
        val h = row.canvasH
        return if (row.canvasKind == "PRINT" && w != null && h != null) {
            CanvasSize.Print(w, h, CanvasSize.PRESETS.firstOrNull { it.wIn == w && it.hIn == h }?.label ?: "$w × $h in")
        } else {
            CanvasSize.FullScreen
        }
    }

    /** The same reading, from the document's own root row rather than the index. */
    fun canvasSizeOfRoot(root: SoilObject): CanvasSize {
        val w = root.width
        val h = root.height
        return if (root.kind == "PRINT" && w != null && h != null) {
            CanvasSize.Print(w, h, CanvasSize.PRESETS.firstOrNull { it.wIn == w && it.hIn == h }?.label ?: "$w × $h in")
        } else {
            CanvasSize.FullScreen
        }
    }

    /**
     * Makes an open document claim [newId] as its own.
     *
     * The root row carries the document's id — that is what makes a `.soil`
     * self-identifying — so a copy that keeps the original's id insists it *is*
     * the original. Both callers that create a document out of another one's
     * bytes come through here: duplicate, and an import kept alongside what it
     * collided with. Its pages, layers and ops keep their ids, which is safe:
     * they are private to this file and nothing outside it refers to them.
     */
    internal fun reidentify(soil: SoilDatabase, oldId: String, newId: String) {
        soil.db.execSQL(
            "UPDATE `${SchemaSql.SKETCHBOOK_TABLE}` SET `parentId` = ? WHERE `parentId` = ?",
            arrayOf<Any?>(newId, oldId),
        )
        soil.db.execSQL(
            "UPDATE `${SchemaSql.SKETCHBOOK_TABLE}` SET `id` = ? WHERE `id` = ?",
            arrayOf<Any?>(newId, oldId),
        )
    }

    /**
     * Takes a private book's passphrase and holds its derived key **in RAM**.
     *
     * That is the whole of what unlocking one means: `SKETCHBOOK` scope is exactly
     * "the key lives in memory until the process ends", so the passphrase is
     * verified against the file, derived once, and never written down. Returns
     * false for a wrong answer, which the caller counts.
     */
    suspend fun unlock(context: Context, documentId: String, passphrase: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = SoilFiles.soilFile(context, documentId)
            if (!SoilCrypto.verifyPassphrase(file, passphrase)) return@withContext false
            RawKeyCache(CryptoStores.derivedKeys(context)).ephemeral(documentId, file, passphrase)
            true
        }

    /** Opens, refreshes the embedded record against the library, and seals again. */
    private suspend fun stampFolderPath(context: Context, id: String) {
        val refresh = MetaUpkeep.from(id)
        val soil = SketchbookStore.open(context, id)
        try {
            soil.readMeta()?.let { soil.writeMeta(refresh(it)) }
        } finally {
            soil.seal()
        }
    }

    internal fun repositoryFor(soil: SoilDatabase, id: String) = SketchbookRepository(
        store = ObjectTable(soil.db, SchemaSql.SKETCHBOOK_TABLE),
        rootId = id,
    )
}
