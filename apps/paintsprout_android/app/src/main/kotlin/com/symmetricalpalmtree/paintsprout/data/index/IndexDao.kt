package com.symmetricalpalmtree.paintsprout.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * The index's queries, as constants.
 *
 * They live here rather than inline in the annotations so the same string is both
 * the DAO's query and the one a unit test executes against a real SQLite built
 * from `SchemaSql` — semantics get tested off-device, and the query and the
 * schema cannot drift apart without something failing.
 *
 * `parentId IS :parentId` rather than `=` throughout: root is NULL, and `=` never
 * matches NULL, so the obvious query would silently return an empty root folder.
 */
object IndexSql {

    const val BY_ID = "SELECT * FROM objects WHERE id = :id"

    const val LIVE_BY_IDS = "SELECT * FROM objects WHERE id IN (:ids) AND deletedAt IS NULL"

    const val LIVE_CHILDREN =
        "SELECT * FROM objects WHERE parentId IS :parentId AND deletedAt IS NULL"

    /** The shape of essentially every library read. */
    const val LIVE_CHILDREN_OF_TYPE =
        "SELECT * FROM objects WHERE parentId IS :parentId AND type = :type " +
            "AND deletedAt IS NULL ORDER BY name COLLATE NOCASE"

    const val LIVE_OF_TYPE =
        "SELECT * FROM objects WHERE type = :type AND deletedAt IS NULL ORDER BY name COLLATE NOCASE"

    /**
     * Filename search, and only filename search. No content can reach this table,
     * so "search inside artwork" is forced to be a deliberate design decision
     * rather than something that leaks in.
     */
    const val SEARCH_BY_NAME =
        "SELECT * FROM objects WHERE type = :type AND deletedAt IS NULL " +
            "AND name LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY name COLLATE NOCASE"

    const val LIST_MEMBER_EDGES =
        "SELECT * FROM objects WHERE parentId = :listId AND type = 'list_item' ORDER BY sortOrder"

    const val MAX_SORT_ORDER =
        "SELECT MAX(sortOrder) FROM objects WHERE parentId = :listId AND type = 'list_item'"

    /**
     * Membership churn hard-deletes. Soft deletes exist to protect user content; a
     * pin toggle is not user content, and tombstoning it would leave every list
     * accumulating dead rows forever. This is the one routine hard delete here.
     */
    const val DELETE_EDGE = "DELETE FROM objects WHERE parentId = :listId AND type = 'list_item' AND refId = :refId"

    /** Deleting a member scrubs its edges everywhere, before the member itself goes. */
    const val DELETE_EDGES_FOR = "DELETE FROM objects WHERE type = 'list_item' AND refId = :refId"

    /**
     * Tombstones from before a moment, for the compactor.
     *
     * The index accumulates these the same way a document does, and they are not
     * weightless: a deleted sketchbook's row still carries its **cover**, which is
     * a picture of artwork the user asked to be rid of.
     */
    const val PURGE_TOMBSTONES = "DELETE FROM objects WHERE deletedAt IS NOT NULL AND deletedAt < :before"

    const val TOMBSTONED_BEFORE = "SELECT * FROM objects WHERE deletedAt IS NOT NULL AND deletedAt < :before"

    const val SOFT_DELETE = "UPDATE objects SET deletedAt = :at, updatedAt = :at WHERE id = :id"

    const val COUNT_LIVE_CHILDREN =
        "SELECT COUNT(*) FROM objects WHERE parentId IS :parentId AND deletedAt IS NULL"
}

@Dao
interface IndexDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: IndexObject)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: IndexObject)

    @Update
    suspend fun update(row: IndexObject)

    @Query(IndexSql.BY_ID)
    suspend fun byId(id: String): IndexObject?

    @Query(IndexSql.LIVE_BY_IDS)
    suspend fun liveByIds(ids: List<String>): List<IndexObject>

    @Query(IndexSql.LIVE_CHILDREN)
    suspend fun liveChildren(parentId: String?): List<IndexObject>

    @Query(IndexSql.LIVE_CHILDREN_OF_TYPE)
    suspend fun liveChildrenOfType(parentId: String?, type: String): List<IndexObject>

    @Query(IndexSql.LIVE_OF_TYPE)
    suspend fun liveOfType(type: String): List<IndexObject>

    @Query(IndexSql.SEARCH_BY_NAME)
    suspend fun searchByName(type: String, query: String): List<IndexObject>

    @Query(IndexSql.LIST_MEMBER_EDGES)
    suspend fun listMemberEdges(listId: String): List<IndexObject>

    @Query(IndexSql.MAX_SORT_ORDER)
    suspend fun maxSortOrder(listId: String): Int?

    @Query(IndexSql.DELETE_EDGE)
    suspend fun deleteEdge(listId: String, refId: String)

    @Query(IndexSql.DELETE_EDGES_FOR)
    suspend fun deleteEdgesFor(refId: String)

    @Query(IndexSql.SOFT_DELETE)
    suspend fun softDelete(id: String, at: Long)

    @Query(IndexSql.PURGE_TOMBSTONES)
    suspend fun purgeTombstones(before: Long): Int

    @Query(IndexSql.TOMBSTONED_BEFORE)
    suspend fun tombstonedBefore(before: Long): List<IndexObject>

    @Query(IndexSql.COUNT_LIVE_CHILDREN)
    suspend fun countLiveChildren(parentId: String?): Int
}

object ActivitySql {
    /**
     * Most recently touched first, one entry per sketchbook. Grouping rather than
     * `SELECT DISTINCT` over an ordered set, because what's wanted is the *latest*
     * timestamp per book, not the latest rows.
     */
    const val RECENT_IDS =
        "SELECT sketchbookId FROM sketchbook_activity WHERE activityType = :type " +
            "GROUP BY sketchbookId ORDER BY MAX(timestamp) DESC LIMIT :limit"

    const val DELETE_FOR = "DELETE FROM sketchbook_activity WHERE sketchbookId = :sketchbookId"

    const val COUNT = "SELECT COUNT(*) FROM sketchbook_activity"
}

@Dao
interface ActivityDao {

    @Insert
    suspend fun insert(row: ActivityRow)

    @Query(ActivitySql.RECENT_IDS)
    suspend fun recentIds(type: String, limit: Int): List<String>

    @Query(ActivitySql.DELETE_FOR)
    suspend fun deleteFor(sketchbookId: String)

    @Query(ActivitySql.COUNT)
    suspend fun count(): Int
}
