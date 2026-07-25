package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SoilFiles

/**
 * The filename an exported sketchbook is given.
 *
 * In the library a document is a UUID; outside it, it is whatever the user called
 * it — a file in a share sheet, an email attachment, a thing in Downloads with a
 * name they have to recognise months later. So the name travels, and the id stays
 * behind in [NotebookMeta] where it belongs.
 *
 * Every rule here is about the *outside*, where the filename becomes a path in
 * somebody else's storage:
 *
 * - **Spaces are kept.** "Harbour studies.soil" is what the user typed; turning
 *   it into `Harbour_studies.soil` is a tidiness nobody asked for.
 * - **Everything outside `[A-Za-z0-9_\-. ]` is dropped**, which is narrow on
 *   purpose. A colon is a path separator on some systems, a slash is one on all
 *   of them, and a name is not worth a traversal.
 * - **A name that sanitises to nothing falls back to the id.** Emoji-only titles
 *   are a real thing, and a file called `.soil` is not a file.
 */
object ExportName {

    private val ALLOWED = Regex("[^A-Za-z0-9_\\-. ]")

    /** Length cap, well under any filesystem's, leaving room for the extension. */
    const val MAX_LENGTH = 96

    fun of(name: String, documentId: String): String = "${stem(name, documentId)}.${SoilFiles.EXTENSION}"

    fun stem(name: String, documentId: String): String {
        val cleaned = ALLOWED.replace(name, "")
            // Collapse the runs a stripped character leaves behind, then trim: a
            // name that was "Studies / 2026" should not export as "Studies  2026".
            .replace(Regex(" {2,}"), " ")
            .trim()
            // Leading dots hide the file on every unix-like system, and a trailing
            // dot is silently dropped on Windows — either way the name the user
            // sees stops being the name they gave it.
            .trim('.')
            .take(MAX_LENGTH)
            .trim()
        return cleaned.ifEmpty { documentId }
    }
}
