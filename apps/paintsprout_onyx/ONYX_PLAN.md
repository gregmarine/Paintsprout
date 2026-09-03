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
  which is exactly why arc 1 is greyscale. **Measured in G0: 1860 × 2480 px, densityDpi 300
  (density 1.875), physical ≈ 304.8 × 304.3 dpi, ≈ 10.2 in diagonal.** That works out to
  smallestWidth **992 dp**, so `values-sw720dp` is the resource tier that actually applies and
  the base `values/` tier is a fallback no target device will ever use.

**Paintsprout house rules that still bind** (repo-root `CLAUDE.md`):

- **Commit messages are a single plain sentence**, no prefix, no type tag, roughly under 78
  characters, written in the artist's terms rather than the code's.
- **Comments explain why, at length, and in the same register** — the decision and the failure it
  avoids, not the mechanism.
- Work proceeds in named phases.

---

## Dependency — g-paper Phase 10 "Graphite"

**Status:** ✅ Published as **0.1.24** and approved on the panel 2026-09-03 (`7239366` → `HEAD` in `~/git/g-paper`; its Phases 10, 11
and 12) — fourteen device findings folded back in, then the pencil reset to an upright hairline ·
**Tracked in that repo's `PLAN.md`.**

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
**Status:** ✅ Complete (commit 2d23edf)

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

**Answers:** 1. Notesprout's e-ink system **wholesale** — mono palette, no Material, no
elevation or ripple, 1 dp inkBlack borders, Tabler outline vocabulary — written fresh rather
than copied. It is the proven answer for this panel, arc 1 is greyscale anyway, and diverging
later costs nothing. 2. A **fresh e-ink-native mark**: Paintsprout's own sprout geometry
redrawn graphite-on-white with heavier strokes, rather than the white-on-sproutGreen original
whose green field dithers to a restless grey. 3. **Confirmed**, `0.1.0-onyx`, versionCode 1,
debug `0.1.0-onyx-dev`.

**Outcome:** The scaffold stands and the gate is green — `assembleDebug`, `assembleRelease` and
`test` all pass, the release hand-signs with the debug keystore and verifies, and the debug build
installs and launches on the NA5C with a clean crash buffer.

- **The panel, measured:** 1860 × 2480 px, densityDpi 300, density 1.875, physical ≈ 304.8 × 304.3
  dpi, ≈ 10.18 in diagonal. That is **992 dp** across, so `values-sw720dp` is the tier that actually
  applies and base `values/` is a fallback no target device will reach. Recorded up in the standing
  traps too, next to the Kaleido note it belongs to.
- **g-paper is pinned at 0.1.6**, the newest published build, rather than left out until 0.1.7. The
  point is that the Onyx build baggage then has a real dependency to prove itself against:
  `libmmkv.so`, `libneopen_jni.so` and `libonyx_pen_touch_reader.so` are in the packaged APK, which
  means the insecure BOOX repo resolved, jetifier ran, the label `tools:replace` merged and the
  `libc++_shared.so` pickFirsts fired. Baggage that is never exercised is baggage nobody knows is
  broken. It re-pins to 0.1.7 when g-paper Phase 10 lands. `OnyxEngine.register` is **not** called
  yet — that belongs to G3, with the view it exists to serve.
- **`PlaceholderActivity` is deliberately throwaway** and G1 deletes it. It earns one phase by being
  the proof: theme resolves, type scale renders, viewBinding is wired, and it prints the panel
  numbers above.
- **Fable's review found four things**, all addressed before commit. The one that mattered: the
  window-inset handler *replaced* the layout's own padding instead of adding to it, on the very
  screen whose comment declares it the pattern later screens copy — harmless where it sits, and a
  toolbar shoved against the status bar everywhere it would have been pasted. Also: the status bar's
  icon colour was the panel's accident rather than a decision (`windowLightStatusBar` now stated),
  the `sw720dp` comment claimed everything grows when the BOOX top guard deliberately does not, and
  the six mipmap density copies are inert at minSdk 29 and now say so rather than posing as a
  fallback.
- **Not done, on purpose:** no `docs/` yet (G6 owns the subsystem docs), no data or crypto
  dependencies (G1), no engine registration (G3). The KSP and serialization plugins are applied and
  unused, which is what "scaffold" means here.
- **Left for the user's eye:** how the sprout icon reads on the BOOX launcher shelf at real size.
  Everything else in this phase was adb-visible.

---

### G1 — Crypto + data core
**Status:** ✅ Complete (commit 6bd0f69)

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

**Outcome:** The crypto spine and the data core stand, and the whole gate is green — **78 JVM
tests**, `assembleDebug` and `assembleRelease`, and a device walk on the NA5C that reaches the
library from a cold first run and back again through the lock.

- **The family-compatibility claim is proved, not asserted.** Paper's real `StrokeCodec.kt` was
  compiled and run to generate fixture blobs across every flag combination; `MarkCodecTest` decodes
  those and re-encodes to Paper's exact bytes. Independently, the two codecs were compared over 16
  point-sets — byte-identical every time, in both directions. Format B genuinely holds, so the two
  apps are one family at the layer where a drawing actually lives.
- **Room's schema export is on and pinned by a test.** The `room.schemaLocation` arg was inert
  (both `@Database`s had `exportSchema = false`), so the committed-schema trail it argued for did
  not exist. Export is on, `app/schemas/` is committed, and `SchemaParityTest` compares Room's
  generated DDL against `SoilSchema`'s hand-written DDL column for column, plus the primary key,
  the index and both versions. That failure would otherwise land on a device as an identity-hash
  error, which reads like a corrupt file and invites deleting a perfectly good one.
- **`PaintsproutApplication` arrived a phase early**, holding nothing but `System.loadLibrary("sqlcipher")`.
  Every database here is encrypted and the launcher opens one, so there is no ordering in which a
  screen could load it in time for itself. `OnyxEngine.register` joins it in G3, and the file says so.
