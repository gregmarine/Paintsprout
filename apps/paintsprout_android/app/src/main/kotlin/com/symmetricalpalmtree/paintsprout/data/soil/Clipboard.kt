package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.Sentinels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * What was copied, waiting to be pasted somewhere.
 *
 * The clipboard holds **ops**, not pixels: the same universal object rows a page
 * holds, in the index's own `clipboard` table. That is what makes a paste into
 * another book, or into the scratchpad, ordinary code — the rows are already in
 * the shape the target wants, and a mark keeps being a mark rather than becoming
 * a picture of one.
 *
 * ```
 * clipboard_root (sentinel, parentId = "")
 *   └── op rows          ← order 0..n-1, the sequence they were drawn in
 *         └── clip / wet-state rows
 * ```
 *
 * It survives process death because it is a table, not a field. It survives
 * *damage* because every read is per item: one unreadable blob costs the mark it
 * describes, never the paste.
 *
 * The `…In(store)` functions are the whole implementation; the suspending ones
 * are the app's way in. That split is what lets the clipboard's rules be tested
 * against real SQL without a device.
 */
object Clipboard {

    /** What the rail needs to know without decoding anything. */
    class Summary(val count: Int, val sourceDocumentId: String?) {
        val isEmpty: Boolean get() = count == 0
    }

    suspend fun store(): ObjectStore =
        ObjectTable(IndexGate.awaitConnection(), SchemaSql.CLIPBOARD_TABLE)

    suspend fun replace(
        subtree: List<SoilObject>,
        opIds: List<String>,
        sourceDocumentId: String,
    ): Int = withContext(Dispatchers.IO) {
        replaceIn(store(), subtree, opIds, sourceDocumentId)
    }

    suspend fun contents(): List<SoilObject> = withContext(Dispatchers.IO) { contentsIn(store()) }

    suspend fun summary(): Summary = withContext(Dispatchers.IO) { summaryIn(store()) }

    suspend fun clear() = withContext(Dispatchers.IO) {
        val store = store()
        store.transaction { clearIn(store) }
    }

    /**
     * Replaces the clipboard's contents with [subtree].
     *
     * [subtree] is the copied ops **and everything under them**, exactly as read
     * from the source document; [opIds] names which of those rows are the ops
     * themselves, in the order they were drawn.
     *
     * Ids are minted here rather than by the caller, through the one shared
     * helper — see [Subtrees.remapIds] for why there is only one of those. The
     * index correspondence below relies on `remapIds` returning its rows in the
     * order it was given them, which it does because it is a `map`.
     */
    fun replaceIn(
        store: ObjectStore,
        subtree: List<SoilObject>,
        opIds: List<String>,
        sourceDocumentId: String,
        now: Long = System.currentTimeMillis(),
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Int = store.transaction {
        clearIn(store)
        val root = ensureRootIn(store, now)
        val fresh = Subtrees.remapIds(subtree, newId)
        store.upsertAll(
            subtree.indices.map { i ->
                val position = opIds.indexOf(subtree[i].id)
                val row = fresh[i].copy(createdAt = now, updatedAt = now)
                // The copied ops hang off the clipboard root; everything under
                // them keeps the parent `remapIds` already rewired.
                if (position >= 0) {
                    row.copy(parentId = Sentinels.CLIPBOARD_ROOT_ID, order = position)
                } else {
                    row
                }
            },
        )
        // The root doubles as the metadata: what was copied, how much of it, and
        // out of which document. Ids and counts only — a preview image here would
        // be content, cached under the global key, for something the user has
        // merely copied.
        store.upsert(root.copy(refId = sourceDocumentId, opCount = opIds.size, updatedAt = now))
        opIds.size
    }

    /** The copied ops, oldest first, followed by their attachments. */
    fun contentsIn(store: ObjectStore): List<SoilObject> {
        val ops = store.childrenOf(setOf(Sentinels.CLIPBOARD_ROOT_ID)).sortedBy { it.order }
        return ops + store.childrenOf(ops.map { it.id })
    }

    fun summaryIn(store: ObjectStore): Summary = Summary(
        count = store.childrenOf(setOf(Sentinels.CLIPBOARD_ROOT_ID)).size,
        sourceDocumentId = store.byId(Sentinels.CLIPBOARD_ROOT_ID)?.refId,
    )

    /**
     * A hard delete, not a tombstone.
     *
     * The clipboard is not user history — nothing undoes a copy — and a table
     * that only ever grew would carry every selection ever copied for the life of
     * the install.
     */
    fun clearIn(store: ObjectStore) {
        val below = Subtrees.collect(Sentinels.CLIPBOARD_ROOT_ID, store::childrenOf)
        if (below.isNotEmpty()) store.hardDelete(below.map { it.id })
    }

    private fun ensureRootIn(store: ObjectStore, now: Long): SoilObject {
        store.byId(Sentinels.CLIPBOARD_ROOT_ID)?.let { return it }
        val root = SoilObject(
            id = Sentinels.CLIPBOARD_ROOT_ID,
            parentId = "",
            type = SoilType.SKETCHBOOK,
            createdAt = now,
            updatedAt = now,
        )
        store.insert(root)
        return root
    }
}
