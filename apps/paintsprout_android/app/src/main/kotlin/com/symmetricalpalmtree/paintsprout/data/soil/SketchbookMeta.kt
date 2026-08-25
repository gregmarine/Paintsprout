package com.symmetricalpalmtree.paintsprout.data.soil

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a document says about itself.
 *
 * This is what makes a `.soil` self-describing, and therefore what makes export a
 * plain file copy: everything an importing device needs is already inside the
 * file, so exporting never has to open — and therefore never has to unlock — it.
 *
 * **These names are the format**, not an implementation detail: they are what a
 * reader finds inside a file it did not write, so they are pinned by a test.
 * `sketchbook_meta` and `sketchbookId` say what the document is; the *shape* —
 * `folderPath` and the rest of the field set — is the Sprout contract's, carried
 * verbatim.
 *
 * [folderPath] is the clever part and the reason it is carried at all: it holds
 * the *stable UUIDs* of every ancestor folder, ordered root → immediate parent.
 * An importing device walks it in order and recreates the missing folders **with
 * the same ids and names**, so importing one document onto three devices
 * converges on an identical hierarchy — no sync, no server, no merge.
 */
@Serializable
data class SketchbookMeta(
    /** The container's schema version, not the content's. Nothing branches on it yet. */
    val formatVersion: Int = 1,

    /** The document's stable UUID — the same one its filename is built from. */
    val sketchbookId: String,

    /** Display name at last refresh. */
    val name: String,

    val createdAt: Long,
    val updatedAt: Long,

    val encrypted: Boolean = false,

    /** `GLOBAL` or `SKETCHBOOK`; null when not encrypted. */
    val keyScope: String? = null,

    /**
     * Base64 cover. **Plaintext documents only** — always null when encrypted,
     * because this field travels outside the encrypted zone in no sense at all,
     * but a reader that has the file yet not the key must never be handed a
     * picture of its contents.
     */
    val cover: String? = null,

    /** Full ancestry, ordered root → immediate parent. */
    val folderPath: List<FolderRef> = emptyList(),

    /** Stamped at export; absent in a document sitting in the library. */
    val exportedAt: Long? = null,

    /** Which build wrote this. */
    val appVersionCode: Int? = null,
)

@Serializable
data class FolderRef(val id: String, val name: String, val parentId: String? = null)

/**
 * Lenient in both directions, deliberately.
 *
 * A field added by a newer build must not make the record undecodable by an older
 * one, and a field removed by a newer build must not make old records
 * undecodable by it. That is the whole forward-compatibility story for a file
 * format that will be read by builds nobody has written yet.
 */
val SoilJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