- **Auto Backup is off, and the Onyx SDK fights it.** A restored install would get ciphertext
  without the Keystore key and an `EncryptedSharedPreferences` blob that throws before the unlock
  screen could be offered — a library that looks complete and cannot be opened. `onyxsdk-pen`
  declares `allowBackup="true"` in its own manifest, so `tools:replace` now covers that attribute
  as well as the label. One more piece of mandatory BOOX build baggage.
- **The review found two real things.** `AttemptLimiter.check` returned an epoch deadline while the
  contract promised remaining milliseconds — harmless today because the caller compensated, and a
  trap for the first G2 caller who believed the contract. And `ensureReady`'s first-launch branch
  cached a raw key without invalidating a stale one first, so an index deleted out of band could
  re-bless the old file's key against a new salt. Both fixed. Paper carries the same wrinkle in
  `PaperIndex.kt:76`.
- **An `auto_vacuum = INCREMENTAL` was removed for doing nothing.** SQLite only accepts that pragma
  while a file has no tables, and Room runs its `onCreate` callback after creating them, so it was
  ignored on every file ever made. Carried over faithfully from Paper, which has the same latent
  bug. Choosing it properly means setting it at creation before Room opens the file, which belongs
  with the new-sketchbook flow in **G2**. Left out rather than left in and inert.
- **The device walk, on the NA5C, in full:** first run mints a `PSPT-` key in the Crockford
  alphabet; Continue is genuinely gated by the tick; the index file is encrypted from its first
  bytes; the acknowledgement survives a relaunch; the debug menu shows the same key it minted;
  forgetting the key clears both secure-pref files and the next launch lands on Unlock; **attempts
  1–2 are free, the third hides the entry row and counts down from 30 s, and the field returns when
  it expires**; the correct key opens the library; a further relaunch goes straight there. No
  crashes in the buffer at any point. The BOOX status-bar guard measurably works — the library top
  bar lays out at y=77, below the bar rather than under it.
- **A wrong key leaves the file untouched**, verified the way Paper verified it: md5 identical
  across an isolated failed attempt. An earlier delta in the same session spanned a `killProcess`
  and a relaunch as well as the attempts, and is attributable to WAL state settling after the kill,
  not to the attempts themselves.
- **The first device walk reported a critical defect that did not exist** — it concluded the forget
  flow left the app in the library and named a class the app does not contain. Re-run by hand, the
  flow routes to `UnlockActivity` correctly. Worth recording as a working note: a device agent's
  *failures* need reproducing before they are believed, exactly like its passes.
- **Left for G2:** `IndexRepository.deleteSketchbook`/`deleteFolderRecursive` deliberately do not
  remove files or call `KeyMaterial.invalidate` — that duty belongs to the caller that does not
  exist yet, and Paper's own audit flags forgetting the RAM half of that invalidation as a real
  past bug. `ancestry` does not filter `deletedAt`. `SoilCrypto.createRaw` is written and uncalled,
  waiting for the new-sketchbook flow. And the library screen is a shell on purpose.
- **Not done, on purpose:** no `docs/` (G6 owns the subsystem docs), no engine registration (G3),
  no sketchbook creation or listing (G2). `PlaceholderActivity` and its layout and strings are gone,
  as G0 said they would be.

### G2 — The shelf
**Status:** ✅ Complete (commit 13460b2)

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

**Answers:** 1. **Three columns by two rows — six large cards a page.** Cards keep a page's
proportions (1.4, a 3:4 cover over a two-line label), which on the measured grid area works out
at 590 × 826 px each, about 1.9 × 2.7 inches of real panel. Four columns would have fitted twelve
and filled the height exactly; six was chosen anyway, because a sketchbook is found by looking at
it and from G5 that cover is a photograph of a real page. **The grid anchors to the top and the
leftover height stays at the foot** — centring the block was tried on the panel and reads wrong:
a shelf fills from the top down, and sharing the slack out top and bottom makes the first row sit
lower on a half-full page than on a full one, so the shelf appears to move as it is paged through. 2. **The contents go too**, and the
confirmation names how much — see the outcome below for why that count is taken all the way down
rather than one level.

**Outcome:** The shelf stands and the gate is green — **98 JVM tests** (79 after G1),
`assembleDebug` and `assembleRelease`, and a full walk on the NA5C: create, nest, breadcrumb,
rename, move, sort, paginate, delete, and back again after a force-stop, with the `Garden/`
file count matching the shelf at every step and an empty crash buffer throughout.

- **G1's two loose ends are tied off, both of them here because this is the phase that first has a
  caller.** `auto_vacuum = INCREMENTAL` is stamped on a `.soil` before Room ever opens it, which is
  the only moment SQLite will accept it — a seed table is made and dropped so the header gets
  written, leaving `user_version` at 0 so Room still takes its own create path. It reads the pragma
  straight back afterwards and warns if it did not take, because the trap it replaces was a pragma
  that ran, returned no error and did nothing. Verified on the device: no warning. Note the debt it
  leaves — INCREMENTAL only makes reclaiming *possible*; the actual `incremental_vacuum` is owed by
  **whichever phase first hard-deletes rows** — G4 was expected to be it and is not, since every
  delete there is a soft delete. And `ancestry` now stops at deleted rows, so a
  breadcrumb can never offer a crumb that leads off the shelf.
- **The folder-delete sweep is crash-safe by ordering, not by luck.** It walks the tree first and
  then stamps **children before parents**. Each stamp is its own statement and this device kills
  background processes routinely; stamped parent-first, a kill halfway through would leave a deleted
  folder holding live sketchbooks that no listing walks down to again — the rows and the files all
  still there, and the drawings gone as far as anyone could tell. Deepest-first, whatever is still
  alive at any instant still has a living parent all the way to the root.
- **The delete confirmation counts all the way down.** The cheap version counts direct children and
  would tell someone deleting a folder holding one folder holding thirty sketchbooks that "1 folder"
  goes with it. On the device the real sentence read *"2 sketchbooks and 1 folder inside will go with
  it, for good"* for a folder whose second sketchbook was two levels down — which a one-level count
  would have destroyed without naming.
