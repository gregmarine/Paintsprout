# Paintsprout File Format & Library — Multi-Phase Plan

> **Status:** planning complete, implementation not started.
> **Started:** 2026-07-24
> **Reference specs:** `~/git/Notesprout/docs/soil-file-format.md`, `~/git/Notesprout/docs/global-index-format.md`
> **Tracking:** update the [Phase ledger](#phase-ledger) as each phase lands. One commit per phase.

Paintsprout currently persists **nothing**. Artwork lives in `PaintCanvasView`'s in-memory
`PaintOp` history and dies with the process; the only outputs are PNG exports to the gallery and a
`SharedPreferences` entry for screen calibration. This plan builds the whole storage half of the
app: a `.soil` document container, a global index, a library, multi-page sketchbooks, a scratchpad,
a clipboard, and import/export — following the Sprout family container contract.

---

## Table of contents

- [Part 1 — Decisions](#part-1--decisions)
- [Part 2 — Storage topology](#part-2--storage-topology)
- [Part 3 — The global index (`paintsprout.db`)](#part-3--the-global-index-paintsproutdb)
- [Part 4 — The document container (`<uuid>.soil`)](#part-4--the-document-container-uuidsoil)
- [Part 5 — Binary codecs](#part-5--binary-codecs)
- [Part 6 — Encryption](#part-6--encryption)
- [Part 7 — App surfaces](#part-7--app-surfaces)
- [Part 8 — Invariants checklist](#part-8--invariants-checklist)
- [Part 9 — Phase ledger](#phase-ledger)
- [Part 10 — Phase detail](#part-10--phase-detail)
- [Part 11 — Risks & open questions](#part-11--risks--open-questions)

---

# Part 1 — Decisions

Answered by the user during planning (2026-07-24), plus the technical calls that follow from them.

| # | Decision | Choice | Consequence |
|---|---|---|---|
| 1 | Paint storage model | **Ops + cached raster** | Every `PaintOp` becomes an object row; a composited raster per layer is cached in the file for instant open. Ops are the source of truth. |
| 2 | Encryption | **Full parity from the start** | SQLCipher everywhere from the first commit: minted global key, `PSPT-` recovery key, key scopes, raw-key cache, unlock UI. Phases 1–6 are pure plumbing before any art is saved. |
| 3 | Pages & layers | **Pages fully, layers schema-only** | Multi-page UI ships. `layer` rows exist and every page gets exactly one; no layer panel, no blend/opacity compositing yet — but the columns are there. |
| 4 | Launch target | **Last-open surface** | The app reopens the sketchbook page or scratchpad page you left; the library is one tap away. |
| 5 | Size / surface scoping | **Size per book, surface per page** | Canvas size is a sketchbook property (all pages share it → uniform thumbnails). Surface kind, params and seed are per page. |
| 6 | Scratchpad | **Multi-page, same editor** | App-level surface in the index DB using the document row schema; restricted tool set; no library entry. |
| 7 | Selection tools | **Existing wand + new free-draw lasso** | The lasso is a new tool feeding all the existing selection machinery. |
| 8 | Portability | **Export + import `.soil`** | Backup/restore is explicitly out of scope for this plan. |
| 9 | Tray/palette | **Per sketchbook, in the file** | Palette pots, mixing well and brush load travel with the artwork on export. Scratchpad keeps its own in the index. |
| 10 | Clipboard | **Whole objects** | Copy takes the op rows wholly inside the selection; paste inserts them with fresh ids into any page. See the [risk note](#clipboard-whole-object-copy-will-surprise). |
| 11 | Undo/redo persistence | **Both persist** | Undone ops are retained; reopening a page days later can still step backwards and forwards. |
| 12 | DB layer | **Room, like Notesprout** | Room entities + DAOs + migrations over SQLCipher's `SupportFactory`. Carries the `user_version` hazard — carried across every `sqlcipher_export` round-trip, no exceptions. |

## Technical calls made during planning

- **Extension `.soil`, object table `sketchbook`.** Same container family. A single file may one day
  carry both a `notebook` and a `sketchbook` table; nothing here ever drops a table it doesn't own.
- **The identity table keeps the name `notebook_meta`.** It belongs to the container, not to
  Notesprout (spec invariant #6), and its field set is copied verbatim including `folderPath`.
- **`keyScope` values stay `GLOBAL` / `NOTEBOOK`.** `NOTEBOOK` means "this document's own
  passphrase". Renaming it to `SKETCHBOOK` would fork the portable `notebook_meta` vocabulary for
  no gain.
- **No `data` / `boundingBox` legacy columns.** Greenfield; the spec says to omit them.
- **A small `params TEXT` JSON payload is permitted on exactly three types** (`page`,
  `surface_op`, `palette`) for closed, never-queried parameter bags — surface parameter structs and
  pigment recipes. This follows the spec's own rule of thumb: *promote a field to a column when the
  database must answer a question about it; leave it in the payload when only the app cares.* Every
  such payload decodes leniently (unknown keys ignored, missing keys default).
- **Undo depth is one integer, not a per-row flag.** A `layer` row carries `undoDepth`: ops with
  `"order" < undoDepth` are committed, ops at or beyond it are the redo stack. Committing a new op
  hard-deletes the redo tail. Persisted undo/redo falls out for free with no extra table.
- **No data migration exists.** Nothing is persisted today, so every schema here is v1 and there is
  no legacy shape to sweep. The migration *discipline* (additive DDL, format-agnostic readers,
  convert on write) still applies from day one because v2 will come.

---

# Part 2 — Storage topology

```
<app external files dir>/                         ← context.getExternalFilesDir(null)
├── paintsprout.db                                ← global index (SQLCipher, always encrypted)
├── paintsprout.db-wal                            ← legitimate: the index never closes
├── paintsprout.db-shm
└── Garden/                                       ← flat, no subdirectories, ever
    ├── 3f2a1b8c-….soil                           ← one sketchbook, filename = its UUID
    └── …
```

Exactly one function constructs a document path:

```kotlin
fun soilFile(context: Context, sketchbookId: String): File {
    val garden = File(context.getExternalFilesDir(null)!!, "Garden")
    garden.mkdirs()
    return File(garden, "$sketchbookId.soil")
}
```

| | Global index | Document |
|---|---|---|
| Count | one per install | one per sketchbook |
| Holds | folders, sketchbook rows, names, covers, pins, recents, scratchpad, clipboard | all artwork |
| Lifetime | whole app lifetime | while the sketchbook is open |
| Encryption | global key, always | global **or** per-document key |
| Portable | no | yes — self-describing |

---

# Part 3 — The global index (`paintsprout.db`)

Room, SQLCipher, schema **v1**.

## `objects`

```sql
CREATE TABLE objects (
    id          TEXT    NOT NULL PRIMARY KEY,   -- UUIDv4 or sentinel
    type        TEXT    NOT NULL,               -- discriminator
    name        TEXT    NOT NULL,               -- top-level (unlike .soil)
    parentId    TEXT,                           -- NULL = root
    createdAt   INTEGER NOT NULL,
    updatedAt   INTEGER NOT NULL,
    deletedAt   INTEGER,
    "order"     INTEGER,                        -- reserved: user-draggable tree order

    pageCount   INTEGER,                        -- sketchbook
    flags       INTEGER,                        -- sketchbook bitfield
    keyScope    TEXT,                           -- 'GLOBAL' | 'NOTEBOOK'
    canvasKind  TEXT,                           -- 'FULL_SCREEN' | 'PRINT'
    canvasW     REAL,  canvasH  REAL,           -- inches, PRINT only — card aspect ratio
    refId       TEXT,                           -- list_item → member id
    sortOrder   INTEGER,                        -- list_item → position
    blob        BLOB                            -- cover image bytes
);
CREATE INDEX index_objects_parentId_type_deletedAt ON objects(parentId, type, deletedAt);
```

**Types:** `folder` · `sketchbook` · `list` · `list_item` · `clipboard`

| Type | Payload |
|---|---|
| `folder` | structure only: `name` + `parentId`. Ancestry walk is cycle-guarded with a 50-hop cap. |
| `sketchbook` | `name`, `pageCount`, `flags` bit0 = encrypted, `keyScope`, `canvasKind`/`canvasW`/`canvasH`, `blob` = cover (page 1 thumbnail). Enough to render any card **without opening the file**. |
| `list` | `PINNED_LIST_ID` sentinel. Membership is child rows, never a JSON array. |
| `list_item` | `parentId` = list, `refId` = member, `sortOrder`. **Hard-deleted** on unpin — membership is not user history. Deleting a sketchbook scrubs its edges everywhere first. |
| `clipboard` | Singleton metadata: source document id, copied-at, item count, source bounds. The copied *objects* live in the `clipboard` table below. |

## `scratchpad` and `clipboard` tables

Both are **column-for-column identical to the `.soil` `sketchbook` table** (Part 4), so every
serializer, columnar mapping, codec and subtree walk works on them unchanged.

```
scratchpad:  scratchpad_root (sentinel, parentId="") → page rows → layer rows → op rows
clipboard:   clipboard_root  (sentinel, parentId="") → copied op rows
```

> ⚠️ Columnar tables need columnar writers. There is no JSON write path at all in this
> implementation — that is what makes this safe by construction.

## `sketchbook_activity`

```sql
CREATE TABLE sketchbook_activity (
    id           TEXT    NOT NULL PRIMARY KEY,
    sketchbookId TEXT    NOT NULL,
    activityType TEXT    NOT NULL,   -- 'OPENED' | 'EDITED'
    timestamp    INTEGER NOT NULL
);
CREATE INDEX index_sketchbook_activity_activityType_timestamp ON sketchbook_activity(activityType, timestamp);
CREATE INDEX index_sketchbook_activity_sketchbookId           ON sketchbook_activity(sketchbookId);
```

Powers **Recents**. Ids and verbs only — never names, never content. "Created" is derived from the
row's `createdAt` and never logged.

## Sentinel ids

All are `00000000-0000-0000-0000-<group>`, created by idempotent `ensure…Exists()` at every launch,
**never by a migration**.

| Constant | Group | Decodes to | Table |
|---|---|---|---|
| `PINNED_LIST_ID` | `70696e6e6564` | `pinned` | `objects` |
| `CLIPBOARD_ID` | `636c69706264` | `clipbd` | `objects` |
| `SCRATCHPAD_ROOT_ID` | `736372746368` | `scrtch` | `scratchpad` |
| `CLIPBOARD_ROOT_ID` | `636c69706272` | `clipbr` | `clipboard` |

## The `updatedAt` discipline

| Operation | Bumps `updatedAt`? |
|---|---|
| Rename, move, cover refresh, page-count refresh | **Yes** |
| Encryption state change | **Yes** |
| Pin / unpin | **No** — a list toggle is not a modification |
| Activity logging | **No** |

---

# Part 4 — The document container (`<uuid>.soil`)

Room, SQLCipher, schema **v1**. Tables: `sketchbook` (object table), `notebook_meta` (identity).

## The object table

```sql
CREATE TABLE IF NOT EXISTS sketchbook (
    -- universal row
    id          TEXT    NOT NULL PRIMARY KEY,
    parentId    TEXT    NOT NULL,               -- "" for the root meta row
    type        TEXT    NOT NULL,
    "order"     INTEGER NOT NULL DEFAULT 0,     -- RESERVED WORD: quote everywhere
    createdAt   INTEGER NOT NULL,
    updatedAt   INTEGER NOT NULL,
    deletedAt   INTEGER,

    -- geometry (buffer px unless the type says otherwise)
    x           REAL,  y       REAL,
    width       REAL,  height  REAL,

    -- shared scalars, interpreted by type
    text        TEXT,      -- title / layer label / pigment name
    color       TEXT,      -- '#AARRGGBB'
    refId       TEXT,      -- intra-file reference
    flags       INTEGER,   -- per-type bitfield
    seed        INTEGER,   -- per-artwork / per-stroke randomness
    kind        TEXT,      -- SurfaceKind name, or CanvasSize kind on the root row
    params      TEXT,      -- small closed JSON bag (page, surface_op, palette only)

    -- paint-specific
    tool        TEXT,      -- Tool enum name
    strokeWidth REAL,      -- stroke: nominal (unpressed) width, buffer px
    opacity     REAL,      -- layer
    blendMode   TEXT,      -- layer
    undoDepth   INTEGER,   -- layer: committed op count
    opCount     INTEGER,   -- raster_cache: ops this cache represents
    amount      REAL,      -- pot / recipe quantity

    blob        BLOB
);
CREATE INDEX IF NOT EXISTS idx_sketchbook_parent_order
    ON sketchbook(parentId, "order", deletedAt);
```

```sql
CREATE TABLE IF NOT EXISTS notebook_meta
    (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL);
```

## Hierarchy

```
sketchbook            (root meta row, parentId = "")
├── page              (parentId = sketchbook.id, "order" = page index)
│   └── layer         (parentId = page.id) — exactly one for now
│       ├── stroke        (parentId = layer.id, "order" = op index)
│       │   ├── stroke_clip   (frisket mask, if the stroke was drawn inside a selection)
│       │   └── wet_state     (watercolor only: tick schedule, crop, dry freeze)
│       ├── fill          }
│       ├── erase         }  selection ops, mask in blob
│       ├── move          }
│       ├── surface_op    (a surface/background change on the undo timeline)
│       └── raster_cache  (composited pixels at opCount ops — NOT an op)
└── palette           (parentId = sketchbook.id)
    └── pot           (parentId = palette.id)
```

## Type catalog

### `sketchbook` — the document meta row
`parentId = ""`. Its id is the parent of every page.

| Field | Column | Notes |
|---|---|---|
| title | `text` | Mirrored into `notebook_meta` and the index row |
| last opened page | `refId` | Restored on open |
| canvas kind | `kind` | `FULL_SCREEN` \| `PRINT` |
| canvas size | `width`, `height` | **Inches**, `PRINT` only. Book-level (decision 5) |
| feature flags | `flags` | Reserved — per-document toggles travel with the file |

### `page`
`parentId` = sketchbook row. `"order"` = position in the book.

| Field | Column | Notes |
|---|---|---|
| surface kind | `kind` | `SurfaceKind` enum name |
| plain colour | `color` | `PLAIN` surface background |
| surface seed | `seed` | Per-artwork procedural seed |
| surface params | `params` | JSON: whichever of the seven param structs is in play |
| authoring size | `width`, `height` | Buffer px at authoring time — lets a cached raster render correctly on a differently-sized screen |

The page row holds the **currently resolved** surface, written in the same transaction as the
`surface_op` that changed it. That denormalization is deliberate: a page thumbnail and a page open
must not have to replay the op history to know what the paper looks like.

### `layer`
`parentId` = page row. One per page today.

| Field | Column | Notes |
|---|---|---|
| label | `text` | e.g. `"Paint"` |
| locked / visible | `flags` | bit0 = locked (1), bit1 = visible (2). Default `2` |
| opacity | `opacity` | 0–1, default 1 — reserved for the layer phase |
| blend mode | `blendMode` | Enum name, default `NORMAL` — reserved |
| undo frontier | `undoDepth` | Ops with `"order" < undoDepth` are committed; the rest are redo |

### `stroke`
The dominant row type. Maps 1:1 to `StrokeOp`.

| Field | Column | Notes |
|---|---|---|
| tool | `tool` | `Tool` enum name |
| colour | `color` | `#AARRGGBB` |
| base width | `strokeWidth` | `Stroke.baseWidth`, buffer px |
| seed | `seed` | `Stroke.seed` — bristle layout reproducibility |
| water mode | `flags` bit0 | `Stroke.water` |
| geometry | `blob` | Format B, see Part 5. **Geometry + per-point channels only** |
| bounding box | *(not stored)* | Recomputed at load — never persist derivable geometry |

### `stroke_clip`
`parentId` = stroke. Present only when the stroke was drawn inside an active selection (frisket).
`blob` = mask, `x`/`y`/`width`/`height` = mask bounds in buffer px, `amount` = downsample factor.

### `wet_state`
`parentId` = stroke. Watercolor only. `blob` packs `wetSchedule` (int array), `wetCrop`
(4 ints, nullable) and `dryFreeze` (float array, nullable) so an interrupted wash commits exactly as
the screen showed it on replay.

### `fill` / `erase` / `move`
`parentId` = layer. Map to `FillOp` / `EraseOp` / `MoveOp`.

| Type | Columns |
|---|---|
| `fill` | `color`, `blob` = mask, bounds in `x`/`y`/`width`/`height`, `amount` = downsample |
| `erase` | `blob` = mask, bounds, `amount` |
| `move` | `blob` = mask **+ a 9-float matrix header**, bounds, `amount` |

### `surface_op`
`parentId` = layer. Maps to `SurfaceOp`. `kind`, `color` (plain colour), `seed`, `params` (the param
struct JSON). Paint-neutral: a rebuild skips it, the resolved surface is read off the page row.

### `raster_cache`
`parentId` = layer. **Not an op** — it never appears in `"order"` space with the ops (it is stored
with `"order" = -1`) and is skipped by every history read.

| Field | Column |
|---|---|
| ops represented | `opCount` — must equal the layer's `undoDepth` to be usable |
| pixel size | `width`, `height` |
| pixels | `blob` — lossless (PNG initially; re-measured in the final phase) |

Opening a page with a valid head cache is a decode, not a replay. If `opCount != undoDepth` (an
interrupted write, an older build), the cache is ignored and the ops replay — degradation, never
failure.

### `palette` / `pot`
`parentId` = sketchbook row / palette row. The tray travels with the artwork (decision 9).

| Type | Columns |
|---|---|
| `palette` | `params` = JSON `{mixture: [[argb, amount]…], load: [[argb, amount]…], capacity}` |
| `pot` | `text` = pigment name, `color`, `flags` bit0 = custom (added from the wheel), `"order"` = rim position |

### Reserved for later
`raster` (tiled pixels), `group` (local coordinate space — model on `sticky_note`, never on `link`),
`text`, `shape`, `mask`, `adjustment`, `reference_image`. All cost zero schema change.

## Bitfields

| Type | Bit | Value | Meaning |
|---|---|---|---|
| `layer` | 0 | 1 | locked |
| `layer` | 1 | 2 | visible |
| `stroke` | 0 | 1 | water mode |
| `pot` | 0 | 1 | custom pigment |

## Units

| Quantity | Unit | Why |
|---|---|---|
| Stroke geometry, widths, masks, cache rasters | **buffer px** | Captured from hardware; `SUPER_SAMPLE` is 1.0 today, so buffer px == logical px == canvas px |
| Canvas size on the root row | **inches** | It is a physical print size (`CanvasSize.Print`) |
| Tool sizes in the UI | **mm** | Already true today — converted to px at the calibrated PPI |
| Timestamps | **epoch ms** | Container contract |

Write this down anywhere a new column is added. It is invisible at the schema level and silently
wrong across devices when mixed up.

---

# Part 5 — Binary codecs

## Stroke geometry — format B, Paintsprout channel profile

```
byte 0   : version : u8 (= 1)                      ← PLAINTEXT, outside the compression
bytes 1+ : zlib{ flags:u8 | (channels…) × N }      ← little-endian, BEST_COMPRESSION
```

| flags bit | Channel | Size | Set by Paintsprout? |
|---|---|---|---|
| — | `x`, `y` | f32 ×2 | always (base stride 8) |
| 0 | pressure | f32 | **no** — pressure is baked into width/density at capture |
| 1 | tilt | f32 | **no** — same |
| 2 | width | f32 | yes (`StrokePoint.width`) |
| 3 | density | f32 | yes (`StrokePoint.density`) |
| 4 | colour | u32 ARGB | yes (`StrokePoint.color`, `INHERIT_COLOR` = 0) |
| 5 | load | f32 | yes (`StrokePoint.load`) |
| 6–7 | free | — | future |

**Every channel is exactly 4 bytes**, in ascending bit order. That makes
`stride = 8 + 4 × popcount(flags)` and lets any decoder — including a Notesprout-era one — skip
channels it does not understand. No version bump, no migration, ever, for a new per-point channel.
Bump the version byte only if the *geometry* encoding changes (e.g. f64 for very large canvases).

## Masks (selection, frisket, move source)

```
byte 0   : version : u8 (= 1)
bytes 1+ : zlib{ w:u32 | h:u32 | alpha:u8 × w*h }
```

Wand masks are already downsampled by `WandFloodFill.DOWNSAMPLE` (2); the factor is stored in
`amount` so the decoder never assumes it. Cropped to the op's bounds before encoding — a mask is
mostly empty and cropping is the cheapest win available.

## `move` matrix

Prefixed onto the `move` row's blob: `version u8 (=1) | 9 × f32` (`Matrix.getValues` order),
then the mask blob as above.

## `wet_state`

```
version u8 (=1)
zlib{ scheduleCount:u32 | schedule:i32 × n
    | hasCrop:u8 | crop:i32 × 4
    | freezeCount:u32 | freeze:f32 × m }
```

## Raster cache

Lossless PNG via `Bitmap.compress(PNG)` to start. **Re-measure** in the final phase against
raw-BGRA + zlib; the spec is explicit that Notesprout's WEBP-q100 finding is about sparse
transparent ink and does not transfer to dense painted pixels.

## Decoding is an attack surface on your own data

Non-negotiable, all three learned the hard way in Notesprout:

- **Bail the inflate loop on any zero-progress round.** A corrupt zlib header makes `inflate()`
  return 0 bytes forever without reporting "finished" — a hang on the page-load path, i.e. an ANR
  the user cannot escape.
- **Guard every blob decode, per row.** One corrupt stroke degrades to a stroke-less render, never
  to an unopenable page — and, via launch-restore of the last-open surface, never to an unopenable
  app.
- **Decode `params` JSON leniently.** Unknown keys ignored, missing keys defaulted, in both
  directions of version skew.
- **Bounded decode on every image.** Sampled decoder, target size, `MAX_DIMENSION = 4096` fallback.

---

# Part 6 — Encryption

Identical to the Notesprout model — see `soil-file-format.md` Part VII. What is Paintsprout-specific:

| | |
|---|---|
| Recovery key prefix | `PSPT-` + 8 Crockford-base32 groups of 4 (160 bits) |
| Index raw-key cache id | `__paintsprout_index__` |
| Key scopes | `GLOBAL` (cached, prompted once per device) · `NOTEBOOK` (per-document, prompted every open, **never persisted**) |
| KDF | Stock: PBKDF2-HMAC-SHA512, 256,000 iterations, AES-256, 16-byte header salt. No `kdf_iter` override, no page-size override |
| Passphrase encoding | UTF-8, always |
| Cover caching | Unencrypted → yes. `GLOBAL` scope → **yes** (the index is encrypted under that same key). `NOTEBOOK` scope → **never**; converting to private scope clears any existing cover in the same write, and the card renders a lock |
| Rate limiting | Per-document buckets + `"GLOBAL"` + `"IMPORT"`; 3 fails → 30 s, 5 → 5 min, ≥10 → 1 hr. Cancel does not count. Nothing about attempts is ever logged |

**Acceptance test for every phase that touches keying:**

```sh
sqlcipher /tmp/test.soil
PRAGMA key = '<passphrase>';
SELECT count(*) FROM sqlite_master;   -- an integer, not an error
```

The two data-loss guards are build-order requirements, not features:

1. **The non-destructive open-helper factory wraps every open** — plaintext, passphrase, raw-key,
   probe, and migration transients alike. A wrong key is indistinguishable from corruption and
   Room's default handler *deletes and recreates the file*. This lands in Phase 2, before any key
   exists.
2. **Every open and verification helper requires the file to exist and be non-empty.** Creation gets
   its own explicitly named entry points used only by the new-document bootstrap. An empty encrypted
   database "verifies" any passphrase you type.

And the `sqlcipher_export` rule, which applies to encrypt, decrypt, and re-key alike:
**copy `PRAGMA user_version` yourself** (`PRAGMA target.user_version = <source>`). Room reads
version 0 as "brand-new database" and rejects an otherwise intact file.

---

# Part 7 — App surfaces

## Screens

| Screen | Status | Role |
|---|---|---|
| `BootstrapActivity` | new | Drives index open; unlock UI; retry-able error screen — never a launcher crash-loop |
| `LibraryActivity` | new | Folders, pinned, recents, search, sort, sketchbook CRUD, export/import entry points |
| `MainActivity` | **becomes the editor** | Hosts one page of a sketchbook *or* one scratchpad page. Gains page navigation, autosave, title, back-to-library, copy/paste, send-to |
| `CalibrationActivity` | unchanged | |

**Launch routing** (decision 4): bootstrap gate → last-open surface, resolved from a plaintext pref
holding **ids only** (`(kind, documentId, pageId)`), names resolved against the encrypted index at
read time. Both the index row *and* the file must exist before opening, or the route falls back to
the library — a stale pointer otherwise mints an empty ghost document through a create-capable open.

## Library

- Sections: **Pinned** → **Recents** → current folder's contents (folders first, then sketchbooks).
- Card: cover + name + page count; lock glyph instead of a cover for `NOTEBOOK`-scope books.
- Actions: create, rename, move, duplicate, delete, pin/unpin, export.
- **Search is filename-only** (`objects.name`) — by construction, since no content ever reaches the
  index. A future "search inside artwork" is therefore forced to be an explicit design decision.
- Sort: name / created / updated.

## Editor

- Existing rail, tray and canvas are untouched in feel; persistence is additive.
- New: page strip (thumbnails, add / duplicate / delete / reorder), prev/next page, book title,
  back-to-library, copy/paste, send-to-scratchpad, send-to-sketchbook.
- **Autosave**: ops persist on commit (debounced ~300 ms and coalesced); the raster cache is written
  at seal or on backgrounding. No save button, no dirty prompt.
- **Seal sequence** (on leaving a document), each step individually guarded, on an
  application-scoped non-cancellable coroutine:

```
flush pending ops → write raster cache → refresh notebook_meta → refresh the index row
  (pageCount, cover, updatedAt) → hard-delete rows tombstoned in PRIOR sessions
  → incremental_vacuum → wal_checkpoint(TRUNCATE) → close → delete stray -journal
```

## Scratchpad

Multi-page, always available, in the index's `scratchpad` table. Restricted tool set: **pencil, pen,
brush, eraser, magic wand, lasso**. No library entry, no cover, no export of its own — a scratch
page reaches a sketchbook via *send to sketchbook*.

## Transfer operations

| Operation | Mechanism |
|---|---|
| Copy | Ops **wholly inside** the selection are deep-copied into the `clipboard` table with fresh ids; the `clipboard` singleton records source id, bounds, count |
| Paste | Clipboard ops are inserted into the target layer with fresh ids, appended past `undoDepth`'s frontier as one undoable unit; works across pages, books, and the scratchpad |
| Send to scratchpad | The page's ops are copied as a new scratchpad page (surface and all) |
| Send to sketchbook | A scratchpad page is copied into a chosen (or new) sketchbook as a new page |

> ⚠️ **Every subtree write mints fresh ids for descendants** — insert *and* replace, through **one**
> shared `remapDescendantIds` helper. Notesprout shipped this bug twice by having two. A composite's
> child ids are private to its subtree and never referenced from outside, so remapping is always safe.

---

# Part 8 — Invariants checklist

Check every phase against this. Each line is a real bug someone already paid for.

1. One document = one SQLite file; the file is self-describing via `notebook_meta`.
2. Everything is an object in one wide sparse table; `type` is a plain string.
3. Stable UUIDs, assigned at creation, never reassigned. Copy is the only operation that mints new ones.
4. Soft deletes only; hard deletion happens in a deliberate compaction pass (and for list edges).
5. Payload is columnar; `params` JSON only for closed, never-queried parameter bags.
6. Composites are parent + child rows, never nested serialized documents.
7. Filenames are UUIDs in a flat directory; **all** structure lives in the index.
8. Never drop or rewrite a table you don't own.
9. `"order"` is a SQLite reserved word — quote it in every hand-written statement, backtick it in `ContentValues`.
10. Never persist derivable geometry (stroke AABBs, shape AABBs).
11. Never store per-sample timestamps.
12. Explicit stable `@SerialName` on every polymorphic subtype, from day one.
13. Non-destructive open helper on **every** open path, including probes and migration transients.
14. Every open/verify helper requires a non-empty existing file; creation has its own named entry points.
15. Repair an interrupted swap **before** probing anything — a missing index must never read as "fresh install".
16. Commit swaps never hold zero copies: fsync → rename original aside → rename temp in → fsync dir → delete aside.
17. Carry `user_version` across every `sqlcipher_export` round-trip.
18. Guard per row, not per pass, in every sweep, decoder and batch job.
19. No content in the index — covers only, governed by key scope.
20. Plaintext prefs hold ids and settings, never names, never content.
21. `updatedAt` bumps only for real modifications.
22. Format deterministic keys with the root locale.
23. Close on an application-scoped, non-cancellable coroutine with an exception handler.
24. No stray `-wal`/`-shm`/`-journal` beside a closed document; re-apply `wal_autocheckpoint` on **every** open; PRAGMAs that return rows must be stepped, never `execSQL`'d.
25. Validate every id from an imported file against the UUID alphabet before it becomes a path component.

---

# Phase ledger

Legend: ⬜ not started · 🚧 in progress · ✅ done · 🧪 needs device test

| # | Phase | Device test | Status |
|---|---|---|---|
| 0 | Plan & decisions (this document) | — | ✅ |
| 1 | Container skeleton: deps, paths, schema constants, WAL | no | ✅ |
| 2 | Non-destructive opens, probe, swap repair | no | ⬜ |
| 3 | Crypto core: keystore stores, key mint, KDF cache, rate limiter | no | ⬜ |
| 4 | Global index DB: Room entities, DAOs, repository, sentinels | no | ⬜ |
| 5 | Bootstrap gate + unlock UI + launch routing | **yes** | ⬜ |
| 6 | `.soil` container: schema, `notebook_meta`, create/open/seal, registry | **yes** | ⬜ |
| 7 | Object model: universal row, columnar mapping, subtree helpers | no | ⬜ |
| 8 | Binary codecs: stroke format B, masks, matrix, wet state | no | ⬜ |
| 9 | Document repository: pages, layers, ops, undoDepth, cache, palette | no | ⬜ |
| 10 | Editor save — strokes + surface ops | **yes** | ⬜ |
| 11 | Editor save — selection ops, clips, wet state, palette | **yes** | ⬜ |
| 12 | Editor load — raster cache fast path, cross-session undo/redo | **yes** | ⬜ |
| 13 | Autosave, lifecycle, seal, crash safety | **yes** | ⬜ |
| 14 | Library screen (flat): create, open, rename, delete, covers | **yes** | ⬜ |
| 15 | Folders, move, sort, name search | **yes** | ⬜ |
| 16 | Pinned + recents | **yes** | ⬜ |
| 17 | Multi-page UI: page strip, add/duplicate/delete/reorder | **yes** | ⬜ |
| 18 | Scratchpad | **yes** | ⬜ |
| 19 | Lasso tool | **yes** | ⬜ |
| 20 | Clipboard: copy/paste whole objects | **yes** | ⬜ |
| 21 | Send to scratchpad / send to sketchbook | **yes** | ⬜ |
| 22 | Export `.soil` + `notebook_meta` upkeep | **yes** | ⬜ |
| 23 | Import `.soil` | **yes** | ⬜ |
| 24 | Encryption UX: per-document passphrase, rotation, lock states | **yes** | ⬜ |
| 25 | Compaction, VACUUM, size/perf measurement, leak audit | **yes** | ⬜ |

## Working protocol

- **Every phase ends with a clean build**: `./gradlew :app:assembleDebug` plus `:app:testDebugUnitTest`.
- **Phases marked "device test" pause for the user** to exercise the build on the Movink 11
  (serial `5HL21V5007384` — prompt before installing, ignore every other connected device).
- After a phase is verified: tick it here, then commit and push that phase's work.
- Commits go to `main` directly, one per phase (matching the line/arc/polyline/polyarc phases).

---

# Part 10 — Phase detail

## Phase 1 — Container skeleton ✅
Add Room + KSP, `net.zetetic:sqlcipher-android`, `androidx.sqlite`. Create
`data/` package: `SoilFiles` (the *one* path-deriving function), `SchemaSql` (the single source of
truth for every `CREATE TABLE` — referenced by every bootstrap site and every migration),
`WalConfig` (`journal_mode=WAL`, `wal_autocheckpoint=100`, `auto_vacuum=INCREMENTAL`, applied on
every open via a stepped `rawQuery`, never `execSQL`).
**Verify:** builds; unit test asserts path shape and schema-constant identity across call sites.

**As built.** Also added the serialization plugin + `kotlinx-serialization-json` (the container's
JSON payloads) and `androidx.security:security-crypto` (Phase 3's keystore stores), so the dependency
set is complete for the plumbing phases. Three deviations from the plan text, all deliberate:

- **`org.xerial:sqlite-jdbc` as a test dependency.** It was already in the Gradle cache (Room's
  compiler pulls it), so the schema constants are *executed* against a real SQLite engine in unit
  tests and read back through `PRAGMA table_info` / `index_list` — column types, nullability,
  defaults, the `order` quoting, and the `CHECK (id = 0)` are all proven on every build instead of
  string-matched. A DDL typo now fails in Phase 1 rather than on a device in Phase 6.
- **Index names follow Room's convention** (`index_<table>_<col>_<col>…`) rather than the plan's
  `idx_sketchbook_parent_order`. Room compares indices *by name* during on-open validation, so
  matching its default naming lets an entity declare `@Index` with no custom name and still validate
  against a hand-created table. Index names are a per-database detail and carry no portability cost.
- **The swap-in-flight name helpers** (`.old.bak` / `.tmp` / `.new`) landed here rather than in
  Phase 2, because `SoilFiles` is the one place paths are constructed and splitting that rule across
  two files would defeat it. Phase 2 consumes them.

24 unit tests, including the hostile-id battery that proves `soilFile()` refuses to build a path from
anything that isn't a bare UUID (invariant 25) and that `listDocuments` never mistakes a half-written
swap file for a document.

## Phase 2 — Non-destructive opens, probe, swap repair
`SafeOpenHelperFactory` wrapping every open with a corruption handler that **reports and never
deletes**. `DbProbe`: empty/missing → `Invalid`; first 16 bytes ≠ `SQLite format 3\0` → `Encrypted`;
opens as plain SQLite and reads `sqlite_master` → `Plaintext`; else `Encrypted`. Exists-guarded
open entry points, separately named creation entry points. `SwapRecovery.repair(dir)` implementing
the three interrupted-swap states from the spec.
**Verify:** JVM unit tests over crafted files — truncated, garbage, real SQLite, aside-name present.

## Phase 3 — Crypto core
Three keystore-backed preference files (secure prefs / derived-key store / reserved), created under
one lock with one cached instance each and one retry (the keystore throws transiently right after
boot). Global key minting is synchronized — two concurrent first-callers must not mint two secrets.
`PSPT-` recovery key generator (160 bits, Crockford base32, 8 groups of 4). `keyBytes` (UTF-8),
`deriveKey(file, passphrase)` (salt = file's first 16 bytes), raw-key cache with the resolution order
`RAM → keystore (GLOBAL only) → derive+store`, `verifyPassphrase` returning **false** for missing or
empty files. Escalating rate limiter, persisted. Nothing logged, ever.
**Verify:** JVM tests for base32 alphabet, KDF vectors, rate-limit escalation; keystore paths by
inspection.

## Phase 4 — Global index DB
Room entities/DAOs for `objects`, `scratchpad`, `clipboard`, `sketchbook_activity`. One repository
owns every write (DAO access is read-only elsewhere) so the `updatedAt` discipline is enforceable in
one place. Idempotent `ensurePinnedList()`, `ensureClipboard()`, `ensureScratchpadRoot()`,
`ensureClipboardRoot()`. Cycle-guarded (50-hop) ancestry walk.
**Verify:** instrumented/unit tests on a temp encrypted file: create, insert, query by
`(parentId, type, deletedAt)`, list-edge scrub on delete.

## Phase 5 — Bootstrap gate + unlock UI 🧪
The open state machine: `repair → probe → (Invalid: mint key, create ENCRYPTED, derive+cache) |
(Encrypted: cached key → verify → open, else NEEDS_UNLOCK)`. One mutex serializes every open,
unlock, re-key and seal. `awaitReady()` latch that every consumer suspends on.
`BootstrapActivity` with unlock prompt, recovery-key display on first run, and a retry-able error
screen. Launch routing to last-open surface (falls back to library).
**Device test:** fresh install (key minted, recovery key shown), cold relaunch (fast raw-key open),
force-stop mid-open, wrong passphrase × N (lockout), verify with the stock `sqlcipher` CLI.

## Phase 6 — `.soil` container 🧪
`sketchbook` + `notebook_meta` tables, document Room DB, create/open/seal, the open-document
registry, per-document key scope resolution. A temporary debug menu item creates and deletes a
sketchbook file so the container can be exercised before any UI exists.
**Device test:** create a file, `adb pull`, open it in the stock `sqlcipher` CLI with the recovery
key, confirm the table set and that no `-wal`/`-shm` survives a clean close.

## Phase 7 — Object model
`SoilObject` universal row data class + a columnar mapper shared by the document table, the
scratchpad table and the clipboard table. `collectDescendants`, `deepCopySubtree`, and the single
shared `remapDescendantIds`. Soft-delete and order helpers.
**Verify:** JVM unit tests, including "remap on both insert and replace".

## Phase 8 — Binary codecs
Format B encoder/decoder with the Paintsprout channel profile, mask codec, matrix header, wet-state
codec, lenient `params` JSON. Zero-progress inflate guard. Per-row decode guards.
**Verify:** JVM round-trip tests for every op type; corrupt-input tests (truncated blob, bad zlib
header, unknown flag bits) assert *degradation*, never a hang or a throw that escapes.

## Phase 9 — Document repository
`SketchbookRepository`: create sketchbook (meta row + page + layer + palette), page CRUD/reorder,
layer accessors, `appendOp` (truncating the redo tail), `undo`/`redo` as `undoDepth` moves,
raster-cache read/write, palette read/write. Same API surface parameterized over table name so the
scratchpad reuses it verbatim.
**Verify:** JVM tests against a temp file DB — full lifecycle, undo/redo across a close/reopen.

## Phase 10 — Editor save: strokes + surface 🧪
`DocumentSession` owns the open document for the editor. `PaintCanvasView` gains a persistence
callback: each committed `StrokeOp` and `SurfaceOp` is serialized and appended (debounced,
coalesced, off the UI thread). Page row's resolved surface written in the same transaction as its
`surface_op`.
**Device test:** paint a page, leave, pull the file, confirm rows and point counts.

## Phase 11 — Editor save: selection ops, clips, wet state, palette 🧪
`FillOp` / `EraseOp` / `MoveOp` with cropped masks; `StrokeOp.clip` as a `stroke_clip` child;
watercolor `wetSchedule` / `wetCrop` / `dryFreeze` as `wet_state`; tray pots, mixture and brush load
as `palette`/`pot` rows written on change.
**Device test:** wand-fill, erase, move/scale/rotate, a frisket stroke, an interrupted wash, and a
custom pigment — all present in the file.

## Phase 12 — Editor load 🧪
Open a page: restore canvas size (book), surface kind/params/seed/plain colour (page), palette and
brush load, then the raster cache fast path (decode → `paintBmp`), with op replay as the fallback
when `opCount != undoDepth` or the cache is missing/corrupt. Ops load lazily for undo. Cross-session
undo/redo through `undoDepth`.
**Device test:** the full round trip — paint, leave, relaunch, confirm pixel-identical restore, then
undo back past the session boundary and redo forward again.

## Phase 13 — Autosave, lifecycle, crash safety 🧪
Debounce/coalesce policy, `onPause` seal, application-scoped non-cancellable close with a per-step
guarded seal, dirty tracking, cover + `pageCount` + `updatedAt` refresh on close, in-progress work
never written to a plaintext temp file.
**Device test:** force-stop mid-stroke, kill during seal, disk-full simulation if practical; confirm
no data loss beyond the last debounce window and no stray sidecars.

## Phase 14 — Library screen (flat) 🧪
`LibraryActivity`: list, create (with a canvas-size chooser — book-level), open, rename, delete,
duplicate. Cover capture on seal. Launch routing wired to the library button.
**Device test:** create several books, confirm covers, names, page counts, and that deleting scrubs
cleanly.

## Phase 15 — Folders, move, sort, search 🧪
Folder rows, breadcrumb navigation, create/rename/delete folder (with a non-empty-folder policy),
move sketchbook, sort by name/created/updated, name-only search across the library.
**Device test:** nested folders, move in and out, search hits and misses.

## Phase 16 — Pinned + recents 🧪
`PINNED_LIST_ID` list with `list_item` child rows (hard-deleted on unpin, scrubbed on document
delete, members resolved and filtered at read). `sketchbook_activity` logging `OPENED`/`EDITED`, and
a Recents section derived from it.
**Device test:** pin/unpin, delete a pinned book (no ghost), confirm recents ordering.

## Phase 17 — Multi-page UI 🧪
Page strip with thumbnails, add / duplicate / delete / reorder, prev/next navigation, per-page
surface selection, `lastOpenedPage` restore, page-count refresh.
**Device test:** a 10-page book — reorder, delete the current page, relaunch onto the right page.

## Phase 18 — Scratchpad 🧪
Scratchpad hosting in the editor, multi-page, restricted tool set, always-available entry point, its
own tray in the index.
**Device test:** paint on several scratch pages, relaunch, confirm restore and that the sketchbook
tool set is unaffected.

## Phase 19 — Lasso tool 🧪
New `Tool.LASSO` + icon + rail entry: freehand closed loop → mask → the existing selection
machinery (fill, erase, move/scale/rotate, frisket-constrained painting). Available in both the
sketchbook editor and the scratchpad.
**Device test:** lasso a region, all four operations, and a frisket stroke inside it.

## Phase 20 — Clipboard 🧪
Copy ops wholly inside the selection into the `clipboard` table (fresh ids), paste into any page
with fresh ids as one undoable unit, cross-document and cross-surface. Clipboard survives process
death; a corrupt clipboard item is dropped per item, never thrown at launch.
**Device test:** copy in a sketchbook → paste in the scratchpad and in another book; paste the same
clipboard twice (the id-remap test that broke Notesprout).

## Phase 21 — Send to scratchpad / send to sketchbook 🧪
Whole-page transfer both directions with a target picker, carrying the surface across.
**Device test:** both directions, including into a brand-new sketchbook.

## Phase 22 — Export 🧪
`notebook_meta` continuous upkeep (create / open / close), then export as a raw file copy named
`<Sketchbook Name>.soil` (sanitized `[^a-zA-Z0-9_\-. ]`, UUID fallback, spaces preserved),
`application/octet-stream`, via the share sheet. Encrypted books export silently as ciphertext.
**Device test:** export plaintext and encrypted books; open the exported file in the `sqlcipher` CLI.

## Phase 23 — Import 🧪
The full pipeline: copy to cache → probe → unlock (`IMPORT` bucket) → read manifest with **UUID
alphabet validation on every id** → collision (replace / keep both / cancel) → placement with
**create-only** folder recreation from `folderPath` → name conflict → keying chooser → install by
`.new` + rename → index registration in one transaction → retire the replaced document **only after
commit** → refresh embedded meta → cleanup on every exit path including cancels.
**Device test:** import a file exported in Phase 22 on a clean install, then again for each collision
branch; refuse to replace an open document; attempt a crafted bad id.

## Phase 24 — Encryption UX 🧪
Per-document passphrase (set / change / remove) with in-place `sqlcipher_export` conversion and the
never-zero-copies swap; global passphrase rotation as a crash-resumable batch re-key (marker written
before the first file, per-file verify-then-skip, quarantine a mislabeled file and continue, cached
global passphrase updated only when `pendingIds` is empty); lock glyphs and cover suppression for
`NOTEBOOK` scope.
**Device test:** encrypt a book privately, relaunch and unlock, rotate the global key with several
books present, kill mid-rotation and confirm resumption.

## Phase 25 — Compaction, measurement, leak audit 🧪
Compactor sweep (guarded per row, `VACUUM` only if something actually changed, `updatedAt`
preserved). Measure real file sizes for a typical page and a heavy page; re-measure PNG vs raw+zlib
for the raster cache; decide a cache-retention policy (all pages vs the N most recent). Audit: no
stray sidecars, no names in plaintext prefs, no content in the index, bounded decode everywhere.
Write the final `docs/soil-format.md` spec describing what actually shipped.
**Device test:** a large book — open time, seal time, file size, storage growth over a session.

---

# Part 11 — Risks & open questions

## Raster cache size is the main unknown
A full-screen page on the Movink 11 is roughly 1920 × 1200 buffer px (`SUPER_SAMPLE` is 1.0 today) —
about 9 MB uncompressed ARGB per layer. PNG will do well on flat art and badly on a fully painted
watercolour page. A 20-page book could plausibly land anywhere between 10 MB and 100 MB. Mitigations,
in order of preference: crop the cache to painted bounds; keep caches only for the N most recently
opened pages and replay the rest; measure raw+zlib against PNG (Phase 25). **This is why Phase 25
exists as a real phase and not a cleanup task.**

## Clipboard whole-object copy will surprise
Decision 10 was made deliberately, and it is the right call for keeping pasted content editable and
re-toothable on the destination surface. But in a *paint* app the mismatch is real: a stroke crossing
the selection boundary is dropped entirely, and a stroke that starts inside carries its whole tail
outside the region the user drew. Mitigation to build in Phase 20: highlight exactly which ops will
be copied at copy time, so the result is predictable rather than surprising. If it still reads wrong
on device, a raster-patch fallback is a small additional phase — the selection machinery for it
already exists (`floating` / `paintHole` / `floatSourceMask`).

## Watercolor replay fidelity
The wet simulation runs on the wall clock. `wetSchedule` / `wetCrop` / `dryFreeze` exist precisely so
replay is honest, and they round-trip in Phase 11 — but replay is only exercised on the *slow* path
(cache miss / deep undo). Phase 12's device test must include a deep undo through a wash to prove it.

## Room's `user_version` hazard
Every `sqlcipher_export` round-trip in Phases 6, 23 and 24 must copy `user_version` explicitly. This
bricked real Notesprout files. Put it in the one shared export helper and nowhere else.

## Deliberately deferred
- **Backup and restore** (user decision 8) — its own plan later. The `flags` bit and the per-destination
  timestamp columns are *not* reserved in this schema; they will be an additive migration.
- **Layer UI** — columns exist (`opacity`, `blendMode`, `flags`), compositing does not.
- **Raster / tiled pixel objects** — reserved type, no implementation. Needed if a photo-import or
  smudge-bake feature ever lands.
- **Cross-app import semantics.** If Paintsprout is handed a file with only a `notebook` table:
  reject cleanly? Import as an unopenable shell? Undecided in the source spec too, and it must be
  settled before Phase 23 ships an importer that can receive Notesprout files. **Proposed answer for
  Phase 23: reject cleanly with a clear message, and never modify the file.**
- **Who owns a mixed file's index row** — one row, one `type`. Not reachable until both apps can
  write one file; not solved here.
