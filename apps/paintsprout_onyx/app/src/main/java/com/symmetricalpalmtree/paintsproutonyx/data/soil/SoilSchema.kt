package com.symmetricalpalmtree.paintsproutonyx.data.soil

/**
 * The `.soil` schema — one universal `sketchbook` table plus the single-row
 * `sketchbook_meta`.
 *
 * Room generates the `sketchbook` table from [SoilObjectEntity]; the DDL below
 * is the *contract* those annotations must produce, kept in one place so the
 * docs, any raw-SQL path and any future external reader have a single
 * authoritative statement of what is on disk. `sketchbook_meta` is created by
 * raw SQL in the Room open callback — it is not an entity.
 *
 * The column set is deliberately identical, column for column, to the rest of
 * the Notesprout family's document table. That includes `x` and `y`, which
 * nothing in arc 1 writes: they are object bounds in the family format, and a
 * table that quietly dropped two columns would no longer be the identical
 * structure the locked decision calls for. They are kept so a future arc — or
 * a family tool reading the file — finds the shape it expects. Do not file
 * them as an oversight and delete them; the oversight would be the deletion.
 *
 * `"order"` is an SQL keyword — double-quote it in SQL, backtick it in Room
 * and ContentValues, everywhere, always.
 */
object SoilSchema {

    /** `PRAGMA user_version` of a Paintsprout `.soil`. Bump only with a migration. */
    const val SOIL_VERSION = 1

    const val TABLE = "sketchbook"
    const val META_TABLE = "sketchbook_meta"

    // Row types. The hierarchy: one `sketchbook` row (parentId = "", text = title,
    // refId = last-open page) → `page` rows (refId = the paper row id,
    // width/height px) → `mark` rows (color, strokeWidth, style, blob = format-B
    // geometry). One `paper` row sits under the sketchbook row (text = the paper
    // identity, blob = a WEBP). Nothing else exists in this file — no object
    // rows, no link rows: those are other family members' types, not dormant
    // features of ours.
    const val TYPE_SKETCHBOOK = "sketchbook"
    const val TYPE_PAGE = "page"
    const val TYPE_PAPER = "paper"
    const val TYPE_MARK = "mark"

    /** The sketchbook row's `parentId` — it is the root, and the root has no parent. */
    const val ROOT_PARENT = ""

    /**
     * The label written to the index `paperKind` column for a sketchbook whose
     * paper is plain — which in arc 1 is every sketchbook, since all the tooth
     * lives in the pencil's grain. The value stays the family's spelling
     * ("BLANK", not a fresh word) because the column is the schema's kept home
     * for paper texture as a later arc, and a reader that already understands
     * the family label should not need a dialect for ours. Informational only;
     * nothing reads it yet.
     */
    const val PAPER_BLANK = "BLANK"

    const val CREATE_SKETCHBOOK = """
        CREATE TABLE IF NOT EXISTS sketchbook (
            id          TEXT    NOT NULL PRIMARY KEY,
            parentId    TEXT    NOT NULL,
            type        TEXT    NOT NULL,
            "order"     INTEGER NOT NULL DEFAULT 0,
            createdAt   INTEGER NOT NULL,
            updatedAt   INTEGER NOT NULL,
            deletedAt   INTEGER,
            text        TEXT,
            refId       TEXT,
            x           REAL,
            y           REAL,
            width       REAL,
            height      REAL,
            color       TEXT,
            strokeWidth REAL,
            style       TEXT,
            flags       INTEGER,
            blob        BLOB
        )
    """

    const val CREATE_SKETCHBOOK_INDEX =
        """CREATE INDEX IF NOT EXISTS idx_sketchbook_parent_order ON sketchbook(parentId, "order", deletedAt)"""

    const val CREATE_META =
        "CREATE TABLE IF NOT EXISTS sketchbook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)"
}