- **Three defects the device found that no test could have.** The last grid column was drawn half off
  the panel, because a view's `width` includes its own padding and the grid was measured against the
  whole 1860 px rather than the 1770 left inside the screen margin — it looks like a card that is
  merely too big, which is the wrong thing to go and fix. The new-sketchbook field opened unfocused,
  so `selectAll` did nothing and the first tap put a caret at the end of a timestamp the artist then
  had to delete by hand. And every `AlertDialog` shipped with SHOUTING BUTTONS: an AppCompat dialog
  builds its buttons from `buttonBarPositiveButtonStyle`, not the `android:`-prefixed one the theme
  had been setting since G0, so the style was resolved by nobody.
- **`Activity.onBackPressed` was dead code and nothing on this device could have shown it.** Android
  15 plus targetSdk 35 means predictive back is on by default and the framework never calls it — back
  from three folders deep would have left the app. Back now goes through `onBackPressedDispatcher`
  with the callback armed only while there is somewhere to go up to. It cannot be verified from a
  desk either: an injected `KEYCODE_BACK` reaches nothing on this device, not even the system
  settings, so **this one is on the user's checklist**. logcat does at least confirm the app
  registers an `OnBackInvokedCallback`, which is the same thing seen from the other side.
- **Fable's review found six things and was right about five.** The two that mattered are the delete
  ordering and the recursive count, both above. It also caught that the create path's cleanup did not
  cover the whole create — the file was made outside the `try` — and that a comment claimed otherwise;
  that a slow refresh could land after a newer one and bind a shelf assembled out of two folders,
  now fenced with a generation counter; and that the pager's ⏮ ◀ ▶ ⏭ carry emoji presentation and
  would have arrived as accidental colour on a Kaleido panel — moot now the pager is drawn, but the
  trap is real and worth knowing for any future glyph. Two smaller things came out of it: name rules moved from hardcoded English into
  `strings.xml` (returning a resource id keeps them testable without a Context), and the duplicate
  check is `COLLATE NOCASE`, since the shelf already sorts case-insensitively and "Studies" beside
  "studies" is the exact confusion that check exists to prevent.
- **The Haiku device walk was not usable and the walk was redone by hand.** It spent its budget on
  adb text-entry mechanics and stopped after two of eighteen steps, then reported two "critical
  findings" that were neither: the disabled package is the documented `install -r` race, and the text
  field behaviour was the focus bug seen through an agent that could not tell it from an adb quirk.
  The standing rule earned its place again — a device agent's failures need reproducing before they
  are believed. **The corollary for later phases: a walk this long does not fit in one Haiku, so cut
  it into a few short ones or drive it directly.**
- **Not done, on purpose:** no covers, pins or recents (G5, once there is a page to snapshot); no
  opening a sketchbook — tapping a card does nothing at all yet, and G3 is what it is waiting for;
  no `docs/` (G6). `IndexRepository.setCover`/`cover`/`pin`/`unpin`/`pinnedSketchbookIds` stay
  written and uncalled, as G1 left them.

**Follow-up after the phase closed — the chrome speaks in icons.** G2 shipped its toolbars as words
and typographic glyphs, with a comment arguing that one folder mark was the only icon this app should
own. That was a **deviation from G0's locked answer**, which adopts Notesprout's e-ink system whole,
Tabler outline vocabulary included — so it was corrected rather than defended, and the comments that
had argued the other way were rewritten rather than left contradicting the rest of the app.

What landed: fourteen Tabler outline drawables at stroke 2 on a 24 viewport; `ic_folder` redrawn to
match them; `toolbar_background_bottom`, whose 1 dp top edge is the only thing that can separate a
toolbar from the page on a panel with no shadow to give; and **Paper's arrangement**, not merely its
icons — Up at the far left of the breadcrumb bar and `GONE` at the root so the trail starts at the
panel's edge, Sort down beside New folder and New sketchbook, the pager taking the weight between
them so it centres in whatever room they leave. **The empty left end of the bottom bar is where
Pinned and Recents go in G5**; it is a gap on purpose, not one to tidy away. The long-press and sort
sheets took icons too, with the icon column reserved on every row so a sort sheet's labels stay in
one line and the tick is the thing that stands out.

`Widget.Paintsprout.ToolbarButton` and `bg_toolbar_button` needed no change at all — G0 had written
them to match, and the shelf simply had not been using them.

**And one more thing the panel showed:** a dialog's Cancel and Create read as a single button with two
words in it. AppCompat's button-bar button is transparent, unbordered and barely padded, which on a
display with no ripple, no shadow and no colour leaves two bare runs of black text touching. They now
carry padding, a gap and the app's own pressed ring — which also gives back the tap feedback the
missing ripple took away, and that matters most on a dialog button waiting on a database read, since
one already tapped looked exactly like one that was missed. **Paper carries this style character for
character and has the same defect**; it was found here first, and fixing it there is a change in that
repo, not this one.

**Checked by the user on the panel:** the back gesture walks up a folder at a time and leaves the app
only at the root; the long-press to open a card's action sheet is right as it is. The cards were
**centred vertically and that read wrong** — reversed to top-anchored, which is the last change in
the phase and the one thing here that was settled by looking at the panel rather than by argument.

---

