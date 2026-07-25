package com.symmetricalpalmtree.paintsprout.data.soil

import java.util.concurrent.ConcurrentHashMap

/**
 * Which documents are open right now.
 *
 * Small, but it exists to stop a specific disaster: an import that "replaces" a
 * document currently held open by the editor swaps the file out from under a live
 * connection, and corrupts both the incoming copy and the one being edited.
 * Import checks here and fails cleanly instead.
 *
 * It also answers the gentler question of whether a document can be deleted or
 * re-keyed right now — both of which need a sealed file.
 */
object OpenDocuments {

    private val open = ConcurrentHashMap.newKeySet<String>()

    fun register(documentId: String) {
        open.add(documentId)
    }

    fun unregister(documentId: String) {
        open.remove(documentId)
    }

    fun isOpen(documentId: String): Boolean = documentId in open

    fun ids(): Set<String> = open.toSet()

    /** For tests, and for a hard reset after a restore replaces the whole library. */
    fun clear() = open.clear()
}
