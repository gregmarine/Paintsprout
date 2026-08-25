# ONYX_PLAN.md — Paintsprout Onyx

**Branch:** `onyx` · **Location:** `apps/paintsprout_onyx/` · **Package:** `com.symmetricalpalmtree.paintsproutonyx`
**Label:** Paintsprout Onyx (debug: "Paintsprout Onyx Dev") · **Version:** `0.1.0-onyx`
**Device:** BOOX NoteAir5C (NA5C) `92c16533` — **the only device anything installs to.**
**This file is the cross-session memory for the effort. Read it first, whole, at every phase start.**

A from-scratch, **BOOX-only** rebuild of Paintsprout, in the spirit of the Notesprout Paper and
Notesprout SN experiments. It asks one question: **what does g-paper on an Onyx e-ink panel give
us for Paintsprout?** The answer arc 1 goes after is a graphite pencil, a rubber eraser, white
paper, and a shelf of multi-page sketchbooks — nothing else.

`apps/paintsprout_android` (the live Wacom app) and `~/git/Notesprout` (Paper + SN) are **reading
references — no app code is copied from either.** Build boilerplate (the Gradle wrapper) is exempt.

---

## Working protocol

1. **One phase per session.** At phase start: read this file, the repo-root `CLAUDE.md`, and
   `apps/paintsprout_onyx/CLAUDE.md`. Confirm the next `⬜` phase with the user, flip it to `🔄`,
   then ask that phase's **Questions to resolve at phase start** wizard-style — one at a time —
   before writing any code. No knowledge from prior conversations may be assumed: if it is not in
   the repo or project memory, it does not exist.
2. **Model recipe (applies to every phase):**
   - **Fable** plans, orchestrates, reviews, and writes the genuinely complex code — crypto and key
     lifecycle, schema contracts, engine seams, tricky EPD behaviour.
   - **Opus** for substantial feature implementation; **Sonnet** for scaffolding, layouts,
     resources, docs; **Haiku** for on-device adb test runs.
   - Background agents only for Opus/Sonnet/Haiku, **≤ 5 concurrent**.
3. **Testing gate:** JVM unit tests for all pure logic. Haiku device agents verify everything adb
   can see on the NA5C. The user gets a **short numbered checklist** only for what needs a human
   eye or hand — live EPD ink, pencil feel, how graphite reads on the panel. Failures are fixed
   with the right model for the job, then re-tested.
4. **Devices:** NA5C only. Never install anywhere else without an explicit ask.
5. **Commit + push only when all tests pass or the user gives the all-clear** — and only after
   docs, memory and `CLAUDE.md` updates are in. Then the user runs `/clear`.
6. **Status markers:** `⬜ Not started` · `🔄 In progress` · `🧪 Awaiting device verification` ·
   `✅ Complete (commit <hash>)`. Every phase records an **Outcome** note when it closes.
7. **g-paper gaps are fixed in g-paper** (`~/git/g-paper`): add a phase to its `PLAN.md`, build it
   there, bump `GPAPER_VERSION`, `./gradlew publishToMavenLocal`, re-pin here. **Never work around
   an engine gap in the host.** Arc 1 depends on exactly one such phase — g-paper Phase 10,
   "Graphite" — and it lands *before* this app draws its first mark.

---

## Locked decisions (from the planning wizard — do not re-ask)

