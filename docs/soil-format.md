# The `.soil` format, as Paintsprout ships it

This describes what is actually on disk after Phases 1–25, not what was planned.
Where the two differ, the difference is called out. The plan and its reasoning
live in [`file-format-plan.md`](file-format-plan.md); this is the reference.

Paintsprout is one member of the Sprout family container contract: the *shape* is
the family's — one document per SQLite file, a universal wide-sparse object row, an
identity record beside it, `folderPath` ancestry — while the names say what this
app actually holds. A `.soil` written here carries a `sketchbook` table and a
`sketchbook_meta` record, and nothing in it is called a notebook.

---

## 1. What is on disk

```
<app external files>/
├── paintsprout.db          the global index (+ its live -wal/-shm)
└── Garden/                 flat, no subdirectories, ever
    ├── 3f2a1b8c-….soil
    └── 9c71d0e4-….soil
```

- **One document is one SQLCipher database.** Its filename is its UUID; its
  display name lives in the index and inside the file, never in the path.
- **`Garden/` has no structure.** Folders, ordering, pins and recents are the
  index's business, which is exactly what makes a document portable: it carries no
  assumption about where it lived.
- **Sidecars are transient.** A sealed document has none — verified on device. The
  index keeps its `-wal`/`-shm` for as long as the app runs, because it never
  closes during normal use.

### In-flight names

A file being replaced passes through names that are deliberately *not* documents,
so no sweep mistakes a half-written file for a real one:

| Suffix | Meaning |
|---|---|
| `.tmp` | a verified replacement waiting to be renamed in |
| `.old.bak` | the original, renamed aside mid-swap |
| `.new` | an incoming copy being written by import |

`SwapRecovery` runs **before any probe** at launch and repairs an interrupted
swap. That order is load-bearing: a probe of an absent file says INVALID, INVALID
means "fresh install", and a fresh install would replace the library.

---

## 2. Encryption

Every document and the index are SQLCipher databases, encrypted from their first
byte. There is never a moment at which a plaintext index exists.

| | |
|---|---|
| KDF | PBKDF2-HMAC-SHA512, **256,000** iterations (SQLCipher 4 defaults) |
| Cipher | AES-256-CBC with HMAC-SHA512 page authentication |
| Salt | the file's first 16 bytes |
| Page size | 4096 |
| `user_version` | **1** |

### Key scopes

- **`GLOBAL`** — the device's own passphrase, minted at first launch as a `PSPT-`
  recovery key (160 bits, Crockford base32, 8 groups of 4). Opens silently.
- **`SKETCHBOOK`** — the document's own passphrase. Asked for every time it opens;
  the derived key lives in RAM until the process ends and **never touches disk**.
  No cover may be cached for such a document, at any time: the index is encrypted
  with the *global* key, and a thumbnail there would cross exactly the boundary
  the user drew.
- **Not encrypted** — a real state, reachable on purpose, so a document can be
  handed to another program. The library says so plainly before doing it.

Derived keys for global-scope files are cached on disk (`PRAGMA key = x'…'`) so a
cold launch costs ~35 ms instead of 300–700. **A cached key is verified before it
is trusted**: one that no longer opens its file is indistinguishable from
corruption by the time SQLite sees it.

### Changing a key

`sqlcipher_export` into a fresh database, then a never-zero-copies swap. One
helper does it for all three callers (per-document keying, global rotation,
decryption), because of the line that is easy to leave out:

> **`sqlcipher_export` does not copy `user_version`.** The export copies content;
> the version lives in the header. Without an explicit copy the new file is left
> at 0, and the next open runs `onCreate` over a database full of data. This
> bricked real Notesprout files.

Global rotation is resumable. A marker naming the passphrase being rotated *to*
is written **before the first file is touched**; each file gets a verdict from two
facts — opens with the old key → convert, opens with the new → skip (an
interrupted run already did it), neither → quarantine and carry on. The index is
converted last and the cached passphrase changes last of all, so a half-finished
rotation resumes from an ordinary launch.

