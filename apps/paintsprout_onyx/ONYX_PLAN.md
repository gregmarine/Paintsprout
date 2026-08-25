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
  leaves — INCREMENTAL only makes reclaiming *possible*; **G4 owes the actual `incremental_vacuum`**,
  since G4 is the first phase that frees a page. And `ancestry` now stops at deleted rows, so a
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
  would have arrived as accidental colour on a Kaleido panel, now U+25C4/25BA which have no emoji
  mapping at all. Two smaller things came out of it: name rules moved from hardcoded English into
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

**Checked by the user on the panel:** the back gesture walks up a folder at a time and leaves the app
only at the root; the long-press to open a card's action sheet is right as it is. The cards were
**centred vertically and that read wrong** — reversed to top-anchored, which is the last change in
the phase and the one thing here that was settled by looking at the panel rather than by argument.

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
