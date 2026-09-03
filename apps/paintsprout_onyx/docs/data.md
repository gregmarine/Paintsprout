# Data

This is the reference for what Paintsprout Onyx keeps on disk: the index that knows what
sketchbooks exist, the `.soil` files that hold what is drawn in them, the byte format a mark is
written in, and the single write queue everything funnels through. Written at the **G6 close-out**
(2026-09-03), against the code as it stood after G5. It distils the KDoc in the files it cites
rather than restating the plan that got them there — `ONYX_PLAN.md` carries the phase history,
`docs/sketchbook.md` the screen that draws the marks this format stores, `docs/crypto.md` how every
file named here comes to be unreadable without the global key.

The short version, if nothing else survives: this data model is Notesprout Paper's shapes wearing
Paintsprout's words. Table structure, codec and crypto spine are the family's, verbatim — only the
vocabulary is ours (`sketchbook` for `notebook`, `paperKind` for `templateKind`). That kinship is a
locked decision (`ONYX_PLAN.md`, "Data model"), not a convenience this doc talks itself into.

## Where things live on disk

Every path the app owns is built in exactly one place, `data/SoilFile.kt` — nothing else in the
codebase constructs one, which keeps "where does this live" a one-file question:

- `gardenDir(context)` — `getExternalFilesDir(null)/Garden`, the one directory holding every
  sketchbook.
- `soilFile(context, sketchbookId)` — `Garden/<uuid>.soil`. Flat, no subfolders: a sketchbook's
  place on the shelf is a fact about its index row, never about where its file sits, so a directory
  tree mirroring the folders would be a second copy of that fact, free to drift from the first.
  **Nothing enumerates the Garden** — the index says what exists, the directory only holds it.
- `indexFile(context)` — `getExternalFilesDir(null)/paintsprout.db`, the one shelf memory for the
  whole library.
- `sidecarsOf(dbFile)` — the `-wal`/`-shm`/`-journal` files SQLite may leave beside either database.
  Whatever deletes or moves a database must take these with it, or a stray `-wal` carries pages of
  the old file into whatever next claims the name.

Both `paintsprout.db` and every `.soil` are **encrypted from the first byte** — no plaintext
header, nothing readable without the key. How the key is derived, cached and verified is
`docs/crypto.md`'s subject; what matters here is only that every open of either file kind routes
through it, via `NonDestructiveOpenHelperFactory` and `crypto/SoilCrypto`.

## The index

`paintsprout.db` is one Room database, `IndexDatabase`, `user_version` **1**, one table: `objects`.
It is opened exactly once per process by `PaintsproutIndex` and never closed.

### `ObjectEntity`

| Column | What it means, by `type` |
|---|---|
| `id` | Stable UUID, primary key. |
| `type` | `folder` \| `sketchbook` \| `list` \| `list_item`. |
| `name` | Folder/sketchbook: display name. List/list_item: empty. |
| `parentId` | Folder/sketchbook: parent folder, or null at root. List: null. List_item: **the list's id**. |
| `createdAt`/`updatedAt` | See below. |
| `deletedAt` | Soft delete only. |
| `pageCount` | Sketchbook only — what the card shows without opening the file. |
| `flags` | Sketchbook only — bitset, today just `SketchbookFlags.ENCRYPTED`. Every sketchbook is encrypted; the bit is recorded rather than assumed, so a reader can trust the row without verifying the file. |
| `keyScope` | Sketchbook only — always `"GLOBAL"`, stated rather than assumed. |
| `paperKind` | Sketchbook only — `SoilSchema.PAPER_BLANK` ("BLANK") for every sketchbook so far. |
| `blob` | Sketchbook only — the cover, WEBP q100, 620×827. Never read in a listing. |
| `refId` | List_item only — id of the member (a sketchbook id). |
| `sortOrder` | List_item only — position within the list. |

`"order"` does not appear here — that column belongs to `.soil` (below); the `"order"`-in-SQL /
`` `order` ``-in-Room rule is for that table.

