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
    // refId = last-open page, flags = the mode bits below) → `page` rows
    // (refId = the paper row id, width/height px) → `mark` rows (color,
    // strokeWidth, style, blob = format-B geometry) and, on a raster page, at
    // most one `raster` row (blob = a PNG of the whole page, "order" = -1). One
    // `paper` row sits under the sketchbook row (text = the paper identity,
    // blob = a WEBP). Nothing else exists in this file — no object rows, no link
    // rows: those are other family members' types, not dormant features of ours.
    //
    // A page holds marks or an image, never both: which of the two it holds is a
    // fact about the whole sketchbook, stamped in the sketchbook row's flags when
    // the book is made and never changed in place afterwards.
    const val TYPE_SKETCHBOOK = "sketchbook"
    const val TYPE_PAGE = "page"
    const val TYPE_PAPER = "paper"
    const val TYPE_MARK = "mark"

    /**
     * The page's picture, on a raster sketchbook: one row per page, `blob` = a
     * PNG of the whole page, upserted in place every time the image is saved.
     *
     * One row and not one per change, because the page image is most of what a
     * raster book weighs — the family measured its own raster cache at 75–88% of
     * a document (`docs/soil-format.md` § The raster cache) — so a book's size
     * has to be its page count times one image and nothing else. It is a child of
     * the page rather than a column on it because the page rows are read whole on
     * **every page turn**, and a blob living there would drag every page's
     * picture through SQLCipher each time the artist flipped a leaf.
     */
    const val TYPE_RASTER = "raster"

    /**
     * Where a [TYPE_RASTER] row sits in the stacking order: nowhere.
     *
     * Marks stack from zero upwards and the family's own raster cache sits at −1
     * for the same reason — the image is not an operation in the sequence, it is
     * the result of all of them, so it is kept out of op space entirely rather
     * than being handed a number that some later reader would try to sort against
     * the marks. Copied from the family shape exactly, so a family tool that
     * already understands a raster cache understands ours.
     */
    const val RASTER_ORDER = -1

    /**
     * Bit 0 of the **sketchbook row's** `flags`: this book's pages are images,
     * not marks.
     *
     * The mode goes here and not in `refId`, which the sketchbook row already
     * spends on the last-open page, and not in `SketchbookMeta`, which would then
     * be a second place to ask the same question and a second place for the
     * answer to be wrong. The row is the truth; a book with no flags at all — one
     * written before this bit existed — is a stroke book, which is what every
     * file made until now actually is.
     */
    const val FLAG_RASTER = 1

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