---

## 3. The document (`<uuid>.soil`)

Two tables.

```sql
CREATE TABLE IF NOT EXISTS sketchbook_meta
    (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL);
```

One row, `id = 0`, holding the identity record described in §5.

```sql
CREATE TABLE sketchbook ( … 26 columns … );   -- the universal object row
CREATE INDEX index_sketchbook_parentId_order_deletedAt
    ON sketchbook(parentId, "order", deletedAt);
```

### The universal object row

Every object — page, layer, stroke, fill, palette pot — is one row in one table,
with a string `type` discriminator and columns shared **by role**: `text` is a
sketchbook's title, a layer's label and a pigment's name. Read `type` first, then
interpret.

Almost every column is nullable and that costs nothing worth counting: a NULL is
about a byte of record header and trailing NULLs are truncated away entirely. A
stroke row populates six columns; the other twenty never reach the disk. What it
buys is that a new object type costs no migration, no join and no per-type reader.

| Column | Role |
|---|---|
| `id` | UUID, primary key |
| `parentId` | `""` on the root row; otherwise the containing object |
| `type` | see the catalog below |
| `order` | sort among siblings; **the op index** under a layer |
| `createdAt`, `updatedAt` | epoch ms |
| `deletedAt` | NULL is alive. Soft delete is the only delete |
| `x`, `y`, `width`, `height` | geometry, buffer px — top-left plus extents, never right/bottom |
| `text`, `color`, `refId`, `flags`, `seed`, `kind`, `params` | shared scalars |
| `tool`, `strokeWidth`, `opacity`, `blendMode`, `undoDepth`, `opCount`, `amount` | paint-specific |
| `blob` | geometry, masks, pixels |

### Hierarchy

```
sketchbook            root meta row, parentId = ""
├── page              "order" = page index
│   └── layer         exactly one for now; the schema allows more
│       ├── stroke        "order" = op index
│       │   ├── stroke_clip   frisket mask, if drawn inside a selection
│       │   └── wet_state     watercolor: tick schedule, crop, dry freeze
│       ├── fill      }
│       ├── erase     }  selection ops, mask in blob
│       ├── move      }
│       ├── paste         a clipboard paste — the one op with ops beneath it
│       │   └── stroke/fill/erase …
│       ├── surface_op    a surface change, on the undo timeline
│       └── raster_cache  composited pixels — NOT an op (see below)
└── palette
    └── pot
```

**Shipped beyond the plan:** `paste`. A paste is one step in the timeline holding
several marks, because a paste of thirty marks that takes thirty presses to undo
is not one paste. It is the only op with op children.

### The undo model

A layer carries one integer, `undoDepth`, and it is the whole thing:

```
order:      0    1    2    3    4
ops:       [A]  [B]  [C]  [D]  [E]
                      ↑
                 undoDepth = 3     A B C committed; D E are the redo stack
```

Undo decrements it, redo increments it, and neither touches a row — so undo
history **survives closing the document**. Reopen a page days later and you can
still step backwards through it.

The price is that `order` must stay dense for a layer's ops, which is why
appending truncates the redo tail with a *hard* delete. That is the one routine
hard delete on the content path, and it is safe because a truncated redo tail is
unreachable: the user has already replaced that future with a different one.

### The raster cache

A `raster_cache` row sits at **`order = -1`**, out of op space entirely, so every
history read can simply take `order >= 0`. It holds the composited paint as a
PNG, with `opCount` recording the frontier it was composited at; it is read back
only while `opCount == undoDepth`, so an undo makes it stale rather than wrong.

Measured on real artwork, **the cache is 75–88% of a document** — 651 KB for one
heavy page, 185 KB across thirteen sparse ones. It is rebuildable, so the
compactor keeps only the four most recently written and drops the rest; the pages
that lose theirs replay their ops instead.

**PNG, not raw+zlib.** Measured both ways on the same artwork: PNG is 2–4%
smaller *and* self-describing.