**Why `paperKind` writes `"BLANK"` instead of null.** Arc 1's paper is plain white — the tooth all
lives in the pencil's grain, and a second grain in the paper would fight the first over the panel's
few grey levels. But the column is the schema's kept home for a later arc's real paper, and a null
is ambiguous between "made before this app knew paper could vary" and "deliberately plain." Writing
the family's own spelling for blank, out loud, at creation (`NewSketchbook.kt`) lets a later reader
tell the two apart with no migration.

**The index** is on `(parentId, type, deletedAt)` — exactly the triple every listing query asks:
"alive children of this parent, of this type." Without it a folder with hundreds of sketchbooks
would be a table scan on every shelf draw.

**Soft deletes only.** `softDelete` stamps `deletedAt`; every listing says `deletedAt IS NULL` out
loud. The one routine hard delete is a `list_item` edge (`deleteListItem`, `deleteEdgesTo`) — an
edge records only "this is pinned right now," and a tombstoned one would have to be stepped over by
every read of the pinned shelf forever, for no history anyone wants.

### `updatedAt` means "worked on"

The rule shaping this table more than any other: **`updatedAt` moves for a rename, a move, ink, an
erase, a page added or thrown away — never for opening a sketchbook, turning its pages, or closing
it.** Two mechanisms enforce it, not just document it:

- `ObjectDao.touch(id, at)` — the plain stamp, used by `rename`/`move`: things the artist did *to
  the shelf* that the `.soil` file has no way of knowing about.
- `ObjectDao.touchIfNewer(id, at)` — `UPDATE objects SET updatedAt = :at WHERE id = :id AND
  updatedAt < :at` — what a sketchbook's **close** calls, handing over the `.soil` row's own
  last-edit stamp (`SketchbookSession.lastEditAt()`), forward only. A plain assignment on close
  would drag a sketchbook renamed this morning and merely opened this afternoon back to whenever it
  was last drawn in. Before G5 every close bumped the stamp unconditionally, and looking at an old
  sketchbook filed it as the newest work on the shelf.

Page count and cover move nothing either (`setPageCount`/`setCover`) — consequences of an edit
already recorded, not edits of their own. "Recently opened" therefore cannot live in this column
and does not: it is `RecentsPrefs`, ids and timestamps in plain prefs, never names, never the index.

### The pinned shelf

Pinned is a `list` row plus `list_item` edges — the same shape a folder uses to hold sketchbooks,
reused. `ListIds.PINNED_LIST_ID` is a written-down sentinel UUID
(`00000000-0000-0000-0000-70696e6e6564` — the last group spells "pinned" in hex, checked by
`ListIdsTest`) rather than a fresh one, because it has to be *found again* every launch.

**`ensurePinnedListExists` runs on the read path, not a migration** — a migration runs once against
whatever the file is at that moment; a restored library or a pre-pinned-shelf index would arrive
without the row and never get one. An idempotent `ensure…` called on every library launch cannot
miss.

Pinning writes a `list_item` (`parentId` = the list, `refId` = the sketchbook, `sortOrder` = one
past the max) — the order preserved even though the Pinned shelf currently draws in whatever sort
the library is set to, not pin order, kept for a later arc that lets pinned cards be dragged.
Unpinning hard-deletes the edge. Deleting a sketchbook — alone or swept up in a folder delete —
scrubs its pinned edges **before** the row is stamped, never after: stamped first, a kill
mid-operation leaves a pinned edge pointing at a row every future read must remember to distrust.

**`SUMMARY_COLS`** (`ObjectDao.kt`) is what every listing actually selects — every column above
except `blob`. A cover is a full WEBP; reading whole rows for a shelf of dozens would drag every
cover out of the encrypted index only to discard most of them. A card asks for its own cover, one
row at a time, only when it is actually about to be drawn.

### `ancestry` and the folder-delete order

`IndexRepository.ancestry(folderId)` walks the parent chain to the root for the breadcrumb, and
**stops at the first deleted row** it meets — a crumb pointing off the shelf would navigate to a
screen with nothing to explain itself. It is also **hop-capped** (50): a breadcrumb that deep is
already unusable, so the cap turns a pathological file into a truncated trail rather than a frozen
screen.

