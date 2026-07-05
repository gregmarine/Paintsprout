# paintsprout_android

Native Android implementation of Paintsprout — the port target. See the Flutter
reference at `../paintsprout_flutter`.

## Stack

- Kotlin + Android **View system** (XML layouts + viewBinding), AppCompat.
  Mirrors the sibling native Notesprout app.
- **AGSL `RuntimeShader`** for pigment mixing. AGSL is SkSL-derived — the same
  lineage as Flutter's fragment shaders — so `pigment_mix.frag` from the Flutter
  reference is expected to port with minor syntax changes.
- AGP 8.11.1 · Kotlin 2.2.20 · Gradle 8.14 · JDK 17
- compileSdk/targetSdk 35 · **minSdk 33** (hard floor: AGSL requires API 33) ·
  arm64-v8a only
- `applicationId = com.symmetricalpalmtree.paintsprout` — same as the Flutter
  build; the native app replaces it on-device.

## Build

```bash
cd apps/paintsprout_android
./gradlew :app:assembleDebug
```

## Status

Scaffold only. `PigmentCanvasView` renders a two-pigment AGSL blend to prove the
shader pipeline end-to-end. Feature port from the Flutter reference follows.
