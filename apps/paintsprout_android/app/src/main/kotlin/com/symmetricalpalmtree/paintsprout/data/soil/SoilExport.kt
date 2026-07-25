package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Sending a sketchbook out of the app, as a file.
 *
 * It is a **byte-for-byte copy**, and that is the whole design. The document is
 * self-describing — [NotebookMeta] carries its id, its name, its ancestry and how
 * it is keyed — and [MetaUpkeep] has kept that record current every time the file
 * was open. So export never opens the document, which means it never has to
 * unlock one: a book with its own passphrase exports as ciphertext, silently,
 * with nobody asked for anything and nothing readable leaking on the way out.
 *
 * The consequence to be aware of is the honest one: what leaves is exactly what
 * is on disk. An encrypted book is useless to anyone without the key, which is
 * the point, and a plaintext one is readable by anything that can open SQLite,
 * which is also the point.
 */
object SoilExport {

    /** Where copies are staged for sharing. Cleared on every export. */
    const val SHARE_DIR = "export"

    /**
     * Opaque bytes, deliberately. A `.soil` is a SQLite database and something
     * would happily offer to open it as one; the receiving app's job is to hand
     * the file to Paintsprout, not to interpret it.
     */
    const val MIME = "application/octet-stream"

    /**
     * Stages [documentId] under its display [name] and returns a shareable URI.
     *
     * Refuses an open document. The bytes on disk are only the whole story once
     * the file is sealed — a live connection can be holding the last few strokes
     * in a `-wal` that this copy would not include — and the editor seals on the
     * way out, so "closed" is the normal state and refusing is not a hardship.
     */
    suspend fun stage(context: Context, documentId: String, name: String): Uri =
        withContext(Dispatchers.IO) {
            if (OpenDocuments.isOpen(documentId)) {
                throw IOException("Refusing to export a sketchbook that is open: $documentId")
            }
            val source = SoilFiles.soilFile(context, documentId)
            if (!source.exists()) throw IOException("No such sketchbook on disk: $documentId")

            val dir = File(context.cacheDir, SHARE_DIR)
            // Swept rather than accumulated: these are copies of the user's
            // artwork sitting in a cache directory, and one export's worth at a
            // time is all that ever needs to be there.
            dir.deleteRecursively()
            dir.mkdirs()

            val staged = File(dir, ExportName.of(name, documentId))
            source.copyTo(staged, overwrite = true)
            FileProvider.getUriForFile(context, "${context.packageName}.files", staged)
        }

    /** The share sheet, with read permission granted to whoever the user picks. */
    fun shareIntent(uri: Uri, name: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        name,
    )
}
