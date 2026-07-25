package com.symmetricalpalmtree.paintsprout.data

/**
 * Every `CREATE TABLE` in Paintsprout, written once.
 *
 * There are several sites that create these tables — the new-document bootstrap,
 * the index bootstrap, an import, and every future migration — and they must all
 * produce a schema that validates identically, or Room's on-open validation
 * refuses to open a perfectly good file. Notesprout has three such sites and
 * learned to centralise them; we start centralised.
 *
 * Identifiers are backtick-quoted throughout, in Room's own generated style, for
 * two reasons: it matches what the ORM will compare against, and it quotes
 * `order` — a SQLite reserved word that is unquotable-by-accident exactly once
 * per codebase. Index names follow Room's convention
 * (`index_<table>_<col>_<col>…`) so an entity can declare `@Index` with no custom
 * name and still match what we create by hand.
 *
 * See `docs/file-format-plan.md` Parts 3 and 4 for what each column means.
 */
object SchemaSql {

    // --- Versions -----------------------------------------------------------

    /** `.soil` document schema version (`PRAGMA user_version`). */
    const val SOIL_SCHEMA_VERSION = 1

    /** Global index schema version. */
    const val INDEX_SCHEMA_VERSION = 1

    /**
     * The *container's* format version, carried in `notebook_meta`. Not a
     * content-type marker — content type is declared by which object table the
     * file has. Bump only if the container contract itself changes.
     */
    const val CONTAINER_FORMAT_VERSION = 1

    // --- Table names --------------------------------------------------------

    /**
     * The object table's name declares what the file contains. Notesprout's is
     * `notebook`; ours is `sketchbook`. One file may carry both — which is why no
     * writer may ever drop or rewrite a table it doesn't own.
     */
    const val SKETCHBOOK_TABLE = "sketchbook"

    /** App-level scratch canvas, in the index. Document row shape. */
    const val SCRATCHPAD_TABLE = "scratchpad"

    /** The persisted clipboard's copied objects, in the index. Document row shape. */
    const val CLIPBOARD_TABLE = "clipboard"

    /**
     * The container's identity table. Keeps Notesprout's name deliberately: it
     * belongs to the container, not to Notesprout, and a shared reader must find
     * it at a fixed name.
     */
    const val META_TABLE = "notebook_meta"

    /** The index's object table: folders, sketchbooks, lists, singletons. */
    const val INDEX_OBJECTS_TABLE = "objects"

    /** Open/edit log powering Recents. Ids and verbs only — never names. */
    const val ACTIVITY_TABLE = "sketchbook_activity"

    /** Every table that uses the document row shape. */
    val DOCUMENT_TABLES = listOf(SKETCHBOOK_TABLE, SCRATCHPAD_TABLE, CLIPBOARD_TABLE)

    // --- The document row ---------------------------------------------------

    /**
     * One wide, sparse table holds every object a document contains. It looks
     * wasteful and isn't: a NULL column costs about a byte in the record header
     * and trailing NULLs cost nothing at all, because the record is simply
     * truncated. A stroke row populates `tool`, `color`, `strokeWidth`, `seed` and
     * `blob`; the rest never reach the disk.
     *
     * What it buys is that a new object type — a raster tile, a group, a text
     * block — costs no `ALTER TABLE`, no join and no per-type reader. `type` is
     * just a string.
     *
     * Columns are shared *by role*, not owned by a type: `text` is a sketchbook's
     * title, a layer's label and a pigment's name. Read the type first, then
     * interpret.
     */
    private val DOCUMENT_COLUMNS = listOf(
        // Universal row — identical names and semantics across the Sprout family.
        "`id` TEXT NOT NULL",
        "`parentId` TEXT NOT NULL", // "" on the root meta row
        "`type` TEXT NOT NULL",
        "`order` INTEGER NOT NULL DEFAULT 0", // sort among siblings; op index under a layer
        "`createdAt` INTEGER NOT NULL", // epoch ms
        "`updatedAt` INTEGER NOT NULL", // epoch ms
        "`deletedAt` INTEGER", // NULL = alive; epoch ms = soft-deleted

        // Geometry, in buffer px. Boxes are top-left + extents, never right/bottom.
        "`x` REAL",
        "`y` REAL",
        "`width` REAL",
        "`height` REAL",

        // Shared scalars.
        "`text` TEXT", // title / layer label / pigment name
        "`color` TEXT", // '#AARRGGBB'
        "`refId` TEXT", // intra-file reference (lastOpenedPage, …)
        "`flags` INTEGER", // per-type bitfield
        "`seed` INTEGER", // per-artwork surface seed / per-stroke texture seed
        "`kind` TEXT", // SurfaceKind name, or CanvasSize kind on the root row
        "`params` TEXT", // small closed JSON bag: page, surface_op, palette only

        // Paint-specific.
        "`tool` TEXT", // Tool enum name
        "`strokeWidth` REAL", // nominal (unpressed) width, buffer px
        "`opacity` REAL", // layer
        "`blendMode` TEXT", // layer
        "`undoDepth` INTEGER", // layer: ops before this order are committed, the rest are redo
        "`opCount` INTEGER", // raster_cache: how many ops these pixels represent
        "`amount` REAL", // pigment quantity / mask downsample factor

        "`blob` BLOB",
    )

