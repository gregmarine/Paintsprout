# apps/paintsprout_android

Commands and architecture notes for the live Wacom app. This file loads only when
a session works under this directory; the repo-root `CLAUDE.md` carries what
applies everywhere (what Paintsprout is, the repository layout, the conventions).

## Commands

All Gradle work happens in `apps/paintsprout_android`.

```bash
./gradlew :app:assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # the whole JVM unit-test suite
```

- There is **no lint, detekt or ktlint config** — `assembleDebug` plus the unit
  tests are the whole automated check. There are no instrumented tests either;
  everything under `app/src/test` runs on the JVM.
- JDK 17 is pinned in `gradle.properties` (`org.gradle.java.home`). KSP and the
  serialization plugin are pinned to the Kotlin version and move together with
  it.
- **minSdk 33** (hard floor — AGSL `RuntimeShader` is foundational, not an
  enhancement), arm64-v8a only.
- `DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET` come from the environment at build
  time for the Google Drive backup destination. Building without them is fine;
  the Drive slot just reports itself unconfigured.

**Installing to a device: use the `device-build-install` skill.** It has the
serials, the signing step, and the state that does not travel between build
variants. Two things that bite otherwise: never run `./gradlew installDebug`
(it pushes to every attached device), and the debug build is a *different app*
(`…paintsprout.dev`) with its own library, its own calibration and its own
recovery key.

Frame-time benchmark against a connected device: `bench/bench.sh` (records
RenderThread percentiles from `dumpsys gfxinfo` per tool; results land in
`bench/results/`). `onDraw` wall time is misleading here — measure frames.

Room's generated schema JSON lives in `app/schemas/` and is committed. A unit
test compares it against the hand-written DDL in `data/SchemaSql.kt`; the ORM
and the constants must describe identical tables or an open fails validation on
a device holding a perfectly good file.

## Architecture

### Startup path

`BootstrapActivity` is the launcher entry, not the editor. The global index is
SQLCipher-encrypted, and opening it can require a key derivation, an unlock
prompt, a repair, or a first-run key mint — all of that is visible on this one
screen so no other screen has to know about it. It then routes via
`LastOpen` + `LaunchRoute` to `LibraryActivity` (the shelf) or `MainActivity`
(the editor). `PaintsproutApplication` loads the SQLCipher native library and
kicks `IndexGate.ensureReady` off without waiting for it.

### The editor

`MainActivity` is chrome: it builds the floating tool rail in code, owns the
tray/layers/pages panels, and holds the `DocumentSession`. `PaintCanvasView`
is everything about marks — input, live preview, bake, undo, selection, layer
folding.

**`Focus.kt` is the switchboard.** It names the small set of tools and rail
controls the current work actually calls for; everything else stays built but
hidden. Nothing there removes a feature, and turning one back on is one line
changed in that file and nowhere else. When a phase widens or narrows scope,
`Focus.kt` is the file that records it.

### The paint model (`paint/`)

Pure, Android-UI-free, and unit-tested: `Stroke`, `StrokeGeometry`,
`StrokeRenderer`, `Tool`/`ToolProfile`, `Surface`, `Pigment`, `LayerStack`,
`PageSpace`, `Calibration`, `CanvasSize`, `WandFloodFill`, `WetSim`, `Tray`.
Keep new geometry, colour and structure logic here rather than in the view —
that separation is what makes the suite possible at all.

### Ops are the document

The load-bearing idea, in both the view and the file format:

- A page's history is an ordered list of `PaintOp`s. Layer bitmaps are only the
  *folded result* of those ops — throwaway and rebuildable. Every op carries the
  `layerId` it landed on, so each layer folds from its own ops and an undo on
  one layer cannot disturb another.
- Undo/redo move a boundary; they never delete anything. On disk a layer carries
  one integer `undoDepth` and the ops stay put, so **undo history survives
  closing the document** — reopen a page days later and step backwards through
  it. The price is that op `order` must stay dense, which is why appending
  truncates the redo tail with a *hard* delete. Everywhere else in the format,
  soft delete is the only delete.
- **If the sketchbook remembers it, undo can take it back.** Not just strokes:
  layer opacity/visibility, layer and folder add/delete/reorder, renames, folder
  collapse and surface changes are all ops on the same timeline.
- State that a `SurfaceOp`/`LayerOpacityOp`/`NameOp` describes is **re-derived
  from history** after every undo (`syncSurfaceToHistory`,
  `syncLayerStateToHistory`), never written straight onto the object — a value
  written directly would sit there contradicting the timeline it came from.
