package com.symmetricalpalmtree.paintsproutonyx.data.soil

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Reads and writes the single `sketchbook_meta` row.
 *
 * Raw SQL rather than a Room entity, and on purpose. Room owns the `sketchbook` table because that
 * table has a shape it can check; this is one row with a `CHECK (id = 0)` on it, holding a blob of
 * JSON, and making it an entity would buy nothing but a second table Room's identity hash has an
 * opinion about. It is also the row most likely to be read by something that is not Room at all — a
 * stock `sqlcipher` CLI, or a later tool — which is another reason to keep it plain.
 *
 * Callers are on IO.
 */
object SketchbookMetaStore {

    /**
     * The table is created here as well as at file creation. A sketchbook written by a build that
     * predates this row is otherwise a file that opens fine and then fails on the one statement meant
     * to describe it — and the artist's marks are all still in there, perfectly readable, behind an
     * error about a missing table.
     */
    fun write(db: SupportSQLiteDatabase, meta: SketchbookMeta) {
        db.execSQL(SoilSchema.CREATE_META)
        db.execSQL(
            "INSERT OR REPLACE INTO sketchbook_meta (id, json) VALUES (0, ?)",
            arrayOf(meta.toJson()),
        )
    }

    /**
     * Null when there is no row, or when what is in it cannot be read.
     *
     * Swallowing the failure is the right answer here and only here: this row describes the
     * sketchbook, it is not the sketchbook. A page of drawing is not worth withholding because the
     * label on the cover is smudged.
     */
    fun read(db: SupportSQLiteDatabase): SketchbookMeta? = try {
        db.query("SELECT json FROM sketchbook_meta WHERE id = 0").use { cursor ->
            if (!cursor.moveToFirst()) null else SketchbookMeta.fromJson(cursor.getString(0))
        }
    } catch (_: Exception) {
        null
    }
}
