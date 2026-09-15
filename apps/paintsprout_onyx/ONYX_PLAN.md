# ONYX_PLAN.md — Paintsprout Onyx

**Branch:** `main` (developed on `onyx`, merged 2026-09-15) · **Location:** `apps/paintsprout_onyx/` · **Package:** `com.symmetricalpalmtree.paintsproutonyx`
**Label:** Paintsprout Onyx (debug: "Paintsprout Onyx Dev") · **Version:** `0.1.0-onyx`
**Device:** BOOX NoteAir5C (NA5C) `92c16533` — **the only device anything installs to.**
**This file is the cross-session memory for the effort. Read it first, whole, at every phase start.**
**Arc 1 is closed. Arc 2 "Raster" is built through E1 and reviewed — § Phases — Arc 2 below.** The
raster experiment that decided it (R0–R5, verdict **yes** on 2026-09-14) is recorded whole in the
standalone `RASTER_PLAN.md`: its ledger is the primary record of what each phase found and is not
copied here. **Arcs 3 "Paper" and 4 "Coloured pencils" are ABANDONED (Greg, 2026-09-15).** Both
were planned on 2026-09-14; arc 3 was pinned the same day before a line of code, and arc 4's C1
was built, installed and measured on 2026-09-14/15 and then withdrawn whole — engine and host
reverted to g-paper 0.1.31, nothing merged, nothing published. Their sections stay below as a
record only. **The app stands where arc 2 left it: the hairline pencil, the rubbing eraser, stroke
and raster books.** Nothing is in flight; what comes next is Greg's call, with a clear goal first.

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
| Identity | Developed on branch `onyx`, merged to `main` 2026-09-15; `apps/paintsprout_onyx/` with its **own Gradle root**; package `com.symmetricalpalmtree.paintsproutonyx` (debug suffix `.dev`); label "Paintsprout Onyx" / "Paintsprout Onyx Dev" |
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
**Status:** ✅ Complete (commits `9e5967b` → the arrows-always-bright follow-up; gate passed by Greg's hand 2026-09-03 — all six checklist items, including both finger taps and the pen-hovering swap)

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

**Closed by Greg's hand, 2026-09-03: all six passed** — undo and redo of a drawn mark by button and
by the two- and three-finger taps (so the BOOX cancel rule holds on the real SDK), undo of an erase,
a mark staying on the page it was drawn on across a swipe and a reopen, a swipe under a hovering pen
waiting rather than dropping ink, and a drawn-on page coming back whole from a delete.

**One thing settled on the panel afterwards: the undo arrows carry no state.** The first build faded
them at 0.3 alpha when there was nothing to undo. But the moment there *is* something to undo is the
moment a mark was just made, and while the pen is armed for writing this device does not update the
display at all — so the arrow cannot brighten when the stack fills or dim when it empties, and an
arrow still dim after the first stroke reads as undo being broken rather than as the panel being
slow. Greg's call, matching BOOX's own apps: always bright, plain black glyphs, a tap with nothing
behind it does nothing. Recorded as a standing rule in `CLAUDE.md`, because every toolbar button
that would change state on a pen event has the same problem.

---

### G5 — Covers, pins and recents
**Status:** ✅ Complete (commit `32796a4`; gate passed by Greg's hand 2026-09-03 — all three checklist items)

`CoverSnapshot` on sketchbook close, written to the index as WEBP q100 and drawn on the library
card; the pinned overlay (sentinel list id, `ensurePinnedListExists` on library launch — never a
migration); the recents overlay; the sort control finished against all three views.

**Gate:** tests green; Haiku device walk — a sketchbook drawn in shows its own cover, pin and unpin
persist, recents order is right after several opens, sort applies in every view.
*Opus implements; Sonnet does the card and overlay layouts.*

**Questions to resolve at phase start:**
1. Which page is the cover — the first, or the last one touched?
2. Cover snapshot size and whether it renders through `StrokeRasterizer` or off the live view.

**Answers.** 1. **The last page shown** — the page on the glass when the sketchbook is put down, so
the card is a picture of where the artist left off. Chosen over a fixed first page and over "the last
page with marks on it"; a fresh blank leaf swiped into at the end therefore *can* become the cover,
and that is the honest picture of where the book was left. 2. **Offline, full page, shrunk to a
third.** `StrokeRasterizer` re-renders the page from its rows at the panel's full size, so the
hairline and the grain are the real bake, then a box average takes it down by exactly three to
620 × 827, stored as WEBP q100 on the index row. Rendering straight at a third would put a 1.2 px
lead and single-fleck grain below a pixel and the cover would be a likeness rather than the page.
It runs on the application scope behind the write queue, never on the UI thread, and never needs the
view — which also means a cover can be re-taken from rows alone if the renderer ever changes.

The library side follows Paper v0 exactly, as G0's locked answer requires: pinned and recents are
**modes of the shelf**, toggled from the left end of the bottom bar that G2 held open, the top bar
swapping the breadcrumb for a title and a ✕, sort applying to the pinned view and never to recents,
and recents kept in prefs as ids and timestamps — opening a sketchbook is not work, so it does not
move `updatedAt`.

**Outcome (code complete; the hand's half of the gate is open).** **146 JVM tests** (133 after
G4), `assembleDebug` green, installed on the NA5C, and the whole adb-reachable gate driven directly
and passed: Greg's tree from G3 became its card's cover on the first back-out, with the hairline
surviving the shrink as a faint grey line; the pin row pins, the badge appears, the Pinned shelf
lists it under its title with New folder / New sketchbook stood down and Sort still live; a second
pin and the mode itself survived a force-stop and relaunch; Name Z–A reordered the Pinned shelf and
Name A–Z put it back; Unpin emptied it down to *"Nothing pinned yet. Long-press a sketchbook to pin
it."*; two opens put Recent in the right order with *In Library* under each; a sort change left
Recent alone; a sketchbook made inside a folder arrived at the top of Recent reading *In One*;
deleting it from inside Recent took it off the list and out of the prefs file, which held nothing
but ids and timestamps throughout; and under "Last worked on", with Graphite renamed to put it
ahead, opening G4walk and backing out left the order exactly as it was. Empty crash buffer
throughout.

- **The first thing the panel showed was a cover that was there and not there.** The tree baked
  correctly on the first close and the shelf drew a blank card anyway — it was there on the *next*
  listing. Android resumes the shelf before it destroys the page (pause → resume the caller → stop
  → destroy), so a card written from `onDestroy` lands after the shelf has already listed. Fixed by
  writing the card *before* `finish()`: the arrow and the system back gesture both go through
  `leave()`, which writes page count, cover and last-edit stamp while the screen is still up, then
  finishes; `onDestroy` knows from `leaving` that the card is done and only closes the file.
  `onStop` still takes the card for the background-kill case. G4walk then showed its cover on the
  very first listing after backing out, which is the proof.
- **The write queue's close had a hole, and the cover snapshot is what would have fallen into it.**
  `SoilWriter.close()` was a drain followed by a cancel; a task accepted between the two never ran,
  and a `perform` caller waiting on it would have parked for the life of the process — survivable
  only while every caller lived on the screen's own scope. The cover render is a `perform` on the
  application scope, so it was the first caller that could actually be stranded. The close now
  shuts the channel and joins the pump: whatever is inside is let through, nothing is cancelled,
  and the file is sealed after the last of it has landed. Opus's Part B report found this; Fable
  fixed it.
- **`updatedAt` was not honest, and this is the phase where it had to be.** Every close bumped the
  index's "last worked on" stamp, so looking at an old sketchbook filed it as the newest work; and
  every page *turn* bumped the `.soil` row's own stamp too. With Recent arriving as the shelf for
  "opened but not worked on", the two shelves would have been the same shelf. The close now carries
  the file's own last-edit time forward with `touchIfNewer` (forward only — the index also moves for
  renames the file never hears about), and the open-page pointer is written by `setOpenPage`, which
  does not touch the stamp. Proven on the panel by the rename-then-open check above.
- **The cover is baked from the rows at full size and box-averaged down by three**, exactly as the
  answer to question 2 asked, and the hairline came through as a faint grey line on the card rather
  than vanishing. It runs on the write queue so the picture is of the page as it will reopen, marks
  still in the queue included. A blank page stores no cover; a failed render keeps the old one.
- **Pinned and Recent are modes of the shelf**, with `ShelfListing` (the three card sources) and
  `CoverLoader` (per-visible-card reads, bytes cached not bitmaps, decode bounded and on IO) lifted
  out so `LibraryActivity` stays under the line. Covers are read before the bind rather than painted
  in afterwards, because the grid is rebuilt on every bind and a bare-then-filled pass is two
  full-panel repaints for one page turn. `SketchbookActivity` crossed the ~800-line rule in this
  phase and carries a written reason at its top: what is in it is the order of calls, and the order
  is the correctness.
- **Paper v0's design was followed for the library and departed from for the cover.** Paper takes
  `renderToBitmap()` off the live view at 512 px on the long edge; here the cover is a bake of the
  rows at 620 × 827, which the plan's answer chose so a 1.2 px hairline survives, and which also
  means a cover can be re-taken from rows alone if the renderer ever changes. Paper's own library
  has the same resume-before-destroy ordering; whether its cover suffers the same first-listing
  blank is a question for that repo, not this one.
- **Watch item, not fixed:** every `onStop` — every press of Home — renders a full page (an 18 MB
  bitmap and a full re-render of every mark) whether or not anything was drawn, at exactly the
  moment the device is deciding what to kill. Correct, and the most expensive thing this app does on
  a path taken casually. A dirty flag on the session would skip it; left for G6 to weigh.
- **Not done, on purpose:** no `docs/library.md` (G6 owns the subsystem docs), and the frame-silence
  ledger is unchanged — nothing in this phase presents a frame while the pen is down; the cover is
  a file, not a frame.
- **Left for Greg's hand** (the gate's "a sketchbook drawn in shows its own cover" was passed with
  drawings already on the device; these are the parts only a pen can reach):
  1. Draw a few strokes and tap the back arrow *immediately* — the last stroke should be in the
     cover, because the render queues behind the mark writes.
  2. Leave a page with the system back gesture instead of the arrow — the cover should be on the
     card just the same.
  3. Whether the cover reads as the page at card size on this panel: the hairline is a faint grey
     line at a third scale, and whether that is legible or too pale is a matter for the eye.

