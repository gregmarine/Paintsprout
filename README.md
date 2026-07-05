# Paintsprout

Where art has a place to grow.

A digital art app that feels like real paper and canvas — spectral pigment
mixing, watercolor washes, and material surfaces.

## Repository layout

This is a monorepo. The app is being ported from Flutter to native Android.

| Path                       | Description                                                                     |
| -------------------------- | ------------------------------------------------------------------------------- |
| `apps/paintsprout_flutter` | The original Flutter implementation. **Frozen reference** for the native port.  |
| `apps/paintsprout_android` | Native Android implementation (Kotlin + Jetpack Compose + AGSL). *In progress.* |

### paintsprout_flutter (reference)

```bash
cd apps/paintsprout_flutter
flutter pub get
flutter run
```
