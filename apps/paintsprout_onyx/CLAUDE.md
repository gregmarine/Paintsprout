# Paintsprout Onyx — Claude Code instructions (apps/paintsprout_onyx)

**Branch `onyx` · Package `com.symmetricalpalmtree.paintsproutonyx` · Label "Paintsprout Onyx"
("Paintsprout Onyx Dev" in debug) · Plan/status: `ONYX_PLAN.md` — read it whole at every phase
start; it holds the working protocol, model recipe, locked decisions, non-goals, standing traps
and phase statuses.**

A from-scratch, **BOOX-only** rebuild of Paintsprout, asking one question: what does g-paper on an
Onyx e-ink panel give us for Paintsprout? Arc 1 is a graphite pencil, a rubber eraser, white paper
and a shelf of multi-page sketchbooks — nothing else.

`apps/paintsprout_android` (the live Wacom app) and `~/git/Notesprout` (Paper + SN) are **reading
references — no app code is copied from either.** The Gradle wrapper is the one boilerplate
exemption.

**Device: BOOX NoteAir5C (NA5C) `92c16533`, and nothing else.** Never install anywhere else
without an explicit ask.

## Standing rules

The repo-root `CLAUDE.md`'s conventions bind here — in particular **commit messages are a single
plain sentence, no prefix, no type tag, roughly under 78 characters, in the artist's terms rather
than the code's**, and **comments explain why, at length, in that same register**: the decision and
the failure it avoids, not the mechanism. Its *architecture* sections describe the Wacom app and do
not apply here. Plus:

- **Own Gradle root, single `:app` module.** Kotlin, Java 17 (Temurin, pinned via
  `org.gradle.java.home`), compileSdk/targetSdk 35, minSdk 29, arm64-v8a only.
- **kotlinx.serialization only** — never `org.json`. **No Material Components.** **No new Gradle
  dependencies without explicit discussion.** **Never `runBlocking` on the UI thread.**
- **g-paper `gpaper-core` + `gpaper-onyx` only**, from mavenLocal. No `gpaper-ratta`. The Onyx
  build baggage is mandatory and non-negotiable: BOOX insecure maven repo, jetifier,
  `tools:replace="android:label"`, arm64-v8a abiFilter, `libc++_shared.so` pickFirsts — all four
  spelled out in `~/git/g-paper/docs/integration-guide.md`. **`OnyxEngine.register(this)` runs from
  `Application.onCreate` and takes the `Application`** — it also installs the hidden-API bypass and
  heals leaked EPD state, which is keyed by name rather than process and would otherwise ghost the
  panel until reboot.
- **Engine gaps are fixed in g-paper** (`~/git/g-paper`): add a phase to its `PLAN.md`, bump
  `GPAPER_VERSION`, `publishToMavenLocal`, re-pin here. **Never work around an engine gap in the
  host.** The host does only the documented host responsibilities
  (`~/git/g-paper/docs/host-responsibilities.md`): page swap = `clearForContentSwap` →
  `setPageSize`/`setTemplate` → `loadStrokes`; undo/redo via `addStrokes`/`removeStrokes`; chrome
  via `setExclusionRects`; lifecycle `resumeDrawing`/`releaseForHandoff`/`release`.
- **Data model is Paper/SN's shapes in Paintsprout's vocabulary** — index `paintsprout.db`
  `objects` table (user_version 1) + `Garden/<uuid>.soil` universal `sketchbook` table v1 +
  `sketchbook_meta`, `MarkCodec` (format B, byte-identical encoding), encrypt-by-default global
  key, SQLCipher **stock defaults**. The full mapping table is in `ONYX_PLAN.md`. Structural
  references: `~/git/Notesprout/apps/notesprout_paper/docs/data.md` + `docs/crypto.md`. **Never
  customise `kdf_iter` or the page size** — stock-default portability *is* the format.
- **`data/SoilFile.kt` is the only path constructor.** Folders live exclusively in the index, never
  derived from the filesystem. Soft deletes only; stable UUIDs everywhere. `"order"` is
  double-quoted in SQL and backticked in Room.
- **Every SQLCipher open routes through `crypto/SoilCrypto`**, wrapped in
  `NonDestructiveOpenHelperFactory`. Passphrases are never logged, never in Intent extras, never in
  the index. **Never delete a DB on corruption.**
- **`IndexGuard.ready(this)` first thing in every index-touching `onCreate`**; `BootstrapActivity`
  is the only index opener and is `noHistory`.
- **The write queue must outlive the screen.** `SoilWriter`'s pump runs on
  `PaintsproutApplication.scope`, never an Activity's `lifecycleScope` — that one is cancelled the
  instant `onDestroy` returns, which is exactly when `SketchbookSession.close()` is draining it, so
  the call written to save the last marks drawn would be the call that threw them away. Anything else
  that must finish after a screen goes belongs on that scope too, and nothing else does yet.
- **Turning the pen over erases, and there is no code here to find.** The BOOX SDK intercepts the
  stylus eraser end at hardware level and g-paper's Onyx engine erases with it whichever tool is
  armed (`onBeginRawErasing`). Worth knowing precisely because someone will go looking for the host
  code that does it.
