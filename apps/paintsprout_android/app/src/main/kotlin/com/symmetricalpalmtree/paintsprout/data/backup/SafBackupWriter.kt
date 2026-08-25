package com.symmetricalpalmtree.paintsprout.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Writing into the LOCAL destination — a tree the user picked through the Storage
 * Access Framework.
 *
 * The one rule that shapes all of it: **never hold zero good copies at the
 * destination.** SAF has no atomic replace, so a copy streamed straight over the
 * previous backup leaves a truncated file the moment a USB drive is unplugged or
 * a card fills up — and that file is what a restore would then install. Every
 * write here goes to a `.part` sibling first and is renamed in only once it is
 * whole, which is the same discipline `CommitSwap` uses on the library itself.
 */
object SafBackupWriter {

    private const val TAG = "SafBackupWriter"

    /** The picked tree, if it is still there and still writable. */
    fun rootDir(context: Context, treeUri: Uri): DocumentFile? {
        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return if (doc.canWrite()) doc else null
    }

    fun ensureChildDir(parent: DocumentFile, name: String): DocumentFile? =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)

    /**
     * Replaces [fileName] in [dir] with the contents of [source].
     *
     * ```
     * stream into <name>.part      ← the existing backup is untouched if this dies
     *   → move <name> to <name>.old
     *   → rename <name>.part in
     *   → drop <name>.old
     * ```
     *
     * A provider that refuses `renameTo` falls back to rewriting the final name
     * directly, and puts the `.old` copy back if even that fails. The bytes have
     * already streamed once successfully by then, so the window is as small as
     * SAF allows. Restore only ever looks at `*.soil`, so a `.part` or `.old`
     * left by a killed run is never mistaken for a sketchbook.
     */
    fun replaceFile(
        context: Context,
        dir: DocumentFile,
        fileName: String,
        source: File,
        mime: String = "application/octet-stream",
    ): Boolean {
        return try {
            dir.findFile("$fileName.part")?.delete()
            val part = dir.createFile(mime, "$fileName.part") ?: run {
                Log.e(TAG, "createFile returned null for $fileName.part")
                return false
            }
            if (!stream(context, source, part)) {
                part.delete()
                return false
            }

            dir.findFile("$fileName.old")?.delete()
            val old = dir.findFile(fileName)
            if (old != null && !old.renameTo("$fileName.old")) old.delete()
            if (part.renameTo(fileName)) {
                if (part.name != fileName) {
                    // Some providers de-duplicate by appending "(1)". Worth knowing
                    // about: the restore side looks the file up by name.
                    Log.w(TAG, "SAF renamed $fileName → ${part.name}")
                }
            } else {
                part.delete()
                val target = dir.createFile(mime, fileName) ?: run {
                    Log.e(TAG, "createFile returned null for $fileName")
                    dir.findFile("$fileName.old")?.renameTo(fileName)
                    return false
                }
                if (!stream(context, source, target)) {
                    target.delete()
                    dir.findFile("$fileName.old")?.renameTo(fileName)
                    return false
                }
            }
            dir.findFile("$fileName.old")?.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "replaceFile failed: $fileName", e)
            false
        }
    }

    /** Removes [fileName] if it is there. True when it is absent afterwards. */
    fun deleteFile(dir: DocumentFile, fileName: String): Boolean =
        dir.findFile(fileName)?.delete() ?: true

    private fun stream(context: Context, source: File, target: DocumentFile): Boolean = try {
        context.contentResolver.openOutputStream(target.uri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
            true
        } ?: run {
            Log.e(TAG, "openOutputStream null for ${target.name}")
            false
        }
    } catch (e: Exception) {
        Log.e(TAG, "stream failed: ${target.name}", e)
        false
    }
}