| Decision | Answer |
|---|---|
| Identity | Branch `onyx`; `apps/paintsprout_onyx/` with its **own Gradle root**; package `com.symmetricalpalmtree.paintsproutonyx` (debug suffix `.dev`); label "Paintsprout Onyx" / "Paintsprout Onyx Dev" |
| Device fleet | **NA5C only** (`92c16533`). Kaleido colour panel — see the standing traps. |
| Rebuild depth | **Fresh code.** The Wacom app and both Notesprout rebuilds are reading references; no file copying (Gradle wrapper exempt). |
| Engine | g-paper from mavenLocal: `gpaper-core` + `gpaper-onyx` **only**. No `gpaper-ratta`. `OnyxEngine.register(this)` from `Application.onCreate`; `GPaper.create(this)` (generic fall-through = desk testing off-device). Pin **0.1.7** once g-paper Phase 10 publishes it. |
| Crypto | **Paper/SN's spine, whole.** Encrypt-by-default, global key only, `PSPT-` Crockford recovery key, PBKDF2-HMAC-SHA512 ×256 000, SQLCipher 4 **stock defaults**, Bootstrap → RecoveryKey → Unlock, never-delete-on-corruption. |
| Data model | **Paper/SN's shapes, Paintsprout's vocabulary.** Identical table structure, codec and crypto; the index is `paintsprout.db`, documents are `.soil` sketchbooks, row types read as sketchbook/page/mark. Structurally family-compatible; **not** intended to open in Notesprout. Mapping table below. |
| The pencil | **Graphite lives in g-paper core.** The textured `PENCIL` committed renderer is g-paper Phase 10, not a host-side renderer and not a `ContentRenderer` detour. Marks stay g-paper strokes. |
| Colour | **Greyscale graphite only** in arc 1. Colour is a later arc, planned once we know how our grain actually reads on Kaleido. |
| Paper | **Plain white.** All the tooth lives in the pencil's own grain — two grain systems would fight over the panel's few grey levels. Paper texture is a later arc; the schema keeps a home for it (`paperKind`). |
| Eraser | **g-paper's stroke eraser as-is** (sweep + scribble-erase). A real rubbing eraser needs a partial-erase model g-paper does not have; that is its own named arc later. Noted as a deliberate, temporary break with Paintsprout's WYSIWYG rule. |
| Canvas size | **Full screen.** The page is the panel. No millimetres, no calibration, no real-size presets — the Sketchbook effort's canvas sizes are explicitly not implemented here. |
| Orientation | **Portrait-locked.** One layout per screen, a page size that never changes. "Turn the tablet" is a later arc. |
| Pages | Multi-page sketchbooks; **finger swipe left/right** turns a page, palm-gated so the pen never triggers it. |
| Library | **Full Paper-v0 parity**: breadcrumb folders, paginated non-scrolling card grid, covers, pinned + recents, sort, rename/move/delete, long-press action sheet. |
| Undo/redo | **SN's model** — bounded 100, replayed through the store with the DB as the source of truth, cleared when the sketchbook closes. Paintsprout's durable op timeline is a later arc. |
| Export | **None in arc 1.** Nothing leaves the device. |
| Layers | **None.** Not now, not scaffolded "for later". |

---

## Non-goals for arc 1 — do not build, do not scaffold "for later"

- **No layers**, no layer folders, no layer stack of any kind.
- **No paint**: no pigment mixing, no spectral anything, no watercolor, no washes, no brushes,
  no marker, no spray, no mixing tray, no brush load, no dirty brush.
- **No surfaces/materials**, no per-artwork seed, no tooth field.
- **No shape tools** — no line, arc, polyline, polyarc.
- **No selection** — no magic wand, no lasso. g-paper offers lasso; we do not arm it.
- **No physical sizing**: no millimetres, no PPI calibration, no canvas presets, no true-size
  output, no DPI-stamped anything.
- **No zoom, pan or rotate** (deferred in Paintsprout on philosophy; deferred here on scope too).
- **No landscape**, no other devices, no per-sketchbook passphrases (global key only).
- **No export, no import, no backup, no Drive.**
- **No extension system** of any kind — no AIDL, no `<queries>`, no proxy or binder surface.
- **No scratch pad, no clipboard, no links, no content objects, no text, no recognition.**
- No Ratta or generic *device* support — the generic engine stays only as g-paper's built-in
  desk-testing fall-through.

