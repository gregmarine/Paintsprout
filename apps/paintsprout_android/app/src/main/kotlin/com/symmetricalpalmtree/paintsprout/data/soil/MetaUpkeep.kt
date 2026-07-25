package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.BuildConfig
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.IndexObject

/**
 * Keeping a document's embedded record true.
 *
 * `notebook_meta` is what makes a `.soil` self-describing, and self-describing is
 * what makes **export a plain file copy**: everything an importing device needs
 * is already inside the file, so exporting never has to open it — and therefore
 * never has to unlock it. A book with its own passphrase exports as ciphertext
 * without anybody being asked for anything.
 *
 * That only holds if the record is *already* current when the copy is taken,
 * which is why upkeep happens at create, at open and at close rather than at
 * export. The library is where a document is renamed and moved, and the library
 * never opens the file to do it — so the two drift apart between sessions, and
 * this is what closes the gap the next time the file is open anyway.
 *
 * Pure, and over an [IndexObject] rather than a database, so the merge rule is
 * testable on its own: which fields the index owns, which the file owns, and
 * which are refused outright.
 */
object MetaUpkeep {

    /**
     * [meta] brought up to date against the library's [row] and [folderPath].
     *
     * The index owns the *name* and *where it is filed*: both are library
     * business, changed there, and the file is the copy that goes stale. The file
     * owns everything else about itself — its id, when it was made, what it is
     * encrypted with — and none of that is taken from the index, which is a
     * separate database that could be restored from a different backup than the
     * document beside it.
     *
     * The cover is the one field with a rule of its own: it travels only for a
     * plaintext document. A reader holding an encrypted file it cannot open must
     * not be handed a picture of what is inside it.
     */
    fun refresh(
        meta: NotebookMeta,
        row: IndexObject?,
        folderPath: List<FolderRef>,
        cover: String? = null,
        now: Long,
        appVersionCode: Int? = meta.appVersionCode,
    ): NotebookMeta = meta.copy(
        name = row?.name?.takeIf { it.isNotBlank() } ?: meta.name,
        folderPath = folderPath,
        updatedAt = now,
        cover = if (meta.encrypted) null else (cover ?: meta.cover),
        appVersionCode = appVersionCode,
    )

    /** The library's ancestry, as the portable record wants it: root → parent. */
    fun folderPathOf(ancestry: List<IndexObject>): List<FolderRef> =
        ancestry.map { FolderRef(id = it.id, name = it.name, parentId = it.parentId) }

    /**
     * Reads the library once and hands back the refresh to apply.
     *
     * A function rather than a value because the seal takes one, and because the
     * index read has to happen *before* the file starts closing — a suspending
     * call inside the seal would be a read on a database on its way out.
     */
    suspend fun from(
        documentId: String,
        now: Long = System.currentTimeMillis(),
        appVersionCode: Int = BuildConfig.VERSION_CODE,
    ): (NotebookMeta) -> NotebookMeta {
        val index = IndexGate.awaitReady()
        val row = index.byId(documentId)
        val folders = MetaUpkeep.folderPathOf(index.ancestryOf(documentId))
        return { meta -> refresh(meta, row, folders, now = now, appVersionCode = appVersionCode) }
    }
}
