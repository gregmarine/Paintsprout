package com.symmetricalpalmtree.paintsprout.data.index

import androidx.room.withTransaction
import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.data.backup.BackupConfig
import com.symmetricalpalmtree.paintsprout.data.backup.BackupConfigStore
import com.symmetricalpalmtree.paintsprout.data.backup.BackupKind
import com.symmetricalpalmtree.paintsprout.data.backup.needsBackup
import java.util.UUID

/**
 * The only thing that writes to the index.
 *
 * Everything else reads. That is what makes two rules enforceable in one place
 * instead of at every call site: the [IndexEdit] discipline about when
 * `updatedAt` may move, and the leak-hygiene rule about which covers may exist.
 * Both are invisible at the point of a write and expensive to get wrong.
 *
 * [now] and [newId] are parameters so behaviour is reproducible in tests.
 */
class IndexRepository(
    private val db: IndexDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val objects: IndexDao get() = db.objects()
    private val activity: ActivityDao get() = db.activity()

    // --- Bootstrap ----------------------------------------------------------

    /**
     * Creates the sentinel rows if they are absent. Called at every launch, never
     * from a migration — a migration that inserts data can fail halfway on a
     * user's device, and this simply runs again.
     */
    suspend fun ensureSentinels() {
        ensureRow(Sentinels.PINNED_LIST_ID, IndexType.LIST, "Pinned")
        ensureRow(Sentinels.CLIPBOARD_ID, IndexType.CLIPBOARD, "Clipboard")
    }

    private suspend fun ensureRow(id: String, type: String, name: String) {
        if (objects.byId(id) != null) return
        val at = now()
        objects.upsert(IndexObject(id = id, type = type, name = name, parentId = null, createdAt = at, updatedAt = at))
    }

    // --- Reads --------------------------------------------------------------

    suspend fun byId(id: String): IndexObject? = objects.byId(id)

    suspend fun folders(parentId: String?): List<IndexObject> =
        objects.liveChildrenOfType(parentId, IndexType.FOLDER)

    suspend fun sketchbooks(parentId: String?): List<IndexObject> =
        objects.liveChildrenOfType(parentId, IndexType.SKETCHBOOK)

    suspend fun childCount(parentId: String?): Int = objects.countLiveChildren(parentId)

    /** Every folder, for a move picker and for ancestry walks. */
    suspend fun allFolders(): List<IndexObject> = objects.liveOfType(IndexType.FOLDER)

    /** Every sketchbook, wherever it is filed — for a send-to picker. */
    suspend fun allSketchbooks(): List<IndexObject> = objects.liveOfType(IndexType.SKETCHBOOK)

    /**
     * Whether a folder still holds anything.
     *
     * Deleting a folder is refused while it does. The alternative — a recursive
     * delete — would put a user two taps from losing every sketchbook inside
     * something they thought was empty, and the index cannot show them what is in
     * there without them going and looking.
     */
    suspend fun isEmptyFolder(id: String): Boolean = childCount(id) == 0

    /**
     * Filename search. The `%`, `_` and `\` a user can type are literal characters
     * to them and wildcards to SQL; the query is escaped so they behave as typed.
     */
    suspend fun searchSketchbooks(query: String): List<IndexObject> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return objects.searchByName(IndexType.SKETCHBOOK, escapeLike(trimmed))
    }

    /** Folders containing [id], root first. Loads the folder set once and walks in memory. */
    suspend fun ancestryOf(id: String): List<IndexObject> {
        val folders = objects.liveOfType(IndexType.FOLDER).associateBy { it.id }
        val self = objects.byId(id) ?: return emptyList()
        val lookup: (String) -> IndexObject? = { wanted ->
            if (wanted == self.id) self else folders[wanted]
        }
        return Ancestry.pathTo(id, lookup)
    }

    suspend fun breadcrumbOf(id: String): String =
        ancestryOf(id).joinToString(" / ") { it.name }

    // --- Creation -----------------------------------------------------------

    /**
     * [id] is a parameter for one caller: import, recreating the ancestry an
     * incoming document names. The ids are the same on every device, which is what
     * makes importing one file onto three of them converge on the same tree — so
     * the folder has to be created *as* that id, not as a fresh one with the same
     * name.
     */
    suspend fun createFolder(name: String, parentId: String? = null, id: String = newId()): IndexObject {
        val at = now()
        val row = IndexObject(
            id = id, type = IndexType.FOLDER, name = name,
            parentId = parentId, createdAt = at, updatedAt = at,
        )
        return write(row)
    }

    /**
     * Insert, or bring a tombstone back.
     *
     * Only a caller that supplies an id can land on an existing row, and there is
     * one: import, recreating what a document names. Deleting is soft, so the id
     * it names may still be here as a tombstone — and an `INSERT` onto a primary
     * key that exists is a crash, not a no-op. The revived row keeps its original
     * `createdAt`, because that is when the thing was made; everything else is
     * what is arriving now.
     */
    private suspend fun write(row: IndexObject): IndexObject {
        val existing = objects.byId(row.id)
        if (existing == null) {
            objects.insert(row)
            return row
        }
        val revived = row.copy(createdAt = existing.createdAt)
        objects.upsert(revived)
        return revived
    }

    suspend fun createSketchbook(
        name: String,
        parentId: String? = null,
        id: String = newId(),
        canvasKind: String? = null,
        canvasW: Float? = null,
        canvasH: Float? = null,
        encrypted: Boolean = true,
        keyScope: KeyScope = KeyScope.GLOBAL,
    ): IndexObject {
        val at = now()
        val row = IndexObject(
            id = id,
            type = IndexType.SKETCHBOOK,
            name = name,
            parentId = parentId,
            createdAt = at,
            updatedAt = at,
            pageCount = 0,
            flags = if (encrypted) IndexObject.FLAG_ENCRYPTED else 0,
            keyScope = if (encrypted) keyScope.name else null,
            canvasKind = canvasKind,
            canvasW = canvasW,
            canvasH = canvasH,
        )
        return write(row)
    }

    // --- Modification -------------------------------------------------------

    suspend fun rename(id: String, name: String) = edit(id, IndexEdit.RENAME) { it.copy(name = name) }

    /**
     * Moving a folder into its own descendant would create a cycle that every
     * ancestry walk then has to survive; refuse it instead.
     */
    suspend fun move(id: String, newParentId: String?): Boolean {
        val folders = objects.liveOfType(IndexType.FOLDER).associateBy { it.id }
        if (Ancestry.wouldCycle(id, newParentId, { folders[it] })) return false
        edit(id, IndexEdit.MOVE) { it.copy(parentId = newParentId) }
        return true
    }

    suspend fun setPageCount(id: String, pageCount: Int) =
        edit(id, IndexEdit.PAGE_COUNT_REFRESH) { it.copy(pageCount = pageCount) }

    /**
     * Stores a cover — unless the document has its own passphrase.
     *
     * The rule is about **key boundaries**, not about encryption: the index is
     * itself encrypted under the global key, so a global-scope document's cover is
     * protected by exactly the key that protects the document, and suppressing it
     * would cost every library card its thumbnail for no security. A private-scope
     * document is the case this exists for — the user chose a separate passphrase
     * precisely so that content is not readable with the global key.
     *
     * Returns false when the cover was refused, so a caller can render a lock.
     */
    suspend fun setCover(id: String, cover: ByteArray?): Boolean {
        val row = objects.byId(id) ?: return false
        if (row.isPrivateScope) return false
        edit(id, IndexEdit.COVER_REFRESH) { it.copy(blob = cover) }
        return true
    }

    /**
     * Records a change of encryption state. Converting to a private passphrase
     * clears any existing cover **in the same write** — a cover cached under the
     * old scope must not outlive it.
     */
    suspend fun setEncryption(id: String, encrypted: Boolean, keyScope: KeyScope?) =
        edit(id, IndexEdit.ENCRYPTION_CHANGE) { row ->
            val private = encrypted && keyScope == KeyScope.SKETCHBOOK
            row.copy(
                flags = if (encrypted) {
                    (row.flags ?: 0) or IndexObject.FLAG_ENCRYPTED
                } else {
                    (row.flags ?: 0) and IndexObject.FLAG_ENCRYPTED.inv()
                },
                keyScope = if (encrypted) keyScope?.name else null,
                blob = if (private) null else row.blob,
            )
        }

    /**
     * Soft-deletes a row, scrubbing its membership edges first.
     *
     * Order matters: leaving the edges would give every list a dangling reference
     * that each reader then has to defend against, and a list that can resurrect a
     * deleted sketchbook is a picker crash waiting to happen. The document's
     * activity log goes too — the history of a book that no longer exists is not
     * history.
     */
    suspend fun delete(id: String) = db.withTransaction {
        objects.deleteEdgesFor(id)
        activity.deleteFor(id)
        objects.softDelete(id, now())
    }

    private suspend fun edit(id: String, kind: IndexEdit, change: (IndexObject) -> IndexObject) {
        val row = objects.byId(id) ?: return
        val changed = change(row)
        objects.update(if (kind.bumpsUpdatedAt) changed.copy(updatedAt = now()) else changed)
    }

    // --- Pins ---------------------------------------------------------------

    /**
     * Members are resolved and filtered at read: an edge pointing at a missing,
     * soft-deleted or wrong-typed row is skipped silently, so a list can never
     * resurrect a deleted sketchbook or crash a picker.
     */
    suspend fun pinnedSketchbooks(): List<IndexObject> {
        val edges = objects.listMemberEdges(Sentinels.PINNED_LIST_ID)
        val ids = edges.mapNotNull { it.refId }
        if (ids.isEmpty()) return emptyList()
        val members = objects.liveByIds(ids).associateBy { it.id }
        return ids.mapNotNull { members[it] }.filter { it.type == IndexType.SKETCHBOOK }
    }

    suspend fun isPinned(id: String): Boolean =
        objects.listMemberEdges(Sentinels.PINNED_LIST_ID).any { it.refId == id }

    suspend fun pin(id: String) = db.withTransaction {
        if (isPinned(id)) return@withTransaction
        val at = now()
        objects.upsert(
            IndexObject(
                id = newId(),
                type = IndexType.LIST_ITEM,
                name = "",
                parentId = Sentinels.PINNED_LIST_ID,
                createdAt = at,
                updatedAt = at,
                refId = id,
                sortOrder = (objects.maxSortOrder(Sentinels.PINNED_LIST_ID) ?: -1) + 1,
            ),
        )
    }

    /** Hard delete: a pin is not user content, and a tombstoned edge is litter forever. */
    suspend fun unpin(id: String) = objects.deleteEdge(Sentinels.PINNED_LIST_ID, id)

    // --- Recents ------------------------------------------------------------

    /**
     * Purges rows tombstoned before [before], with the activity that pointed at
     * them. Returns how many went.
     *
     * The same rule the document compactor uses, for the same reason, and with
     * one extra: a deleted sketchbook's row still holds its cover, so leaving it
     * means keeping a picture of artwork the user asked to be rid of. Nothing can
     * restore an index tombstone — deleting a book removes its file first — so
     * "before this launch" is margin enough.
     */
    suspend fun compact(before: Long): Int = db.withTransaction {
        val dead = objects.tombstonedBefore(before)
        dead.forEach { activity.deleteFor(it.id) }
        objects.purgeTombstones(before)
    }

    suspend fun recordOpened(id: String) = log(id, ActivityRow.OPENED)

    suspend fun recordEdited(id: String) = log(id, ActivityRow.EDITED)

    private suspend fun log(id: String, type: String) =
        activity.insert(ActivityRow(newId(), id, type, now()))

    // --- Backup -------------------------------------------------------------

    suspend fun backupConfig(): BackupConfig? = BackupConfigStore.read(objects)

    /** Reads the settings, minting defaults on the first ask. */
    suspend fun ensureBackupConfig(defaultDeviceFolderName: String): BackupConfig =
        BackupConfigStore.ensure(objects, defaultDeviceFolderName)

    suspend fun saveBackupConfig(config: BackupConfig) = BackupConfigStore.write(objects, config)

    /** "Not this one." A policy choice about a sketchbook, so [IndexEdit] leaves `updatedAt` alone. */
    suspend fun setExcludedFromBackup(id: String, excluded: Boolean) =
        edit(id, IndexEdit.BACKUP_EXCLUSION) { row ->
            val flags = row.flags ?: 0
            row.copy(
                flags = if (excluded) {
                    flags or IndexObject.FLAG_EXCLUDE_BACKUP
                } else {
                    flags and IndexObject.FLAG_EXCLUDE_BACKUP.inv()
                },
            )
        }

    /**
     * Records that [id] landed at [kind] at [at].
     *
     * Stamped **only on success**, and never bumping `updatedAt` — a failed copy
     * leaves the row flagged so the next run retries it, and a successful one
     * must not immediately re-flag itself.
     */
    suspend fun markBackedUp(id: String, kind: BackupKind, at: Long) =
        edit(id, IndexEdit.BACKUP_STAMP) { row ->
            when (kind) {
                BackupKind.LOCAL -> row.copy(lastBackedUpLocal = at)
                BackupKind.DRIVE -> row.copy(lastBackedUpDrive = at)
            }
        }

    /**
     * Every live sketchbook that has to move to [kind] on the next run.
     *
     * A tombstoned book is not here — the sweep never reaps, so its bytes stay at
     * the destination until somebody tidies up, which is harmless: the restored
     * index simply doesn't reference them.
     */
    suspend fun sketchbooksNeedingBackup(kind: BackupKind): List<IndexObject> =
        objects.liveOfType(IndexType.SKETCHBOOK).filter { row ->
            val last = when (kind) {
                BackupKind.LOCAL -> row.lastBackedUpLocal
                BackupKind.DRIVE -> row.lastBackedUpDrive
            }
            needsBackup(row.updatedAt, last, row.isExcludedFromBackup)
        }

    /**
     * Most recently opened first. Resolved against `objects` at read time, so a
     * renamed book's history renames with it and a deleted one's disappears.
     */
    suspend fun recentSketchbooks(limit: Int = 10): List<IndexObject> {
        val ids = activity.recentIds(ActivityRow.OPENED, limit)
        if (ids.isEmpty()) return emptyList()
        val rows = objects.liveByIds(ids).associateBy { it.id }
        return ids.mapNotNull { rows[it] }.filter { it.type == IndexType.SKETCHBOOK }
    }

    companion object {
        /** `\` first, or it would escape the escapes added after it. */
        fun escapeLike(input: String): String = input
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
