# RASTER_PLAN.md — The raster experiment (Paintsprout Onyx, branch `onyx`)

**Standalone plan for a raster drawing mode beside strokes.** This file is the cross-session
memory for the experiment: read it whole at every phase start, together with the repo-root
`CLAUDE.md` and `apps/paintsprout_onyx/CLAUDE.md`. **Do not load `ONYX_PLAN.md` for this work**
unless a standing trap or an arc-1 decision needs checking; its protocol and traps are summarised
at the end so this file is enough. Notesprout's `RESTORE_PLAN.md` and `ENCRYPTION_PLAN.md` are the
shapes this file copies.

**Status: NOT STARTED.** Planned with Opus 4.8 on 2026-09-03 · reviewed by Fable 2026-09-06
(amendments A1–A8, § Review amendments) · lifted into the repo 2026-09-06 · R0 ⬜ · R1 ⬜ · R2 ⬜ ·
R3 ⬜ · R4 ⬜ · R5 ⬜.

**Phase letters:** arc 1 took **G**. This experiment takes **R**.

**Not an arc, yet.** Arc 1's verdict left arc 2 deliberately undecided. This is an **experiment
branch** with light ceremony that becomes arc 2 — and is lifted into `ONYX_PLAN.md` — only if R5's
verdict says a raster page feels truer than strokes for pencil. Until then nothing here is a
commitment beyond the next phase.

---

## What this is

Arc 1 proved that graphite through g-paper on the NA5C feels like pencil, and closed with a verdict
(`ONYX_PLAN.md` § G6 → *The verdict*): *"I like the look and feel. I'd say this exercise was a
success. The Kaleido is fine for this pencil sketch stuff. The eraser is fine for now too."* Two
things the artist noticed as *not* like paper are both consequences of storing **vector strokes**
rather than pixels:

- **The eraser takes whole marks, not graphite.** It is the one deliberate break with Paintsprout's
  WYSIWYG rule. A real eraser rubs pigment off the tooth; a stroke eraser deletes an object. A
  rubbing eraser was Fable's recorded arc-2 recommendation — "a partial-erase model g-paper does
  not have."
- **Strokes give the artist powers paper does not** — recolour, reflow, a mark that comes back
  whole — while blocking the ones paper has: rubbing, smudging, blending, tooth. For a sketching
  pencil, a page that *is* pixels is arguably truer to paper, not less.

This experiment explores that shift: store the page as an **image**, and make the eraser a **pixel
eraser**. The anchor decision (Greg, 2026-09-03) is that stroke mode and raster mode **coexist at
runtime, per book, in one codebase** — you draw some books as strokes and some as raster, on the
same panel, and decide which feels better. Front-load getting raster ink and a pixel eraser into the
hand; formalise the ceremony only if the feel proves out.

**Framing that keeps `ONYX_PLAN.md`'s non-goals honest:** raster ink is still the approved arc-1
graphite — the hairline lead, `#505050`, pressure carrying tone, broken coverage. This is a
**storage and eraser change, not a paint feature.** "No paint, no surfaces" still stands.

## What already exists (do not rebuild)

