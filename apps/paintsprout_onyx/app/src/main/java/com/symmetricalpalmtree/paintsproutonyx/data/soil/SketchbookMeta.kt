package com.symmetricalpalmtree.paintsproutonyx.data.soil

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON settings this row is written and read with, and each of them earns its place:
 *
 *  - `encodeDefaults` — a field left at its default is still written out. A reader that has to guess
 *    what an absent field meant is a reader that will eventually guess wrong.
 *  - `explicitNulls = false` — a null is simply absent rather than spelled out. Nothing here treats
 *    "absent" and "null" as different, so writing `null` would only make the row longer.
 *  - `ignoreUnknownKeys` — a field written by a later version, or by a sibling app in the family, is
 *    stepped over rather than thrown at. This row exists to say what the file is; refusing to read it
 *    because it says one thing too many would be exactly backwards.
 *  - `isLenient` — the same argument, one level down.
 */
private val codec = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * The only key scope there is. Every sketchbook is encrypted under the one global key, so this is
 * always "GLOBAL" — kept as a written-down string rather than assumed, because a file that says which
 * key opens it can be answered by a reader that has more than one, and a file that stays silent about
 * it can only be guessed at.
 */
const val KEY_SCOPE_GLOBAL = "GLOBAL"

/**
 * The one row of `sketchbook_meta` — the sketchbook saying, in plain readable JSON, what it is.
 *
 * The rest of the file is a wide sparse table that means nothing without the code that wrote it. This
 * row is the part a person can open and understand: which sketchbook this is, what it was called,
 * when it was made, which folder it was sitting in, and that it is encrypted under the global key.
 * That matters most in the case nobody plans for — a `.soil` found on its own, with no index to say
 * anything about it. Without this row it is a UUID and nothing else.
 *
 * The field set is deliberately the same as the rest of the `.soil` family's rather than the shortest
 * set this app could get away with. The family membership *is* the field set: two of these fields
 * ([cover] and [exportedAt]) are never written to anything but their defaults in arc 1, and
 * [textDocument] is never true here at all, because this app draws and does not type. Dropping them
 * would save nothing and would quietly make these files a different format that merely looks like the
 * one it came from. They are here on purpose; do not tidy them away.
 *
 * Ids and names only. No key material ever goes in here — the file is encrypted, but this row is the
 * part most likely to be read by something that is not this app.
 */
@Serializable
data class SketchbookMeta(
    val formatVersion: Int = 1,
    val sketchbookId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val encrypted: Boolean = true,
    val keyScope: String? = KEY_SCOPE_GLOBAL,
    /** Reserved by the family for an embedded cover. Covers live in the index here; always null. */
    val cover: String? = null,
    /** Where the sketchbook was sitting when this row was last refreshed — root-first. */
    val folderPath: List<FolderRef> = emptyList(),
    /** Reserved by the family. Arc 1 exports nothing, so always null. */
    val exportedAt: Long? = null,
    val appVersionCode: Int? = null,
    /** Reserved by the family. This app draws; it is never true here. */
    val textDocument: Boolean = false,
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        fun fromJson(s: String): SketchbookMeta = codec.decodeFromString(serializer(), s)
    }
}

/**
 * One folder on the path down to a sketchbook.
 *
 * A copy of what the index says, frozen at the moment the row was refreshed, and never the authority
 * — folders live in the index. It is here so that a `.soil` found without its index still remembers
 * roughly where it belonged, which is the difference between putting it back and starting again.
 */
@Serializable
data class FolderRef(val id: String, val name: String, val parentId: String?)
