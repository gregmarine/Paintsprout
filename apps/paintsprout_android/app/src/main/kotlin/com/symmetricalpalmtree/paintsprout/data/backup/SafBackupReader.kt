package com.symmetricalpalmtree.paintsprout.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import java.io.File

/**
 * The read half of a LOCAL destination: what restore uses to find a backup and
 * copy it out.
 *
 * Only `*.soil` counts as a sketchbook, which is what keeps a `.part` or `.old`
 * left behind by a killed backup run from being staged as one.
 */
object SafBackupReader {

    private const val TAG = "SafBackupReader"

    /** The index's filename at a destination is the same as at home. */
    const val INDEX_NAME = SoilFiles.INDEX_FILE

    fun treeDir(context: Context, treeUri: Uri): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory && it.canRead() }

    fun subDirs(dir: DocumentFile): List<DocumentFile> = dir.listFiles().filter { it.isDirectory }

    /** True when [dir] holds an index — that is what makes it a device folder. */
    fun hasIndex(dir: DocumentFile): Boolean = dir.findFile(INDEX_NAME)?.isFile == true

    fun soilFiles(dir: DocumentFile): List<DocumentFile> =
        dir.listFiles().filter { it.isFile && it.name?.endsWith(".${SoilFiles.EXTENSION}") == true }

    /**
     * Copies one document's bytes to [dest], via a `.part` sibling.
     *
     * A connection that drops mid-body must not leave a truncated file under the
     * real name: staging validates what it finds, and a half-copied `.soil` that
     * still probes as a database would be committed as a sketchbook.
     */
    fun copyTo(context: Context, doc: DocumentFile, dest: File): Boolean {
        val part = File("${dest.absolutePath}.part")
        return try {
            val opened = context.contentResolver.openInputStream(doc.uri)?.use { input ->
                part.outputStream().use { out -> input.copyTo(out) }
            } != null
            if (opened && part.renameTo(dest)) {
                true
            } else {
                part.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyTo failed for ${doc.name}: ${e.message}")
            part.delete()
            false
        }
    }
}
