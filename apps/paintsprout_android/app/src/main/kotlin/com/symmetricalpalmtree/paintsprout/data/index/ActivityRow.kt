package com.symmetricalpalmtree.paintsprout.data.index

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line of the append-only open/edit log that powers Recents.
 *
 * Two things it deliberately does not do. It holds **ids and verbs, never names
 * or content**, so a renamed sketchbook's history renames with it and a deleted
 * one's history disappears — the name is resolved against `objects` at read time.
 * And it logs only forward-looking facts: "created" is derived from the row's
 * `createdAt` and is never written here, so switching the feature on doesn't
 * require backfilling history that doesn't exist.
 */
@Entity(
    tableName = "sketchbook_activity",
    indices = [
        Index(value = ["activityType", "timestamp"]),
        Index(value = ["sketchbookId"]),
    ],
)
data class ActivityRow(
    @PrimaryKey val id: String,
    val sketchbookId: String,
    val activityType: String,
    val timestamp: Long,
) {
    companion object {
        const val OPENED = "OPENED"
        const val EDITED = "EDITED"
    }
}
