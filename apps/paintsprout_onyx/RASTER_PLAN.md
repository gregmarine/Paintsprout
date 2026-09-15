# RASTER_PLAN.md — The raster experiment (Paintsprout Onyx, developed on branch `onyx`, now on `main`)

**Standalone plan for a raster drawing mode beside strokes.** This file is the cross-session
memory for the experiment: read it whole at every phase start, together with the repo-root
`CLAUDE.md` and `apps/paintsprout_onyx/CLAUDE.md`. **Do not load `ONYX_PLAN.md` for this work**
unless a standing trap or an arc-1 decision needs checking; its protocol and traps are summarised
at the end so this file is enough. Notesprout's `RESTORE_PLAN.md` and `ENCRYPTION_PLAN.md` are the
shapes this file copies.

**Status: EXPERIMENT COMPLETE — the verdict is YES; arc 2 is raster. Lifted into `ONYX_PLAN.md` § Phases — Arc 2 on 2026-09-14; this file is the record, that one is binding. Since the lift, Raster is the picker's default (Greg's call).** Planned with Opus 4.8 on 2026-09-03 · reviewed by Fable 2026-09-06
(amendments A1–A8, § Review amendments) · lifted into the repo 2026-09-06 · R0 ✅ · R1 ✅ · R2 ✅ ·
R3 ✅ · R4 ✅ · R5 ✅.

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
**storage and eraser change, not a paint feature.** "No paint, no surfaces" still stands. (R0
found that the same grain lands paler through the software rasteriser than through the panel's
hardware bake, and the artist chose the paler tone for raster pages — see § Ledger, R0. The
pencil's geometry is unchanged; its tone on a raster page is a rasteriser fact, not a tuning.)

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

### ✅ R0 — The raster page in g-paper (commit 5c66992)
**Owner:** Fable. **Publishes:** g-paper 0.1.25 (g-paper commit `5eb3731`, Phase 13 there carries the engine notes). Closed 2026-09-06 — Outcome under § Ledger.

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
**Questions to resolve at phase start:** ~~bitmap format~~ **`ARGB_8888`** · ~~whether
`onStrokeCommitted` keeps firing~~ **yes** (both Greg, 2026-09-06). JVM tests landed only for
the pure rect rule (`RasterDirtyTest`); the copy, the clear-on-mode-set and the pixel-identical
composite need a `Bitmap` and are the device walk's — with `RASTER_SWITCH` the rows still
persist, so the same page reopened with the switch off gives the stroke-mode reference to diff.

### ✅ R1 — The pixel eraser in g-paper
**Owner:** Fable. **Publishes:** g-paper 0.1.26 (g-paper commit `855f483`, Phase 14 there carries the
engine notes and measurements). Closed 2026-09-06 — Outcome under § Ledger. *The headline deliverable — erasing to paper in the
hand early.*

- D2 entire, through the existing Onyx handoff; the hardware eraser end confirmed.
- g-paper JVM tests: a sweep clears exactly the round-capped corridor and nothing outside it; the
  batch rect covers the cleared pixels; chained batches leave no gap on a fast flick.

**Gate:** rubbing lifts graphite along the sweep and leaves the rest; a flick erases a continuous
corridor; the pen's eraser end does the same; one repaint at sweep end, no flicker. Greg's hand.
**Questions to resolve at phase start:** ~~repaint per batch or once at sweep end~~ **live, per
batch, as far as the panel allows** — Greg, 2026-09-06, against the default; the Onyx engine
posts a regional `handwritingRepaint` after each throttled (one-frame) redraw · ~~eraser radius~~
**same as stroke mode** (Greg, 2026-09-06).

### ✅ R2 — Persistence in the host (commit e9ddc36)
**Owner:** Opus on a Fable brief; Fable read it before the walk. Closed 2026-09-14 — Outcome under § Ledger.

- D3 entire; the R0 throwaway switch removed; `showPage` branches on the session's mode.
- JVM tests: `RasterRows` round-trip, the header/bounds guard rejects a wrong-size image, the mode
  flag reads and writes, the debounce collapses a burst into one save.

**Gate:** draw, close, reopen — the page is what was drawn; turn pages and come back; kill the app
from the background after a save and reopen; a stroke book opened after all this is untouched.
Haiku's `screencap` walk covers all of it (raster content is capturable); Greg confirms nothing
was lost.
**Questions to resolve at phase start:** ~~does `SketchbookMeta` mirror the mode~~ **no — the
row's `flags` bit is the truth** · ~~debounce interval~~ **3 s** · ~~the PNG size ceiling for the
log line~~ **4 MB** (all Greg, 2026-09-14, the defaults). Fable's phase decisions: the throwaway
switch moves from open-time to **create-time** — a debug-menu toggle "new sketchbooks are raster"
stamps the flag until R4's picker exists (release builds make stroke books only); g-paper stays at
0.1.26 (0.1.27/28 carry hardware-untested Onyx changes for Notesprout SN); raster marks and erases
record nothing on the undo stack until R3; a raster book's cover is `Blank` until R3; a raster row
the bounded-decode guard refuses is **tombstoned, never overwritten** — the page opens blank and
the next save makes a fresh row.

### ✅ R3 — Undo and covers (commit aabb355)
**Owner:** Opus on a Fable brief; Fable wrote the engine call in g-paper (`swapPageRaster`, 0.1.29,
g-paper commit `d0bc484`, Phase 17 there) and read the host before the walk. Closed 2026-09-14 —
Outcome under § Ledger.

- D4 and D5 entire.
- JVM tests: the byte budget evicts oldest-first and never an id-only edit; tile swap is an
  involution; a raster edit on another page replays through a page turn; the pixels-in cover
  matches the bake-in cover for the same page.

**Gate:** undo a stroke, undo an erase, redo both, across a page turn; the arrows and both finger
gestures; a raster book's card on the shelf shows its last page. Haiku walks the gestures with
`screencap`; Greg's hand on the feel of taking an erase back.
**Questions to resolve at phase start:** ~~patch via a new engine call or a full
`loadPageRaster`~~ **the engine call** — `patchPageRaster` in g-paper 0.1.29 (the pin jump carries
0.1.27/28, whose Onyx changes touch only the lasso tools this app never arms) · ~~byte budget~~
**48 MB** (both Greg, 2026-09-14, the defaults).

### ✅ R4 — Mode choice and the bake (commit fe1defc)
**Owner:** Opus on a Fable brief; Sonnet for the picker layout and strings; Fable reads before the
walk.

- D6 entire.
- JVM tests: bake determinism; the copy carries name, paper, page order and every live page; the
  original is byte-identical before and after. *(Amended at the walk: the original's **rows** are
  untouched and its index row is untouched; the file's bytes are not, and never were on any open —
  see the R4 outcome.)*

**Gate:** make a book of each kind; bake a stroke book and compare the two side by side on the
shelf and in the hand; the picker reads right at arm's length on the panel.
**Questions to resolve at phase start:** copy or in place (default copy, A7) · default mode in the
picker (default strokes) · whether the bake offers to delete the original afterwards (default no).

### ✅ R5 — The verdict
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

*(Each phase appends an **Outcome** here when it closes: what landed, what was measured, what the
hand said, and the commit. R5 appends the verdict in Greg's words.)*

### R0 — Outcome (2026-09-06)

**Landed.** g-paper 0.1.25 (`5eb3731`): `PageMode`, the page bitmap (ARGB_8888, dropped not
erased at a swap, a layer over the paper), composite-and-drop in `commitCapturedStroke`,
`onRasterWillChange` / `onRasterChanged` with the rect from the pure `RasterDirty` rule (bounds
+ full width + 2 px, clipped; 9 JVM tests), `loadPageRaster` / `getPageRaster` /
`copyPageRaster`, the Onyx handoff on the load. Host: pins to 0.1.25 and the throwaway
`RASTER_SWITCH` (rows still persist under it — that is what made the diff below possible).
Phase-start answers: ARGB_8888; `onStrokeCommitted` keeps firing.

**Measured.** A minute of sketching on the NA5C: ink accumulated, every committed mark in the
`screencap`, no engine log lines, the eraser inert on the page as expected before R1. The
`gfxinfo` count was not taken cleanly (no reset before the sketching minute; 159 frames from
process start) and is owed by R1's walk. The JVM tests the plan listed for the copy, the
clear-on-mode-set and the pixel-identical composite need a `Bitmap` and moved to the device.

**Found.** The same rows `screencap`ed as a raster page and reopened as a stroke page are **not
pixel-identical**: same flecks in the same places, the raster page paler — 78 k ink pixels
against 86 k, 5.46 M ink mass against 7.66 M, 10.6 k pixels at half-dark against 23.8 k. The
stroke page bakes on the hardware `RenderNode`, the raster page on a software `Canvas(bitmap)`,
and a round dot under 1.2 px is where the two rasterisers part company. The plan's "the artist's
approved pixels, unchanged" held for the grain and not for the tone. Covers have always baked
this paler way, hidden under the ×3 shrink.

**The hand.** Greg, side by side at 1×: *"To me, the raster looks better than the stroke."*
Decision: **keep the software tone, tune nothing.** Recorded with its cost: R5 compares two
books that differ in tone as well as in storage and eraser. If that confound ever matters, the
clean fix is to bake the stroke page's committed layer through a software bitmap too — a
separate g-paper decision, not taken.

**Next.** R1, the pixel eraser. The device carries the raster-on build.

### R1 — Outcome (2026-09-06)

**Landed.** g-paper 0.1.26 (`855f483`): `eraseAlong` on a raster page strokes the chained sweep
onto the page image with a round-capped, round-joined CLEAR paint at `2 · eraserRadius`
(transparent, never white — the layer-over-paper rule, A5); `onRasterWillChange` /
`onRasterChanged` per batch with the rect from the pure `RasterErase.batchRect` (bounds +
radius + 2 px, clipped; 7 JVM tests including the gapless chain on a flick); `onStrokesErased`
silent. The hardware eraser end needed nothing — it already ran the same sweep. Host: pin to
0.1.26, nothing else; `RASTER_SWITCH` still opens every book raster until R2.

**Decided at phase start (Greg).** Rubbing is shown **live, per batch, as far as the panel
allows** — against the plan's default of once at pen-up. With the raw pipeline armed the panel
withholds ordinary frames until the contact ends, so the engine gained
`presentRasterEraseProgress(rect)`, which the Onyx engine answers with one coalesced regional
`EpdController.handwritingRepaint` after each redraw. Eraser radius: stroke mode's.

**Found and fixed in the walk.** (1) A fast curved sweep cleared a second corridor beside the
first. The SDK reports a contact twice — streamed samples, then the whole list at pen-up — and
the list chained to the last streamed sample, so the replay began with a chord back to the
start. Probed: identical coordinates, list one sample longer. The Onyx engine now drops the
list when the contact streamed. This reaches stroke mode too, where the chord silently took
marks it crossed. (2) The hand felt the lifting as slightly delayed. Measured before tuning:
re-record < 1 ms, frame 14 ms (bitmap upload 4 ms), panel call 2 ms, input delivered 23 ms
after its own sample time. The 60 ms stroke-mode throttle was the only lever; the raster eraser
now redraws once a frame (16 ms). Greg: *"a little better… I think it might be acceptable."*
What remains is the e-ink's own update.

**Measured.** No full-panel flash mid-sweep. `gfxinfo` for the sketching-and-erasing minute:
**169 frames, 6 janky** — the R0 debt paid, though the number is not comparable to G6's 26
because every erase batch now presents a frame by design; a pencil-only minute is the fair
comparison and is owed by R2's walk if anyone wants it.

**The hand.** *"The second path is gone. That's fixed."* *"There's no full-panel flash."* Good
enough to close on; the delay is the panel's, and the rubbing eraser follow-on inherits the
question of whether a lighter update mode exists for it.

**Next.** R2, persistence in the host — Opus on a Fable brief. The device carries the 0.1.26
build; nothing written under `RASTER_SWITCH` survives a page turn yet.

### R2 — Outcome (2026-09-14)

**Landed** (`e9ddc36`). Host only; g-paper stays at 0.1.26. The sketchbook row's `flags` bit 0 says a book is
raster, stamped by `createSketchbook(raster)` and read once at `SketchbookSession.open`. Each page
of a raster book has at most one `raster` child row (`order = -1`, blob = PNG of the whole page,
upserted in place) — the family's raster-cache shape, on its own row for A3's reason. `RasterRows`
is the pure border (row ↔ bytes, the IHDR header read, the exact-size guard, the flag helpers; 13
JVM tests, 159 in all) and `RasterImage` the Android half (PNG encode with the 4 MB watch line;
guard **before** `BitmapFactory`, always). `showPage` is still the only door: it reads the incoming
page's row **through `SoilWriter.perform`** (Opus's addition — turn away from a leaf and straight
back and its encode is still queued; a read off to the side would hand back the previous sitting
and then save it over the good row), waits on the gate, flushes the outgoing page, then
`clearForContentSwap` → `setPageSize` → `loadPageRaster` under a `loadingRaster` flag so the
load's own whole-page callback is not taken for the hand. Saves: 3 s after the last change through
the gate, and immediately on page turn, page delete, replay, `onPause` and `onDestroy` — every one
a ~10 ms main-thread copy and a queued encode, never a bake. The delete and the replay flush
**before** the store change so the image is tombstoned with its page rather than minted live on a
dead one. A row the guard refuses is soft-deleted, never overwritten. `RasterSwitch.kt` is gone;
until R4's picker the debug menu's "New sketchbooks are raster" toggle (a `LibraryPrefs` boolean)
stamps the flag, and a release build makes stroke books only.

**Decided at phase start (Greg).** No meta mirror; 3 s debounce; 4 MB ceiling — the defaults.

**Measured.** The walk on the NA5C, driven from adb for everything but the graphite: the toggle
flipped and read back ON; the book created and opened raster; after the first marks the `-wal`
grew to 251 KB in two writes (page images, not mark rows); after Home the WAL folded into a
348 KB `.soil` and the card wrote `Blank` (R3's); `am kill` and a relaunch reopened both pages;
two stroke books then opened and wrote `Image` covers as before. No error lines in the streaming
log. `meminfo` during drawing: graphics ≈ 107 MB, native heap ≈ 35 MB — the page bitmap and its
copies are inside that, and nothing was killed.

**The hand.** *"Page 1 retained the raster image as expected."* *"Both pages of R2_raster were
exactly as I left them. Page turns feel fine."* Step 5, a stroke book untouched: *"passes."*

**Deferred, on purpose.** A raster book's cover is `Blank` and its undo arrows move pages only —
both R3. The picker is R4. A pencil-only `gfxinfo` minute was not taken.

**Next.** R3, undo and covers — Opus on a Fable brief; Fable writes `patchPageRaster` in g-paper if
R3 takes the engine route. The device carries the R2 build with the debug toggle ON.

### R3 — Outcome (2026-09-14)

**Landed.** g-paper 0.1.29 (Phase 17 there): `RasterPatch(rect, pixels)`, `readPageRaster(rect)`
(the before-image as row-major ARGB, a page with no image yet reading as transparent) and
`swapPageRaster(patches)` — each patch's pixels go onto the page and **the array is left holding
what was there**, row by row through one reused buffer, so one entry serves undo and redo with no
second copy of an 18 MB page; on Onyx `epdRepaintHandoff` gained a region and a swap refreshes only
the patches it touched. Host (Opus on the brief, Fable reviewed): `Edit.RasterChanged(pageId,
tiles)` with `Edit.bytes`; `RasterTiles` + `RasterEditBuilder` (pure) keep a contact's before-image
on a fixed grid of **64 px cells, each read once per contact** — the plan's "one tile per batch"
would have let one slow scrub pile tens of megabytes of overlapping pixels into a single entry and
overflow the budget from the inside; on the grid an entry is bounded by the page, its tiles are
disjoint, and a hairline mark costs 16–64 KB. `UndoRedoStack` keeps a running byte total and evicts
the oldest entry that costs something, never an id-only edit and never the newest. The activity
opens a builder at the first `onRasterWillChange` (guarded by `loadingRaster`, page captured then)
and records at `onPenLifted`; `applyEdit`'s raster branch turns the page if the edit was made
elsewhere, waits on the gate, swaps, dirties, and answers **no page to show** — a `showPage` after
the swap would reload the row, which still holds the picture as it was, and undo the undo.
Covers: `CoverSnapshot.render(pixels, w, h)` composites straight alpha **over paper white** (the
image is a layer, unmarked pixels transparent) before the shrink and WEBP; `renderCover` in raster
mode reads the row inside its existing `perform`, no row = `Blank`, a refused row = `Failed` and
nothing tombstoned on the way out; `leave()` now flushes the page first, being the one exit with no
`onPause` ahead of it. JVM: 187 tests (159 + 16 tiles, 7 stack, 5 cover); g-paper 400.

**Decided at phase start (Greg).** The engine call, and 48 MB — the defaults. The pin jump from
0.1.26 carries 0.1.27/28, whose Onyx changes touch only the lasso tools this app never arms.

**Measured (Haiku's adb walk, 2026-09-14, R2_raster on the NA5C).** A leaf swiped into, undone and
redone: the `screencap` after the undo is pixel-identical to the one before the swipe, and the one
after the redo to the blank leaf (PIL diff, both zero). Sleep and wake through `KEYCODE_SLEEP` /
`WAKEUP`: the page came back, the card wrote once and then answered *unchanged*. **The raster
book's card wrote `Image` with the drawn page on the glass** (R2 wrote `Blank`) and `Blank` with the
fresh leaf showing — both right — and the shelf shows the face drawing on R2_raster's card. No
`AndroidRuntime`, no "could not be replayed", no skipped patch in the log. `meminfo` with the book
open: native heap 86 MB, graphics 71 MB, PSS 255 MB. The walk left R2_raster with a blank third
page. What adb cannot do is draw, so the stroke and erase entries — the swap itself, its regional
repaint at pen-idle, and the feel of taking a rub back — are the hand's, below.

**The hand** (Greg, the seven-step checklist: a mark undone and redone with only its patch
refreshing, a sweep taken back as one step, an undo across a page turn, both finger gestures, the
walk's blank leaf deleted and the delete undone and redone, a page-wide stroke undone, the card on
the shelf): *"All pass."* Nothing found, nothing tuned.

**Next.** R4, the mode picker and the one-way bake — Opus on a Fable brief, Sonnet for the picker's
layout and strings. The device carries the R3 build with the debug toggle ON.

### R4 — Outcome (2026-09-14)

**Landed.** Host only, g-paper still 0.1.29. The **picker**: the New sketchbook screen's second
question, "Pages" — *Strokes* ("Marks you can take back whole.") / *Raster* ("Graphite you rub
off."), radio rows in the recovery screen's existing style, Strokes checked in the layout every
time the screen opens and nothing remembering the last answer; the debug toggle, the
`LibraryPrefs.newSketchbooksRaster` preference and their two strings are gone. **The bake**:
*Bake to raster…* on the shelf's long-press sheet between Move and Delete, confirm first, then a
modal counting dialog ("Page n of m") over a job on `PaintsproutApplication.scope` behind a
`BakeCommand.running` guard. `RasterBake` (pure: `plan`, `copyName`, `digest`) decides the copy —
same `order` values, same sizes, opens on the leaf the original was left on, blank leaves get no
raster row, "<name> raster" then a counter from 2 trimmed at the source name never the suffix;
`BakeSketchbook` (Android) opens the source, reads book + pages + marks, **seals it before any
bitmap exists**, then makes the copy exactly as `createSketchbook` does — transparent page bitmap,
`StrokeRasterizer.draw`, PNG, one bitmap alive at a time, cover from the open leaf via
`CoverSnapshot.render(pixels…)`, meta row, seal, **index row last**, `setCover` — and a `Throwable`
anywhere after the create discards the half-made file. The copy's folder path comes from the index,
not the source's meta row (written once at creation, never refreshed — Fable's review catch). The
sheet offers Bake on every sketchbook card, raster ones included, because the index does not mirror
the mode; the bake answers `AlreadyRaster` and the shelf shows a problem dialog. Each baked leaf
logs `baked page i/n of <id>: <bytes> bytes, <digest>`. JVM: 203 tests (187 + 16 `RasterBakeTest`).

**Decided at phase start (Greg).** Copy, default Strokes, no offer to delete — the three defaults.

**Measured (Haiku's adb walk + Fable's probes, 2026-09-14, NA5C).** Picker: dump shows the label,
Strokes checked, Raster not, both hints; a tap flips it (`02_picker.png` is for the eye). A raster
book and a stroke book were made through it. Bake of *Graphite* (1 page): confirm dialog, counting
dialog, card *Graphite raster* with a drawn cover and "1 page"; **baked twice → identical bytes and
digest** (265340 bytes, `7a943083dcd0c85d`); *G4walk* (4 pages, one blank) → three leaves logged
(57938 / 141158 / 87429 bytes), 316 KB copy beside a 132 KB stroke book. The copy's page and the
original's page `screencap`ed side by side: **0.03 % of pixels differ, identical bounding boxes**
(377,256)–(1646,1967); mean darkness 1.2 vs 1.4 — geometry exact, tone within noise (the R0
paleness confound is not visible at this coverage). *Already raster*: baking a raster copy shows the
problem dialog and makes no file. Sleep/wake with the copy open: page back. Log: no
`AndroidRuntime`, no leaked window. Index: the original's card still reads "Sep 3, 2026" — its
`updatedAt` did not move.

**Finding — the original's file bytes change on every open, and always have.** The walk's md5 of
the source `.soil` moved at the bake's open moment though no row was written. A staged probe build
(digest of the file at six points) put the two writes **before any read**: the first inside
`KeyOpener`'s verify open (`SoilCrypto.verifyRawKey`: raw open, `SELECT count(*) FROM
sqlite_master`, close) and the second inside Room's own open (`forceOpen`); a second verify in a row
changed nothing, so the first is idempotent, and the reads and the seal changed nothing. Consistent
with the raw open flipping the header's journal mode from WAL to DELETE and Room's WAL open flipping
it back — page 1 re-encrypted under a fresh IV each time. This is **every** `SoilDatabase.open` in
the app since G1, ordinary sketchbook opens included, and it touches no row. Left as it is in R4 (a
crypto-path change is not this phase's); the plan's "byte-identical" gate is amended to "rows and
index row untouched", which the second bake's identical digests and the Sep 3 card both show.
Watch item for arc 2's formalisation: verify with the WAL flag, or accept the flip and say so.

**Left on the shelf by the walk** (Greg to delete or keep): `20260914_124317rrR4_rasteaR4_raster`
(adb's text entry mangled the name; the book is raster and fine), `R4_strokes`, `Graphite raster`,
`Graphite raster 2`, `G4walk raster`, `20260906_110500 raster`.

**The hand** (Greg, the five-step checklist: the picker at arm's length; a book of each kind drawn
in; a drawn stroke book baked and the two compared on the shelf and in the hand; a multi-page bake
with Home pressed mid-count; Back out of the New screen): *"Tests pass."* Nothing found, nothing
tuned.

**Next.** R5, the verdict — Greg's, written down with Fable. The device carries the R4 build.

### R5 — The verdict (2026-09-14)

Greg drew in a four-page raster book, *RasterSketching*, on the R4 build, the afternoon after R4
closed. First word after the sitting: *"Sketching feels pretty good."* Then, the same day, the
verdict in his words:

> **"That's the verdict... raster rocks!"**

**Yes.** A raster page feels truer than strokes for this pencil. The experiment becomes **arc 2**.
What the plan asks for next, in order, each its own session: lift the design (§ Decisions, A1–A8,
D1–D6) into `ONYX_PLAN.md` as arc 2; a `/code-review` over the whole R range (commits 5eb3731 →
bb9f606 on `onyx`, plus g-paper 0.1.25–0.1.29) — the review the experiment deliberately skipped;
freeze; then open the gated follow-on, the **rubbing eraser**, as arc 2's first phase. The things
the verdict was asked to carry alongside it — what the hard eraser is missing, the Kaleido on
composited graphite at 1×, what a real book costs, what strokes still do better — were not itemised
in this sitting and are the first questions of the rubbing-eraser phase's brief. The open-path
byte-flip finding (R4 outcome) goes to the code review's list.

---

## Appendix — build & install

See `ONYX_PLAN.md` § Appendix and the `device-build-install` skill. In short: `./gradlew test` and
`./gradlew assembleDebug` here; `adb -s 92c16533 install -r` the debug APK; verify
`mResumedActivity` before believing a screencap. g-paper: `cd ~/git/g-paper && ./gradlew test
publishToMavenLocal`, then bump the three pins in `app/build.gradle.kts`.