Everything on this list that is a real Paintsprout idea (durable undo, rubbing eraser, colour,
paper texture, true size, rotation) is a **candidate later arc**, recorded here so nobody has to
rediscover that it was left out on purpose.

---

## Architecture

- **Own Gradle root** at `apps/paintsprout_onyx/` (no monorepo root build, and no relationship to
  `apps/paintsprout_android`'s build). Gradle 8.14, AGP 8.11.1, Kotlin 2.2.20, KSP 2.2.20-2.0.4,
  compileSdk/targetSdk 35, **minSdk 29**, Java 17 via `org.gradle.java.home` (Temurin-17).
  Repos: `mavenLocal()`, `google()`, `mavenCentral()`, **and the insecure BOOX repo**
  (`http://repo.boox.com/repository/maven-public/`, `isAllowInsecureProtocol = true`).
- **Single `:app` module.** Namespace `com.symmetricalpalmtree.paintsproutonyx`. Dependencies:
  appcompat, core-ktx, Room 2.7.0 + KSP, coroutines, lifecycle, kotlinx-serialization-json,
  SQLCipher 4.6.1, androidx.security-crypto, junit, plus
  `com.symmetricalpalmtree.gpaper:gpaper-{core,onyx}`.
- **The Onyx build baggage is mandatory here** (it was excluded on SN, and that difference is the
  single biggest departure from the SN scaffold): `android.enableJetifier=true`, the BOOX maven
  repo above, `tools:replace="android:label"` on `<application>`, `ndk { abiFilters += "arm64-v8a" }`,
  and `packaging.jniLibs.pickFirsts` for the four colliding `libc++_shared.so` paths. All four are
  spelled out in `~/git/g-paper/docs/integration-guide.md` § "BOOX (Onyx) consumer".
- **`OnyxEngine.register(this)` must run from `Application.onCreate`** and must be given the
  `Application` — besides registering the engine it installs the hidden-API bypass the SDK needs on
  Android 14+ and heals EPD state leaked by a process killed mid-pen-session. Leaked state is keyed
  by *name*, not process, and would otherwise ghost the whole panel until reboot.
- **Screens:** `BootstrapActivity` (the only index opener, `noHistory`) → `RecoveryKeyActivity` /
  `UnlockActivity` → `LibraryActivity` (breadcrumbs, paginated non-scrolling card grid, pinned and
  recents overlays, sort, long-press action sheet) + `NewSketchbookActivity` +
  `FolderPickerActivity` → `SketchbookActivity` (full-bleed paper, chrome overlaid via
  `setExclusionRects`, pencil/eraser toolbar, `PageGestures` observer, `SketchbookSession`, a single
  serial `SoilWriter`, `UndoRedoStack` bounded 100, `CoverSnapshot` on close).
- **Host/engine split:** the host does only the documented host responsibilities
  (`~/git/g-paper/docs/host-responsibilities.md`): page swap = `clearForContentSwap` →
  `setPageSize`/`setTemplate` → `loadStrokes`; undo/redo via `addStrokes`/`removeStrokes`; chrome
  via `setExclusionRects`; lifecycle `resumeDrawing`/`releaseForHandoff`/`release`.

### Data model — Paper's shapes, Paintsprout's words

Authoritative structural references: `~/git/Notesprout/apps/notesprout_paper/docs/data.md` and
`docs/crypto.md`. What changes is vocabulary, and only vocabulary:

| Paper / SN | Paintsprout Onyx |
|---|---|
| `notesprout.db` | `paintsprout.db` |
| index `objects` table, `user_version` 1 | **unchanged** — same columns, same index |
| index type `notebook` | `sketchbook` |
| index types `folder` / `list` / `list_item` | **unchanged** |
| index column `templateKind` | `paperKind` |
| `Garden/<uuid>.soil` | **unchanged** — flat dir, UUID filenames, no permissions |
| `.soil` table `notebook`, `user_version` 1 | table `sketchbook`, same column set |
| `.soil` table `notebook_meta` | `sketchbook_meta` |
| row types `notebook` / `page` / `stroke` / `template` | `sketchbook` / `page` / `mark` / `paper` |
| `StrokeCodec` format B | `MarkCodec`, **byte-identical encoding** (`byte0=1`, zlib, f32 LE, flags bit0 pressure / bit1 tilt) |
| `InkColorCodec` | **unchanged** |
| `NotebookMeta` | `SketchbookMeta`, same field set |
| recovery-key prefix `NSPT-` | `PSPT-` |
| `KeyMaterial.INDEX_FILE_ID` | `__paintsprout_index__` |

Everything else carries over verbatim: soft deletes only, stable UUIDs, `"order"` double-quoted in
SQL and backticked in Room, folders live **exclusively** in the index (never derived from the
filesystem), `data/SoilFile.kt` as the **only** path constructor, every SQLCipher open through
`crypto/SoilCrypto` wrapped in `NonDestructiveOpenHelperFactory`.

**No object rows, no link rows, no extension stores.** Those types simply do not exist here.

---

## Standing traps

**BOOX / Onyx** (from g-paper's `CLAUDE.md` and the prior arcs — assume they still apply):

- **BOOX spams logcat** (`test_keymap` and friends) hard enough to wrap the buffer in seconds.
  Debug with `adb logcat -G 16M` plus a **streaming** filtered capture (`logcat -s TAG`) — never
  `-d` after the fact.
- **`install -r` + an immediate `am start` can race package finalization**, leaving the package
  installed but **disabled** (`enabled=3`, "Activity class does not exist"). Reproduced on NA5C and
  G102. Heal with `pm enable <pkg>`.
- **BOOX has a real status bar overlaying the window top** (unlike Supernote, where the guard is
  zero). Layouts must apply system-bar insets, and no tappable chrome may sit against the top edge
  — tapping there pulls the status bar down instead.
- **EPD pen overlays are invisible to `screencap`.** Committed (baked) content and ordinary app UI
  do appear. Screenshot-verify only committed marks; live ink is the user's eye.
- **adb cannot inject stylus ink** — injected events carry toolType UNKNOWN and the engine ignores
  them. Finger `input tap` / `input swipe` work normally, so chrome, panels, page-turn gestures and
  persistence are all agent-verifiable; the pencil itself needs the user's hand.
- **`monkey -p <pkg> 1` does not reliably foreground the target app.** Device agents must launch
  with `am start -n <pkg>/<fully.qualified.Activity>` and verify
  `dumpsys activity activities | grep mResumedActivity` shows the target package **before every
  screencap-based conclusion.** An entire device walk once silently "passed" against the wrong app.
- **`adb push` into `/sdcard/Android/data/<pkg>/files/` fails with `remote fchown failed` — and the
  failed push DELETES the existing target file.** Push to `/data/local/tmp/`, then `adb shell cp`
  into place (`rm` the target first if it pre-exists), then `rm` the temp. `adb pull` is fine.
- **Onyx proximity is off by default** and g-paper turns it on inside `openRawDrawing`. The host
  never touches `TouchHelper` — if the pen gate misbehaves, it is a g-paper bug.
- **Frames presented during a live raw contact are withheld from the panel**, and a pen-up
  `invalidate()` of identical content is damage-free. This is what makes the frame-silence rule
  below load-bearing rather than cosmetic.
- **The NA5C is a Kaleido colour panel.** The colour filter layer sits over the mono layer and
  costs both effective resolution and contrast. Greyscale graphite renders on the crisp layer —
  which is exactly why arc 1 is greyscale. Record the panel's real resolution and density in G0.

**Paintsprout house rules that still bind** (repo-root `CLAUDE.md`):

- **Commit messages are a single plain sentence**, no prefix, no type tag, roughly under 78
  characters, written in the artist's terms rather than the code's.
- **Comments explain why, at length, and in the same register** — the decision and the failure it
  avoids, not the mechanism.
- Work proceeds in named phases.

---

## Dependency — g-paper Phase 10 "Graphite"

**Status:** ⬜ Not started · **Repo:** `~/git/g-paper` · **Tracked in that repo's `PLAN.md`.**

Arc 1 cannot start drawing until this lands. It is a g-paper phase, run under g-paper's own
protocol, and it publishes **0.1.7**. Summary of what it owes us:

- A real textured `PENCIL` in `core/canvas/StrokeRenderer.kt` — grain, and pressure → darkness.
  Today `PENCIL` falls through to the `PEN` branch (`StrokeRenderer.kt:61`).
- **Portable Canvas code only.** Core has near-zero dependencies and no SDK may enter it.
- **Deterministic grain.** The grain must be stable across re-renders — seeded from the stroke id,
  never from a running RNG — or a page reload reshuffles every mark on it.
- **Pressure carries everything; tilt carries nothing.** `OnyxPaperView.kt:618` hard-zeroes tilt
  because the fleet survey found per-device tilt scales with no SDK normalizer. Pressure arrives
  normalized against `getMaxTouchPressure`. So the Wacom app's "pressure → darkness, tilt → width"
  profile transfers only by half.
- **Transferable knowledge from the Wacom app** (`apps/paintsprout_android`): grain is drawn as
  **meshes with per-vertex colour, not mask filters** — a `BlurMaskFilter` on a software canvas was
  twice the single largest per-frame cost there. `paint/Tool.kt:215` holds its pencil profile.
- Must render identically through `StrokeRasterizer` (the offline door) as through the live view.
- The known risk to measure on the NA5C: **live vs. baked mismatch.** The Onyx engine maps live
  `PENCIL` to the firmware's `STROKE_STYLE_CHARCOAL`; our bake is our own grain. If the pen-up
  "pop" between the two is visible, that is a finding for the phase, not a defect to hide.

---

## Phases — Arc 1 "Graphite"

### G0 — Scaffold & identity
**Status:** ⬜ Not started

Gradle root at `apps/paintsprout_onyx/` (wrapper copied — boilerplate exemption) + a single `:app`;
`gradle.properties` (Temurin-17 home, AndroidX, **jetifier ON**); `settings.gradle.kts` (mavenLocal,
google, mavenCentral, **BOOX insecure repo**, `FAIL_ON_PROJECT_REPOS`); the full Onyx build baggage
(`tools:replace` label, arm64-v8a abiFilter, `libc++_shared.so` pickFirsts); e-ink design resources
written fresh (colors/themes/styles/dimens + the `values-sw720dp` tier); adaptive icon + density
aliases; debug variant (`.dev` suffix, `-dev` versionName suffix, "Paintsprout Onyx Dev" label via
the debug manifest) and release (unsigned, hand-signed with the debug keystore); a placeholder
launcher screen (temporary, replaced in G1); JVM test harness with one smoke test; a
`device-build-install` skill entry for the NA5C.

**Gate:** `assembleDebug` + `assembleRelease` + `test` green; installs and launches on the NA5C
(Haiku device check: launch, screencap, empty crash buffer, record panel resolution and density).
*Sonnet scaffolds; Fable reviews.*

**Questions to resolve at phase start:**
1. **The design system.** Notesprout's e-ink system (mono only, Tabler outline icons, no Material,
   no shadows/elevation/ripple, 1 dp inkBlack borders) is the obvious base for an EPD app — but
   Paintsprout has its own visual identity on the Wacom side. Adopt Notesprout's e-ink system
   wholesale, or derive a Paintsprout-flavoured one?
2. **The app icon.** A variant of Paintsprout's existing icon, or a fresh e-ink-native mark?
3. Confirm the version string `0.1.0-onyx`.

---

### G1 — Crypto + data core
**Status:** ⬜ Not started

The `crypto/` stack (GlobalKey with the `PSPT-` prefix, SecurePrefs, PassphraseStore, AttemptLimiter,
DerivedKeyStore, RawKeyDerivation, KeyMaterial, KeySession, KeyOpener, SoilCrypto) and
`data/NonDestructiveOpenHelperFactory`; `data/SoilFile.kt` (the only path constructor); the index
Room DB + DAO + repository + `IndexGuard`; the soil Room DB + `SoilSchema` + meta store;
`MarkCodec` + `InkColorCodec`; the Bootstrap → RecoveryKey → Unlock flow replacing G0's placeholder.

**JVM tests:** `MarkCodec` round-trip **plus fixture bytes generated by Paper's `StrokeCodec`**
(the structural-compatibility proof), KDF vectors, `InkColorCodec`, `SketchbookMeta` serialization,
index and list-id constants.

**Gate:** tests green; Haiku device walk — first run mints the recovery key → acknowledge → relaunch
→ unlock → empty library shell; the attempt limiter's schedule behaves (1–2 free · 3–4 → 30 s ·
5–9 → 5 min · ≥ 10 → 1 h).
*Fable writes the schema and crypto contracts; Opus implements around them.*

