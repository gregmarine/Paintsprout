# paintsprout_android

The Paintsprout app. Ported from Flutter; the reference it was ported from is
frozen at `../paintsprout_flutter`.

## Stack

- Kotlin + Android **View system** (XML layouts + viewBinding), AppCompat.
  Mirrors the sibling native Notesprout app. No Compose.
- **AGSL `RuntimeShader`** for pigment mixing and the wet-paint simulation
  (`app/src/main/res/raw/*.agsl`). AGSL is SkSL-derived — the same lineage as
  Flutter's fragment shaders — so the reference's `pigment_mix.frag` ported with
  minor syntax changes. `Pigment.kt` is its CPU counterpart, held to the same
  arithmetic by a unit test.
- **SQLCipher + Room** for the `.soil` sketchbook container and the global
  index, both encrypted from the first byte. See `docs/soil-format.md`.
- AGP 8.11.1 · Kotlin 2.2.20 · Gradle 8.14 · JDK 17
- compileSdk/targetSdk 35 · **minSdk 33** (hard floor: AGSL requires API 33) ·
  arm64-v8a only

## Build

```bash
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # the whole JVM unit-test suite
```

Google Drive backup reads `DRIVE_CLIENT_ID` and `DRIVE_CLIENT_SECRET` from the
environment at build time. A build without them compiles fine; the Drive slot
says it is not configured. See `docs/backup.md`.

## Build variants

A development build is a different app from a shipped one, and says so in its id:

| Variant | Application id |
|---|---|
| debug | `com.symmetricalpalmtree.paintsprout.dev` |
| release | `com.symmetricalpalmtree.paintsprout` |

The two coexist on one tablet, so a build under test cannot replace the install
holding real paintings. They share nothing: each id has its own library, its own
screen calibration, and its own recovery key. **An uncalibrated install falls
back to the OEM-reported PPI silently**, which on the Movink 14 Pro is a third
too high — calibrate before judging any physical size.

## What it does

Pencil, pen, brush, watercolor, marker, spray and eraser; editable line, arc,
polyline and polyarc; magic-wand and lasso selection. Nine procedural surfaces
with per-artwork seeds and a tooth that breaks up a stroke. Spectral
Kubelka-Munk pigment mixing on a physical mixing tray, with a brush that carries
a finite load and picks up what it is dragged through. Layers and layer folders,
unlimited undo that survives closing the document, multi-page sketchbooks, a
scratchpad and a clipboard, PNG export, and backup to a local folder or Google
Drive. Sizes are in millimetres against a calibrated screen, so a canvas preset
is a real sheet and an exported PNG prints at the size it was drawn.

`Focus.kt` decides which of that is on the rail at any one time — everything
else stays built but out of sight until the work asks for it.