**Closed by Greg's hand, 2026-09-03: all three passed** — the last stroke drawn is in the cover on
an immediate back, the system back gesture writes the card just as the arrow does, and the
third-scale cover reads as the page on the panel. G6 is next.

---

### G6 — Hardening and the verdict
**Status:** ✅ Complete (commit `aa214b0` + the closing commit; the pen half of the `gfxinfo` gate passed and **the verdict given by Greg 2026-09-04** — at the end of this section). **Arc 1 "Graphite" is closed.**

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

**Outcome (audit walked, hardening in, docs in; the verdict is open).** **146 JVM tests** (unchanged
— nothing added in this phase is pure logic that a desk can exercise), `assembleDebug` green,
installed on the NA5C, the adb-reachable half of the gate driven directly and passed, crash buffer
empty throughout.

**The six-point data-loss audit**, walked against this source, claim by claim. The full text is in
`docs/crypto.md` § "Data-loss audit"; the short form:

1. *Every open path wrapped* — true, and **the reason written in the code was wrong.** `Room.databaseBuilder`
   appears at two sites, both taking a `SoilCrypto` factory wrapped in `NonDestructiveOpenHelperFactory`.
   But reading `sqlcipher-android` 4.6.1's bytecode showed that SQLCipher's `SupportHelper` builds an
   inner `SQLiteOpenHelper` that forwards every androidx callback *except* `onCorruption` and passes a
   **null** `DatabaseErrorHandler` — so the wrapper's override is unreachable. What actually spares a
   mis-keyed file is SQLCipher's `DefaultDatabaseErrorHandler`, whose first act after logging is
   `if (hasCodec()) return`. G1's md5 measurement of a wrong-key attempt was measuring *that*, not the
   wrapper. The wrapper stays (it is the belt for any future non-SQLCipher factory) and its comment now
   says what is true. **Paper's `docs/crypto.md` makes the same wrong claim** — a note for that repo.