### G3 — Paper and pencil
**Status:** ✅ Complete (commits `db6ef2e` → `87107d2`; gate passed by Greg's hand 2026-09-03: the hairline approved, marks survive close and reopen, the eraser end works) · **g-paper Phases 10–12 ran inside this phase.**

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

**Answers.** Four questions were put, because the first of them turned out to be about g-paper
rather than about this app.

1. **Tilt stays at zero** — g-paper's fleet decision stands and this arc does not reopen it. The
   premise needed correcting first: BOOX is *not* deaf to tilt. It delivers `tiltX`/`tiltY` on every
   raw point and the NA5C's spans are sane (`-43..55` / `-13..38`). g-paper discards them because a
   five-device survey found one model reporting roughly 100× the others with no `getMaxTilt()` to
   normalize against, so a fleet library publishing a tilt number would be publishing a different
   unit per model. Characterizing it per model is **a candidate later g-paper phase**, and it is what
   the pencil's missing half — the side-of-lead regime, lighter and broader and streakier as the pen
   lays over — would need.
2. **Pressure moves darkness; the width never moves.** One variable on a panel that cannot show two,
   and it keeps the verdict attributable.
3. **Graphite is broken coverage, not tonal shading** — flecks on the paper's tooth with bare paper
   between them, denser under pressure. Chosen over carrying the Wacom app's tonal five-lane mesh
   across for two reasons: an e-ink panel with a handful of grey levels dithers any continuous grey
   it is handed and invents a texture on top of ours, and the Wacom mesh was judged right *beside a
   surface model supplying the gaps* — this arc's paper is plain white with nothing behind it, so the
   pencil has to carry the tooth itself.
4. **Chrome follows Paper's arrangement**: a top bar of tool buttons below the status-bar inset, and
   a bottom strip carrying the sketchbook's name and page count that is not tappable at all. Proven
   on this exact panel, and it keeps every target away from the bottom edge where the heel of a
   drawing hand rests.
5. **Three discrete leads** behind the pencil button — tap it when it is already in hand and the tin
   opens, the same idiom as the shelf's sort sheet. Real pencils come in sizes; you pick one up, you
   do not dial one.
6. **One fixed eraser radius, no control.** Worth being precise about what the control would have
   done: arc 1 uses g-paper's *stroke* eraser, so the radius is "how near you have to pass", not "how
   much rubber is on the paper" — a bigger one does not take more off a mark, it takes marks that
   were not aimed at. There is no setting there an artist would want, and offering one would promise
   a rubbing eraser this is not. **The pen's eraser end needed no work at all**: the BOOX SDK
   intercepts it at hardware level and g-paper's Onyx engine erases with it whichever tool is armed,
   so turning the pencil over erases and nothing on the toolbar moves — the same behaviour the Wacom
   app builds by hand, here for free.

**Outcome (code complete; the pencil has not been drawn with yet).** **111 JVM tests** (98 after G2),
`assembleDebug` and `assembleRelease` green, installed on the NA5C, and every step adb can reach
walked: the shelf opens a sketchbook, creating one lands straight on its page, the lead sheet ticks
the lead in hand, the tool ring moves between pencil and eraser, back returns to the shelf, and
`GPaperOnyx: openRawDrawing: pipeline claimed (1860x2480)` says the engine took the panel at full
resolution. Empty crash buffer throughout.

- **g-paper Phase 10 was built first and published as 0.1.7** (`~/git/g-paper`, commit `7239366`) —
  `geometry/GraphiteGrain.kt` decides which specks of tooth caught the lead,
  `StrokeRenderer.drawPencil` puts them down, 15 new JVM tests hold the determinism. The engine gap
  was fixed in the engine, exactly as the standing rule says; nothing about graphite lives in this
  app. Its own outcome note is in that repo's `PLAN.md`.
- **The determinism constraint turned out to be stronger than g-paper's plan had asked for.** The
  plan wanted no reshuffle on reload; a grain must also not reshuffle at **pen-up**, or every mark
  ends in a flinch. So `CanvasPaperView` now mints a stroke's id when the contact *starts* rather
  than at commit, and the live preview seeds off the id the stroke is about to get.
- **Two flaws an offline preview caught that no test would have.** Rendering the grain to a PNG
  before ever touching the device showed the lanes of tooth spread endpoint-to-endpoint, so the two
  outermost lanes sat exactly on the lead's rim and took the full edge falloff — ruinous on a fine
  lead, where those two lanes *are* two thirds of the mark, which came out patchy grey instead of a
  firm dark line. And the fleck diameter floors the apparent width: a mark measures about
  `width + 2 px` however fine the lead. That second one is why the three leads are 3 / 6.5 / 12 px
  and not evenly spaced — closer together they would look like one pencil in three disguises.
- **The write queue outlives the screen, and that was a real bug caught before it shipped.**
  `SoilWriter`'s pump was first given the Activity's `lifecycleScope`, which is cancelled the instant
  `onDestroy` returns — so `close()`'s drain, the call written specifically to save the last marks
  drawn, would have been the call that threw them away. It runs on the application scope now.
- **`releaseForHandoff()` is deliberately called nowhere**, and the comment in `SketchbookActivity`
  says so rather than claiming otherwise. There is no second paper-hosting screen in arc 1; when
  there is, that is where the call goes.
- **The frame-silence rule arrived with a ledger and the ledger is empty on purpose.**
  `sketchbook/PenIdleGate.kt` and `docs/sketchbook.md` — G3's chrome is static by construction (the
  toolbar changes only on a tap, a tap is a finger, and a finger while the pen is active is a palm
  the component has already refused), so there are no exceptions to record. The gate is built and
  wired anyway so G4's page turns and undo counters land in a screen that already obeys the rule.
- **`docs/` starts here rather than in G6**, holding only `sketchbook.md`, because the plan named
  that file as the home for the frame-silence justifications and a ledger written later is a ledger
  nobody kept. G6 grows it.
- **Not done, on purpose:** no undo/redo and no page turning (G4 — the page indicator reads `1 / 1`
  and means it); no covers, pins or recents (G5); no `SoilDao.restore`/`setOrder`/`liveChildIds`
  callers yet, and `incremental_vacuum` is still owed by G4 as G2 recorded.
**First device finding, fixed: the live preview was lying about width, and it read as the bake being
broken.** Drawing on the panel showed marks collapsing dramatically at pen-up, worst when the pen was
laid over. The cause was not tilt. `PENCIL` armed the firmware's textured charcoal
(`STROKE_STYLE_CHARCOAL`, 4), which is a **stamp** pen: BOOX multiplies its nominal width by
`CHARCOAL_STROKE_WIDTH_EXTRA_SCALE = 5.0` before rendering, because the grain bitmap is scaled to the
stroke and below roughly 20 px no texture can exist at all. So a 6.5 px lead previewed about 32 px
wide and committed 8 — a fifth of itself, the instant the pen lifted.