- Rebuilds are checkpointed (every `CHECKPOINT_STRIDE` ops, capped at
  `MAX_CHECKPOINTS`) for the active layer only; holding full-size copies for
  every layer is how a page runs out of memory.

### Anything async must CAPTURE its layer

Bakes and folds run off the UI thread. Capture the `Layer` (or the id) at the
point the work is scheduled and use that; never read `activeLayer` when the work
lands. A layer tapped meanwhile means a checkpoint filed under someone else's op
count, and an undo that resumes from someone else's paint.

### Coordinate spaces — do not conflate them

Three, and mixing them is the recurring bug in this codebase:

- **View px** — logical pixels of the on-screen sheet. Input arrives here.
- **Buffer px** — the bitmaps' resolution, view px × `superSample`. Masks,
  op geometry and `MoveOp` transforms are in buffer px.
- **Page space** — `PageSpace` maps the view px a page's marks were *recorded*
  in to the view px it is being drawn in now (uniform scale, centred fit). A
  page records the view size it was made at and keeps it for life, so a
  sketchbook drawn on one tablet opens correctly on the other without rewriting
  anything in bulk.

Physical size is separate again: tool sizes and canvas presets are in
**millimetres**, converted at the device's calibrated PPI (`Calibration`). OEM-
reported PPI is not trustworthy (319 reported vs 242.69 measured on the Movink
14 Pro), so an uncalibrated install silently draws a third too large. Exported
PNGs are DPI-stamped so they print at the on-screen physical size.

### Rendering

- `StrokeRenderer` is shared by the live preview *and* the bake, so what you see
  while drawing is exactly what commits. Preserve that contract.
- Grain, bristle and multi-point wash marks are drawn as **meshes with
  per-vertex colour**, not mask filters — a `BlurMaskFilter` on a software
  canvas was twice the single largest per-frame cost, and geometry is the only
  way a mark can carry colour and strength that vary along its length.
- AGSL `RuntimeShader` only runs on a *hardware* canvas, and the bakes are
  software `Canvas(bitmap)`. `GpuRender` bridges that: a `RenderNode` drawn by a
  pooled `HardwareRenderer` into an `ImageReader`, read back to a software
  bitmap. Shaders live in `app/src/main/res/raw/*.agsl`.
- `Pigment.kt` is the CPU counterpart of `pigment_mix.agsl` — spectral
  Kubelka-Munk, 38 bands. The two implementations must agree; `PigmentTest`
  pins them together. The constant tables are extracted verbatim from the
  shader so they cannot drift.
- Only stylus input draws; touch is ignored (palm rejection). The eraser end of
  the pen is read fresh at every pen-down and lasts exactly as long as the mark.
- The magic wand and lasso read the **paint layer only** — surface texture never
  participates in a selection.

### Storage (`data/`, `crypto/`)

`.soil` sketchbooks and the global `paintsprout.db` index are SQLCipher
databases, encrypted from the first byte, with **stock SQLCipher 4 defaults** so
a stock `sqlcipher` CLI opens them. Never customise `kdf_iter` or the page size —
that portability *is* the format.

- One document is one SQLite file, named by its UUID, in a flat `Garden/`
  directory. Display names, folders, pins and recents belong to the index, which
  is what makes a document portable.
- `SoilObject` is one wide, sparse row type serving every object — page, layer,
  stroke, palette pot — with columns shared **by role** (`text` is a title, a
  layer's label and a pigment's name). Read `type` first, then interpret. A new
  object kind costs no migration and no join.
- `ObjectTable` is parameterised on the table name, because the sketchbook file,
  the scratchpad and the clipboard are the same table in three places — which is
  what makes "send this page to the scratchpad" the same code path as a paste.
- `DocumentSession` turns committed ops into rows off the UI thread, debounced
  and batched. The debounce is a **floor, not a cancel-and-reschedule** — a fast
  pen would otherwise never write at all.
- File replacement passes through `.tmp` / `.old.bak` / `.new` names that are
  deliberately not documents, and `SwapRecovery` runs **before any probe** at
  launch. That order is load-bearing: a probe of an absent file says INVALID,
  INVALID means "fresh install", and a fresh install would replace the library.
- The per-install recovery key lives in `EncryptedSharedPreferences` under a
  Keystore key bound to the package, so it cannot be copied anywhere. **A
  library whose recovery key is lost is unrecoverable, backups included** —
  backups are copied as ciphertext.

Backup (`data/backup/`) is manual-trigger only, incremental by `updatedAt`, to
two independent slots: LOCAL via the Storage Access Framework (no storage
permission is ever requested) and DRIVE via a hand-rolled Drive REST v3 client
(no Play Services). See `docs/backup.md`.
