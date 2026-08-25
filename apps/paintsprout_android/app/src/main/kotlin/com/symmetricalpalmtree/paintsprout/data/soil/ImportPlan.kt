package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexObject

/**
 * What an incoming file claims, checked before anything acts on it.
 *
 * Import is the one path where **the app reads a file it did not write**, and
 * every id in that file becomes either a row key or a filename. So this layer
 * exists to answer three questions with no side effects at all: is the manifest
 * trustworthy, does it collide with something already here, and where would it
 * land — all pure, all testable without a device, because the alternative is
 * finding out on somebody's library.
 *
 * The rule underneath the validation is narrow and absolute: **a document id must
 * be a plain UUID.** `Garden/<id>.soil` is built from it, so an id containing
 * `../` writes wherever the sender likes. [SoilFiles.isDocumentId] is the one
 * place that shape is defined, and this checks the manifest's ids against it —
 * the document's own, and every ancestor folder's, because those become index
 * keys that other rows will point at.
 */
object ImportPlan {

    /** Why an incoming file was refused, or [OK] when it wasn't. */
    enum class Verdict {
        OK,

        /** No `sketchbook_meta`, or a record that would not decode. */
        NO_MANIFEST,

        /** An id in the manifest is not a UUID. Nothing further is trusted. */
        BAD_ID,
    }

    /** What is already here under the incoming document's id. */
    enum class Collision {
        /** Nothing with this id. A plain install. */
        NONE,

        /** A document with this id is in the library. */
        EXISTS,

        /** …and the editor has it open, which nothing may overwrite. */
        EXISTS_AND_OPEN,
    }

    /** What the user chose to do about a collision. */
    enum class Resolution { REPLACE, KEEP_BOTH, CANCEL }

    /**
     * A folder from the incoming ancestry, and whether this device already has it.
     *
     * Recreation is **create-only**: a folder that already exists is used as it
     * stands and never renamed or moved to match the file. The incoming record is
     * a snapshot of somebody else's library, and it does not get to reorganise
     * this one — but it does get to say "this book lived in *that* folder", and
     * because the ids are stable, importing the same document onto three devices
     * converges on the same tree without a server anywhere.
     */
    class FolderStep(val ref: FolderRef, val exists: Boolean)

    class Checked(
        val verdict: Verdict,
        val meta: SketchbookMeta?,
        val badId: String? = null,
    ) {
        val isOk: Boolean get() = verdict == Verdict.OK && meta != null
    }

    /** The manifest, validated. [meta] is null when the file carried none. */
    fun check(meta: SketchbookMeta?): Checked {
        if (meta == null) return Checked(Verdict.NO_MANIFEST, null)
        if (!SoilFiles.isDocumentId(meta.sketchbookId)) {
            return Checked(Verdict.BAD_ID, null, meta.sketchbookId)
        }
        for (folder in meta.folderPath) {
            if (!SoilFiles.isDocumentId(folder.id)) return Checked(Verdict.BAD_ID, null, folder.id)
            val parent = folder.parentId
            if (parent != null && !SoilFiles.isDocumentId(parent)) {
                return Checked(Verdict.BAD_ID, null, parent)
            }
        }
        return Checked(Verdict.OK, meta)
    }

    /**
     * A **tombstone is not a collision.** The index answers `byId` for deleted
     * rows too, so a book the user deleted and is now re-importing looked, on
     * device, like a book they already had — and offered to replace something
     * that was not there. What is left of it is a row to reuse, not a document to
     * ask about.
     */
    fun collisionOf(existing: IndexObject?, isOpen: Boolean): Collision = when {
        existing == null || !existing.isAlive -> Collision.NONE
        isOpen -> Collision.EXISTS_AND_OPEN
        else -> Collision.EXISTS
    }

    /**
     * The ancestry to walk, root first, each marked as already-here or to-create.
     *
     * Anything the incoming record names that this device knows by a *different*
     * type — an id that is a sketchbook here and a folder there — is treated as
     * absent and skipped rather than adopted, because rewriting what a row *is*
     * on the word of a file is not a thing an import gets to do.
     */
    fun folderSteps(path: List<FolderRef>, known: (String) -> IndexObject?): List<FolderStep> =
        path.map { ref ->
            val row = known(ref.id)
            val usable = row != null &&
                row.isAlive &&
                row.type == com.symmetricalpalmtree.paintsprout.data.index.IndexType.FOLDER
            FolderStep(ref, exists = usable)
        }

    /** Where the document lands: its immediate parent folder, or the root. */
    fun parentOf(path: List<FolderRef>): String? = path.lastOrNull()?.id

    /**
     * A name that isn't already taken in the folder it is landing in.
     *
     * Suffixed rather than prompted for. The user asked to import a file, not to
     * hold a conversation about it, and "Harbour (2)" beside "Harbour" says what
     * happened more clearly than a dialog they would have to remember answering.
     */
    fun uniqueName(wanted: String, taken: Collection<String>): String {
        val base = wanted.ifBlank { "Sketchbook" }
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }
}