2. *No create-capable open outside the named doors* — true. Two doors (`createRaw` → `SoilDatabase.create`
   → `createSketchbook`; the index's `Invalid` branch), both refusing existing files; every other open
   passes `requireExisting` or a read-only verify first. Noted: a 1–15 byte index probes `Invalid` and
   would be created over — nothing that short is a database.
3. *A missing file never loops into unlock* — true. Missing `.soil` → "not here" dialog; empty `.soil` →
   "would not open" dialog; missing index → first-launch create, never Unlock. Noted: an index deleted
   out of band leaves intact, invisible orphans in `Garden/`.
4. *Delete invalidates the key cache* — true, both tiers, for single and recursive deletes and for a
   failed create; index row stamped before the file goes. Noted: a background warm can re-cache under a
   dead UUID, harmlessly.
5. *No passphrase in logs, intents or ordinary prefs* — true. Every `Log.*` call listed; extras are ids,
   types and display names on in-app explicit intents; the clipboard copy is at the artist's request.
6. *No display names in prefs* — true. Ids, enum names, `RecentEntry(id, at)`.

**What the audit changed, beyond the comment:**

- **The cold open of a `.soil` no longer hands Room an unverified key.** `KeyOpener` used to open a
  cache-miss file with the passphrase (SQLCipher's own KDF inside the open) while a second KDF warmed
  the cache — two derivations for one open, and the only Room open in the app made with a key nobody
  had checked. It was survivable only because of the codec guard in point 1, which is a fact about a
  dependency's default. It now derives once, verifies read-only, caches, and opens with the raw key —
  same wall-clock, one KDF fewer, and **"Room is never handed a key that has not first opened the file
  read-only" is now true everywhere**, which is a guarantee this app can own. A new standing rule.
- **A failed cover render no longer clears the cover.** `CoverSnapshot.render` returned null for both a
  blank page and a failure, and the caller stored null for either — so an out-of-memory on the
  full-size bitmap, on a device that runs short of it, would have wiped the card's picture. `Cover`
  is now `Blank` / `Image` / `Failed`, and only the first two are stored.
- **The first page failing to show no longer crashes the app.** `openSketchbook` caught a file that
  would not open but not a page that would not load; that exception escaped a coroutine and took the
  process down, which on the panel is a sketchbook that "crashes when opened" and invites the wrong
  remedy. It gets the same dialog as any file that will not open.

**G5's watch item is closed: nothing is written when nothing changed, and that is decided on the
write queue.** Every Home press used to render the full page — an 18 MB bitmap and every mark on it —
at exactly the moment this device decides what to kill. `CardKey` (the page on the glass + the
sitting's edit count, bumped by `touchSketchbook`) is read *inside* `SoilWriter.perform`, behind every
queued mark, and `renderCover` answers `Unchanged` when it matches the last card the index took. The
placement is the whole point: a key read off the screen thread lies in precisely the case that matters
— a stroke still in the queue — and would drop the last stroke from the cover, which is G5's checklist
item 1. The key is recorded only after cover, count and stamp have all been written. **Proven on the
panel** with `KEYCODE_SLEEP`/`WAKEUP` driving stop and resume on one session: the first stop wrote the
card (`Image`), the next two idle stops wrote nothing, a page turn then a stop wrote it again, and the
back arrow after that wrote nothing and landed on the shelf.

**The frame-silence ledger** gained two rows and still holds no real exception: G5 (none — the cover
is a file), and G6's decision on the "Opening…" overlay G4 had flagged: it is hidden in the same
main-thread continuation as the swap, with no suspension point after `awaitIdle` returns, so the pen
cannot arrive in between. Written down as *not an exception* rather than left as an open note.

**`dumpsys gfxinfo`, finger half:** after a reset, four forward swipes (three of them appending a leaf),
two back swipes, two undo taps and one redo tap produced **13 frames, 1 janky (89 ms), 50th percentile
15 ms** — one frame per action, no storm. The pen half needs a hand and is on the checklist below.

**The subsystem docs are in:** `docs/data.md`, `docs/crypto.md` and `docs/library.md` written by Sonnet
against the finished source and fact-checked line by line by Fable (four corrections: marks record
`#505050` since the hairline, not `#000000`; the colour list; a `windowLightStatusBar` embellishment; a
toast count), and `docs/sketchbook.md` finished by Fable — the ledger rows, the shelf's card, the
cover rule.

**Three device lessons, recorded in `CLAUDE.md`:** after `KEYCODE_WAKEUP` the window is covered again
for ~4 s and input injected in that gap is lost (g-paper logs a close/reopen of the raw pipeline and
the screen gets a second stop/resume) — wait ~10 s; a launcher-style `am start` while backgrounded
stacks a fresh `LibraryActivity` rather than resuming the page, so sleep/wake is the way to drive
`onStop` on one session; and verify the resumed activity after every screen-changing tap, not only
after launches — a whole sleep/wake sequence was once driven against the BOOX launcher before the
check caught it.

**Not done, on purpose:** `incremental_vacuum` is still owed by whichever phase first hard-deletes a
`.soil` row (none in arc 1; unpin hard-deletes an *index* edge, and the index was never stamped
INCREMENTAL); a card whose file has gone stays on the shelf showing its dialog on every tap, as in
Paper; the three "noted" items in the audit stand as written. The Wacom app owes two fixes found here
(lean and tangent smoothing; connected-geometry grain) and one corrected claim (Paper's audit text).

**`dumpsys gfxinfo`, pen half — passed 2026-09-04.** Greg drew for about a minute with a few erases
after a reset: **26 frames in total**, about one per committed stroke plus the open — no storm. The
frames are slow (50th percentile 57 ms, worst 300 ms) because every pen-up bakes the whole page again
through the software renderer, a cost that grows with the marks on the page. That is g-paper's
committed-render path, not this host; recorded as a **watch item for the engine** (an incremental
bake), not a defect of the phase. Crash buffer empty.

---

#### The verdict

The experiment asked one question: **what does g-paper on an Onyx e-ink panel give Paintsprout?**
Greg's answer, given 2026-09-04 after drawing with the finished build: *"I like the look and feel.
I'd say this exercise was a success. The Kaleido is fine for this pencil sketch stuff. The eraser is
fine for now too. I feel good about this."*

**Does graphite through g-paper on an Onyx panel feel like pencil on paper?** *Yes — for one pencil,
and only once the firmware's own idea of a pencil was taken out of the loop.* Fourteen measured
findings tuned a tilt-driven, three-lead pencil against the firmware's charcoal stamp, each one
measurably right, and Greg rejected the result whole after an evening's sketching: "Both the EPD and
the baked strokes don't look like pencil." The reset — one hairline lead, no tilt, the plain even line
live, pressure carrying tone, inked in `#505050` because the first capture "read as a fine pen" in
black — was the first pencil on this panel he approved ("I like this"), and a day of drawing with the
hardened build confirmed it: the look and the feel both. That pencil is the reference for anything
that follows. What it is not, yet, is a pencil that can be laid over: the side-of-lead regime needs a
tilt number g-paper will not publish without a per-model measurement.

**What did the Kaleido layer cost?** *Nothing the artist could see at this pencil.* The NA5C's colour
filter sits over the mono layer; arc 1 draws greyscale so the graphite renders on the crisp layer
beneath it, and the one design decision it forced — broken coverage rather than tonal shading,
because a panel with a handful of greys behind a filter dithers any continuous grey it is handed —
turned out to be the right pencil anyway. Greg's word: the Kaleido "is fine for this pencil sketch
stuff." Whether it stays fine under colour is arc 2's question, if arc 2 is colour.

**Where did the live firmware charcoal and our baked grain disagree?** *Everywhere that was measured,
and the measuring was the mistake.* Width (the stamp scaled 5×), overdraw (1.3×), density (0.68×),
the grain's direction, its connectedness, the "pipe cleaner" (raw tilt and a raw tangent turned into
geometry), chisel ends, a hook, a wedge, a bead, a knot at the start of broad strokes — every one
documented and fixed in g-paper, fifteen releases from 0.1.7 to 0.1.24. The finding that outlives
all of them: **a firmware style is a target only if the artist has approved the firmware style**, and
nobody had asked whether the charcoal stamp looked like a pencil. With the plain line live the two no
longer disagree about *size* at all; the pen-up change is tone and texture only, and it is settled.

**And the eraser?** Arc 1's stroke eraser is the one deliberate break with Paintsprout's WYSIWYG
rule — it takes whole marks rather than rubbing graphite away — and it was expected to be the thing
the hand noticed first. Greg: "fine for now." It stays on the candidate list, not the defect list.

**What does g-paper give Paintsprout on this panel?** *The pen, the palm, the EPD pipeline, the eraser
end, and the discipline.* Fifteen engine releases landed inside one phase without a single host-side
workaround, which is the standing rule proving itself; the hardware eraser end erased "whichever tool
is armed" with no code written here at all; the frame-silence rule and the pen-idle gate held through
page turns, undo and covers with an empty ledger of exceptions; a minute of sketching costs 26 frames.
What it costs: the live ink is a firmware black box (`TouchHelper` offers style, colour and width,
nothing else — so a textured live style cannot be had without its tilt response, which is what forced
the plain line), live ink is invisible to `screencap` so every look at a stroke is a camera or a bake,
each pen-up re-bakes the whole page (the engine's watch item above), and the panel's refresh model
dictates the shape of every piece of chrome (no state on buttons, no toast that explains a failure).

**What would arc 2 have to be?** **Undecided, and deliberately so.** The candidates the plan recorded
on purpose stand: side-of-lead shading (a per-model tilt phase in g-paper, the pencil's missing half);
a rubbing eraser (a partial-erase model g-paper does not have); paper tooth (`paperKind` already has a
home); colour on Kaleido; true size and rotation; durable undo; export and backup. Fable's
recommendation was the rubbing eraser; Greg has not chosen, and the experiment does not need him to
today. **The verdict is that the experiment succeeded** — graphite through g-paper on this panel is a
pencil the artist wants to draw with — and that is the answer arc 1 was built to get.

**What came next (2026-09-03/06):** not an arc — a **raster experiment**, planned with Opus and
reviewed by Fable, in the standalone `RASTER_PLAN.md`: a per-book raster mode beside strokes with
a pixel eraser, formalised as arc 2 only if its own verdict says a raster page feels truer. **It
did** (2026-09-14, Greg: *"raster rocks!"*), and arc 2 is below.

---

## Phases — Arc 2 "Raster"

**Lifted from `RASTER_PLAN.md` on 2026-09-14, the day the experiment closed yes.** That file stays
as the experiment's record — decisions with their reasoning, review amendments A1–A8, design
D1–D6, and a ledger with what every phase measured and what the hand said. This section is the
binding summary and the home of arc 2's own phases. Where the two disagree, this file wins; where
this file is silent, that one speaks.

### What arc 2 is

A **raster page** beside the stroke page: a sketchbook is stamped at creation as *Strokes* (marks
the artist can take back whole) or *Raster* (graphite they rub off), and never changes mode. Raster
is the **default** since the verdict; Strokes stays as the second choice. A stroke book can be
**baked** into a raster copy beside it, one way, never in place. Both modes share g-paper's
firmware live ink, palm gate, EPD handoff and the approved arc-1 pencil bake; they diverge only
after pen-up — persist, erase, undo, cover. Raster ink is still arc-1 graphite: the hairline lead,
`#505050`, pressure carrying tone. This is a storage-and-eraser change, not a paint feature; arc 1's
non-goals stand (no layers, no paint, no surfaces, no zoom, no export…) except where a later arc-2
phase lifts one by name.

### The decisions that bind (Greg, 2026-09-03; Fable's amendments 2026-09-06 — argued in `RASTER_PLAN.md`)

1. **Dual-mode, runtime, per-book;** the mode in bit 0 of the sketchbook row's `flags`, read once at
   `SketchbookSession.open`, mirrored nowhere (not the index, not the meta row).
2. **Hybrid in g-paper:** `PageMode.RASTER` is a first-class page mode of the engine, never a
   host-side surface. Engine gaps are g-paper phases (`PLAN.md` there, `GPAPER_VERSION` bump,
   `publishToMavenLocal`, three pins here).
3. **The raster is an alpha layer over the paper.** Unmarked pixels transparent; the eraser clears
   **to transparent**, never white, so paper tooth can one day sit under it.
4. **One image per page, on its own `raster` child row at `order = -1`**, PNG, overwritten in place
   on save (copy-and-submit through the serial `SoilWriter`, encoded on the queue; the guard
   `RasterRows.fitsPage` runs before any decode and a refused row is tombstoned, never overwritten).
5. **Undo is in-session and bounded by bytes** (48 MB beside the 100-entry cap): one contact = one
   `Edit.RasterChanged` holding the before-image on a 64 px cell grid, each cell read once per
   contact; `swapPageRaster` leaves the array holding what was there, so one entry serves both
   directions; a raster replay must **not** `showPage` after the swap.
6. **A cover is the stored page composited over paper white**, then the G5 shrink-by-three.
7. **The bake is a copy.** Source opened, read, sealed before any bitmap; no write of any kind to its
   rows or index row; transparent page bitmap; one bitmap alive at a time; index row last, discard
   on `Throwable`; folder path from the index. `RasterBake` pure, `BakeSketchbook` Android.
8. **The hard pixel eraser was first**, shown live per batch as far as the panel allows (R1). The
   **rubbing eraser** is arc 2's first phase, gated behind the verdict and now open.

### Experiment phases, closed (details and numbers in `RASTER_PLAN.md` § Ledger)

| Phase | What | Closed | Commits |
|---|---|---|---|
| R0 | The raster page in g-paper (0.1.25): `PageMode.RASTER`, composite-and-drop at pen-up, `getPageRaster`/`loadPageRaster`, will-change/changed callbacks | 2026-09-06 | g-paper `5c66992`; host on `onyx` |
| R1 | The pixel eraser in g-paper (0.1.26): the sweep clears the raster to transparent, regional repaint per batch; the SDK's pen-up list dropped when the contact streamed (reaches stroke mode too) | 2026-09-06 | g-paper `855f483`; host `01d40bd` |
| R2 | Persistence in the host: flags bit, `raster` row, 3 s debounce, save on pause, read through the writer | 2026-09-14 | `e9ddc36` |
| R3 | Undo (swap, 64 px grid, 48 MB) and covers (over white) — g-paper 0.1.29 | 2026-09-14 | g-paper `d0bc484`; host `aabb355` |
| R4 | The mode picker and the one-way bake | 2026-09-14 | `fe1defc` |
| R5 | The verdict: *"raster rocks!"* | 2026-09-14 | `359cf19` |

**Findings that outlive the experiment:** the software rasteriser lays the hairline ~40 % paler than
the panel's hardware bake and Greg preferred the paler tone (R0); a fast eraser sweep's chord back
to the start came from the SDK re-delivering the whole contact at pen-up (R1); a slow scrub reports
dozens of overlapping batches, which is why the before-image lives on a grid (R3); and **every
`SoilDatabase.open` rewrites page 1 of the file twice before any read** — the key verification's raw
open and Room's open, consistent with a WAL→DELETE→WAL journal-mode flip — pre-existing since G1,
rows untouched, recorded for the arc-2 code review (R4).

### Arc-2 phases

Phase letters: **E**. The arc-1 recipe binds (one phase per session; the phase-start wizard; Fable
plans, writes engine phases and every walk; Opus builds host phases on a Fable brief; Sonnet
layouts, strings, docs; Haiku adb walks; the user's short numbered checklist for the hand). The
`/code-review` over the whole R range is **owed and deferred by Greg's call on 2026-09-14**; it
runs before arc 2 freezes, not before E1.

#### ✅ E1 — The rubbing eraser (commit `7d899a0`; g-paper `ba8ee8b`)
**Owner:** Fable (a g-paper phase: the partial-erase compositing model), then a host pin.

A real eraser rubs graphite off the tooth; it does not cut a hole. The sweep **lightens** what it
crosses progressively — alpha reduced per pass, pressure-weighted — rather than clearing it, so a
light pass softens a line and a few firm passes take it out. The hard eraser proved the pipeline
(R1) so this is tuned against a known thing, not the Phase 10/11 mistake again. Everything the R1
eraser has stays: the corridor along the sweep, the regional repaint per batch, the R3 grid undo
(a rub is a `RasterChanged` like any other contact).

**Decided at phase start (Greg, 2026-09-14):** one pass lifts **pressure-weighted, about a quarter
at a light touch to about 60 % firm**, and dwell counts (rubbing back and forth in one contact keeps
lifting); the corridor's edge is **feathered**; **the rubber replaces the hard eraser** — no second
tool. His notes from the sitting, the phase's opening brief: the hard eraser *cut holes rather than
lightened*; *the corridor's edge showed*; and **the rubber is too large** — "the impacted area was
too large, I'd like to make it smaller" (the host's `ERASER_RADIUS_PX` is 18 px, a 3 mm rubber; this
phase brings it down and the hand judges the number).

**What landed (2026-09-14):** g-paper **0.1.30**, Phase 18 there —
`RasterRubbing(liftLight = 0.25, liftFirm = 0.60, feather = 0.5)` on `PaperView.rasterRubbing`;
pure `geometry/RasterRub` (coverage profile, pressure→lift, reversal, `rubBatch` raising a
page-sized byte **pass mask** and scaling alpha by `(1 − new) / (1 − old)`; 13 JVM tests, 213
green); `eraseRasterAlong` is now read-rub-write on the batch rect with the batch's mean pressure,
the `CLEAR` paint gone; `beginEraseSweep` drops the pass. Host: three pins to 0.1.30,
`ERASER_RADIUS_PX` **18 → 12 px** (a 2 mm rubber, both kinds of book), `docs/sketchbook.md` § The
rubbing eraser, a `CLAUDE.md` rule. No host logic changed — the per-batch calls the undo grid rides
on are the same.

**Gate:** the hand — a line softened in one pass and gone in a few; a page of shading lightened
evenly; the eraser end of the pen rubs; undo takes a rub back whole; `screencap` before/after a
single pass shows the corridor's alpha reduced by the agreed fraction and nothing outside it moved.

**Outcome (Greg's hand, 2026-09-14):** *"I'm impressed. This eraser feels fantastic!"* And on the
radius, which both kinds of book share: *"The stroke eraser feels better now as well. I think making
it smaller was the right call."* Nothing tuned; the phase-start numbers and the 12 px rubber stand.
The `screencap` half of the gate was not taken — the hand answered the question it was there to
ask. Left open: whether the pen's eraser end reports pressure (unmeasured; the guard makes an
unreported pressure the middle lift).

**Next:** the R-range `/code-review` ran (below). Paper tooth and colour on Kaleido became arcs 3
and 4 on 2026-09-14; side-of-lead shading stays the one unscheduled candidate.

#### ✅ The arc-2 code review (2026-09-14)

Run by Greg's call after E1 closed, over the host range `d9167a4..817ed1a` (R0–E1) with g-paper
Phases 13, 14, 17 and 18 read alongside; eight finder angles, verified by Fable against the code
before anything was changed. **Fixed in this pass** (host commit below; g-paper 0.1.31 `f2bcc2e`):

- **A raster undo that waited for the pen while a new mark landed** swapped the before-image over
  the new mark's cells and left the new entry holding the undone mark. Now `applyEdit` re-checks the
  stack's generation after `awaitIdle` and, if it moved, puts the entry back *beneath* the newer
  ones (`putBackBeneath`) and does nothing; the arrow is tapped again. Stroke books never had the
  bug — their replay re-reads rows.
- **A page thrown away while a save was queued** could get a fresh live picture row under a dead
  leaf (the pen is not stopped by the confirm; a mark between the flush and the tombstone dirtied
  it, and the turn away saved it). `saveRaster` now refuses a page whose row is deleted.
- **A half-gathered undo entry carried across a page turn** (a contact whose pen-up never came:
  panel asleep mid-sweep) would collect the next leaf's cells under the old page's id. `showPage`
  drops `rasterEdit` on a page change.
- **`OutOfMemoryError` is an `Error`**, and three catches written for it caught `Exception`:
  `RasterImage.decode` (a page-turn crash instead of a blank leaf), the `SoilWriter` pump (a dead
  queue: every later `perform` waits for ever, the close never folds the log), and
  `createSketchbook` (an orphan file). All three catch `Throwable`; `flushRasterSave` guards its
  page-sized copy the same way and keeps the page dirty on failure.
- **The bake's `setCover` sat inside the try after the index row**, so a cover that failed to write
  deleted the file and left the card — the exact lie the ordering exists to prevent. Moved after the
  try, best-effort.
- **`BakeCommand.running` armed before `show()`**: a window gone by the time the confirm fired
  would have left every later Bake silently refused. Armed only once the dialog is up.
- **A page with no recorded size had its picture tombstoned on every open** (the guard cannot
  judge without a rectangle). Now left alone and not shown, with a log line.
- **`saveRaster` read the whole previous PNG to learn one id** on the queue every other write waits
  behind; a `rasterRowId` query reads the id.
- **A rubbed-out leaf never read as blank**: a lift is a ratio and never reaches zero, so ghosts of
  alpha 1–2 lingered and the cover showed a smudge. g-paper 0.1.31 lets a pixel go below alpha 3.
- **Docs and rules that contradicted the R3 exception**: the "`showPage` is the only thing that
  changes what the paper shows" rule and "the DB is the source of truth" now state the raster
  exception; `docs/sketchbook.md` said "default Strokes".

**Recorded, not fixed — candidates for a later phase**, in rough order of worth:
1. **Per-segment dirty rects for a composited stroke** (g-paper): the pen-up composite reports the
   stroke's whole bounding box, so a corner-to-corner hairline makes the host read every 64 px
   cell of the page (~18 MB) on the main thread inside the pen-up callback, and two such strokes
   fill the 48 MB budget. The eraser already reports per batch; the composite should too.
2. **`loadingRaster` is a host workaround for an engine inconsistency**: `swapPageRaster` fires
   nothing (host-made change) but `loadPageRaster` fires the will-change/changed pair; it holds
   only because the callbacks run synchronously. A g-paper phase makes the load silent and the
   flag goes.
3. **Mirror the raster flag in the index row** (`SketchbookFlags`, beside `paperKind` and the
   cover): removes "Bake offered on every card", the *Already raster* dialog, and gives the shelf
   a mode it can show. Greg decided "no mirror" in R2; worth re-asking now that the shelf wants it.
4. **One make-a-`.soil` shell** for `createSketchbook` and `bakeSketchbook` (they already diverged
   once, on the catch), one `readMarks`, one cover-encode tail in `CoverSnapshot`, a consuming
   `CoverSnapshot.render(Bitmap)`; `RasterBake.digest` could use `RawKeyDerivation.toHex`.
5. **`Edit.markIds` / `restorePage(markIds)` now carry the raster row's id** under a mark-named
   field since `liveChildIds` widened; rename to `childIds`.
6. **The flush-before-store-change rule is enforced by hand at six sites**; owning it in the
   session would make it one line.
7. Smaller: `scheduleRasterSave` allocates a coroutine per erase batch; `BakeCommand` pins the
   shelf's window for the bake's length; `rasterCover` holds bitmap + array (~36 MB) on the way
   out; the page row is read three times per turn; `UndoRedoStack.undoBytes` is a hand-kept
   total; `RasterEditBuilder.tooBig` is a belt the grid makes unreachable; commit `fe1defc`'s
   subject is 127 characters.
8. **The open-path byte flip** (R4 finding): every `SoilDatabase.open` rewrites page 1 twice before
   any read — the verify open and Room's open, a journal-mode WAL→DELETE→WAL flip. Benign; verify
   with the WAL flag or accept and say so.

**Dismissed:** "the raster undo entry lags one contact because the SDK's pen-up list arrives after
the end callback" — g-paper documents the end callback firing after the batches, and R3's hand
undid the last mark drawn.

*(Of the later candidates, paper tooth is arc 3 and colour on Kaleido is arc 4, both below.
Side-of-lead shading — the per-model tilt phase in g-paper — is the one still unscheduled. Arc 2
is closed: lifted, reviewed and built through E1; the "freeze" the raster plan named was only
the end of that sequence, and nothing remains of it.)*

---

## Phases — Arc 3 "Paper" — ❌ ABANDONED (pinned 2026-09-14, abandoned with arc 4 on 2026-09-15)

**Planned with Greg on 2026-09-14, the day E1 closed**, in one sitting with the colour arc that
follows it. Paper goes first so that the coloured leads are tuned once, against the sheet they will
actually be drawn on, rather than on white and then again.

### What arc 3 is

A sheet of warm, toothed sketch paper under the pencil, in both kinds of book. The paper is a
**layer of its own beneath the ink** — g-paper's template slot — so nothing the rubber does can
reach it. The pencil's flecks land where the sheet's crests are: a light touch skims the crests,
pressing fills the valleys. The rubber takes the crests first and leaves a ghost in the valleys
until a firm scrub. **One recipe drives what is seen and what is felt.** Sketchbooks already on the
shelf were made on blank white and stay that way for life; every new sketchbook is made on the
paper. One paper this arc; a second surface is a later arc and brings the picker with it.

This lifts arc 1's "no surfaces, no tooth field" non-goal by name. It does not lift any other.

### The decisions that bind (Greg, 2026-09-14)

1. **Both kinds of book.** Tooth reaches the raster page and the stroke page alike; both share the
   arc-1 bake, so both share the sheet.
2. **Felt and seen, and the paper is its own layer.** A page-space tooth field the grain samples,
   *and* a visible fibre texture — drawn beneath the ink, never part of it, so the eraser cannot
   touch it. Arc 1 kept the paper plain because two grain systems would fight over the panel's few
   greys; that fear is now a risk to measure (P4), not a reason to stop.
3. **Warm off-white, like the Wacom app's Paper** (`#F6F1E7`, fibre). It draws on the Kaleido colour
   layer; what that costs is measured on the panel, with a neutral sheet one constant away.
4. **Stamped at creation.** `paperKind` on the index row, written when the book is made and never
   again. Existing books keep `BLANK`; new books get `PAPER`; **no question on the New sketchbook
   screen** — with one paper there is nothing to choose, and the question arrives with the second
   surface.
5. **One field for both.** The fibre you see is the tooth the pencil catches on. The Wacom app keeps
   its visual tile and its tooth as two unrelated noise fields; here a mark sits *in* the paper.
6. **Tooth API in g-paper, recipe in the host.** g-paper gains a generic page-space tooth field that
   the grain and the rubber sample — an engine phase, under the standing rule. Paintsprout owns the
   recipe: what *this* paper is, generated as one seeded field and handed in as both a template
   bitmap and a tooth field. Nothing Paintsprout lives in the engine.
7. **The rubber reads the tooth.** Valleys keep a ghost until a firm scrub. A change to g-paper's
   `RasterRub`, gated so a blank book rubs exactly as E1 did.
8. **Covers over the paper**, not white — the card is a photograph of the real page.

### The design

**The engine's tooth field.** `core/model/ToothField(texels: ByteArray, width, height, pitchPx)`
with `at(x, y)` — 0 a valley, 1 a crest, nearest texel, wrapping — and a precomputed table of
`E[t^b]` so the grain can normalise against the sheet it was given. Page-space at 2 px per texel is
930 × 1240 on the NA5C, about 1.15 MB; wrapping keeps a tile legal for any host that wants one.
`PaperView.setToothField(ToothField?)` sits beside `setTemplate`, and the Onyx override wraps it in
the EPD handoff for the same reason: it changes the bake of everything on the page.
`StrokeRenderer.draw`, `StrokeRasterizer.draw` and `GraphiteGrain.of` all take the field as a
defaulted parameter, so **null is today, byte for byte**, at every one of the five call sites.

**How the grain reads it: place is the paper's, tone is the pencil's.** Today every fleck's
position comes from a hash of the stroke's own id, station and lane — two strokes crossing the same
spot of the sheet never share a peak, because there is no sheet. In `deposit` and `tap` the fleck's
jittered position is computed *before* the coverage gate (a pure reorder), the tooth is read there,
and the existing hash draw gates on `cover × catch`, where `catch = t^bias / E[t^bias]` and
`bias = TOOTH_BIAS × (1 − press)`: a feather touch sees `t³` and lands on crests only; full pressure
sees `t⁰` and floods. The division by the sheet's mean is the point — **the tooth redistributes
where graphite lands, never how much**, so the approved pressure→darkness ramp does not move
because paper arrived. That is the arc-1 discipline (a correction aimed at one axis stays flat on
the others) applied before the fact rather than after. Darkness stays on the hash. Stations, lanes,
jitter and the fleck cap are untouched, so a live prefix still matches the committed whole.

**How the rubber reads it.** One factor in `rubBatch`: `shelter = 1 − valleyShelter × (1 − t) ×
(1 − pressure)`, default 0.7. A light pass takes the crests and leaves a ghost that *is* the
tooth's pattern in negative; a firm scrub takes everything, as E1's numbers promise. Pass mask,
reversal, seams, the alpha floor, the host's 64 px undo grid: unchanged.

**The host's recipe** (new package `paper/`). `PaperRecipe` is pure Kotlin and JVM-tested: one
field from a **constant seed** through a ten-line xorshift — never `kotlin.random`, whose bits may
change under a Kotlin bump and re-roll every sheet ever made. Fine white noise at one texel (the
tooth's high frequency), two octaves of value noise for blotch, a few thousand short faint fibres,
rank-normalised to a flat [0, 1]. **Two px per texel is about 0.17 mm** — fine cartridge paper;
cold-press at 0.3–0.5 mm is a later surface. A recipe once shipped is never edited under its name:
a stroke book re-renders from rows, so a changed recipe under an old name would move every fleck on
an old drawing. `PaperField.toTooth()` wraps the **same array**. `PaperSheet` turns the field into
the template: RGB_565, generated straight at page size (1860 × 2480, about 9 MB) so the engine's
stretch is 1:1, warm base with brightness `1 + AMP × (2t − 1)`, `AMP` starting at 0.06; Kaleido
shows 4096 colours, so more than 565 is wasted. One sheet per process, generated on IO, cached on
the application, dropped on `onTrimMemory`. **One global seed**: a pad is one stock, covers and
bakes agree by construction, and there is nothing to persist but a name.

**Persistence.** The `.soil` gets one `paper` row — the family shape that has sat unused since G1
(`TYPE_PAPER`, `text = "PAPER"`, no blob: the sheet is code) — and each page row's `refId`, which
the schema already documents as "this page's paper", points at it. `SketchbookSession.open` reads
it beside the sketchbook row, once, the way it reads the raster flag; `addPage` copies it onto new
leaves. The index's `paperKind` is the shelf's mirror, written at creation only. An old book has no
row and reads `BLANK`: null field, no template, the same bytes as yesterday.

**Wiring.** The sheet and field are set **once, inside the first `showPage`**, between
`clearForContentSwap` and `setPageSize` — the documented sequence — in the same synchronous run as
the swap, so the EPD handoff coalesces into the refresh the open already pays; later turns set
nothing, and page turns cost what they cost today. Live ink stays the firmware's line over the
template; the pen-up pop grows by the tooth's gaps, which is a finding for P4 to write down. The
bake gives the copy its own paper row and passes the field to `StrokeRasterizer`, or the copy
would lose the look it was drawn with. Covers composite over the paper's **base colour** rather
than white: at a third scale a ±6 % texture on 2 px texels averages to ±2 %, below the eye, and the
close is the path already flagged for memory; the real sheet is one argument away if the card
shows a difference. The blank card for a paper book draws the same flat colour. `Edit`, the undo
grid and the gestures are untouched.

### P1 — The tooth in the engine (g-paper Phase 19 → 0.1.32) ⬜
**Owner:** Fable — the engine seam and the determinism contract. Sonnet for `docs/api.md`,
`host-responsibilities.md` and g-paper's `CLAUDE.md`, **including the stale claims found while
planning**: `CLAUDE.md` still says `LEVELS` is 3, `docs/api.md` still describes `CHARCOAL_V2` and
the 1.3× divide, `integration-guide.md` still pins 0.1.22.

The field, the sampling in `deposit` and `tap`, the defaulted parameter through all five call
sites, `setToothField` with its Onyx override; and **a JVM preview writer** in the test source set
(`java.awt.image` to `build/previews/*.png`, a disc per fleck, behind a system property so it
never slows the suite) rendering BLANK against a sine field and a white-noise field at five
pressures on the hairline and on a 6 px lead — the offline-PNG habit that caught the first tin's
flaws finally gets a home. Publish; re-pin the host's three coordinates with no host change.

**Tests:** a **null-field golden** — a checksum of the grain arrays for three fixed strokes, so
that changing today's bytes is a deliberate act; the same field twice identical; a different field
different; the prefix stable with a field; crests catch more than valleys at light pressure and
equally at full; mean coverage within 3 % of BLANK at every pressure; fleck width unchanged; the
wrap and pitch arithmetic of `at()`.

**Gate:** suite green; the previews looked at *at 1×*; and a `screencap` of an existing drawing on
the NA5C **identical** before and after the pin — the on-device proof that BLANK did not move.

**Questions to resolve at phase start:**
1. Preserve mean coverage (recommended), or let paper physically lighten a light touch?
2. Nearest texel or bilinear sampling? (Recommend nearest; the jitter already breaks the grid.)
3. Should darkness read the tooth as well as place? (Recommend no.)

### P2 — The sheet in the host ⬜
**Owner:** Opus on a Fable brief; Fable writes the schema bits (the paper row, the refIds, the
session read); Sonnet strings, `docs/sketchbook.md` § Paper and `docs/data.md`; Haiku the walk.

`PaperRecipe`, `PaperField`, `PaperSheet`, the cache; the paper row and `paperKind = "PAPER"` at
creation; the read at open; the set-once in `showPage`; the bake copy's paper; `overGround` and
the field in `CoverSnapshot`; a **debug-menu toggle for the visual alone** — warm, neutral grey,
off — so that P4 can measure the Kaleido tint cost without a rebuild (the tooth is untouched by
it); and a host JVM preview writing the sheet at 1× and grain-on-sheet PNGs, **looked at before the
panel sees them**.

**Tests:** the recipe's checksum pinned; a flat histogram after normalisation; field and visual
dimensions; 565 packing; xorshift vectors; the paper row and refIds round-tripping through
`RasterBake.plan`; `overGround`; the `SketchbookMeta` field.

**Gate:** tests green; the walk — a new book opens on the warm sheet (`screencap`), an arc-1 or
arc-2 book is byte-identical, a bake copy shows the sheet and its cover does too, a force-stop and
relaunch, `dumpsys meminfo` up by about 10 MB and no more, generation under 300 ms in the log, a
page turn unchanged, and `gfxinfo` after a reset in the shape of G6's 26 frames.

**Questions to resolve at phase start:**
1. The `.soil` paper row as the truth with the index as mirror (recommended), or the index alone?
   The raster flag is deliberately *not* mirrored (R2), so this is a conscious difference: the shelf
   never needed to know a book's mode, and it does need to know its paper to draw a blank card.
2. Which of three sheet previews — the texel, fibre and blotch weightings at 1× — is the starting
   recipe?
3. Covers over the flat base colour (recommended) or over the textured sheet?

### P3 — The rubber learns the tooth (g-paper Phase 20 → 0.1.33) ⬜
**Owner:** Fable; the host pin.

The shelter factor in `rubBatch`, `RasterRubbing.valleyShelter`, and `eraseRasterAlong` passing the
view's field and the batch pressure it already has; docs. **Tests:** a null-field golden on a fixed
batch; a valley lifts less than a crest at light pressure and equally at firm; shelter compounds
correctly across passes; seams are still lifted once.

**Gate:** suite green; the hand — one light pass over a hairline on paper leaves a tooth-patterned
ghost, a firm scrub takes it out, undo takes the rub back whole, and a BLANK book rubs exactly as
E1 did.

**Questions to resolve at phase start:** the `valleyShelter` default (recommend 0.7).

### P4 — The sitting, and the verdict ⬜
**Owner:** Fable orchestrates; Greg's hand; Haiku for screencaps and `gfxinfo`.

Tuning one number at a time, and a PNG first for anything that changes the grain: the visual's
`AMP` (6, 10, 14 %), **warm against neutral first** — it decides whether the visual lives on the
colour layer at all — then texel pitch, `TOOTH_BIAS`, `valleyShelter`. The measures: threshold-free
ink mass per unit length, BLANK against PAPER at matched pressures from `screencap`ed bakes (the
ramp must not have moved); a photograph for the live-against-bake pop; pen-up timing in stroke
mode on a full page; `gfxinfo`. Then the arc's outcome here and its rules into both `CLAUDE.md`s. **No `/code-review` is
planned for this arc**; one runs only if Greg asks for it.

**Gate:** the verdict — "paper is felt and seen", in Greg's words.

### Risks, and where each is caught
- **The Kaleido tint cost** — P2's toggle; P4 compares warm and neutral first; neutral is one
  constant away.
- **Dither of a faint texture** — ±6 % of 246 is about one of the panel's sixteen greys; 2 px
  texels keep it above the dither cell; `AMP` candidates in P4. Only the panel answers this.
- **Tooth and grain fighting for greys** — the arc-1 fear. The tooth only moves flecks; the visual
  is a separate faint layer. If they fight, `AMP` drops first and the tooth stays.
- **Memory** — about 1 MB of field and 9 MB of sheet, once per process; covers never load the
  sheet; `onTrimMemory` drops the cache.
- **Pen-up re-record time** in stroke mode — measured in P1 and P2. If it shows, the fix is
  g-paper's per-segment dirty rects (the arc-2 review's first candidate), never a host workaround.
- **Old drawings changing** — guarded three ways: the null-field goldens, the `screencap` gate, and
  recipe names that are never reused.

---

## Phases — Arc 4 "Coloured pencils" — ❌ ABANDONED (2026-09-15)

**Withdrawn by Greg on 2026-09-15 during C1's device gate**: *"I've gotten lost on what we are
trying to achieve right now and I don't want to keep going down a rabbit hole without clear
direction."* C1 had been built as g-paper Phase 21 / 0.1.34 (a per-stroke `saveLayer` restored
with `BlendMode.MULTIPLY`, a page-level ink layer, `InkComposite` as the pure reference), passed
its JVM tests and the offline proof, measured on the panel (crossings within three levels of the
reference, a corner-to-corner raster composite at 56 ms, "no slower" in the hand) — and was then
reverted in both repos and dropped from mavenLocal. Not merged, not published, not kept. The text
below is the plan as it was, for the record.

**Planned with Greg on 2026-09-14**, after arc 3 and to run after it. Arc 1's verdict left one
question open on purpose — "whether [the Kaleido] stays fine under colour" — and this is the arc
that asks it.

### What arc 4 is

The tin grows from one pencil to thirteen: graphite first, then twelve artist colours, every one
the approved hairline with a different colour in it. Colour laid over colour builds the way wax
does — blue over yellow reads green, any colour over graphite goes dark — and that one composite
applies to graphite too. Live ink is the firmware's line in the lead's colour; the bake adds grain
at pen-up as it does today. The tin's rows carry a swatch of each lead's colour, the one place
colour appears in this app's chrome. A picker for any colour is a later arc.

This lifts arc 1's "greyscale graphite only" by name. It does not lift "no paint".

### The decisions that bind (Greg, 2026-09-14)

1. **A tin of fixed leads now; a picker later.** Real pencils come in a tin; you pick one up.
2. **One tin, graphite is lead #1**, plus **twelve colours in an artist set** — named after real
   pencil pigments, proposed below and edited by Greg at C2's start.
3. **Every coloured lead is the graphite pencil tinted**: the same 1.2 px hairline, the same
   pressure→coverage→darkness model, no tilt.
4. **Layering multiplies, as wax does, and it applies to every lead, graphite included** — one
   composite for the app. Graphite crossings will read a little differently from the reference;
   the hand judges.
5. **The tin's rows show a swatch beside the name.** Recorded as the one exception to "no colour
   in chrome": the swatch is the tool, not the chrome.

### The design

**Multiply happens once per stroke, never per fleck.** `drawPencil` lays flecks up to 1.6 px on a
0.8 px pitch, so at the dark end a pixel is covered about four times. Multiplying a colour with
itself gives `c²`: `#505050` (0.31) becomes 0.10 after one self-overlap and near black after four —
exactly the "reads as a fine pen" that G3 lightened the ink to escape. So each stroke renders as it
does today, SRC_OVER, *inside a layer* over its own dirty rect (`width + 2 px`, the rule the raster
dirty rect already uses), and the layer restores with multiply. **A single stroke on blank paper is
then byte-identical to today**, because multiply onto nothing is the source — a testable invariant,
so C1 cannot move the approved pencil by itself. Only crossings change, which is what was asked for.

**Multiply lives inside the ink, and ink meets paper by SRC_OVER in every mode.** Today the raster
page is a transparent layer blitted over the paper, while a stroke book draws its marks straight
over white and the template. Under multiply the stroke page would tint every mark by arc 3's warm
sheet and its fibre and the raster page would not. So the stroke branch of `drawCommittedContent`
and `StrokeRasterizer` open one page-level transparent ink layer, restored SRC_OVER onto the paper;
in raster mode the page raster *is* that layer and only the per-stroke layer is needed. Covers and
bakes already draw onto transparent bitmaps.

**A view setting, not a change to `PENCIL`.** `InkLayering { OVER, MULTIPLY }` on the view,
default `OVER` — the `pageMode` precedent: a host that never sets it gets the engine unchanged, and
Notesprout's pencils do not move. `StrokeRasterizer.draw` takes the same. All five renderer call
sites go through one helper so they cannot disagree, and a pure `InkComposite` (straight-ARGB
`over` and `multiply` by Skia's formula) is the JVM reference the device is judged against.

**The recorded fallback, if the layer cost fails C1's gate:** per-fleck `DARKEN`, no layers.
`min(c, c) = c`, so graphite is untouched and blue over yellow still greens per channel — but the
same colour laid twice never deepens, which is not what wax does. The one genuine trade-off, and
Greg decides it only if the measurement forces it.

**The tin stores true colour.** `Lead(widthPx, argb)`, thirteen entries, `GRAPHITE(1.2f,
0xFF505050)` first — the constant and its comment move here from `SketchbookActivity`.
`byName` maps the stored `HAIRLINE` (every device has it in its prefs) and any unknown name to
graphite. A mark row stores the lead it was drawn with; retuning the tin changes new marks only.
Values are tuned by eye on the panel *within pencil-pigment range* and never oversaturated to fight
the Kaleido wash: the file's colours also feed covers and any later export, and a panel deficit
baked into every row cannot be undone.

**Live ink: true colour armed, measured, grey as the fallback.** `penColor` becomes the lead's
colour; the Onyx engine already re-arms `setStrokeColor` on every set. Whether the NA5C firmware
draws its plain line *in* colour, in the right hue, and whether a 1.2 px coloured line reads as a
line or as speckle on the colour layer's half resolution, is **measured with a camera in C2**. If
it reads wrong, the engine gains a live-colour policy (engine-side; the host never touches
`TouchHelper`) that arms the lead's luminance grey live with colour arriving at pen-up — the pop
becomes hue, never size. A preview that lies about width is the one that matters; colour sits
between width and texture.

**The sheet.** `ActionSheetDialog` gains a row form whose icon column holds a filled circle of the
lead's colour with a 1 dp ink ring, and the lead in hand carries the tick at the trailing end; the
24 dp column is unchanged so the shelf's sheets are untouched. Thirteen rows fit the panel in one
column. `colors.xml` records the exception, and the values live in `Lead`, never there.

**Nothing in the host assumes grey** — verified: the blank test is alpha-only, the cover composite
is per-channel, cover and page decodes are ARGB, the rubber lifts alpha only, the undo grid is
ARGB ints, and every mark row already carries its colour through `InkColorCodec`, which G1 wrote
for exactly this day. No migration anywhere. One soft spot, recorded: covers are WEBP, whose
chroma subsampling blurs a coloured hairline — hidden under the shrink-by-three. Old stroke books
re-render under multiply; raster pages keep the pixels they have.

### C1 — The ink layer composites by multiply (g-paper Phase 21 → 0.1.34) ⬜
**Owner:** Fable — the engine seam and the review; Sonnet docs; the demo gains a layering toggle
beside its colour cycle so the generic engine on a desk shows blue over yellow.

`InkLayering`, `PaperView.inkLayering` (setting it re-records), the layered helper through all
five sites, the page-level ink layer in `drawCommittedContent` and `StrokeRasterizer`,
`InkComposite`; `docs/api.md` § Ink layering; `host-responsibilities.md` (set it in three places —
view, covers, bake); a `CLAUDE.md` rule ("multiply is once per stroke inside the ink layer;
per-fleck multiply blackens the hairline"); `PLAN.md`.

**Tests:** `InkCompositeTest` — a transparent destination yields the source; grey over grey is
`c²`; blue over yellow is green; commutative; alpha equals SRC_OVER's. The whole suite green.

**Gate:** tests; an **offline PNG proof** — two graphite hairline crossings at light and firm
pressure under OVER and MULTIPLY side by side at 1× and 4×, blue over yellow in both orders, and a
single stroke on blank byte-identical under both; the device — one fixture page drawn by hand
(graphite crossings, blue over yellow both ways, the same colour laid twice) in a stroke book and
again baked to raster, screencapped, the crossing pixels matching `InkComposite` within the known
GPU/CPU tone gap, and the R3 undo of a crossing stroke pixel-exact (the layer never leaves its
dirty rect); and **cost** — `gfxinfo` over a minute of stroke-page sketching against G6's 26
frames, plus a timing around `compositeIntoRaster` for a corner-to-corner hairline. "No slower in
the hand" is Greg's word. If it fails, DARKEN goes to him before C2.

**Questions to resolve at phase start:**
1. Confirm a view setting over a `PENCIL`-level change.
2. Confirm "ink over paper is SRC_OVER, never multiply", now that the warm sheet exists.
3. Confirm DARKEN as the only fallback, and that it is measurement-gated, not taste-gated.

### C2 — The tin of thirteen, the swatch sheet, colour live ⬜
**Owner:** Opus on a Fable brief; Sonnet strings, drawables and `docs/`; Haiku the walk; Fable
reviews and does the live-colour measurement with Greg.

`Lead` rewritten; `SketchbookActivity` sets colour and width per lead and `inkLayering =
MULTIPLY` beside `pageMode`; thirteen labels; covers and the bake pass `MULTIPLY` to
`StrokeRasterizer`; three pins; `lead_hairline` becomes `lead_graphite` "Graphite" and twelve
names join it, the sheet still titled "Pencil"; `docs/sketchbook.md` § The tin, `docs/data.md`,
the `CLAUDE.md` pencil rule rewritten. **The measurement:** each lead drawn live at 1.2 px,
photographed before pen-up and screencapped after; recorded per lead.

**Tests:** `LeadTest` — thirteen entries, graphite first, all 1.2 px, all opaque, no two equal,
none paper-white, `HAIRLINE`/`FINE`/`MEDIUM`/`BROAD`/null all read as graphite, `InkColorCodec`
round-trips every value.

**Gate:** tests; the walk — the sheet opens, thirteen rows with swatches screencapped, a finger
tap picks a lead, the pick survives a relaunch, old prefs holding `HAIRLINE` open on graphite, a
coloured committed mark is screencap-visible in both kinds of book, its cover shows colour on the
shelf, undo and redo of a coloured mark, the rubber lifts colour paler rather than greyer. Greg's
checklist: a coloured mark live (camera), the pen-up pop, the tin's rows legible at arm's length.

**Questions to resolve at phase start:**
1. **The twelve, proposed** — artist pigment names at mid saturation (multiply of pure primaries is
   black), to be tuned in C3: Cadmium Yellow `#E8C83A`, Cadmium Orange `#E07B2A`, Cadmium Red
   `#C8352E`, Alizarin Crimson `#B8365F`, Dioxazine Violet `#6E4B9E`, Ultramarine `#2F5FA6`,
   Cerulean `#5AA0D8`, Viridian `#2E8A7A`, Sap Green `#3E8A3E`, Permanent Green Light `#8DB84A`,
   Burnt Sienna `#7A4B2A`, Yellow Ochre `#B58A3C`.
2. Is black a lead? Proposed **no**: graphite is the dark lead, and a black would be ink.
3. Label form — names only, no "(0.10 mm)", now that every lead is one width.
4. The live fallback policy if the camera says no.

### C3 — The sitting: the tin tuned on Kaleido, and the verdict ⬜
**Owner:** Greg's hand and eye; Fable drives, moves `Lead` values between rounds, records; Sonnet
the outcome and docs.

An evening's drawing on the arc-3 sheet that layers colour over graphite and colour over colour.
Between rounds only the tin's values move — a rebuild, no engine change — and each round's page is
screencapped and photographed. Three questions: does a coloured hairline read as pencil on this
panel; are the twelve distinguishable from each other and from graphite at arm's length; does
multiply read as wax. Old marks keep their values, so a retune shows only on new marks — the C1
fixture page is redrawn each round, not reloaded. Then the arc's outcome beside arc 1's verdict,
the final tin with one sentence per lead on what moved and why, and both `CLAUDE.md`s. **No
`/code-review` is planned for this arc**; one runs only if Greg asks for it.

**Gate:** Greg's verdict on "what did the Kaleido cost under colour".

**Questions to resolve at phase start:**
1. **The broad-point question.** If C2's photograph showed a 1.2 px coloured line reading as grey
   speckle (a Kaleido colour cell is about 2 × 2 mono px), do coloured leads get a broader point —
   say 2.4 px, one colour cell — while graphite stays 1.2? That breaks decision 3 and is Greg's
   call with the photograph in front of him; the default is one width, and the sitting says.
2. Tune within pigment range, or accept the wash?

### Risks, and where each is caught
- **Kaleido halving colour resolution under a hairline** — measured in C2, decided in C3.
- **Live colour against baked colour** — the pop is hue and tone, never size; the grey-live
  fallback is an engine option.
- **Multiply changing the approved graphite** — single strokes provably unchanged; crossings
  darken by `c²`, which was accepted in advance; the sitting judges.
- **Per-stroke layers slowing pen-up** — measured in C1; DARKEN is the recorded fallback.
- **An sRGB tin against the panel's gamut** — true colour stored, tuned by eye within pigment
  range; oversaturating to defeat the wash is the trap the `Lead` comment names.
- **Memory** — no new page-sized allocation that persists; the per-stroke layer is transient and
  bounded by the stroke's rect.

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