`deleteFolderRecursive` walks its subtree once, breadth-first, then stamps rows **children before
parents**, deepest first. This device kills background processes routinely and each stamp is its
own statement — stamped parent-first, a kill halfway through would leave a deleted folder standing
over folders and sketchbooks still marked alive, and since every listing walks *down* from the
root, nothing would ever surface them again: rows and files intact, the drawings gone as far as
anyone could tell. Deepest-first, whatever is still alive at any instant still has a living parent
to the root, so a kill can only leave a partly-emptied folder the artist can delete again.

## A sketchbook file

A `.soil` is one Room database, `SoilDatabase`, `SoilSchema.SOIL_VERSION` = **1**, one universal
table `sketchbook` plus raw-SQL `sketchbook_meta`. One `.soil` holds exactly one sketchbook.

```sql
CREATE TABLE IF NOT EXISTS sketchbook (
    id TEXT NOT NULL PRIMARY KEY, parentId TEXT NOT NULL, type TEXT NOT NULL,
    "order" INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
    deletedAt INTEGER, text TEXT, refId TEXT, x REAL, y REAL, width REAL, height REAL,
    color TEXT, strokeWidth REAL, style TEXT, flags INTEGER, blob BLOB
)
```

(`SoilSchema.CREATE_SKETCHBOOK`, mirrored by the `SoilObjectEntity` Room entity — see "Schema
discipline" for how the two are kept honest against each other.) An index on
`(parentId, "order", deletedAt)` backs the one query shape that matters: living children of a row,
in stacking order.

Four row `type`s exist:

- **`sketchbook`** — one row, root of the file, `parentId = ""` (`SoilSchema.ROOT_PARENT`). `text`
  = title. `refId` = the id of the page **last open**, written only by `SoilDao.setOpenPage`, on
  every page shown, so a kill mid-session reopens where the hand was. `setOpenPage` deliberately
  does not touch `updatedAt` — turning a page is not work.
- **`page`** — `parentId` = the sketchbook, `"order"` = position, `width`/`height` = the **panel's
  own size at creation**, never rescaled: a sketchbook made on this panel is a sketchbook of this
  panel's pages, for good. `refId` is `""`, never null — "no paper, and the question was asked and
  answered" (`NewSketchbook.kt`, `SketchbookSession.appendPage`).
- **`mark`** — `parentId` = the page, `"order"` = stacking position, `color` = `#RRGGBB`/`#AARRGGBB`
  text (`InkColorCodec`), `strokeWidth` px, `style` = the g-paper `StrokeStyle` name, `blob` =
  geometry in format B (`MarkCodec`, below). `deletedAt` is how erase/undo remove it.
- **`paper`** — one row per sketchbook, `text` = paper identity, `blob` = a WEBP. **Written by
  nothing in arc 1** — kept because paper texture is a real candidate later arc and the table
  already has a home for it.

**`x` and `y` are written by nothing in arc 1** either, kept for the same reason: they are object
bounds in the wider family format, and dropping them would make this "no longer the identical
structure the locked decision calls for" — the oversight would be deleting them, not keeping them.

### `MarkRows` — a stroke becomes a row, and back

`sketchbook/MarkRows.kt` is the seam between a g-paper `Stroke` and a `SoilObjectEntity`, kept away
from both the session and the engine because it alone is provable on a laptop with no tablet in the
room: a mark that goes down and comes back unchanged is a page that reopens as it was closed.

`order` comes from `SoilDao.maxOrder(parentId, type)` at write time, and that query **counts dead
rows as well as living ones**. Counting only the alive ones would let a mark drawn after an erase
claim the erased mark's old position; undo the erase and two marks fight over one slot, decided by
however SQLite broke the tie. Counting the dead means a restored mark always comes back underneath
anything drawn after it was taken out — where it actually was. Page order counts dead pages for the
same reason.

A row that will not decode is **skipped, not guessed at** — an empty mark is indistinguishable from
a mark never made, so there is no honest fallback for damage; the failing row is logged and the
rest of the page still opens.

### Erase and undo: `deletedAt`, never a real delete

`SoilDao`'s rule: **nothing in this table is ever deleted.** An erased mark or a thrown-away page is
stamped `deletedAt` and left exactly where it was, which is what lets `restore` bring it back with
its id and stacking position intact — a real delete would turn undo into recreating something that
merely resembles what was erased. `livePageCount()` counts only `deletedAt IS NULL` pages with no
`parentId` filter at all, correct today because one `.soil` holds exactly one sketchbook.

### `sketchbook_meta`

One row (`CHECK (id = 0)`) of JSON — `SketchbookMeta` — created by raw SQL rather than a Room
entity: Room's ownership of `sketchbook` buys something because that table has a shape Room can
check; a one-row blob buys nothing from being an entity except a second table Room's identity hash
now has an opinion about. It is also the row most likely to be read by something that **is not this
app** — a stock `sqlcipher` CLI opening a `.soil` with no index nearby — another reason to keep it
plain.

`SketchbookMeta` carries `formatVersion`, `sketchbookId`, `name`, `createdAt`, `updatedAt`,
`encrypted` (always true), `keyScope` (`"GLOBAL"`), `cover` (reserved by the family, always null —
Onyx covers live in the index), `folderPath` (a frozen `FolderRef` snapshot to the root — a copy,
never the authority, so a `.soil` found without its index still remembers roughly where it
belonged), `exportedAt` (reserved, always null), `appVersionCode`, `textDocument` (reserved for a
sibling that types — always false here). The field set is the whole family's, not the smallest this
app could get away with — trimming the unused fields would save nothing and quietly make these
files a different format wearing the same name. It is written both on Room's `onCreate` and on
every `onOpen`, so a `.soil` from a build that predates this table does not open cleanly only to
fail on the one statement meant to describe it.

### `auto_vacuum = INCREMENTAL`, and the one moment it can be set

SQLite accepts `PRAGMA auto_vacuum` only while a database has **no tables at all**; after that the
answer is fixed short of a full `VACUUM`, which on an encrypted sketchbook full of drawings means
rewriting the whole file. `SoilDatabase.stampAutoVacuum` sets it before Room ever opens the file: a
raw connection sets the pragma, creates and immediately drops a seed table (an empty database has
no page one to record the pragma on), reads it back to confirm it took, and leaves `user_version`
at 0 so Room still takes its normal create path. An earlier version put the pragma in Room's
`onCreate`, which runs *after* Room makes the tables — silently ignored on every file ever made, and
read as solved for as long as nobody checked.

`INCREMENTAL` only buys the *possibility* of reclaiming space — nothing runs `incremental_vacuum`
yet, because nothing in arc 1 frees a page: every delete is the soft delete above. The debt is owed
by **whichever phase first hard-deletes a row** — G2 first assumed G4 would be that phase; G4's
close-out corrected it, since page and mark deletes there are soft deletes too.

### `seal()` — what makes a closed file the whole sketchbook

`SoilDatabase.seal(file)` folds the WAL back with `PRAGMA wal_checkpoint(TRUNCATE)`, closes the
connection, and removes an empty stray `-journal` (one with anything in it is a rollback somebody
still needs). Never throws — by the time it runs the artist has already put the sketchbook down.
The checkpoint is what makes the closed `.soil`, on its own, the whole drawing: marks left sitting
in a `-wal` beside it mean the `.soil` is only *most* of the sketchbook, which matters the instant
it is copied by anything that does not know to bring the sidecars too.

## The mark encoding

`core/MarkCodec.kt` is **format B** of the `.soil` family, and it is byte-identical to Notesprout
Paper's `StrokeCodec` — not compatible in spirit, the same bytes for the same mark, proved rather
than asserted: `MarkCodecTest` decodes fixture blobs produced by compiling and running Paper's own
`StrokeCodec.kt`, never a hand-written transcription of what it looks like it would emit.

```
byte 0    : u8   = 1                                                         -- plaintext
bytes 1.. : zlib{ flags:u8 | (x:f32, y:f32[, pressure:f32][, tilt:f32]) * N }   little-endian
```

- **Byte 0 is outside the zlib stream** — a reader checks the version without inflating anything,
  refusing an unknown format cleanly instead of feeding it to zlib and reporting garbage.
- **float32, not a quantised integer.** A stroke's smoothness lives in the sub-pixel fractions a
  real pen samples at; rounding to a grid would coarsen every line permanently to save space zlib
  mostly recovers anyway.
- **Channels are optional; the flags byte (`FLAG_PRESSURE = 0x01`, `FLAG_TILT = 0x02`) says which
  are present.** Stride is derived from flags at read time, never assumed. A new channel takes a
  **new version byte**, never a spare flag bit — an unknown flag can be safely ignored, but one that
  silently implied a wider stride would read every point after the first out of step.
- Compression is `Deflater.BEST_COMPRESSION`, part of the format rather than a tuning knob: a mark
  is written once and read for the sketchbook's whole life.
- Decode failure **throws**, for the same reason bad rows are skipped rather than guessed at above.
- `inflate` bails out of any round making zero progress, not only end-of-stream ones — a zlib
  header with `FDICT` set (one flipped bit) otherwise spins `inflate()` at full tilt forever. One
  corrupt row should cost one corrupt row, never a sketchbook that can never open again.

`core/InkColorCodec.kt` is the mark's other on-disk shape: `color` is `#RRGGBB`/`#AARRGGBB` text,
not a packed int, so a `sqlcipher` CLI shows a colour a person can read. Arc 1 draws greyscale
graphite only — the pen inks in `#505050` since the hairline reset, and marks from before it read
`#000000` — so the column carries one of two greys today. The codec exists anyway, at the cost of
one file, rather than a hard-coded colour that would need a migration of every mark ever drawn the
day the first coloured pencil arrives. Unreadable text decodes to opaque black rather than throwing — a
damaged colour cell is still a mark the artist made.

**Tilt stays in the blob even though the engine now reports zero.** It was added when the NA5C's
digitizer turned out to report the pen's lean in degrees, feeding a pencil that drove width from
it; it stayed when g-paper 0.1.24 reset the pencil to an upright hairline and the engine went back
to zero, because the renderer still honours a lean when it finds one. Pages drawn under the
tilt-driven pencil (sketched with and rejected by Greg, `CLAUDE.md`'s pencil section) reopen at the
widths they were actually drawn at, rather than narrowing to lines the moment this build reads them
back — and a future pencil that reads tilt again finds a format already carrying it, no version
bump needed.

## Writes

Every write to an open `.soil` goes through **one** `SoilWriter` per session — a single serial
queue, owned by `SketchbookSession`, on `PaintsproutApplication.scope`. The point is order, not
thread safety: SQLite serialises concurrent writers on its own, but not in the order the hand made
them, and a mark followed immediately by an erase of it are two writes about the same row where
"the erase landed first" is unsurvivable — the row comes back alive with the erased mark still
showing.

**`submit` vs. `perform`.** `submit` fires and forgets — used for a mark drawn or an erase swept,
because there is nothing honest to tell the artist mid-stroke about a failed write. `perform`
submits and *awaits*, letting an exception reach the caller — used for everything undo, redo, page
add and page delete depend on, since those are things the artist asked for and is watching; a
failed undo must never look like it succeeded. Both share the same queue, which is what keeps a
page appended by a swipe and a mark drawn on it a moment later in the order the hand produced them.

**`close()` shuts the channel and joins the pump — it cancels nothing.** An earlier version drained
then cancelled, and the gap between the two was a real hole: a task accepted after the drain marker
but before cancellation simply never ran, parking any `perform` caller that outlived the screen for
the life of the process. The cover snapshot below is exactly such a caller — a `perform` on the
application scope — so it was the first thing that could actually be stranded this way. `close` now
shuts the queue at the door, lets everything already inside land, and seals the file only after the
last of it has.

**A mark captures its page at the commit.** `SketchbookSession.currentPageId` is written by the
Activity only when a page is actually on the glass, and a write is handed the page id the caller
captured at commit time rather than reading the session's pointer when the write finally runs — a
swap in flight can move that pointer before a queued write reaches the front of the line.

### The shelf card: `CardKey` and `updateShelfCard`

Whether there is anything worth writing to the shelf's card — cover, page count, last-edit stamp —
is decided **on the write queue**, not the screen's thread: `CardKey` and
`SketchbookSession.renderCover`.

`CardKey(pageId, edits)` pairs the page on the glass with a count of edits landed this sitting
(bumped inside `touchSketchbook`, which every mark, erase, page add and page delete routes
through). `renderCover` is itself a `writer.perform` call: it compares the current key against the
last card actually written (`cardWritten`) and answers `Unchanged` without touching a pixel if
nothing moved. Two things make that comparison honest: it runs **behind every write already
queued** — the marks a cover most needs are exactly the ones still in flight, since the cover is
taken seconds after the artist's last stroke — and the second half of the key is an **edit count**,
not `updatedAt`, because two edits can share a millisecond and a count cannot tie.

This is what turns a bare Home-button press on a page the artist only looked at into one queued key
read instead of an 18 MB full-page render — a cost G5 shipped and flagged as a watch item, fixed
here. `updateShelfCard` (`SketchbookActivity.kt`) then writes page count and stamp unconditionally
whenever the outcome is not `Unchanged`, and the cover only for `Fresh` — a blank page stores `null`
on purpose (the card's white frame already is the honest picture), a **failed** render stores
nothing so the shelf keeps whatever cover it had. Only once every part has actually landed does it
call `session.cardWritten(key)` — a card the index only partly took is still owed, made again next
departure.

The card is written from three places: `leave()` (arrow or back gesture), before `finish()` —
Android resumes the shelf *before* destroying this screen, so a cover written only from `onDestroy`
would land after the shelf had already drawn, which is the first thing G5 found broken on the
panel. `onStop()` covers a backgrounded screen that never reaches `onDestroy` at all, routine on
this device. `onDestroy()` is the last-resort path for everything else, guarded by a `leaving` flag
so one departure never bakes the same page twice.

## Schema discipline

Both `@Database` classes are built with `exportSchema = true`, and the exported JSON is committed
under `app/schemas/` — one file each for `IndexDatabase` and `SoilDatabase`. Off until G1, when the
`room.schemaLocation` KSP argument had nothing to write and the committed-schema trail it was meant
to provide simply did not exist.

`SchemaParityTest` is what makes the export load-bearing: it reads Room's generated DDL for
`sketchbook` out of the committed JSON and compares it, column for column, against
`SoilSchema.CREATE_SKETCHBOOK` — plus the primary key, the index, and `user_version` on both sides.
It deliberately does not compare quoting or whitespace — Room backticks every identifier, the
hand-written DDL double-quotes only `"order"` — since both describe the same table to SQLite. If
the two ever drift, the file and its drawings are fine, but Room refuses to open it on the device
with a message about an identity hash — which reads exactly like corruption, and is the road to
deleting a perfectly good sketchbook to fix a bug in the source.

**No migrations exist for either database yet** — both are at version 1, and every file this app
has written is at that version. The day a schema change is needed, a Room `Migration` belongs on
the `Room.databaseBuilder` call in `IndexDatabase`/`SoilDatabase`'s `build()`, and `SoilSchema`'s
hand-written DDL constants need updating to match — `SchemaParityTest` would catch a drift the
moment a migration landed without that second half done.

## What is deliberately absent in arc 1

- **No object rows or link rows** — other family members use these for content this app does not
  have; nothing here creates one.
- **No extension stores** — no AIDL, no `<queries>`, no proxy or binder surface.
- **No per-sketchbook keys** — every `.soil` is under the one global key (`keyScope = "GLOBAL"`,
  always).
- **No paper rows in practice**, despite the row type and columns existing — `paperKind` is always
  `"BLANK"` and nothing ever writes a `paper` row; the schema seat is reserved for a later arc.

See `ONYX_PLAN.md`'s "Non-goals for arc 1" for the fuller list this data model sits inside — export,
backup, layers and colour never touch the file format at all and belong to that list, not this one.
