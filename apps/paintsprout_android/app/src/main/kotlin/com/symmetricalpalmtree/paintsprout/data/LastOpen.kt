package com.symmetricalpalmtree.paintsprout.data

import android.content.Context

/**
 * Where the user was, so the app can put them back there.
 *
 * This is an ordinary preference file, not an encrypted one, and that constrains
 * what may go in it: **ids and settings, never names and never content.** Storing
 * the sketchbook's title here would be marginally faster and would leak the
 * user's document titles into a plaintext file readable by anything that can read
 * the app's data directory. The name is resolved against the encrypted index at
 * read time instead.
 *
 * A pointer is also only a hint. Before opening anything it must be checked
 * against reality — the index row *and* the file both have to exist — because a
 * stale pointer handed to a create-capable open mints an empty ghost document
 * that then masquerades as the real one.
 */
object LastOpen {

    enum class Kind { SKETCHBOOK, SCRATCHPAD }

    data class Pointer(val kind: Kind, val documentId: String?, val pageId: String?)

    fun save(context: Context, pointer: Pointer) =
        prefs(context).edit().putString(KEY, encode(pointer)).apply()

    fun load(context: Context): Pointer? = decode(prefs(context).getString(KEY, null))

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    /** `KIND|documentId|pageId`, with empty fields for absent ids. */
    fun encode(pointer: Pointer): String =
        listOf(pointer.kind.name, pointer.documentId.orEmpty(), pointer.pageId.orEmpty())
            .joinToString(SEPARATOR)

    /**
     * Returns null for anything that isn't a well-formed pointer to real-looking
     * ids — a truncated value, an unknown kind, or an id that is not a UUID. The
     * app then falls back to its default surface rather than acting on nonsense,
     * which is the self-healing behaviour a pointer file needs: it is written by
     * an older build as often as by this one.
     */
    fun decode(raw: String?): Pointer? {
        val parts = raw?.split(SEPARATOR) ?: return null
        if (parts.size != 3) return null
        val kind = Kind.entries.firstOrNull { it.name == parts[0] } ?: return null
        val documentId = parts[1].takeIf { it.isNotEmpty() }
        val pageId = parts[2].takeIf { it.isNotEmpty() }

        if (documentId != null && !SoilFiles.isDocumentId(documentId)) return null
        if (pageId != null && !SoilFiles.isDocumentId(pageId)) return null
        // A sketchbook pointer without a sketchbook is not a pointer.
        if (kind == Kind.SKETCHBOOK && documentId == null) return null
        return Pointer(kind, documentId, pageId)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "paintsprout_session"
    private const val KEY = "last_open"
    private const val SEPARATOR = "|"
}