**Questions to resolve at phase start:**
1. Confirm the vocabulary mapping table above, column by column — this is the last cheap moment to
   change a name.
2. Identical crypto UX to Paper v0 (recovery-key wording, attempt-limiter thresholds), or
   Paintsprout-specific adjustments?
3. Debug tools: carry over Paper's "Show recovery key" / "Forget cached key" overflow items?

---

### G2 — The shelf
**Status:** ⬜ Not started

`LibraryActivity`: breadcrumb folders, a paginated **non-scrolling** card grid, empty state,
long-press action sheet, sort; `NewSketchbookActivity`; `FolderPickerActivity`; create, rename,
move and delete for both folders and sketchbooks (soft delete; a folder delete is cycle-guarded and
returns the sketchbook ids so their files can be removed). No covers yet — cards carry a placeholder
— and no pinned or recents overlays; both land in G5, once there is a sketchbook screen to snapshot
from and a reason to come back to one.

**Gate:** tests green; Haiku device walk — create nested folders, create sketchbooks, rename, move,
delete, breadcrumb navigation, pagination at both ends. Text entry on BOOX works normally (unlike
Supernote), so agents can type.
*Opus implements; Sonnet does layouts and resources.*

**Questions to resolve at phase start:**
1. Grid geometry — cards per page at the NA5C's measured size, portrait only.
2. Does deleting a folder delete the sketchbooks inside it, or orphan them to the root?

