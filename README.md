# Paintsprout

Where art has a place to grow.

A digital art app that feels like real paper and canvas — spectral pigment
mixing, watercolor washes, material surfaces, and marks that come out the size
they were drawn.

## Repository layout

This is a monorepo. The app was ported from Flutter to native Android, and the
native app is the one that is worked on.

| Path                       | Description                                                                    |
| -------------------------- | ------------------------------------------------------------------------------ |
| `apps/paintsprout_android` | The app. Kotlin, the Android View system, AGSL shaders.                        |
| `apps/paintsprout_flutter` | The original Flutter implementation. **Frozen reference**, kept for the record. |

### paintsprout_android

```bash
cd apps/paintsprout_android
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # unit tests
```

See [`apps/paintsprout_android/README.md`](apps/paintsprout_android/README.md)
for the stack, and `docs/` for the file format and backup references.

### paintsprout_flutter (reference)

Frozen at the monorepo restructure. It is here to be read, not developed — the
native app has long since passed it.

```bash
cd apps/paintsprout_flutter
flutter pub get
flutter run
```
