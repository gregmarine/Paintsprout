package com.symmetricalpalmtree.paintsproutonyx.data.soil

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Row-level access to the one `sketchbook` table. Nothing here knows what a page is for — the session
 * and the mark store above it do. This is only how rows get in and out.
 *
 * Two things run through every statement.
 *
 * **`order` is spelled `` `order` `` here and `"order"` in hand-written SQL, always.** It is an SQL
 * keyword; unquoted it parses as the start of an ORDER BY and the statement fails at runtime, in a
 * query that looked perfectly fine when it was written.
 *
 * **Nothing is deleted.** An erased mark is stamped with a `deletedAt` and stays exactly where it was,
 * which is what lets [restore] put it back with its id and its position in the stacking order intact.
 * A real delete would make undo a matter of re-creating something that resembles what was erased,
 * which is not the same thing at all.
 */
@Dao
interface SoilDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SoilObjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SoilObjectEntity>)

    @Query("SELECT * FROM sketchbook WHERE id = :id")
    suspend fun byId(id: String): SoilObjectEntity?

    @Query("SELECT * FROM sketchbook WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SoilObjectEntity>

    /**
     * The living children of one row, of one type, in the order they belong in.
     *
     * This is the whole page load: the sketchbook's `page` rows in page order, then a page's `mark`
     * rows in the order they were laid down. Marks are opaque and stack, so reading them out of order
     * would put an eraser sweep underneath the marks it was meant to take out.
     */
    @Query(
        "SELECT * FROM sketchbook WHERE type = :type AND parentId = :parentId AND deletedAt IS NULL " +
            "ORDER BY `order`"
    )
    suspend fun childrenOfType(parentId: String, type: String): List<SoilObjectEntity>

    /**
     * The sketchbook's own row — the root of the file, the only row whose parent is nothing.
     *
     * It carries the title and the id of the page that was open when the sketchbook was last closed,
     * so reopening lands where the artist left off rather than back at page one.
     */
    @Query("SELECT * FROM sketchbook WHERE type = 'sketchbook' AND parentId = '' LIMIT 1")
    suspend fun sketchbookRow(): SoilObjectEntity?

    /** What the shelf's card shows. Alive pages only — a deleted page is not a page you can turn to. */
    @Query("SELECT count(*) FROM sketchbook WHERE type = 'page' AND deletedAt IS NULL")
    suspend fun livePageCount(): Int

    /**
     * Everything a page is currently holding.
     *
     * Deleting a page has to carry its marks down with it, and undoing that has to bring exactly the
     * same set back — so the set is read once, up front, and the same list of ids drives both
     * directions. Recomputing it on the way back would be asking a different question of a table that
     * has changed since.
     *
     * Ids only, so nothing drags a mark's geometry through a bookkeeping read.
     */
    @Query("SELECT id FROM sketchbook WHERE parentId = :pageId AND type = 'mark' AND deletedAt IS NULL")
    suspend fun liveChildIds(pageId: String): List<String>

    @Query("UPDATE sketchbook SET deletedAt = :at, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDelete(ids: List<String>, at: Long)

    /** Undo of an erase, or of a page thrown away. Rows already alive are left alone. */
    @Query("UPDATE sketchbook SET deletedAt = NULL, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NOT NULL")
    suspend fun restore(ids: List<String>, at: Long)

    @Query("UPDATE sketchbook SET refId = :refId, updatedAt = :at WHERE id = :id")
    suspend fun setRefId(id: String, refId: String?, at: Long)

    @Query("UPDATE sketchbook SET text = :text, updatedAt = :at WHERE id = :id")
    suspend fun setText(id: String, text: String?, at: Long)

    @Query("UPDATE sketchbook SET `order` = :order, updatedAt = :at WHERE id = :id")
    suspend fun setOrder(id: String, order: Int, at: Long)

    @Query("UPDATE sketchbook SET blob = :blob, updatedAt = :at WHERE id = :id")
    suspend fun setBlob(id: String, blob: ByteArray?, at: Long)

    /**
     * The next place in the stacking order, counted over the **erased rows as well as the living
     * ones**.
     *
     * If it counted only what is alive, then erasing the top mark and drawing a new one would hand the
     * new mark the number the erased one still holds. Undo the erase and two marks claim the same
     * position, and which of them ends up on top is down to how SQLite felt about the tie. Counting
     * the dead as well means a restored mark always comes back underneath anything drawn after it was
     * taken out, which is where it was.
     */
    @Query("SELECT COALESCE(MAX(`order`), -1) FROM sketchbook WHERE parentId = :parentId AND type = :type")
    suspend fun maxOrder(parentId: String, type: String): Int
}