---

### G3 — Paper and pencil
**Status:** ⬜ Not started · **Blocked on g-paper Phase 10 (0.1.7).**

The first mark. `PaintsproutApplication` with `OnyxEngine.register(this)`; `SketchbookActivity` —
full-bleed white paper, chrome overlaid and declared via `setExclusionRects`, a pencil/eraser
toolbar; `SketchbookSession` owning the open `.soil`; a **single serial `SoilWriter`** for every
write; marks persisted as `mark` rows and reloaded on open; the g-paper lifecycle
(`resumeDrawing` / `releaseForHandoff` / `release`) wired correctly.

The **frame-silence rule** arrives with this phase and binds from here on: never present an app
frame while `paper.isPenActive`. Chrome text and updates route through a pen-idle gate. Every
deliberate exception is written down with its justification in `docs/sketchbook.md`.

**Gate:** tests green; the user draws on the NA5C and reports on pencil feel and how graphite reads
on the panel; marks survive close and reopen; `dumpsys gfxinfo` shows no frame storm during writing.
*Fable owns the engine seam and the frame-silence gate; Opus implements the session and writer.*

**Questions to resolve at phase start:**
1. Toolbar shape and placement — the top guard means it cannot sit flush at the top edge.
2. Pencil sizes: a fixed set of widths, or one pencil whose width comes only from pressure?
3. Eraser size, and whether the pen's own eraser end is read (the Wacom app does; whether the BOOX
   SDK surfaces it needs checking on-device).

