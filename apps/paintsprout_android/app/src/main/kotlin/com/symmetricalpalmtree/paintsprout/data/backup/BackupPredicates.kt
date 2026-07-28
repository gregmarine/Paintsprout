package com.symmetricalpalmtree.paintsprout.data.backup

/**
 * Whether one sketchbook has to move to one destination.
 *
 * The whole of the incremental rule, written once and on its own so it can be
 * tested without a database, a device, or a folder to write into: a sketchbook
 * needs backing up when it has never been sent there, or when it has changed
 * since it last was.
 *
 * [updatedAt] is the index row's, which moves only for a real modification — see
 * `IndexEdit`. Getting that wrong in the "yes" direction re-uploads the entire
 * library on every run.
 */
fun needsBackup(updatedAt: Long, lastBackedUp: Long?, excludeFromBackup: Boolean): Boolean {
    if (excludeFromBackup) return false
    return lastBackedUp == null || updatedAt > lastBackedUp
}
