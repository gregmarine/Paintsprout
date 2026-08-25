package com.symmetricalpalmtree.paintsproutonyx.data

import android.content.Context
import java.io.File

/**
 * The only path constructors in the app. Every file the app owns is named here
 * and nowhere else, so "where does this live" always has exactly one answer and
 * a renamed directory can never strand half the code on the old spelling.
 *
 * The Garden is flat and the filenames are UUIDs on purpose: folder structure
 * lives exclusively in the index, never in the filesystem. A filesystem that
 * mirrored the folders would be a second copy of the truth, and second copies
 * drift. Nothing enumerates the Garden either — the index says what exists, the
 * Garden only holds it.
 */

/** The one directory that holds every sketchbook file. */
fun gardenDir(context: Context): File = File(context.getExternalFilesDir(null), "Garden")

/** The single canonical way to a sketchbook's `.soil` path. No other code builds one. */
fun soilFile(context: Context, sketchbookId: String): File =
    File(gardenDir(context), "$sketchbookId.soil")

/** The global index file. */
fun indexFile(context: Context): File = File(context.getExternalFilesDir(null), "paintsprout.db")

/**
 * The SQLite sidecars that may sit beside a database file. Whatever deletes or
 * moves the database must take these with it — a stray `-wal` left behind
 * carries pages of the old file into whatever next claims the name.
 */
fun sidecarsOf(dbFile: File): List<File> =
    listOf("-wal", "-shm", "-journal").map { File(dbFile.path + it) }
