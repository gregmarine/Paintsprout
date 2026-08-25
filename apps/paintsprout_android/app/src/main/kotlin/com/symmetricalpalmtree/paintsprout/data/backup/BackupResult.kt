package com.symmetricalpalmtree.paintsprout.data.backup

/**
 * What happened at one destination.
 *
 * [skipped] and [failed] are separate on purpose: a sketchbook whose file is
 * missing from disk is not a failed copy, and telling the user "1 failed" about
 * a book that was never there sends them looking for a problem with their
 * storage.
 */
data class DestResult(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val indexCopied: Boolean,
    val errors: List<String>,
)

/**
 * A whole run. A destination that could not even be resolved still gets an entry
 * — with its error and nothing attempted — because "Drive is unreachable" is the
 * result the user needs to see, not an empty summary.
 */
data class BackupResult(val perDestination: Map<BackupKind, DestResult>)
