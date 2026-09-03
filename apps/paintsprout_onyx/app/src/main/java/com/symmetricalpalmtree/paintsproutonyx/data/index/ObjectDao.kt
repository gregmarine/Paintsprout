package com.symmetricalpalmtree.paintsproutonyx.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Every column of an index row except the cover picture.
 *
 * A shelf full of sketchbooks draws from this list, and a cover is a full-size WEBP. Reading whole
 * rows to lay out one screen would pull every cover in the library out of the encrypted file, decrypt
 * it, and throw all but a handful away — the library would get slower with every sketchbook added to
 * it, which is precisely backwards. So the listing reads this projection, and a card asks for its own
 * cover, one at a time, only when it is actually about to be drawn.
 */
private const val SUMMARY_COLS =
    "id, type, name, parentId, createdAt, updatedAt, pageCount, flags, paperKind"

/**
 * The reads and writes of the global index, one statement each.
 *
 * Two habits run through all of it. **Listings are alive-only** — nothing is ever removed from this
 * table, only stamped with a `deletedAt`, so every query that means "what is on the shelf" has to say
 * `deletedAt IS NULL` out loud. And **listings never touch `blob`** — see [SUMMARY_COLS].
 *
 * Room dispatches suspend functions to its own executor, so a caller on the main thread is fine here;
 * it is the layers above that must not block.
 */
