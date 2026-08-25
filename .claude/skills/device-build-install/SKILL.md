---
name: device-build-install
description: Build, sign, and install the Paintsprout Android app (debug/release) to the approved Wacom tablets by nickname (Movink 11, Movink 14); includes the gradle/apksigner commands, device serials, and the per-application-id state that does NOT travel between builds. Use whenever asked to build, install, or sideload the app.
---

## Build variants & install

- **Debug** (`com.symmetricalpalmtree.paintsprout.dev`) — active dev; installs alongside
  stable. **Default — always build/install debug unless told otherwise.**
- **Release** (`com.symmetricalpalmtree.paintsprout`) — stable; release installs are always
  explicit.

```sh
# Debug → app/build/outputs/apk/debug/app-debug.apk
cd apps/paintsprout_android && ./gradlew assembleDebug

# Release (unsigned — must sign before sideloading)
cd apps/paintsprout_android && ./gradlew assembleRelease
~/development/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out app/build/outputs/apk/release/app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

adb -s <serial> install -r <apk-path>
```

Same arrangement as Notesprout: no `signingConfig` in Gradle, and release is signed after
the fact with the **debug keystore**. Good enough for sideloading, and not a Play Store
identity — a real upload key is a separate decision nobody has made yet.

Because both variants carry that one key, a release build can `install -r` over an earlier
release install with no signature mismatch and no uninstall.

## Devices

| Device | Serial |
|---|---|
| Wacom Movink 11 | `5HL21V5007384` |
| Wacom Movink 14 Pro | `5il21u1003409` |

The Movink 14 serial is **lowercase** — copy it, don't retype it. Every other connected
device must be ignored; a BOOX and a Samsung have both been seen attached at the same time.
Always `adb -s <serial>`. **Never `./gradlew installDebug`** — it pushes to every eligible
device and `-Pandroid.injected.device.serial` does not scope it.

Verify what landed where:

```sh
for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  echo -n "$s: "; adb -s $s shell pm list packages | grep paintsprout
done
```

Launch: `adb -s <serial> shell monkey -p <application-id> -c android.intent.category.LAUNCHER 1`

**Do not use `am start -n <id>/.MainActivity`.** Only the application id is suffixed; the
namespace is not, so the shorthand expands to `…paintsprout.dev.MainActivity`, which does not
exist. Spell the class out: `<id>/com.symmetricalpalmtree.paintsprout.MainActivity`.

## What does NOT travel between the two ids

Each application id has its own `getExternalFilesDir` and its own `/data/data`, so a debug
and a release install share nothing. Three things bite in practice:

**The library.** `/sdcard/Android/data/<id>/files/` — copyable with
`adb shell cp -a`, sidecars included. Force-stop the source first.

**Calibration.** `paintsprout.calibration` in SharedPreferences. An uncalibrated install
silently falls back to the OEM-reported PPI — **319 dpi on the Movink 14 Pro against a
measured 242.69**, so every millimetre size and every 1:1 canvas preset comes out a third too
large with nothing on screen saying so. Copy it between *debuggable* builds with:

```sh
adb -s <serial> shell "run-as <src-id> cat /data/data/<src-id>/shared_prefs/paintsprout.calibration.xml" > /tmp/cal.xml
adb -s <serial> shell "run-as <dst-id> sh -c 'cat > /data/data/<dst-id>/shared_prefs/paintsprout.calibration.xml'" < /tmp/cal.xml
```

`run-as` **does not work on a release build** — it is not debuggable. A release install can
only be calibrated in-app: flip `Focus.SHOW_CALIBRATE` to `true`, build + sign + install,
calibrate against a physical card on the device, flip it back, rebuild and `install -r`
(which preserves data, so the calibration survives).

**The recovery key.** In EncryptedSharedPreferences under an Android Keystore master key
bound to the package, so it cannot be copied at all. A library moved to another id always
lands on the unlock gate and needs its key typed in. That is correct behaviour, not a fault —
and it means **a library whose recovery key is lost is unrecoverable**, including from a
backup, since backups are copied as ciphertext. Write the key down when a fresh install
shows it.

Related: `docs/backup.md`.