g-paper **0.1.8** arms the plain even line (`STROKE_STYLE_PENCIL`, 0) instead. Live and baked now
agree on the mark's *size* and differ only in grain, so a stroke **gains its tooth** at pen-up rather
than shrinking. Greg's call alongside it: **three widths, no tilt, a stroke and variable pressure** —
which is what the bake already did, so the whole change is the one-line remap in the engine, exactly
where the standing rule says an engine gap gets fixed. The general lesson is bigger than the fix: **a
preview that lies about width is far worse than one that lies about texture**, because width is what
the hand aims with.

**Second device finding, and it reversed the morning's answer: tilt is back, because the panel can
actually supply it.** With charcoal armed, the collapse was worst when the pen was laid over — so the
extra width was tilt, not a constant to divide out, and `TouchHelper` has no tilt control (its whole
pen surface is style/colour/width, verified by `javap` on the AAR). A textured live style therefore
cannot be had without its tilt response. Greg's call: **keep the texture and make the bake match** —
`CHARCOAL_V2`, whose EPD look he called spot on, with pressure → darkness and tilt → width.

Measuring it settled the question the morning had guessed at. Per-stroke tilt logging over three
deliberate angles, 1300–1600 samples each, found **`hypot(tiltX, tiltY)` is degrees from vertical,
directly** on this panel: upright read 9.2, a deliberate 45° read 44.3, flat read 75.2. No scale
factor. The fleet survey's fear is true *across* models and false *within* one that has been
measured — so g-paper Phase 11 supplies tilt for a measured allowlist (`NoteAir5C`) and zero for
everyone else, and `GraphiteGrain` widens the mark on a curve fitted to what this firmware does
(≈1× / 2.5× / 5.5×), read per station because a shading stroke is a hand rolling the pencil over as
it travels.

**The mark format changed with it, and that was the easy thing to miss.** `MarkRows` now writes the
tilt channel. It had been deliberately left out, with a comment arguing tilt was never measured — but
tilt is now what decides a mark's width, so dropping it would have let a page reopen with every
shading stroke narrowed to a line. Not a mark drawn slightly wrong: a different drawing. Format B
reserved the flag from the start, so it cost no version bump and no migration, and files written
before this reopen as the upright marks they were recorded as.

**Third finding, on the same look: the flank was too narrow and too dark.** Upright was right, so
the correction was to the tilted end alone — refitting with the origin pinned (which moved the
exponent, not just the gain) gave **1× at 9°, ≈4.9× at 44°, ≈10.9× at 75°**, about twice the girth
of the first fit. Pinning upright is not negotiable: it is the width the artist picked from the tin,
and a pencil that will not draw the width it was set to is a broken tool rather than a differently
tuned one. And the flank now deposits **paler** as well as broader, which is why real side-of-lead
shading comes out grey however hard you lean — it reduces *coverage* rather than fleck darkness,
because tone in this renderer is meant to come from how many specks of tooth catch, and darkening
the flecks instead would have quietly turned a spatial texture back into a tonal one. g-paper 0.1.10.

**Fourth finding, and the first one settled by measurement rather than by eye.** Greg photographed
the same three strokes live on the panel and again after they baked. Two things came out of
comparing them that looking could not have given:

- **The width was already right, and the first reading said otherwise.** Thresholding the photos
  high enough to segment cleanly made the baked bands read ~30% narrower — but that was the
  threshold throwing away the bake's pale outer flecks, while inflating the coverage measured inside
  the band, so width and density came out entangled and both wrong. **A binary threshold cannot
  measure a texture whose whole nature is partial coverage.** At a threshold low enough to keep the
  pale flecks, the extents match within 4% at all three angles. The tilt width curve was left alone.
- **The bake was laying down ~30% less graphite, and by the same amount at every angle.** Flat
  across tilt is what identified the cause: the upright stroke gets no tilt-lightening at all and was
  still 0.68×, so this was baseline coverage rather than the lightening being too strong. The rim
  falloff, the skate and the fleck size came up together (g-paper 0.1.11), chosen so the factor is
  **flat across the whole pressure range** — the light-to-hard response was already approved and must
  not move while something else is being fixed. Most of the shortfall was the rim: a mark giving up
  half its coverage at the edge spends a lot of its width on almost nothing.

**Fifth finding: the firmware overdraws, and the leads moved to meet it.** A second photo pair, after
the density fix, put the bake at a uniform **0.76× the live width** — stable across every sensible
threshold and the same at all three angles, which exonerates the tilt curve again and points at the
base width. `CHARCOAL_V2`'s stamps overhang: it draws about 1.3× the width it is handed.

Both paths run through one `penWidth`, so they can only be decoupled inside g-paper, and **which side
to correct was the decision.** Widening the bake to meet the firmware would have made `Stroke.width`
a per-device fiction — a host compositing its own ink through `StrokeRasterizer` would then get a
different answer from the one on screen. So g-paper 0.1.12 divides the width the *engine* asks for,
and the three leads here went up by the same 1.3 in the same change. The two move together or not at
all: scaled together, the firmware receives exactly the number it received before, so **the EPD's
appearance is untouched and it is the bake that grew to meet it** — which is what was asked for.