---

### G4 — Pages
**Status:** ⬜ Not started

Multi-page sketchbooks: palm-gated finger swipe left/right to turn, add page, delete page, page
order, and last-open page restored on reopen. Page swap follows the documented contract exactly —
`clearForContentSwap` → `setPageSize`/`setTemplate` → `loadStrokes`. Undo/redo lands here too, as
the other half of `PageGestures`: bounded at 100, replayed through the store with the DB as the
source of truth, cleared when the sketchbook closes.

**Gate:** tests green; Haiku device walk — swipe turns pages (finger injection works on BOOX), add
and delete behave, page count and last-open survive a relaunch; the user confirms undo/redo by hand.
*Opus implements; Fable reviews the page-swap contract and the undo replay.*

**Questions to resolve at phase start:**
1. Undo gesture — a multi-finger tap like Notesprout's, toolbar buttons, or both?
2. What happens at the last page: a swipe that adds a page, or a hard stop?
3. Delete-page confirmation — a sheet, or undoable without asking?

---

### G5 — Covers, pins and recents
**Status:** ⬜ Not started

`CoverSnapshot` on sketchbook close, written to the index as WEBP q100 and drawn on the library
card; the pinned overlay (sentinel list id, `ensurePinnedListExists` on library launch — never a
migration); the recents overlay; the sort control finished against all three views.

