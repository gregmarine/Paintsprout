package com.symmetricalpalmtree.paintsprout.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A row in the index's object table: a folder, a sketchbook, a list, a membership
 * edge, or a singleton.
 *
 * Same universal row as a document object — same names, same epoch-ms integers,
 * same soft delete — with two deliberate divergences. [name] is promoted to a
 * top-level column, because every index read is a listing that needs it and no
 * index row has a competing use for a generic text column. And [parentId] is
 * nullable with NULL for root, where a document row uses `""`.
 *
 * The column order matches `SchemaSql.INDEX_OBJECTS_DDL` exactly, and a test
 * proves it: Room creates these tables, so a drift between the entity and the
 * constants would go unnoticed until an importer or a repair path used the
 * constants and produced a table Room then refuses to open.
 *
 * **Nothing from inside a sketchbook is ever stored here** — no text, no search
 * terms. The one exception is [blob], a cover image, and only under the key-scope
 * rule in [IndexRepository.setCover].
 */
@Entity(
    tableName = "objects",
    indices = [Index(value = ["parentId", "type", "deletedAt"])],
)
data class IndexObject(
    @PrimaryKey val id: String,

    /** [IndexType] discriminator. */
    val type: String,

    /** Display name. The file on disk is named by UUID; this is all the library has. */
    val name: String,

    /** NULL = root. */
    val parentId: String?,

    val createdAt: Long,

    /**
     * Bumped only for real modifications — rename, move, cover, page count,
     * encryption state. Not for pins, not for activity, not for storage-format
     * changes. It is the input to the backup predicate when backup arrives, and
     * getting it wrong in the "yes" direction re-uploads the library.
     */
    val updatedAt: Long,

    /** NULL = alive; epoch ms = soft-deleted. */
    val deletedAt: Long? = null,

    /** Reserved for a user-draggable tree. Listings sort by the user's choice today. */
    @ColumnInfo(name = "order") val order: Int? = null,

    /** Sketchbook: pages, for the card subtitle. Refreshed on close. */
    val pageCount: Int? = null,

    /** Sketchbook bitfield: bit 0 = encrypted. */
    val flags: Int? = null,

    /** Sketchbook: `GLOBAL` or `NOTEBOOK`, non-null only when encrypted. */
    val keyScope: String? = null,

    /** Sketchbook: `FULL_SCREEN` or `PRINT`. */
    val canvasKind: String? = null,

    /** Sketchbook: canvas size in inches (PRINT only) — the card's aspect ratio. */
    val canvasW: Float? = null,
    val canvasH: Float? = null,

    /** `list_item`: the member's id. */
    val refId: String? = null,

    /** `list_item`: position within its list. */
    val sortOrder: Int? = null,

    /** Cover image bytes. Governed by key scope; never any other content. */
    val blob: ByteArray? = null,
) {
    val isEncrypted: Boolean get() = (flags ?: 0) and FLAG_ENCRYPTED != 0

    /** A private-passphrase document: no cover may be cached for it, at any time. */
    val isPrivateScope: Boolean get() = isEncrypted && keyScope == KEY_SCOPE_NOTEBOOK

    /**
     * Identity is [id]; the generated equals would compare [blob] by reference and
     * make two reads of the same row unequal.
     */
    override fun equals(other: Any?): Boolean = other is IndexObject && other.id == id

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val FLAG_ENCRYPTED = 1

        const val KEY_SCOPE_GLOBAL = "GLOBAL"
        const val KEY_SCOPE_NOTEBOOK = "NOTEBOOK"
    }
}

/** The index's type catalog. Folders and lists are container furniture; the rest is ours. */
object IndexType {
    const val FOLDER = "folder"
    const val SKETCHBOOK = "sketchbook"
    const val LIST = "list"
    const val LIST_ITEM = "list_item"
    const val CLIPBOARD = "clipboard"
}

/**
 * Which edits count as a modification.
 *
 * Written down as a table rather than scattered across call sites because the
 * rule is invisible at the point of the write and expensive in both directions:
 * bump too eagerly and every bookkeeping pass re-flags the whole library for
 * backup; bump too rarely and a renamed sketchbook never gets backed up again.
 */
enum class IndexEdit(val bumpsUpdatedAt: Boolean) {
    RENAME(true),
    MOVE(true),
    COVER_REFRESH(true),
    PAGE_COUNT_REFRESH(true),
    ENCRYPTION_CHANGE(true),

    /** A list toggle is not user content and not a modification. */
    PIN_TOGGLE(false),

    /** Ids and verbs in a side table; the row itself did not change. */
    ACTIVITY_LOG(false),

    /** A storage-shape change is not an edit. */
    FORMAT_CONVERSION(false),
}