@Dao
interface ObjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ObjectEntity)

    @Query("SELECT * FROM objects WHERE id = :id")
    suspend fun byId(id: String): ObjectEntity?

    @Query("SELECT $SUMMARY_COLS FROM objects WHERE id = :id")
    suspend fun summaryById(id: String): ObjectSummary?

    /**
     * The same row, but only while it is still on the shelf.
     *
     * The breadcrumb walks parents with this rather than [summaryById]. A deleted row is still
     * perfectly readable by id, so the plain read would happily draw a trail through a folder that is
     * no longer in the library and offer it as somewhere to tap — a crumb that navigates to an empty
     * screen with no way to explain itself.
     */
    @Query("SELECT $SUMMARY_COLS FROM objects WHERE id = :id AND deletedAt IS NULL")
    suspend fun aliveSummaryById(id: String): ObjectSummary?

    /**
     * The rows of a known handful of ids, alive only, cover-free — in whatever order SQLite likes.
     *
     * The pinned shelf and the recents shelf are both a list of ids from somewhere else that has to
     * become a list of cards, and neither of them is a `parentId` query: what they have in common is
     * not where they live. Asked one id at a time through a whole-row read this would pull every
     * pinned cover out of the encrypted index and discard it, which is the exact cost [SUMMARY_COLS]
     * exists to avoid.
     *
     * The order is the caller's to restore. It matters — pinned order and recency are the whole point
     * of those two shelves — and `IN` does not preserve it, so the caller re-sorts against the list
     * it asked with.
     */
    @Query("SELECT $SUMMARY_COLS FROM objects WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun aliveSummariesByIds(ids: List<String>): List<ObjectSummary>

    /** How many alive children of [type] sit directly under [parentId]. Same null-parent arm as above. */
    @Query(
        "SELECT count(*) FROM objects " +
            "WHERE type = :type AND deletedAt IS NULL AND " +
            "((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)"
    )
    suspend fun countChildrenOfType(parentId: String?, type: String): Int

    /**
     * The alive children of [parentId] of one [type], cover-free.
     *
     * `parentId` being null means the root shelf rather than "any parent", and SQL will not compare a
     * null with `=` — hence the explicit null arm. Without it the root of the library would come back
     * empty, which reads exactly like a lost library.
     */
    @Query(
        "SELECT $SUMMARY_COLS FROM objects " +
            "WHERE type = :type AND deletedAt IS NULL AND " +
            "((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)"
    )
    suspend fun childrenOfType(parentId: String?, type: String): List<ObjectSummary>

    /**
     * How many alive siblings of [type] under [parentId] already answer to [name].
     *
     * [excludeId] is what makes renaming a sketchbook to the name it already has succeed instead of
     * reporting a clash with itself.
     *
     * **`COLLATE NOCASE`, because the shelf itself does not care about case.** The cards are ordered
     * case-insensitively, so "Studies" and "studies" would sit next to each other, identical to read
     * and impossible to tell apart — which is the exact confusion this check exists to prevent. Names
     * here are restricted to ASCII by `NameRules`, which is the range NOCASE actually covers, so the
     * collation is doing the whole job rather than most of it.
     */
    @Query(
        "SELECT count(*) FROM objects WHERE type = :type AND deletedAt IS NULL AND " +
            "name = :name COLLATE NOCASE AND " +
            "((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) AND id <> :excludeId"
    )
    suspend fun countSiblingsNamed(parentId: String?, type: String, name: String, excludeId: String): Int

    @Query("UPDATE objects SET name = :name, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: String, name: String, at: Long)

    @Query("UPDATE objects SET parentId = :parentId, updatedAt = :at WHERE id = :id")
    suspend fun move(id: String, parentId: String?, at: Long)

    /**
     * Stamp a row deleted. `AND deletedAt IS NULL` keeps the *first* deletion's timestamp when a
     * folder sweep reaches the same row twice — the moment something left the shelf should not move
     * because of how the sweep walked.
     */
    @Query("UPDATE objects SET deletedAt = :at WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: String, at: Long)

    /**
     * A real edit happened, now.
     *
     * Renames and moves, which are things the artist did to the shelf that the `.soil` file knows
     * nothing about. **Not opening and closing a sketchbook** — that used to bump it unconditionally,
     * which quietly meant that flipping through an old sketchbook to look at it filed it as the
     * newest work in the library. The close carries [touchIfNewer] instead.
     */
    @Query("UPDATE objects SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    /**
     * Move `updatedAt` forward to [at], and only forward.
     *
     * This is how a sketchbook being closed hands the shelf the file's own last-edit stamp — the one
     * the `.soil` bumps on every mark, erase and page added or thrown away, and on nothing else. The
     * index's copy is not simply that stamp, because the index also moves for renames and moves that
     * the file never hears about; a plain assignment on close would drag a sketchbook renamed this
     * morning and opened this afternoon back to whenever it was last drawn in.
     *
     * `AND updatedAt < :at` is the whole guarantee. The stamp can be brought up to date by a close;
     * it can never be walked backwards by one.
     */
    @Query("UPDATE objects SET updatedAt = :at WHERE id = :id AND updatedAt < :at")
    suspend fun touchIfNewer(id: String, at: Long)

    /**
     * Page count and cover are the two things the shelf shows that the artist never types. Neither
     * moves `updatedAt`: they are consequences of an edit that has already been recorded, and bumping
     * the modified time twice for one drawing session would let a sketchbook drift to the top of a
     * "last worked on" sort for reasons that have nothing to do with work.
     */
    @Query("UPDATE objects SET pageCount = :count WHERE id = :id")
    suspend fun setPageCount(id: String, count: Int)

    @Query("UPDATE objects SET blob = :cover WHERE id = :id")
    suspend fun setCover(id: String, cover: ByteArray?)

    /** One card's cover. Deliberately its own read — see [SUMMARY_COLS]. */
    @Query("SELECT blob FROM objects WHERE id = :id")
    suspend fun cover(id: String): ByteArray?

    // ── The pinned shelf ─────────────────────────────────────────────────────

    @Query("SELECT * FROM objects WHERE type = 'list_item' AND parentId = :listId AND refId = :memberId LIMIT 1")
    suspend fun listItem(listId: String, memberId: String): ObjectEntity?

    @Query("SELECT refId FROM objects WHERE type = 'list_item' AND parentId = :listId ORDER BY sortOrder")
    suspend fun listMemberIds(listId: String): List<String>

    @Query("SELECT coalesce(max(sortOrder), -1) FROM objects WHERE type = 'list_item' AND parentId = :listId")
    suspend fun maxSortOrder(listId: String): Int

    /**
     * Membership edges are the one thing here that is really deleted rather than stamped.
     *
     * Everywhere else a soft delete protects something the artist made and might want back. An edge
     * records nothing but "this sketchbook is currently pinned" — unpinning and re-pinning is not an
     * event anyone wants a history of, and a tombstone left behind would have to be stepped over by
     * every read of the shelf forever after.
     */
    @Query("DELETE FROM objects WHERE type = 'list_item' AND parentId = :listId AND refId = :memberId")
    suspend fun deleteListItem(listId: String, memberId: String)

    /** Every edge pointing at [memberId], for the moment a sketchbook leaves the shelf. */
    @Query("DELETE FROM objects WHERE type = 'list_item' AND refId = :memberId")
    suspend fun deleteEdgesTo(memberId: String)
}
