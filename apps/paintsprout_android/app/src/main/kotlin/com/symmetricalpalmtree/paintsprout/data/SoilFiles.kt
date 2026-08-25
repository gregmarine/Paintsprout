package com.symmetricalpalmtree.paintsprout.data

import android.content.Context
import java.io.File

/**
 * Every path in the storage layer is built here and nowhere else.
 *
 * That is a rule, not a convenience. Scattered path construction is how sidecar
 * leaks, orphaned files and — worst — path traversals out of an imported file
 * start. One sketchbook is one SQLCipher database named by its UUID, sitting in
 * a flat `Garden/` directory beside the global index:
 *
 * ```
 * <external files dir>/
 * ├── paintsprout.db          ← the global index (and its live WAL sidecars)
 * └── Garden/                 ← flat blob storage, no subdirectories, ever
 *     ├── 3f2a1b8c-….soil
 *     └── …
 * ```
 *
 * `Garden/` has no structure at all. Folders, display names, ordering and pins
 * are the index's business — which is precisely what makes a sketchbook file
 * portable: it carries no assumption about where it lives.
 *
 * The `root`-taking overloads exist so all of this is testable on the JVM
 * without a device; the `Context` ones are what the app calls.
 */
object SoilFiles {

    /** Documents are `<uuid>.soil` — the same container extension Notesprout uses. */
    const val EXTENSION = "soil"

    /** The flat document directory. Never given subdirectories. */
    const val GARDEN_DIR = "Garden"

    /** The one global index per install. Sits *beside* Garden/, not inside it, so a
     *  sweep over documents never trips over it. */
    const val INDEX_FILE = "paintsprout.db"

    /**
     * The name a file is renamed to while a commit swap is in flight. The swap
     * (rename original aside → rename replacement in → delete the aside) must
     * never leave zero copies of the user's data under a name we can find; this
     * suffix is that name. Launch-time repair looks for it.
     */
    const val ASIDE_SUFFIX = ".old.bak"

    /** A verified replacement waiting to be renamed in. */
    const val TEMP_SUFFIX = ".tmp"

    /** An incoming copy being written; renamed onto the real name only when whole. */
    const val INSTALL_SUFFIX = ".new"

    /**
     * A document id must be a plain UUID and nothing else.
     *
     * This is the guard on the one place untrusted input reaches the filesystem:
     * an imported file's manifest supplies the id that becomes `Garden/<id>.soil`,
     * and an id containing `../` writes wherever the sender likes. Validate at the
     * boundary, and again here, because [soilFile] refuses to build a path from
     * anything else.
     */
    private val UUID_SHAPE =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isDocumentId(id: String): Boolean = UUID_SHAPE.matches(id)

    // --- Directories --------------------------------------------------------

    /**
     * App-private external storage: no runtime permission, visible to the user in
     * a file manager, removed on uninstall.
     */
    fun storageRoot(context: Context): File =
        context.getExternalFilesDir(null)
            ?: error("External files dir unavailable — storage is not mounted")

    fun garden(root: File): File = File(root, GARDEN_DIR).apply { mkdirs() }

    fun garden(context: Context): File = garden(storageRoot(context))

    // --- Files --------------------------------------------------------------

    fun soilFile(root: File, sketchbookId: String): File {
        require(isDocumentId(sketchbookId)) { "Not a document id: $sketchbookId" }
        return File(garden(root), "$sketchbookId.$EXTENSION")
    }

    fun soilFile(context: Context, sketchbookId: String): File =
        soilFile(storageRoot(context), sketchbookId)

    fun indexFile(root: File): File = File(root, INDEX_FILE)

    fun indexFile(context: Context): File = indexFile(storageRoot(context))

    // --- Swap-in-flight names -----------------------------------------------

    fun asideOf(file: File): File = File(file.path + ASIDE_SUFFIX)

    fun tempOf(file: File): File = File(file.path + TEMP_SUFFIX)

    fun installOf(file: File): File = File(file.path + INSTALL_SUFFIX)

    // --- Enumeration --------------------------------------------------------

    /**
     * The id a document file carries, or null if this isn't one.
     *
     * Asides, temps and installs deliberately fail this test: they are not
     * documents, and a sweep that treats them as such would open a half-written
     * file as if it were the real thing.
     */
    fun documentIdOf(file: File): String? {
        val name = file.name
        val id = name.removeSuffix(".$EXTENSION")
        if (id == name) return null
        return if (isDocumentId(id)) id else null
    }

    /** Every real document in the garden. Sidecars and in-flight names excluded. */
    fun listDocuments(root: File): List<File> =
        garden(root).listFiles()?.filter { documentIdOf(it) != null }?.sortedBy { it.name }
            ?: emptyList()

    // --- Sidecars -----------------------------------------------------------

    /**
     * The journal files SQLite keeps beside a database.
     *
     * A file browser must show only document files, so these are checkpointed and
     * removed on a clean close. Two rules about this list: never delete a sidecar
     * while another connection is open to the same database (SQLite removes them
     * itself when the last connection closes, and deleting them under a live
     * connection corrupts its view), and the index is the one file allowed to keep
     * them, because it never closes during normal use.
     */
    fun sidecars(db: File): List<File> =
        listOf(File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"))
}