**Gate:** tests green; Haiku device walk — a sketchbook drawn in shows its own cover, pin and unpin
persist, recents order is right after several opens, sort applies in every view.
*Opus implements; Sonnet does the card and overlay layouts.*

**Questions to resolve at phase start:**
1. Which page is the cover — the first, or the last one touched?
2. Cover snapshot size and whether it renders through `StrokeRasterizer` or off the live view.

---

### G6 — Hardening and the verdict
**Status:** ⬜ Not started

The close-out phase. Walk Paper's **six-point data-loss audit** against this source, verifying each
claim rather than assuming it: every open path wrapped; no create-capable open outside the named
entry points; a missing file never loops into unlock; delete invalidates the key cache; no
passphrase in logs, intents or ordinary prefs; no display names in prefs. Then the frame-silence
ledger, a `dumpsys gfxinfo` pass on the NA5C, and the subsystem docs —
`apps/paintsprout_onyx/docs/{data.md, crypto.md, library.md, sketchbook.md}`.

And the thing the experiment was actually for: **a written verdict.** Does graphite through g-paper
on an Onyx panel feel like pencil on paper? What did the Kaleido layer cost? Where did the live
firmware charcoal and our baked grain disagree? What would arc 2 have to be? That verdict goes in
this file, under this phase, and it is the deliverable — not a footnote to one.

**Gate:** audit walked and written down; docs in; tests green; the user's verdict recorded.
*Fable owns the audit and the verdict; Sonnet writes the docs.*

**Questions to resolve at phase start:** none — but the verdict is written **with** the user, not
for them.

---

## Appendix — build & install

```sh
cd apps/paintsprout_onyx

./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # JVM unit tests

adb -s 92c16533 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 92c16533 shell am start -n com.symmetricalpalmtree.paintsproutonyx.dev/<Activity>
adb -s 92c16533 shell dumpsys activity activities | grep mResumedActivity   # always verify
```

Release is unsigned and hand-signed with the debug keystore, the same arrangement as the Wacom app
and both Notesprouts — no `signingConfig` in Gradle, and not a Play Store identity. Java 17 comes
from `org.gradle.java.home` (Temurin-17).

g-paper artifacts come from mavenLocal: `cd ~/git/g-paper && ./gradlew publishToMavenLocal`.