    /**
     * The document object table, parameterised on its name so the sketchbook file,
     * the scratchpad and the clipboard share one definition — and therefore one
     * set of serializers, codecs and subtree walks.
     */
    fun documentTable(table: String): String =
        "CREATE TABLE IF NOT EXISTS `$table` (" +
            DOCUMENT_COLUMNS.joinToString(", ") +
            ", PRIMARY KEY(`id`))"

    /**
     * The one index that matters: every content read is "the live children of this
     * parent, in order" — a page's layers, a layer's ops.
     */
    fun documentIndex(table: String): String =
        "CREATE INDEX IF NOT EXISTS `index_${table}_parentId_order_deletedAt` " +
            "ON `$table` (`parentId`, `order`, `deletedAt`)"

    fun documentTableDdl(table: String): List<String> =
        listOf(documentTable(table), documentIndex(table))

    // --- The identity table -------------------------------------------------

    /**
     * Exactly one row, enforced structurally rather than by convention — the
     * `CHECK (id = 0)` makes "there is only one" a database invariant.
     *
     * Its JSON is what makes a document self-describing: id, name, timestamps,
     * encryption state and the full folder ancestry, so an importing device can
     * recreate the hierarchy with the same folder UUIDs and converge on an
     * identical tree with no sync and no server.
     */
    const val META_TABLE_DDL =
        "CREATE TABLE IF NOT EXISTS `$META_TABLE` " +
            "(`id` INTEGER PRIMARY KEY CHECK (`id` = 0), `json` TEXT NOT NULL)"

    // --- The index ----------------------------------------------------------

    /**
     * The index's object table. Same universal row as a document — so the same
     * columnar mapping and subtree code works on both — with two deliberate
     * divergences: `name` is promoted to a top-level column (every index read is a
     * listing that needs it), and `parentId` is nullable with NULL for root
     * (a document row uses `""`).
     *
     * What it must NOT hold is any document content. That is a structural
     * invariant, not a policy: because nothing from inside a sketchbook can reach
     * the index, "search inside artwork" is forced to be an explicit design
     * decision rather than something that leaks in by accident. The single
     * exception is a cover image, governed by key scope.
     */
    const val INDEX_OBJECTS_DDL =
        "CREATE TABLE IF NOT EXISTS `$INDEX_OBJECTS_TABLE` (" +
            "`id` TEXT NOT NULL, " +
            "`type` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`parentId` TEXT, " + // NULL = root
            "`createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " + // drives nothing but presentation today; drives backup later
            "`deletedAt` INTEGER, " +
            "`order` INTEGER, " + // reserved: user-draggable tree order
            "`pageCount` INTEGER, " +
            "`flags` INTEGER, " + // bit 0 = encrypted
            "`keyScope` TEXT, " + // 'GLOBAL' | 'NOTEBOOK'
            "`canvasKind` TEXT, " + // 'FULL_SCREEN' | 'PRINT'
            "`canvasW` REAL, " + // inches, PRINT only — the card's aspect ratio
            "`canvasH` REAL, " +
            "`refId` TEXT, " + // list_item -> member id
            "`sortOrder` INTEGER, " + // list_item -> position
            "`blob` BLOB, " + // cover bytes
            "PRIMARY KEY(`id`))"

    /** "The live folders under this parent", "the live sketchbooks under this parent". */
    const val INDEX_OBJECTS_INDEX_DDL =
        "CREATE INDEX IF NOT EXISTS `index_${INDEX_OBJECTS_TABLE}_parentId_type_deletedAt` " +
            "ON `$INDEX_OBJECTS_TABLE` (`parentId`, `type`, `deletedAt`)"

    /**
     * Append-only open/edit log. Holds ids and verbs, never names or content, so a
     * renamed sketchbook's history renames with it and a deleted one's disappears.
     * "Created" is derived from the row's `createdAt` and is never logged — which
     * is what lets the feature ship without backfilling history that doesn't exist.
     */
    const val ACTIVITY_DDL =
        "CREATE TABLE IF NOT EXISTS `$ACTIVITY_TABLE` (" +
            "`id` TEXT NOT NULL, " +
            "`sketchbookId` TEXT NOT NULL, " +
            "`activityType` TEXT NOT NULL, " + // 'OPENED' | 'EDITED'
            "`timestamp` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"

    const val ACTIVITY_TYPE_INDEX_DDL =
        "CREATE INDEX IF NOT EXISTS `index_${ACTIVITY_TABLE}_activityType_timestamp` " +
            "ON `$ACTIVITY_TABLE` (`activityType`, `timestamp`)"

    const val ACTIVITY_ID_INDEX_DDL =
        "CREATE INDEX IF NOT EXISTS `index_${ACTIVITY_TABLE}_sketchbookId` " +
            "ON `$ACTIVITY_TABLE` (`sketchbookId`)"

    // --- Bootstrap bundles --------------------------------------------------

    /** Everything a fresh `.soil` sketchbook file needs, in order. */
    val SOIL_BOOTSTRAP: List<String> =
        documentTableDdl(SKETCHBOOK_TABLE) + META_TABLE_DDL

    /** Everything a fresh global index needs, in order. */
    val INDEX_BOOTSTRAP: List<String> =
        listOf(INDEX_OBJECTS_DDL, INDEX_OBJECTS_INDEX_DDL) +
            documentTableDdl(SCRATCHPAD_TABLE) +
            documentTableDdl(CLIPBOARD_TABLE) +
            listOf(ACTIVITY_DDL, ACTIVITY_TYPE_INDEX_DDL, ACTIVITY_ID_INDEX_DDL)
}