**Sixth finding, and the one no measurement had caught: the grain had a direction.** Greg's words
were that the mark "looks like it is made up of a ton of tiny lines". Magnifying the same photo pair
showed it at once — our flecks combed into short dashes running *along* the stroke, where the panel's
speckle is fine and isotropic. Structural, not statistical: a fleck is wider than the lattice pitch
that spaces it, so the same lane recurring at the same offset station after station fuses its flecks
into a line. Fixed by sliding the whole comb sideways a random fraction of a lane at every station.
The grain came finer in the same release too (the panel's speckle is visibly smaller-grained), and
[LEVELS] went 3 → 6 because coverage saturates once the tooth is full, so past that point the
darkness ramp is the only thing still carrying pressure and three steps could not carry it.

**The lesson worth more than the fix: an aggregate statistic cannot see structure.** Mass, extent and
coverage had all been matched while the texture was plainly wrong — and a combed texture does not
*look* like an even scatter at the same coverage, so the "density still a little off" reported
alongside it may well have been the streaking rather than the density. **Magnify and compare before
tuning a number.** Density is deliberately unchanged in this round so it can be judged over an even
grain.

**Seventh finding — "pipe cleaner", and it was in the Wacom app all along.** 0.1.13 took the
*direction* out of the grain but not its *connectedness*. Greg named it exactly, and added that the
original Paintsprout has always looked like this too. A pixel-level `screencap` of the re-baked
strokes showed why — and note that it needed **no camera at all**: the bake is screencap-visible, and
marks re-render from stored data on open, so an old drawing can be photographed through a *new*
renderer with one `adb exec-out screencap`. That instrument should have been reached for several
rounds earlier.

**A fleck wider than the lattice that spaces it cannot help but touch its neighbours**, and touching
flecks stop being specks: they become worms a fleck thick and several long — half a millimetre of
connected bristle at this density. The panel's own charcoal, magnified, is essentially a one-pixel
dither with nothing connected in it. The Wacom app fails the same way from the other direction, its
grain being continuous *lanes* along the stroke: **graphite laid down as connected geometry looks
like hair, whichever way the geometry runs.**

The fleck is now sized against the pitch and **ramped by darkness** — about one pitch at the pale
end so specks stand alone, over two at the dark end so they flood into solid ink, and ~1 px chains in
between, under the eye's reach. It carries pressure too, which is welcome: coverage saturates once
the tooth is full, so past that a growing fleck is the only thing left to darken with. Ink mass per
tone was held within ~10% end to end while all of this moved, checked by simulation before shipping,
so this is a change of *texture* and not of density.

**Eighth finding, and the "pipe cleaner" actually solved: noise that becomes shape.** Two releases
had each removed something real about the grain — its direction, then its connectedness — and
neither touched what Greg was seeing. **That a rendering fault survives redesigning the renderer is
itself the finding: suspect the input.** The clue that cracked it was his, offered rounds earlier and
not used hard enough — *the Wacom app has always looked like this too* — and those two renderers
share almost nothing except that **both drive pencil width from raw tilt.**

A digitizer's tilt jitters several degrees sample to sample and a hand cannot roll a pencil that
fast. G3's own tilt log had already recorded it: the flat stroke swung 65.7°–85.6° along its length,
which through the width curve is a 9×–13× swing in how broad the mark should be. Fed in raw that
becomes *geometry* — both edges ripple at the sample rate and the stroke grows a fringe of fine
hairs. Rendering one stroke twice, raw against smoothed, settled it in a single image.

g-paper 0.1.15 averages the lean over ~40 px of arc, **causally**, so a prefix still renders like the
whole stroke. **Pressure stays raw on purpose: noise that becomes *shape* must be smoothed, noise
that becomes *tone* need not be** — there it is doing the same job the tooth is. **The Wacom app
needs this same fix whenever its pencil is next opened.**

**Ninth finding — the "pipe cleaner", solved, and it was never the grain.** Three releases each
removed something real about the texture and Greg reported *no change at all* from any of them. That
was itself the finding and it took too long to read: **when a rendering fault survives redesigning
the renderer three times, stop working on the renderer.**

The search failed on **scale**. The defect was inspected at 10× pixel zoom, where a bristle is one
pixel wide and reads as ordinary speckle. Viewing the same stroke at **1× and 2×** showed it
instantly — transverse striations combing the mark. *Inspect a texture at the size it will be looked
at.*

The cause: a cross-section of grain is laid perpendicular to the pen's direction, and that direction
came from **one adjacent pair of raw samples**. At 2 px spacing, 0.35 px of digitizer jitter swings
the computed angle with ~14° of standard deviation, past ±35°. Every comb of flecks is rotated by
that much — and **the error is multiplied by the half-width of the mark**, so on a lead laid over at
80-odd px it throws grain tens of pixels out of line. Bristles radiating from a core. Rendering one
clean path beside the same path with 0.35 px of jitter produced a textbook pipe cleaner and settled
it in one image. g-paper 0.1.16 smooths the travelled direction over ~10 px of arc, causally, exactly
as 0.1.15 does the lean. Verified on the panel: the combing is gone.

**Greg offered his remaining weekly Fable budget for this and it was not needed** — the synthetic
jitter test was decisive on its own.

**Tenth finding: the ends were chisels.** With the texture finally right, Greg compared our ends
against BOOX's own Notes app — screencapped side by side, since committed marks in either app are
screencap-visible. Theirs finish in a rounded dome; ours stopped at the last cross-section, leaving
a straight cut clean across the mark with corners on it. **A lead meets the paper as a disc**, so the
ink ends in a half-round of the mark's own half-width. g-paper 0.1.17 walks out past each end,
shrinking the half-width along a circle.

Ordering carries a real guarantee: the touch-down cap is laid **before** the body so ink already on
the paper keeps its place as the stroke grows, and only the lifting cap travels with the pen — which
is what the real tip does. The prefix-stability test was **tightened rather than loosened** to say
exactly that: the guarantee covers ink already laid down, and stops at the pen.

**Eleventh finding, and one this work introduced: broad strokes began with a hook.** A defect of
0.1.16's own making, spotted on the panel. Only the widest strokes did it — the signature of an error
multiplied by the half-width. The tangent smoother was **seeded from the first pair of samples**, the
noisiest direction measurement in a stroke, and two things hang on that seed: the touch-down dome is
thrown backwards along it (a half-disc of the mark's half-width, aimed tens of degrees wrong) and the
filter then swings for a window's worth of travel as it converges, sweeping the first cross-sections
through a curve. Seeded from a **chord across the whole smoothing window** there is no transient at
all — the seed is already where the filter would settle. Fixed in 0.1.18 with a regression test.

**Worth carrying: a filter added to remove noise brings a transient of its own, and a stroke's start
puts it on display.** Check the beginning of a mark whenever smoothing is added anywhere.

**Twelfth and thirteenth: stroke ends.** Greg compared ours against BOOX Notes (both apps'
committed marks are screencap-visible, so no camera was needed) — theirs finish in a rounded dome,
ours in a straight cut with corners. 0.1.17 caps each end with a half-round of the mark's own
half-width. Then a dark bead appeared around the cap's outline: `laneCount` rounds a lane count up so
a hairline still gets grain, which is invisible in a body of dozens of lanes and doubles the density
of a cap's narrowing strips, the excess landing on the outline because the outermost lanes sit there
by construction. 0.1.19 asks for coverage per unit *area* instead. And 0.1.20 seeds the **lean**
filter from the smoothing window's mean rather than `points[0].tilt` — the same seed bug 0.1.18 fixed
for the tangent and left unfixed here, showing up as a wedge instead of a hook. **Fix a class of bug
everywhere it lives, not where it was found.**

**Fourteenth: the two tilt effects were compounding.** Greg chose to soften it, so darkness now
follows a much slower lean (150 px) than width does (40 px) — g-paper 0.1.21. Shape belongs to the
instant, tone belongs to the grip. A stroke held flat is still paler than one held upright, so the
lightening he asked for earlier is intact, but a stroke no longer flashes dark where the pen passes
through vertical.

**Still open, and now understood: a small black knot at the start of broad strokes.** Not the caps
(it predates them — verified by thresholding a 0.1.16 build) and not the roll-in (0.1.21 changed his
stroke by two pixels out of a thousand). It is the **path**: the pen touches down, the hand settles,
and a few pixels' excursion follows before the stroke sets off. Invisible on a fine lead; on a lead
laid over — ten times broader — the mark folds across itself and graphite laid twice composites to
solid black. Reproduced synthetically, it draws the same Y-shaped knot the panel shows.

**Damping the path does not fix it**, and the attempt is recorded in g-paper's `PLAN.md` so nobody
repeats it: a plain average lags (shortening every stroke, caught by the end-shape test), and a trend
term that cancels the lag makes the filter *track* the excursion instead of absorbing it — pile-up
unchanged at every strength tried, clean baseline worse. Reverted.

**g-paper 0.1.22 fixes it by dropping the arrival rather than smoothing it.** Whatever the hand did
while landing is not a mark, so the renderer starts after the last sample within 25 px of arc at
which the pen was travelling more than 60° off the direction the stroke turned out to go. A clean
touch-down never travels off-course and is trimmed by nothing — pinned by a test. **Trimming only the
*backward* steps was not enough:** the kink where the path rejoins the stroke's line folds the mark
just as badly (pile-up 10 per pixel against 4 clean, against 13 before). The rule has to catch the
corner as well as the reversal. On Greg's own stroke the darkest connected knot went from 124 px to
52 px and the Y-shape cleared entirely.

**Fifteenth, and it reset the pencil (2026-09-02).** Greg came back to the device after the knot fix,
sketched with the tilt pencil for an evening, and rejected it whole: "Both the EPD and the baked
strokes don't look like pencil", the marks far too broad in an ordinary grip, and the tilt named as
part of the problem. His call was to step back to the basics of pencil sketching on this panel: **no
tilt, the stroke as thin as it will go, pressure kept**, one lead only, at what BOOX Notes labels
0.10 mm — and see whether a happy middle ground exists there at all.

Why fourteen measured findings converged on the wrong pencil is the thing worth keeping. Every one of
them measured the bake against the firmware's charcoal stamp — width curve fitted to it, overdraw
measured on it, density photographed against it — and each came out measurably right while the whole
came out wrong, because nobody had asked whether the charcoal stamp itself looked like a pencil.
**A firmware style is a target only if the artist has approved the firmware style.** The tilt curve
made it worse than it looked on paper: 1× was anchored at 9° from vertical, and nobody sketches at
9°, so an ordinary 30–45° grip drew every lead at three to five times its width from the tin.

The constraint that shaped the change: `TouchHelper`'s pen surface is style/colour/width and both
charcoal styles broaden with the lean inside the firmware, so tilt cannot come out of the live ink
without the texture coming out with it. Greg chose the plain even line (style 0) live, with the bake
supplying grain and pressure → darkness at pen-up — the pen-up change is now tone and texture, never
size. **g-paper 0.1.24** arms style 0 for `PENCIL`, gates tilt to zero on every model (`REPORT_TILT`,
the NoteAir5C measurement kept in the source), drops the 1.3× overdraw divide with the style it
corrected for, and caps a fleck at the lead's width — rendering a 1.2 px lead offline first showed
the 1.6 px darkest flecks baking it at more than twice the live line's width, the same PNG habit that
caught the first tin's flaws. Here, `Lead` is one entry, `HAIRLINE(1.2f)` = 0.10 mm at ≈12 px/mm;
the pencil button no longer opens a sheet while the tin holds one pencil; stored `FINE`/`MEDIUM`/
`BROAD` names read back as the hairline; tilt stays in the mark blob so pages from the three-lead
tin reopen at the widths they were drawn at. The first capture of the hairline (2026-09-03) showed
width holding and pressure carrying tone, but the heavy end read as a fine pen — because the ink was
black. Greg: "lighten up the color, like a #2 pencil". The pencil now inks in `GRAPHITE`
(`#505050`), set in `SketchbookActivity`; marks already on a page keep the colour they were drawn in.
Greg drew a tree with it and compared the captures: **"I like this."** — the first pencil on this
panel he has approved. Hairline, upright, plain line live, graphite grey, pressure carrying tone.
That is the reference everything after it is judged against, not the firmware's charcoal. He then
confirmed the last two items on the gate by hand: **strokes survive a close and reopen, and the
eraser end works.** G3 closed 2026-09-03.

- **Left for the user's hand, which is the whole gate:** every mark. adb cannot inject stylus ink —
  injected events carry toolType UNKNOWN and the engine drops them — and EPD pen overlays are
  invisible to `screencap`, so how graphite reads on this Kaleido panel, whether pressure spans
  usefully from ghost to solid, whether the pen-up pop between the firmware's live CHARCOAL and our
  baked grain is visible, whether the eraser end behaves, and whether marks survive a close and
  reopen are all Greg's eye and nobody else's. After the reset, three more: whether the firmware's
  plain line draws 1.2 px as thin as BOOX Notes' 0.10 mm or has a floor of its own; whether a pale,
  pressure-carried hairline reads as pencil on this Kaleido panel; and whether a live line that is
  uniform black turning grained and pressure-toned at pen-up is acceptable now that it never changes
  size.

---

### G4 — Pages
**Status:** 🧪 Awaiting device verification (code complete 2026-09-03; every adb-reachable step walked green; undo by hand, the finger taps and the pen-during-swap behaviour are Greg's)

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

**Answers.** 1. **Both.** Undo and redo buttons on the top bar, *and* the two-finger stationary
double-tap for undo and the three-finger one for redo — the pair the artist's hand already knows
from the Wacom app and from Notesprout. On BOOX the Onyx SDK intercepts three-finger touches and
cancels the sequence before `ACTION_UP`, so redo takes Paper v0's treatment: an armed, stationary
three-finger cancel counts as the tap. The buttons keep undo discoverable and adb-verifiable; the
taps keep the hand on the paper. 2. **A swipe past the last page adds a page** and lands on it, as a
real sketchbook has a next leaf until it does not. There is no add-page button anywhere: the only
way a page is made is at the end, which is also what keeps page order trivial. A blank page swiped
into by accident is harmless and undoable. 3. **A trash button on the top bar, and it asks first** —
"Delete page 3 of 7?" — then throws the page away as a soft delete that undo brings back, marks and
all, until the sketchbook closes. Deleting the only page leaves one fresh blank page rather than an
empty book.

**Outcome (code complete; the hand's half of the gate is open).** **133 JVM tests** (113 after G3),
`assembleDebug` green, installed on the NA5C, and the whole adb-reachable walk driven directly and
passed: a swipe forward on the last page appends a leaf and lands on it (1 / 1 → 2 / 2 → 3 / 3), a
swipe back turns back (2 / 3), undo of the append shows the page before it (2 / 2) and redo brings
it back (3 / 3), the trash asks "Delete page 3 of 3?" in a bordered dialog and lands on the page
behind (2 / 2), undo restores it, deleting down through the only page leaves one fresh leaf
(1 / 1) and three undos walk the book back to 3 / 3, the shelf's card reads "3 pages", reopening
lands on the last page shown, and so does a force-stop and relaunch. Empty crash buffer throughout.

- **Opus implemented against a written spec; Fable's review found one real defect before the panel
  saw it.** Undoing an added page hid whatever marks were still on it and the redo put the page back
  with an empty list — and the comment beside it claimed the opposite. Ordinarily nothing is on such
  a page (its marks sit above it on the stack and go first), but a history that overflowed past them
  would have turned an undo into a delete. The ids the store hands back now travel with the entry to
  the redo side (`AddedPage.hiddenMarkIds`, `DeletedPage.replacementMarkIds`), entries stay
  ids-only, and the file is never asked twice. Also: a chrome refresh could land on a screen already
  going away, from a replay's `finally` after the scope was cancelled — now guarded.
- **`showPage` is the only path that changes what the paper shows** — open, swipe, delete and every
  replay — so the contract (`clearForContentSwap` → `setPageSize` → `loadStrokes`, nothing between)
  is written once. It reads everything it needs first, then **waits for the pen** through
  `PenIdleGate.awaitIdle`, because `loadStrokes` under a live contact drops ink. The ledger stays
  empty: every frame G4 presents is behind the gate or the wait.
- **The store first, then the picture, on purpose.** g-paper's `addStrokes`/`removeStrokes` would
  be faster; they were not used because they patch the view independently of the rows, and the
  artist finds out the two disagreed a day later when the sketchbook reopens. Replays go through
  `SoilWriter.perform` — the same single queue as every mark write, so ordering holds — and
  exceptions come back to the caller, which puts the entry back on the stack it came off.
- **A mark captures its page at the commit.** `recordMark(stroke, pageId)` takes the page as an
  argument rather than reading the session's current page at write time, because a swap in flight
  moves that pointer before the write reaches the front of the queue.
- **The gesture observer is fed from `dispatchTouchEvent` and consumes nothing**, so a sequence that
  begins on a toolbar button is seen in order to be ignored — a button and the two-finger tap can
  never both fire for one touch. All three palm obligations from host-responsibilities are met:
  refuse to arm under the pen, re-check at finger-up, escrow undo/redo for one pen tail. The flip
  fires at the lift with a re-check and no escrow, since a sixth-of-the-panel horizontal haul is not
  a micro-tap a palm makes.
- **Two things the review deliberately let stand.** `refreshChrome` stands aside while `busy` and
  each operation calls it once at the end, so the bar never draws a state it is about to correct;
  the trash therefore has no greyed state and is protected by refusing the tap. And the "Opening…"
  overlay's hide is a pre-G4 frame outside the gate — effectively behind `awaitIdle` now, but not in
  the ledger; a G6 decision.
- **G2's `incremental_vacuum` debt was mis-assigned.** Nothing in G4 hard-deletes a row, so nothing
  frees a page and there is nothing to reclaim; the debt moves to whichever phase first does.
  Corrected in G2's outcome note above.
- **Opus's report caught a real pre-existing crash path:** `onDestroy` tore down `lateinit` fields
  on an `IndexGuard`-bounced launch, which the guard's own KDoc says every such screen must check.
  Fixed. It also flagged that `SoilDao.livePageCount()` counts every page in the file with no parent
  filter — correct while a `.soil` holds one sketchbook, and left alone.
- **Left for Greg's hand:** the two- and three-finger double-taps (adb cannot inject multi-touch
  here, and the three-finger case depends on the BOOX cancel rule that only the real SDK exercises);
  undo of a *drawn mark* and of an *erase* (no stylus injection); a mark drawn on page 2 turning up
  on page 2 after a swipe and a reopen; and whether a swipe or an undo landing while the pen is
  hovering waits rather than dropping ink. The test sketchbook "G4walk" is on the shelf with three
  blank pages for exactly this.

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