| | PNG | raw + zlib(6) | raw |
|---|---|---|---|
| one heavy page, 2200×1440 | **651,644** | 675,468 | 12,672,000 |
| thirteen sparse pages | **184,909** | 188,479 | 101,544,000 |

### Stroke geometry — "format B"

The blob is pure geometry; everything scalar about a stroke is a column. So
changing a stroke's colour is a scalar update rather than a re-encode, and a query
can ask which tools were used on a page without decompressing anything.

```
[version:1] [ zlib( [flags:1] [count:4] [ per-point: 4 bytes × set channels ] ) ]
```

The version byte is **outside** the compressed region, so a reader can tell what
it is holding before inflating it. Every channel is exactly 4 bytes, so
`stride = 8 + 4 × popcount(flags)` — a decoder computes the row size instead of
branching per channel.

---

## 4. The index (`paintsprout.db`)

Room, SQLCipher, one file per install. Tables: `objects`, `sketchbook_activity`,
and the two document-shaped tables `scratchpad` and `clipboard` — column-for-column
identical to `sketchbook`, so every codec and subtree walk works on them unchanged.

**No artwork from any document ever reaches the index.** Verified on device: the
only blobs in `objects` are covers, on live sketchbook rows, and none for a
private-scope book.

| `objects.type` | Holds |
|---|---|
| `folder` | structure only: `name` + `parentId`, cycle-guarded ancestry |
| `sketchbook` | name, page count, canvas size, key scope, cover in `blob` — enough to draw a card **without opening the file** |
| `list` / `list_item` | the pinned list; membership is child rows, hard-deleted on unpin |
| `clipboard` | metadata singleton; the copied ops live in the `clipboard` table |

### Sentinels

`00000000-0000-0000-0000-<group>`, created by idempotent `ensure…()` at every
launch and **never by a migration** — a migration that inserts data can fail
halfway on somebody's device; this simply runs again.

| Constant | Group | Decodes to |
|---|---|---|
| `PINNED_LIST_ID` | `70696e6e6564` | `pinned` |
| `CLIPBOARD_ID` | `636c69706264` | `clipbd` |
| `SCRATCHPAD_ROOT_ID` | `736372746368` | `scrtch` |
| `CLIPBOARD_ROOT_ID` | `636c69706272` | `clipbr` |

### The `updatedAt` discipline

`updatedAt` is the input to a backup predicate, so it moves only for a real
modification.

| Operation | Moves it? |
|---|---|
| Rename, move, cover refresh, page-count refresh, encryption change | **yes** |
| Pin / unpin, activity logging | **no** — a list toggle is not a modification |
| **Compaction** | **no** — reclaiming space must not make a document look edited |

---

## 5. `sketchbook_meta` — the identity record

JSON, one row, in the `sketchbook_meta` table. This is what makes a `.soil`
self-describing, and therefore what makes **export a plain byte copy**: everything an importing device needs is
already inside the file, so exporting never opens it and therefore never has to
unlock it.

```json
{
  "formatVersion": 1,
  "sketchbookId": "590f9749-703d-4e57-92a6-87d3ae166046",
  "name": "Rope and tide",
  "createdAt": 1785004491281,
  "updatedAt": 1785010727409,
  "encrypted": true,
  "keyScope": "GLOBAL",
  "folderPath": [{ "id": "f176ec74-…", "name": "Voyages" }],
  "appVersionCode": 1
}
```

- **`folderPath` holds stable ancestor UUIDs**, root → immediate parent. An
  importing device recreates missing folders *with the same ids and names*, so
  importing one document onto three devices converges on an identical hierarchy —
  no sync, no server, no merge.
- **`cover` is plaintext documents only** — always absent when encrypted. A reader
  holding a file it cannot open must not be handed a picture of its contents.
- Decoding is lenient in both directions (`ignoreUnknownKeys`, `explicitNulls =
  false`): a field added by a newer build must not make the record undecodable by
  an older one, and vice versa.