| Piece | Today | This experiment |
|---|---|---|
| `CanvasPaperView.drawCommittedContent` (g-paper core, ~line 782) — white, template, below-strokes renderers, then `StrokeRenderer.draw` per stroke into a `RenderNode` | stroke replay | R0 adds a raster branch that blits one page bitmap where the stroke loop runs |
| `CanvasPaperView.commitCapturedStroke` (~876) — builds the `Stroke`, adds it, `bakeAfterCommit()` → `redrawCommitted()` | one whole-page re-record per pen-up | R0 composites the stroke into the raster and **drops the object**; the re-record becomes one `drawBitmap` |
| `CanvasPaperView.eraseAlong` / `beginEraseSweep` / `finalizeEraseRedraw` (~1092–1160) — `EraseHitTest` on the sweep polyline, ids removed, one repaint at sweep end | stroke removal | R1 scrubs pixels along the same sweep in raster mode, same one-repaint-at-end shape |
| `OnyxPaperView` eraser handoff — `beginEraseSweep()` on pen-down, `finalizeEraseRedraw()` + `handwritingRepaint` on pen-up; `setEraserRawDrawingEnabled(true, 2·radius)` | firmware cursor, engine erase | unchanged — the pixel eraser rides the existing handoff; the hardware eraser end already erases "whichever tool is armed" |
| `StrokeRasterizer.draw(canvas, strokes)` — bakes rows pixel-identically onto any `Canvas` | covers (G5) | R4's bake, and the proof that the pencil composites identically off-view |
| `StrokeRenderer.draw(canvas, points, color, width, style, paint, seed)` — the approved pencil | live bake | R0 draws with it into the raster's own `Canvas` — the artist's approved pixels, unchanged |
| `PaperView` — `loadStrokes` / `getStrokes` / `clearForContentSwap` / `setPageSize` / `setTemplate` / `renderToBitmap` | the host-facing API | R0 adds a page-mode property and the raster twins of load/get |
| `PaperListener.onStrokeCommitted` / `onStrokesErased` / `onPenLifted` | host records `Edit`s | R0/R1 add raster-change callbacks with default bodies (Notesprout's listeners keep compiling) |
| `SoilObjectEntity` — `flags`, `style`, `refId`, `blob` spares; `refId` on the sketchbook row is **taken** (open page, `setOpenPage`) | family format, kept exactly for this kind of parity | R2 stamps the mode in `flags` and stores the page image on its own row |
| `docs/soil-format.md` § *The raster cache* — a PNG on a row at **`order = -1`**, out of op space | the Wacom family's prior art | R2 copies the row shape exactly (see A3 for why not the page row) |
| `SketchbookSession` — `loadMarks` / `recordMark` / `recordErase` / `hideMarks` / `restoreMarks` / `renderCover`, every write through the serial `SoilWriter` | stroke persistence | R2 adds the raster load/save on the same writer |
| `UndoRedoStack` + `Edit` — **in-session, bounded at 100, cleared on close**, entries are ids; the generation counter guards record-during-replay | arc-1 undo | R3 adds a raster `Edit` and a byte budget (A1, A2) — one stack, one gesture path |
| `CoverSnapshot.render(marks, w, h)` → full-size bake → `shrink(×3)` → WEBP | G5 covers | R3 adds a pixels-in overload that skips the bake |
| `NewSketchbook` / `NewSketchbookActivity` — name, paper, first page | creation | R4 adds the mode picker |
| `PenIdleGate` + the frame-silence rule — nothing drawn or swapped while the pen is armed | G3/G4 | R2's snapshot-before-encode goes through it (A4) |

## Decisions (Greg with Opus 4.8, 2026-09-03 — binding; Fable's amendments folded in)

| # | Question | Decision |
|---|---|---|
| 1 | Coexistence | **Dual-mode, runtime, per-book.** Stroke and raster books live in one codebase; a book's mode is stamped at creation and never changes in place. |
| 2 | Pipeline | **Hybrid in g-paper.** Both modes **share** the firmware live ink, the palm gate, the EPD render-off/repaint handoff, fast mode, and the approved pencil bake; they diverge only after pen-up — persist / erase / undo / cover. Forced by decision 1: a pure-raster rewrite would remove the stroke path, and a host-side surface would fork the pen path, which the standing rule forbids. |
| 3 | Eraser | **Hard pixel eraser first** — clears the raster along the sweep. A **rubbing / partial** eraser is a later, gated phase (§ Gated follow-on). |
| 4 | Undo | **In-session, bounded — the same shape as today.** Arc-1 undo is already in-session and bounded (A1); a raster book's undo holds before-images instead of ids, bounded by **bytes** (A2). Nothing durable, in either mode. |
| 5 | Mode choice | **Chosen at creation, mode-locked**, with a **one-way stroke→raster bake**. No raster→stroke — pixels cannot be re-vectorised. Whether the bake converts in place or makes a copy is R4's phase-start question (A7). |
| 6 | Scope | **Onyx-only, experiment branch.** The Wacom app is untouched. The pencil is unchanged. |
| 7 | Where the engine work lands | **g-paper**, as a first-class page mode — never a host-side surface. Each engine phase gets a `PLAN.md` entry there, a `GPAPER_VERSION` bump, `publishToMavenLocal`, and a re-pin here. Notesprout's consumers must not notice: stroke mode is the default and every new listener method has a default body. |
| 8 | Phases / ownership | **Six, R0–R5, this standalone file.** Fable writes R0 and R1 (engine seams + the EPD eraser handoff) and drives every walk by hand; Opus builds R2–R4 on a Fable brief with Fable reading each before its walk; Sonnet does docs; R5 is the artist's. No `/code-review` inside the experiment — if R5 says yes, the arc-2 formalisation gets one on the whole range. |

### Derived rules (not separately asked — recorded so they are not re-litigated)

- **The raster is an alpha layer over the paper, never the paper itself.** Unmarked pixels are
  transparent; the committed layer still draws white and the template under it. The eraser
  **clears to transparent** (A5), so paper tooth — a named arc-2 candidate with a `paperKind` home
  already — can one day sit under a raster book without the eraser painting white holes in it.
- **One image per page, overwritten on save, never per stroke.** The family measured the page
  image at 75–88% of a document's bytes. A raster book's size is its page count times its image
  size, and nothing else grows.
- **g-paper keeps no history in raster mode either.** Before-images are the host's business, taken
  in a *will-change* callback; the engine only says where it is about to draw.
- **Every look at a raster mark is a `screencap`.** Committed raster content lives on the ordinary
  canvas, so unlike EPD live ink it is directly capturable — the camera is only needed for the
  live-preview instant. This is a real verification advantage over arc 1 and the phases lean on it.

## Review amendments (Fable pass, 2026-09-06 — folded into the design; recorded so the *why* survives)

- **A1 — The undo asymmetry is smaller than the Opus plan said.** It claimed stroke books keep
  "durable unlimited undo". They do not: `UndoRedoStack` is in-session, bounded at 100, cleared on
  close, and durable undo is an unbuilt arc-2 candidate. So raster undo is the *same* shape, and
  only the entry changes — from a list of ids to a before-image. Decision 4 is rewritten to say so.
- **A2 — Bound raster undo by bytes, not count.** A stroke's dirty rect is small, but an erase
  sweep across the page has a before-image the size of the page: at 1860 × 2480 that is ~18.4 MB
  in ARGB, and twenty of those would kill the process. G6 already learned this with the cover
  bitmap ("a full-size bitmap, on a device that runs short of it, would have wiped the card's
  picture"). The stack gets a byte budget and evicts from the old end.
- **A3 — The PNG lives on its own row, not the page row.** The Opus plan cited the family's raster
  cache (a child row at `order = -1`) and then said "on the page row", which contradicts it. It
  also matters here: `SketchbookActivity.showPage` calls `livePages()` on **every page turn** and
  that returns whole entities, so a blob on the page row would drag every page's image through
  SQLCipher each time the artist flips. A `raster` child row is read only for the page being shown.
- **A4 — Snapshot before encoding.** The bitmap is mutated at pen-up, during an erase, and on undo.
  The PNG encode runs on the writer queue and takes a fraction of a second on this CPU. Without a
  copy taken under the pen-idle gate, a save can encode a half-written image. The plan mentioned
  the debounce but not this.
- **A5 — Erase to transparent, not white.** See the first derived rule.
- **A6 — `refId` is spoken for.** The Opus plan offered `flags`, `style` or `refId` as the spare for
  the mode; the sketchbook row already uses `refId` for the open page. The mode goes in `flags`.
- **A7 — The bake must not destroy.** Whatever R4 decides (in place or copy), the stroke rows are
  **tombstoned, never deleted** — the family's rule everywhere else ("a deleted layer goes on a
  shelf, not in the bin"). Fable's recommendation is a **copy**: a new raster book beside the
  untouched stroke book, because a crash mid-conversion then leaves nothing half-converted and the
  artist can compare the two books directly — which is what the experiment is for.
- **A8 — One stack, not a sibling.** The Opus plan proposed a `RasterUndoStack` beside
  `UndoRedoStack`. But a raster book still adds and deletes pages, and those `Edit`s must interleave
  with its raster edits in one history under one gesture path and one replayer with one generation
  counter. A second stack would mean two histories on one screen. So: a new `Edit` variant, and the
  existing stack learns a byte budget.

---

## Design (binding unless a phase-start question reopens it)

### D1 — The raster page in g-paper (R0)

`CanvasPaperView` gains `enum class PageMode { STROKE, RASTER }` and `var pageMode`, default
`STROKE`. Setting it clears content, exactly as `clearForContentSwap` does — a mode is set on an
empty page, never flipped under ink.

- **`pageRaster: Bitmap?`**, page-sized, allocated lazily in raster mode at `setPageSize` and
  released with `release()`. Format is R0's phase-start question: `ARGB_8888` (18.4 MB at the
  NA5C's page, colour-ready, hardware-bitmap-safe — **the default**) or `ALPHA_8` tinted at draw
  (4.6 MB, mono only, locks out colour on Kaleido). Whichever is chosen, the `PageMode` API does
  not expose it.
- **`drawCommittedContent`**: after the below-strokes renderers, `RASTER` blits `pageRaster` at
  `templateDestRect()`'s page origin; `STROKE` runs today's loop. Above-strokes renderers unchanged.
- **`commitCapturedStroke`** in `RASTER`: same gesture gate, same `Stroke` construction (the id
  seeds the grain, exactly as today), then **`StrokeRenderer.draw` into `Canvas(pageRaster)`**
  with the same `scratchPaint` — the artist's approved pixels, drawn once — followed by
  `bakeAfterCommit()` (now one `drawBitmap` re-record). The stroke object is **not** added to
  `strokeList`. `onStrokeCommitted` still fires (the host keeps timestamps and dirty flags from it)
  and two new listener methods bracket the mutation: `onRasterWillChange(rect)` **before** the
  draw and `onRasterChanged(rect)` after — both with default empty bodies. The rect is the stroke's
  bounds inflated by its width, clipped to the page.
- **Host-facing API on `PaperView`:** `pageMode`, `loadPageRaster(bitmap: Bitmap?)` (copies in,
  replaces, re-records; `null` = blank page), `getPageRaster(): Bitmap?` (a **copy**, never the
  live bitmap — A4 depends on this), and `copyPageRaster(rect): Bitmap` for the host's before-images.
  `loadStrokes`/`addStrokes` in `RASTER` composite into the raster (the bake path, R4) rather than
  adding rows; `getStrokes()` returns empty.
- **`OnyxPaperView`**: nothing changes on the pen path — live ink is firmware, the pen-up bake goes
  through `commitCapturedStroke`, the EPD handoff is untouched. Verified, not assumed, in R0's walk.
- **Nothing in stroke mode moves.** g-paper's existing tests stay green untouched; new tests cover
  the raster branch only.

### D2 — The pixel eraser in g-paper (R1)

`eraseAlong` in `RASTER`: the same chained sweep polyline, but instead of `EraseHitTest` the batch
is stroked onto `Canvas(pageRaster)` with a round-capped, round-joined `Paint` of width
`2 · eraserRadius` and `PorterDuff.Mode.CLEAR`. Per batch: `onRasterWillChange(batchRect)` →
clear → `onRasterChanged(batchRect)`, where `batchRect` is the batch polyline's bounds inflated by
the radius. **No repaint per batch** — `finalizeEraseRedraw` at sweep end re-records and repaints
once, exactly as stroke mode does, and `OnyxPaperView`'s existing `handwritingRepaint` on pen-up
commits it to the panel. The firmware eraser cursor is already armed at `2 · radius`.

- **The hardware eraser end** routes through the same tool switch it does today ("erases whichever
  tool is armed" — G6 found this needed no host code). R1's walk confirms it scrubs pixels.
- Content renderers (`hitContentIds`) behave as in stroke mode — the host has none in this app.
- `onStrokesErased` does **not** fire in raster mode; there are no ids to report.
- Whether the sweep should repaint per batch so the hand *sees* graphite lifting as it rubs is R1's
  phase-start question. Default **no** — stroke mode's one-repaint shape held under the
  frame-silence rule for six phases, and the firmware cursor gives the hand its feedback.

### D3 — Persistence in the host (R2)

- **The mode** is bit 0 of `flags` on the **sketchbook row** (`FLAG_RASTER = 1`), stamped by
  `NewSketchbook` at creation and read once at `SketchbookSession.open`. Whether `SketchbookMeta`
  mirrors it is R2's phase-start question (default: no — the row is the truth, meta stays what it
  is).
- **The page image** is a child row of the page: `type = "raster"`, `order = -1`, `blob` = PNG,
  exactly the family shape. `SoilSchema.TYPE_RASTER`; `readMarks` keeps filtering on `TYPE_MARK`
  so it never sees it; `liveChildIds` (page delete) filters on `'mark'` today and must widen to
  `'mark' OR 'raster'`, so a deleted page's image is tombstoned and restored with the page. One row
  per page, upserted in place.
- **Load** (`showPage` in raster mode): `clearForContentSwap` → `setPageSize` → decode the blob on
  IO under the **bounded-decode guard** (the family's invariant #12: reject anything whose header
  dimensions do not match the page) → `loadPageRaster`. A missing row is a blank page. The swap
  contract and the gate are the same as today; only the load call differs.
- **Save**: the session keeps a `rasterDirty` flag set from `onRasterChanged`. Save fires
  **debounced** (a few seconds after the last change, through `PenIdleGate.awaitIdle`), and
  unconditionally on page turn, on close, and on `onPause`. Each save: `getPageRaster()` (a copy,
  taken on the main thread under the gate — ~10 ms for 18 MB) → PNG on IO → `setBlob` on the
  serial `SoilWriter`. A save whose copy predates a newer change is simply followed by another;
  the dirty flag is cleared when the copy is taken, not when the write lands.
- **Blob size guard**: a PNG over a ceiling (R2 question; family prior art suggests a few MB) is
  logged and still written — the ceiling is a watch item, not a refusal; the artist's drawing is
  never dropped on the floor.
- **`MarkRows`** grows a `RasterRows` sibling: row ↔ PNG bytes, and the header check, all pure —
  the JVM tests live here.

### D4 — Undo in the host (R3)

- **`Edit.RasterChanged(pageId, tiles: List<Tile>)`**, `Tile(rect, pixels: IntArray)`. Captured
  in `onRasterWillChange` via `copyPageRaster(rect)` while a pen-down is open; an erase sweep
  accumulates one tile per batch into **one** edit, closed at `onPenLifted`. Reversing it: for
  each tile, read the current pixels into the tile and write the saved ones back
  (`loadPageRaster` of a patched copy, or a `patchPageRaster(tiles)` engine call — R3 decides;
  the engine call is cheaper and Fable writes it if so). The swap makes undo and redo the same
  operation.
- **`UndoRedoStack` gains a byte budget** beside `MAX`: `record` evicts from the old end while the
  sum of entry bytes exceeds it. Default 48 MB (R3 question) — two and a half page-wide erases, or
  hundreds of strokes. Id-only edits cost zero bytes, so stroke books are unaffected.
- **Cross-page**: an edit carries its page as today; the replayer shows that page first (the same
  route `AddedPage`/`DeletedPage` already take), then patches. A patched page is dirty, so the
  debounced save follows.
- The generation counter and single-flight `replay` are unchanged. The always-bright arrows and
  the two-/three-finger gestures are unchanged.

### D5 — Covers (R3)

`CoverSnapshot.render(pixels: IntArray, w, h)` — straight to `shrink(×3)` and WEBP, no bake. The
session's `renderCover` in raster mode reads the last-shown page's `raster` row (or the live
bitmap's copy if the page is open) and calls it. The shelf, which reads only the pre-baked WEBP,
does not change. The G5 rules hold: rendered offline, bounded, never off the live view.

### D6 — Mode choice and the bake (R4)

- **`NewSketchbookActivity`** gains a two-way choice — *Strokes* / *Raster* — with a one-line
  description each, in the artist's words ("marks you can take back whole" / "graphite you rub
  off"). Default is R4's phase-start question (default **strokes**, the known thing).
- **The bake**: for every live page in order, `StrokeRasterizer.draw` the page's rows at page size
  → PNG → a `raster` row, in a new sketchbook whose mode flag is set (A7's copy, unless R4 decides
  in place). The command lives on the shelf's card action sheet as *Bake to raster…*, confirms
  first, runs off the serial writer with the shelf's usual guard, and lands the new book beside
  the old. Determinism is a JVM test: bake twice, same bytes.

---

## Phases

Each phase ships to the NA5C (`92c16533`) via the `device-build-install` skill and is judged in the
hand. R0–R1 are pure engine and keep g-paper's phase discipline (a `PLAN.md` entry each, a version
bump each, tests pure Kotlin with no Robolectric).

### ⬜ R0 — The raster page in g-paper
**Owner:** Fable. **Publishes:** g-paper 0.1.25.

- D1 entire: `PageMode`, `pageRaster`, the `drawCommittedContent` branch, composite-and-drop in
  `commitCapturedStroke`, the two listener methods, `loadPageRaster` / `getPageRaster` /
  `copyPageRaster`.
- A throwaway host switch (a build-time constant, removed in R2) that opens every book in raster
  mode so the panel can be drawn on.
- g-paper JVM tests: mode default, mode set clears content, composite lands the same pixels
  `StrokeRasterizer` would, `getPageRaster` is a copy, will/changed rects bracket the draw.

**Gate:** ink accumulates on the panel across many strokes; a `screencap` shows every committed
mark; pen-up feels no slower than arc 1 on a busy page (the `gfxinfo` frame count for a minute of
sketching, beside G6's 26). Fable's eyes on the bake at 1×; Greg's hand for feel.
**Questions to resolve at phase start:** bitmap format (default `ARGB_8888`) · whether
`onStrokeCommitted` keeps firing in raster mode (default yes).

### ⬜ R1 — The pixel eraser in g-paper
**Owner:** Fable. **Publishes:** g-paper 0.1.26. *The headline deliverable — erasing to paper in the
hand early.*

- D2 entire, through the existing Onyx handoff; the hardware eraser end confirmed.
- g-paper JVM tests: a sweep clears exactly the round-capped corridor and nothing outside it; the
  batch rect covers the cleared pixels; chained batches leave no gap on a fast flick.

**Gate:** rubbing lifts graphite along the sweep and leaves the rest; a flick erases a continuous
corridor; the pen's eraser end does the same; one repaint at sweep end, no flicker. Greg's hand.
**Questions to resolve at phase start:** repaint per batch or once at sweep end (default once) ·
whether the eraser radius for raster should differ from stroke mode's (default no).

### ⬜ R2 — Persistence in the host
**Owner:** Opus on a Fable brief; Fable reads it before the walk.

- D3 entire; the R0 throwaway switch removed; `showPage` branches on the session's mode.
- JVM tests: `RasterRows` round-trip, the header/bounds guard rejects a wrong-size image, the mode
  flag reads and writes, the debounce collapses a burst into one save.

**Gate:** draw, close, reopen — the page is what was drawn; turn pages and come back; kill the app
from the background after a save and reopen; a stroke book opened after all this is untouched.
Haiku's `screencap` walk covers all of it (raster content is capturable); Greg confirms nothing
was lost.
**Questions to resolve at phase start:** does `SketchbookMeta` mirror the mode (default no) ·
debounce interval (default 3 s) · the PNG size ceiling for the log line.

### ⬜ R3 — Undo and covers
**Owner:** Opus on a Fable brief; Fable writes `patchPageRaster` in g-paper if R3 takes that route
(0.1.27); Fable reads before the walk.

- D4 and D5 entire.
- JVM tests: the byte budget evicts oldest-first and never an id-only edit; tile swap is an
  involution; a raster edit on another page replays through a page turn; the pixels-in cover
  matches the bake-in cover for the same page.

**Gate:** undo a stroke, undo an erase, redo both, across a page turn; the arrows and both finger
gestures; a raster book's card on the shelf shows its last page. Haiku walks the gestures with
`screencap`; Greg's hand on the feel of taking an erase back.
**Questions to resolve at phase start:** patch via a new engine call or a full `loadPageRaster`
(default the engine call) · byte budget (default 48 MB).

### ⬜ R4 — Mode choice and the bake
**Owner:** Opus on a Fable brief; Sonnet for the picker layout and strings; Fable reads before the
walk.

- D6 entire.
- JVM tests: bake determinism; the copy carries name, paper, page order and every live page; the
  original is byte-identical before and after.

**Gate:** make a book of each kind; bake a stroke book and compare the two side by side on the
shelf and in the hand; the picker reads right at arm's length on the panel.
**Questions to resolve at phase start:** copy or in place (default copy, A7) · default mode in the
picker (default strokes) · whether the bake offers to delete the original afterwards (default no).

### ⬜ R5 — The verdict
**Owner:** Greg. Fable writes it down, with him.

Draw with a raster book the way arc 1 was judged — an evening of real sketching, then a day with
the build. The one question: **does a raster page feel truer than strokes for this pencil?**
Alongside it: what the eraser is missing (the rubbing follow-on's brief), what the Kaleido does to
composited graphite at 1×, what storage a real book costs, and what a stroke book still does better.

- **Yes** → this becomes arc 2. Lift the design into `ONYX_PLAN.md`, run a `/code-review` on the
  whole R range, freeze, and open the gated follow-on as the first phase after.
- **No** → the branch is kept as a record, the raster row type stays in the schema as reserved,
  and arc 2 is chosen from the candidate list again with this finding in hand.

**Gate:** the verdict written in this file under § Ledger, in Greg's words. Sonnet folds the
outcome into `docs/sketchbook.md` and `CLAUDE.md` either way.

### Gated follow-on — the rubbing eraser

Only after a yes. A partial-erase compositing model in g-paper: the sweep lightens graphite
progressively (alpha reduced per pass, pressure-weighted), rather than clearing it. Fable's
original arc-2 recommendation and the thing raster most naturally unlocks — deliberately after the
hard eraser has proven the pipeline, because a rubbing model tuned against an unproven pipeline is
the Phase 10/11 mistake again.

---

## Risks and watch items

- **Memory.** One page bitmap (~18.4 MB ARGB) held for the life of the open page, plus the undo
  budget, plus one copy during each save. The cover pipeline already learned not to hold a second
  full-size bitmap casually. Watch `meminfo` on the NA5C during R2's walk; the `ALPHA_8` option
  stays in reserve.
- **Storage.** A page image is most of a book's bytes. One PNG per page, overwritten — never
  per-op, never per-stroke. A book of many dense pages is the R5 measurement.
- **Kaleido dithering.** Arc 1's broken-coverage pencil survives the colour filter; compositing the
  *same* pixels preserves it. Inspect at 1×, never magnified — "inspect at the size it will be
  looked at."
- **Portability.** A raster book has no stroke rows to carry to Notesprout Paper or SN. Accepted;
  stroke books keep theirs, which is why decision 1 exists.
- **The frame-silence rule.** Every new draw into the raster happens at pen-up or under the gate.
  A save, a cover, a patch — none may touch the view while the pen is armed. The G4 arrows lesson
  applies unchanged.
- **Notesprout.** g-paper is shared. Stroke mode stays the default, every new listener method has
  a default body, and the ratta consumer's tests are run before each publish.

## Verification (end of each phase, and of the experiment)

- **JVM tests** (`./gradlew test` here; `./gradlew test` in `~/git/g-paper`) for everything pure:
  raster rows and the guard, the byte budget, tile swaps, bake determinism, the engine's raster
  branch and eraser corridor.
- **Device, eyes-on** (NA5C `92c16533`) for feel: ink accumulation, erase-to-paper, the pen's
  eraser end, close/reopen, undo across a turn, the picker, the bake. Raster committed content is
  `screencap`-visible, so Haiku's walks cover far more than in arc 1; the hand is still needed
  for feel, and Greg's short numbered checklist is written per phase for exactly that.
- **`gfxinfo`** frame counts for a minute of sketching, beside G6's 26 frames.
- Build/install via the `device-build-install` skill; g-paper artifacts via
  `cd ~/git/g-paper && ./gradlew publishToMavenLocal` then re-pin in `app/build.gradle.kts`
  (three pins: core, onyx, and core again under `testImplementation`).

---

## Working protocol (summary — the full text is `ONYX_PLAN.md` § Working protocol)

1. **One phase per session.** At phase start read this file, the repo-root `CLAUDE.md` and
   `apps/paintsprout_onyx/CLAUDE.md`. Confirm the next ⬜ phase with the user, flip it to 🔄, then
   ask that phase's **Questions to resolve at phase start** wizard-style, one at a time, before
   writing code. Nothing from prior conversations may be assumed: if it is not in the repo or
   project memory, it does not exist.
2. **Model recipe (decision 8):** Fable plans, writes R0 and R1 entire and any engine call R3
   needs, reviews every phase's code before its walk, and drives every walk by hand; Opus for R2–R4
   on a Fable brief; Sonnet for layouts, strings, docs; Haiku for adb walks. Background agents only
   for Opus/Sonnet/Haiku, ≤ 5 concurrent.
3. **Testing gate:** JVM unit tests for all pure logic. Haiku device agents verify everything adb
   can see on the NA5C — and in raster mode that includes every committed mark. The user gets a
   **short numbered checklist** only for what needs a hand: live ink, the eraser's feel, how
   graphite reads on the panel.
4. **Devices:** NA5C only. Never install anywhere else without an explicit ask.
5. **Commit + push only when all tests pass or the user gives the all-clear**, and only after docs,
   memory and `CLAUDE.md` updates are in. Then the user runs `/clear`.
6. **Status markers:** ⬜ Not started · 🔄 In progress · 🧪 Awaiting device verification ·
   ✅ Complete (commit `<hash>`). Every phase records an **Outcome** note under § Ledger when it
   closes.
7. **g-paper gaps are fixed in g-paper** — a `PLAN.md` phase there, `GPAPER_VERSION` bump,
   `publishToMavenLocal`, re-pin here. **Never work around an engine gap in the host.** The engine
   commit lands with the host commit: a pin pointing at an uncommitted engine is a tree a fresh
   clone cannot resolve.
8. **House rules:** commit messages are a single plain sentence in the artist's terms; comments
   explain why, at length, in the same register; a change that reverses a recorded decision
   updates the comment that argued for it.

## Standing traps that bind this experiment (the full list is `ONYX_PLAN.md` § Standing traps)

- **EPD pen overlays are invisible to `screencap`; committed content is not.** In raster mode the
  committed content *is* the page, so a `screencap` after pen-up is the whole truth. Live ink is
  still the eye's.
- **adb cannot inject stylus ink.** The pencil and the eraser need the hand; chrome, page turns,
  persistence and undo gestures are agent-verifiable.
- **Frames presented during a live raw contact are withheld**, and the frame-silence rule follows
  from it. Nothing draws while the pen is armed — and now that includes saves, patches and covers.
- **The NA5C is a Kaleido panel, 1860 × 2480, densityDpi 300.** Greyscale renders on the crisp
  mono layer. Inspect graphite at 1×.
- **BOOX spams logcat**; **`install -r` + immediate `am start` can leave the package disabled**
  (`pm enable` heals); **push to `/data/local/tmp` then `cp`**, never straight into app storage;
  **verify `mResumedActivity` before any screencap conclusion.**
- **Every Home press used to render the full page** (G6). Any new "on pause" work — and R2 adds a
  save there — must be a copy-and-encode off the main thread, never a bake.

---

## Ledger

*(Empty. Each phase appends an **Outcome** here when it closes: what landed, what was measured,
what the hand said, and the commit. R5 appends the verdict in Greg's words.)*

---

## Appendix — build & install

See `ONYX_PLAN.md` § Appendix and the `device-build-install` skill. In short: `./gradlew test` and
`./gradlew assembleDebug` here; `adb -s 92c16533 install -r` the debug APK; verify
`mResumedActivity` before believing a screencap. g-paper: `cd ~/git/g-paper && ./gradlew test
publishToMavenLocal`, then bump the three pins in `app/build.gradle.kts`.
