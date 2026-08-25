package com.symmetricalpalmtree.paintsproutonyx.data.index

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room's handle on the global index, `paintsprout.db` — the shelf's own memory: which sketchbooks
 * exist, what they are called, which folder they sit in, which are pinned, and the little cover
 * picture each one shows.
 *
 * None of that lives in the sketchbook files themselves, and none of it is derived from the
 * directory they sit in. A `.soil` file is named by a UUID and carries no folder, so a sketchbook
 * can be copied anywhere without its name or its place following it around as a filename — the
 * index is what remembers where it was left. The price is that this one file matters: it is
 * encrypted from the first byte and it is opened exactly once, by [PaintsproutIndex], and never
 * closed.
 *
 * `user_version` is [VERSION]. There are no migrations yet; the day there is one, it belongs here.
 */
@Database(entities = [ObjectEntity::class], version = IndexDatabase.VERSION, exportSchema = true)
abstract class IndexDatabase : RoomDatabase() {

    abstract fun objectDao(): ObjectDao

    companion object {
        const val VERSION = 1
    }
}