The record is refreshed at **create, open and close** — never at export. The
library is where a document is renamed and moved and it never opens the file to do
it, so the two drift apart between sessions; those three moments are when the file
is open anyway.

---

## 6. Import: reading a file we did not write

The only path where the app acts on somebody else's bytes, so:

1. **Copy to cache first.** Everything after needs a *file*, and a content URI is a
   stream that may be a cloud provider or gone by the second read.
2. **Refuse anything under one page (4096 bytes)** before probing. A file with no
   SQLite header is indistinguishable from an encrypted one, so without this a
   renamed text file gets a passphrase prompt.
3. **Probe**, then open with the device key, then ask for a passphrase (rate
   limited under its own bucket).
4. **Validate every id in the manifest against the UUID shape** — the document's
   and every ancestor folder's. `Garden/<id>.soil` is built from the first and the
   others become index keys; an id containing `../` writes wherever the sender
   likes.
5. **Collision** → replace / keep both / cancel. A tombstone is *not* a collision.
   Keep-both mints a new id and re-identifies the copy's root row.
6. **Folder recreation is create-only.** A folder already here is used as it
   stands, never renamed to match somebody else's library.
7. **Install by `.new` + rename, then write the index row, then retire what was
   replaced** — in that order. Backwards, a failed install leaves a card that opens
   onto nothing.
8. **The staged copy is deleted on every exit**, including cancels.

---

## 7. The seal

What happens before a document goes cold. Each step is guarded on its own, on an
application-scoped non-cancellable coroutine — a disk-full failure seconds after
the user left a page must not crash the app, and must not stop the steps after it.

```
flush pending ops
  → write the raster cache
  → refresh sketchbook_meta (name and ancestry from the index)
  → refresh the index row (page count, cover, updatedAt)
  → purge rows tombstoned in PRIOR sessions, and stale raster caches
  → VACUUM, only if something actually went
  → wal_checkpoint(TRUNCATE), close, delete a stray -journal
```

"Prior sessions" is the compactor's safety margin: a row tombstoned while this
document was open is still this session's business.

---

## 8. Invariants

The rules that are load-bearing. Each has a scar behind it.

1. One document = one SQLite file; the file is self-describing via `sketchbook_meta`.
2. **Never hold zero copies.** Every replacement is verify → rename aside → rename
   in → delete aside, and launch-time repair runs before any probe.
3. **Never open a document with a create-capable helper without checking the file
   exists.** A fabricated empty database masquerades as the real one and, when
   encrypted, "verifies" any passphrase it is given.
4. **Never delete a database because it failed to open.** Wrong key and corruption
   look identical; the default error handler deletes.
5. **A cached derived key is verified before it is trusted.**
6. **Every subtree write mints fresh ids for descendants**, through one shared
   helper. Notesprout shipped this bug twice by having two copies of the rule.
7. **Carry `user_version` across every `sqlcipher_export` round-trip.**
8. `order` stays dense for a layer's ops; the redo tail is hard-deleted.
9. A selection belongs to the page it was drawn on, and clearing it says so.
10. **Nothing about a document reaches a plaintext preference file but ids.**
11. **No artwork reaches the index**, and no cover at all for a private-scope book.
12. **Every decode of stored pixels is bounded** — a header can claim any size, and
    a PNG in an imported file is a multiplication away from an OOM.

---

## 9. What was measured

On the target device (Movink 11), against real artwork rather than fixtures.

| | |
|---|---|
| A 13-page book, before compaction | 245,760 B — **75% raster cache** |
| …after compaction (4 caches kept) | **126,976 B**, a 48% reduction |
| One heavy page (a full-bleed painting) | 737,280 B — **88% raster cache** |
| Stroke geometry, 17 strokes | 11,081 B |
| Cover, per book | ~13 KB WEBP |
| Index, 8 live rows + covers | 446,464 B, no free pages after its own sweep |

The ratio is the finding: **artwork is small and pixels are not.** A page's ops
are a few kilobytes; its composited image is hundreds. Everything about the cache
policy follows from that.
