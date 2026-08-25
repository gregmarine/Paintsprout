package com.symmetricalpalmtree.paintsproutonyx.data.index

import com.symmetricalpalmtree.paintsproutonyx.data.soil.FolderRef
import com.symmetricalpalmtree.paintsproutonyx.data.soil.KEY_SCOPE_GLOBAL
import java.util.UUID

/**
 * Everything the app asks of the shelf's memory, in one place.
 *
 * The rules it enforces, rather than the queries it runs:
 *
 *  - **Folders live here and only here.** A sketchbook file is a UUID in a flat `Garden/` directory
 *    with no folder of its own, so the arrangement the artist made — this in that, that renamed — is
 *    a fact about the index and nothing else. Deriving folders from the filesystem instead would make
 *    every rename a file move and every file move a chance to lose a drawing.
 *  - **Nothing is destroyed.** Deleting a sketchbook stamps its row and leaves it; only the pinned
 *    edges are really removed, and those record a preference, not work.
 *  - **`updatedAt` means "worked on".** It moves for a rename, a move, a page added or removed, and
 *    ink. It does not move for a cover refresh or a page recount, which are things the app did on the
 *    artist's behalf after the fact. A "last worked on" sort is only honest if the timestamp behind
 *    it means what its name says.
 *  - **Display names never leave this file.** They are in the encrypted index; prefs hold ids.
 *
 * Suspend throughout, and Room dispatches to its own executor, so a caller on the main thread is
 * safe. Nothing here is allowed to be wrapped in `runBlocking`.
 */
class IndexRepository(private val dao: ObjectDao = PaintsproutIndex.dao()) {

    // ── Listing ──────────────────────────────────────────────────────────────

    suspend fun folders(parentId: String?): List<ObjectSummary> = dao.childrenOfType(parentId, ObjectType.FOLDER)

    suspend fun sketchbooks(parentId: String?): List<ObjectSummary> =
        dao.childrenOfType(parentId, ObjectType.SKETCHBOOK)

    suspend fun get(id: String): ObjectEntity? = dao.byId(id)

    suspend fun summary(id: String): ObjectSummary? = dao.summaryById(id)

    /**
     * The folder or sketchbook with this id, if it is still on the shelf.
     *
     * Worth its own call because a deleted row is still readable by id, and a screen that was left
     * open across a delete would otherwise happily go on showing something that is gone.
     */
    suspend fun alive(id: String): ObjectSummary? =
        dao.byId(id)?.takeIf { it.deletedAt == null }?.let {
            ObjectSummary(
                id = it.id,
                type = it.type,
                name = it.name,
                parentId = it.parentId,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                pageCount = it.pageCount,
                flags = it.flags,
                paperKind = it.paperKind,
            )
        }

    /**
     * True when something alive of [type] under [parentId] is already called [name].
     *
     * Two sketchbooks with the same name are not a database problem — the ids differ and nothing
     * breaks. They are a problem for the person looking at the shelf trying to remember which is
     * which, which is why this is checked at the point of naming and not by a constraint.
     */
    suspend fun nameTaken(parentId: String?, type: String, name: String, excludeId: String = ""): Boolean =
        dao.countSiblingsNamed(parentId, type, name, excludeId) > 0

    // ── Making things ────────────────────────────────────────────────────────