- **Graphite lives in g-paper, and its grain is seeded from the stroke id.** `StrokeStyle.PENCIL`
  (0.1.7) draws flecks on the paper's tooth — pressure fills in more of the tooth and darkens what
  lands, the width never moves, tilt is unread. A mark's apparent width is about `width + 2 px`, the
  bleed of one fleck, so **pencil sizes must be spaced by more than that** or they look like one
  pencil in disguises. Never add a host-side pencil renderer: a gap in how graphite looks is a
  g-paper phase.
- **Frame-silence rule:** never present an app frame while `paper.isPenActive` — route chrome text
  and updates through a pen-idle gate. Frames presented during a live raw contact are withheld from
  the panel, and a pen-up `invalidate()` of identical content is damage-free, so a careless chrome
  update is not merely wasteful — it is invisible until something else damages the region. Every
  exception needs a written justification in `docs/sketchbook.md`.
- **Toast vs. dialog:** a toast only confirms something that already happened; anything explaining
  why a tap *didn't* work is a problem dialog. On e-ink a missed toast reads as "broken".
- **BOOX has a real status bar overlaying the window top** — apply system-bar insets, and no
  tappable chrome against the top edge (tapping there pulls the status bar down). This is the
  opposite of Supernote, where the guard is zero.
- Portrait-locked everywhere · one layout per screen · **no colour in chrome** · greyscale graphite
  only in arc 1 · sketchbook writes go through the session's single serial `SoilWriter` ·
  undo/redo replays through the store then reloads the page (the DB is the source of truth) · no
  file over ~800 lines without a written reason.
- **Non-goals are enforced, not aspirational.** No layers, no paint, no surfaces, no shape tools,
  no selection, no millimetres or calibration, no zoom/pan/rotate, no export, no backup, no
  extensions. The full list, and which of them are candidate later arcs, is in `ONYX_PLAN.md`.

## Device traps (BOOX)

- **logcat spam wraps the buffer in seconds** — `adb logcat -G 16M` plus a **streaming** filtered
  capture (`logcat -s TAG`), never `-d` after the fact.
- **`install -r` + immediate `am start` races package finalization**, leaving the package installed
  but disabled (`enabled=3`). Heal with `pm enable <pkg>`.
- **EPD pen overlays are invisible to `screencap`**; committed marks and ordinary app UI are not.
- **adb cannot inject stylus ink** (toolType UNKNOWN). Finger `input tap`/`input swipe` work, so
  chrome, page-turn gestures and persistence are agent-verifiable; the pencil needs the user's hand.
- **`monkey` does not reliably foreground the app** — launch with `am start -n <pkg>/<Activity>`
  and verify `dumpsys activity activities | grep mResumedActivity` before any screencap conclusion.
- **`adb push` into `/sdcard/Android/data/<pkg>/files/` fails *and deletes the target*.** Push to
  `/data/local/tmp/`, then `adb shell cp` into place, then `rm` the temp.
- **The NA5C is a Kaleido colour panel** — its colour layer costs resolution and contrast, which is
  why arc 1 is greyscale.
- **The Onyx SDK asserts `android:allowBackup="true"` in its own manifest.** Auto Backup must stay
  off here — the Keystore key cannot travel, so a restored install holds ciphertext it can never
  open and an `EncryptedSharedPreferences` blob that throws before unlock can be offered. That is
  why `tools:replace` covers `android:allowBackup` as well as `android:label`; both source sets
  need it. Add to the mandatory-baggage list, not to the tidying list.
- **An injected `KEYCODE_BACK` does not reach apps on this device at all** — not this app, and not
  the system settings either. Back gestures are the user's thumb, never an agent's. Anything that
  depends on back is unverifiable from a desk and belongs on the user's checklist.
- **`Activity.onBackPressed` is dead code here.** The NA5C runs Android 15 and this app targets SDK
  35, so predictive back is on by default and the framework never calls it. Back handling goes
  through `onBackPressedDispatcher.addCallback` with the callback enabled only while there is
  somewhere to go — an override compiles, reads correctly and silently never runs, which combined
  with the trap above is a defect no amount of adb will find.
- **A view's `width` includes its own padding.** Measuring a grid against it on a screen with a
  screen margin overflows the last column off the panel, and it reads as a card that is merely too
  big — the wrong thing to go and fix. Subtract the padding where the measurement is taken.
- **`releaseForHandoff()` has no caller in arc 1 and that is deliberate.** It belongs immediately
  before launching, or finishing back to, *another* paper-hosting screen — and there is none. Adding
  a second one means adding the call: without it the departing screen's teardown lands ~200 ms after
  the arriving screen reclaimed, and the arriving screen's ink stays invisible until the tool is
  flipped.
- **A device agent's failures need reproducing before they are believed**, exactly like its passes.
  G1's first walk reported a critical defect in a flow that works, and diagnosed it in a class this
  app does not contain. Reproduce by hand before changing code.

## Build & install

See `ONYX_PLAN.md` appendix. Debug: `./gradlew assembleDebug` → `adb -s 92c16533 install -r`.
Release is unsigned and hand-signed with the debug keystore. JVM tests: `./gradlew test`.
