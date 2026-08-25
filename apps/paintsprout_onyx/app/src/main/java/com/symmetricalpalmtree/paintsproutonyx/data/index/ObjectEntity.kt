package com.symmetricalpalmtree.paintsproutonyx.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Row types in the global index. */
object ObjectType {
    const val FOLDER = "folder"
    const val SKETCHBOOK = "sketchbook"
    const val LIST = "list"
    const val LIST_ITEM = "list_item"
}

/** Sketchbook `flags` bits. Everything is encrypted here, but the bit is recorded, not assumed. */
object SketchbookFlags {
    const val ENCRYPTED = 1
}

/**
 * A row of the global index `objects` table — one universal row shape for every
 * kind of thing the shelf knows about, deliberately identical in structure to
 * the rest of the Notesprout family. That structural kinship is a locked
 * decision, not an accident: the columns, their order and the index are the
 * contract, and only the vocabulary (`sketchbook` for `notebook`, `paperKind`
 * for `templateKind`) is ours.
 *
 * How the row is read, by [type]:
 *  - folder: [name], [parentId]
 *  - sketchbook: [name], [parentId], [pageCount], [flags], [keyScope],
 *    [paperKind], [blob] = cover (WEBP q100)
 *  - list: a sentinel id ([ListIds]), [name]
 *  - list_item: [parentId] = the list, [refId] = the member, [sortOrder]
 *
 * [updatedAt] moves ONLY on real edits — a rename, new marks, a page added or
 * removed, a move. It is the "last worked on" the shelf sorts by, and a bump
 * from mere machinery (opening, listing, backup) would shuffle the shelf under
 * the artist's hands. Soft deletes only ([deletedAt]); list membership edges
 * are the one routine hard delete.
 */
@Entity(
    tableName = "objects",
    indices = [Index(value = ["parentId", "type", "deletedAt"])],
)
data class ObjectEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val pageCount: Int? = null,
    val flags: Int? = null,
    val keyScope: String? = null,
    val paperKind: String? = null,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray? = null,
    val refId: String? = null,
    val sortOrder: Int? = null,
)

/**
 * The blob-free projection every listing and card read uses. The blob is a
 * cover image; reading whole rows for a listing would drag every cover out of
 * the encrypted index only to throw the bytes away. Covers are fetched one at
 * a time, per card, when a card is actually drawn.
 */
data class ObjectSummary(
    val id: String,
    val type: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int?,
    val flags: Int?,
    val paperKind: String?,
)