    suspend fun createFolder(
        name: String,
        parentId: String?,
        now: Long = System.currentTimeMillis(),
    ): ObjectEntity {
        val row = ObjectEntity(
            id = UUID.randomUUID().toString(),
            type = ObjectType.FOLDER,
            name = name,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(row)
        return row
    }

    /**
     * Record a sketchbook whose `.soil` file the caller has already made.
     *
     * The id is the caller's because it is also the filename, and the file has to exist before the
     * shelf claims it does. The other order — index row first, file second — leaves a card on the
     * shelf that opens onto nothing if the write fails, and there is no worse thing for a library to
     * do than lie about what it holds.
     */
    suspend fun createSketchbook(
        id: String,
        name: String,
        parentId: String?,
        paperKind: String,
        pageCount: Int = 1,
        now: Long = System.currentTimeMillis(),
    ): ObjectEntity {
        val row = ObjectEntity(
            id = id,
            type = ObjectType.SKETCHBOOK,
            name = name,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            pageCount = pageCount,
            flags = SketchbookFlags.ENCRYPTED,
            keyScope = KEY_SCOPE_GLOBAL,
            paperKind = paperKind,
        )
        dao.upsert(row)
        return row
    }

    // ── Changing things ──────────────────────────────────────────────────────

    suspend fun rename(id: String, name: String, now: Long = System.currentTimeMillis()) =
        dao.rename(id, name, now)

    suspend fun move(id: String, newParentId: String?, now: Long = System.currentTimeMillis()) =
        dao.move(id, newParentId, now)

    /** A real edit happened. See the `updatedAt` discipline in this class's own comment. */
    suspend fun touch(id: String, now: Long = System.currentTimeMillis()) = dao.touch(id, now)

    suspend fun setPageCount(id: String, count: Int) = dao.setPageCount(id, count)

    suspend fun setCover(id: String, cover: ByteArray?) = dao.setCover(id, cover)

    suspend fun cover(id: String): ByteArray? = dao.cover(id)

    // ── Taking things off the shelf ──────────────────────────────────────────

    /**
     * Take a sketchbook off the shelf: scrub its pinned edges, then stamp the row.
     *
     * The edges go first. If the stamp landed first and the scrub never ran, the pinned shelf would
     * hold an edge pointing at a row it is not allowed to show, and every read of it would have to
     * filter the dead ones out forever.
     *
     * **The file is the caller's to remove**, along with its cached key. This is the index, and the
     * index deleting files behind the caller's back is how a delete that was supposed to be undoable
     * turns out not to be.
     */
    suspend fun deleteSketchbook(id: String, now: Long = System.currentTimeMillis()) {
        dao.deleteEdgesTo(id)
        dao.softDelete(id, now)
    }

    /**
     * Take a folder and everything inside it off the shelf, and hand back the ids of the sketchbooks
     * that were in there so the caller can deal with their files and their cached keys.
     *
     * The walk is explicit rather than recursive and carries a `seen` set, because `parentId` is a
     * plain column with nothing stopping a folder from being its own ancestor — a bad move, a
     * half-applied restore, a hand-edited file. A cycle here would be an infinite delete, so the guard
     * is the difference between a corrupt row and a hung app.
     */
    suspend fun deleteFolderRecursive(id: String, now: Long = System.currentTimeMillis()): List<String> {
        val sketchbookIds = mutableListOf<String>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { add(id) }
        while (stack.isNotEmpty()) {
            val folderId = stack.removeLast()
            if (!seen.add(folderId)) continue
            for (book in dao.childrenOfType(folderId, ObjectType.SKETCHBOOK)) {
                dao.deleteEdgesTo(book.id)
                dao.softDelete(book.id, now)
                sketchbookIds += book.id
            }
            for (sub in dao.childrenOfType(folderId, ObjectType.FOLDER)) stack.add(sub.id)
            dao.softDelete(folderId, now)
        }
        return sketchbookIds
    }

    // ── Where am I ───────────────────────────────────────────────────────────

    /**
     * The chain of folders from the root down to [folderId] inclusive — what the breadcrumb reads.
     *
     * Root-first because that is the reading order, walked child-to-parent and reversed because that
     * is the direction the rows actually point. Cycle-guarded for the same reason the delete sweep is,
     * and hop-capped besides: a breadcrumb fifty folders deep is already unusable, so the cap costs
     * nothing real and turns a pathological file into a truncated trail rather than a frozen screen.
     */
    suspend fun ancestry(folderId: String?): List<FolderRef> {
        val chain = ArrayList<FolderRef>()
        val seen = HashSet<String>()
        var cur = folderId
        var hops = 0
        while (cur != null && hops < MAX_ANCESTRY_HOPS && seen.add(cur)) {
            val row = dao.summaryById(cur) ?: break
            if (row.type != ObjectType.FOLDER) break
            chain.add(FolderRef(row.id, row.name, row.parentId))
            cur = row.parentId
            hops++
        }
        chain.reverse()
        return chain
    }

    /**
     * True when [candidateAncestorId] is [folderId] itself or sits above it.
     *
     * This is what stops a folder being moved into its own contents. That move is not a mistake the
     * artist can see coming and not one the shelf can show afterwards: the folder and everything in it
     * would simply stop being reachable from the root, still there, still taking up space, gone.
     */
    suspend fun isSelfOrDescendant(folderId: String?, candidateAncestorId: String): Boolean =
        ancestry(folderId).any { it.id == candidateAncestorId }

    // ── The pinned shelf ─────────────────────────────────────────────────────

    /** Idempotent, and called on the read path rather than by a migration — see [ListIds]. */
    suspend fun ensurePinnedListExists(now: Long = System.currentTimeMillis()) {
        if (dao.byId(ListIds.PINNED_LIST_ID) == null) {
            dao.upsert(
                ObjectEntity(
                    id = ListIds.PINNED_LIST_ID,
                    type = ObjectType.LIST,
                    name = "pinned",
                    parentId = null,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    suspend fun isPinned(sketchbookId: String): Boolean =
        dao.listItem(ListIds.PINNED_LIST_ID, sketchbookId) != null

    /**
     * Pin a sketchbook to the front of the library, at the end of what is already pinned.
     *
     * Pinning something already pinned does nothing rather than adding a second edge — the shelf would
     * otherwise show the same sketchbook twice, and the second one would be unpinnable by tapping the
     * first.
     */
    suspend fun pin(sketchbookId: String, now: Long = System.currentTimeMillis()) {
        ensurePinnedListExists(now)
        if (isPinned(sketchbookId)) return
        dao.upsert(
            ObjectEntity(
                id = UUID.randomUUID().toString(),
                type = ObjectType.LIST_ITEM,
                name = "",
                parentId = ListIds.PINNED_LIST_ID,
                createdAt = now,
                updatedAt = now,
                refId = sketchbookId,
                sortOrder = dao.maxSortOrder(ListIds.PINNED_LIST_ID) + 1,
            )
        )
    }

    suspend fun unpin(sketchbookId: String) = dao.deleteListItem(ListIds.PINNED_LIST_ID, sketchbookId)

    /**
     * The pinned sketchbooks, in the order they were pinned.
     *
     * Filtered against what is actually alive on the way out. A sketchbook deleted through a path that
     * skipped its edges — or restored from a backup taken either side of a delete — would otherwise
     * leave a card on the front shelf that opens onto nothing.
     */
    suspend fun pinnedSketchbookIds(): List<String> =
        dao.listMemberIds(ListIds.PINNED_LIST_ID).filter { alive(it)?.type == ObjectType.SKETCHBOOK }

    private companion object {
        const val MAX_ANCESTRY_HOPS = 50
    }
}
