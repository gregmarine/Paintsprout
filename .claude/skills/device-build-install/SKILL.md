---
name: device-build-install
description: Build, sign, and install a Paintsprout app (debug/release) to its approved tablet by nickname — the Wacom app to Movink 11 / Movink 14, and Paintsprout Onyx to the BOOX NoteAir5C; includes the gradle/apksigner commands, device serials, and the per-application-id state that does NOT travel between builds. Use whenever asked to build, install, or sideload the app.
---

## Two apps, two tablets

This repo builds **two** apps and they never mix:

| App | Gradle root | Tablet |
|---|---|---|
| **Paintsprout** (Wacom) | `apps/paintsprout_android` | Movink 11, Movink 14 Pro |
| **Paintsprout Onyx** | `apps/paintsprout_onyx` | BOOX NoteAir5C only |

Everything down to "## Paintsprout Onyx" below is the **Wacom** app. Onyx has its own Gradle
root, its own package and its own device, and installing either one onto the other's tablet
is always a mistake.

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

## Paintsprout Onyx (BOOX NoteAir5C)

A separate Gradle root, a separate package, and **one** device. Plan and status live in
`apps/paintsprout_onyx/ONYX_PLAN.md`.

- **Debug** (`com.symmetricalpalmtree.paintsproutonyx.dev`, label "Paintsprout Onyx Dev") —
  the default; installs alongside stable.
- **Release** (`com.symmetricalpalmtree.paintsproutonyx`) — explicit only, and unsigned out
  of Gradle exactly like the Wacom app.

```sh
cd apps/paintsprout_onyx && ./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
cd apps/paintsprout_onyx && ./gradlew test              # JVM unit tests

# Release: sign with the debug keystore after the fact, same arrangement as the Wacom app.
cd apps/paintsprout_onyx && ./gradlew assembleRelease
~/development/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out app/build/outputs/apk/release/app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

adb -s 92c16533 install -r app/build/outputs/apk/debug/app-debug.apk
```

| Device | Serial |
|---|---|
| BOOX NoteAir5C | `92c16533` |

A Supernote and the Movinks have all been seen attached at the same time. Always
`adb -s 92c16533`, and never `installDebug`.

**Panel:** 1860 × 2480 px, densityDpi 300 (density 1.875), physical ≈ 304.8 × 304.3 dpi —
smallestWidth 992 dp, so `values-sw720dp` is the tier that actually applies. Kaleido colour
panel: the colour filter costs resolution and contrast, which is why arc 1 is greyscale.

### Launching, and the two ways it goes wrong

```sh
PKG=com.symmetricalpalmtree.paintsproutonyx.dev
adb -s 92c16533 shell am start -n $PKG/com.symmetricalpalmtree.paintsproutonyx.PlaceholderActivity
adb -s 92c16533 shell dumpsys activity activities | grep -m1 ResumedActivity
```

**Spell the activity class out.** Only the application id carries the `.dev` suffix — the
namespace does not — so the `.PlaceholderActivity` shorthand expands to a class that does not
exist. Same trap as the Wacom app.

**`monkey` does not reliably foreground this app.** Use `am start -n`, and confirm
`ResumedActivity` names the package **before** drawing any conclusion from a screenshot. A
whole device walk once silently passed against the wrong app.

**`install -r` immediately followed by `am start` can race package finalization**, leaving the
package installed but disabled (`enabled=3`, "Activity class does not exist"). Heal it with
`adb -s 92c16533 shell pm enable <pkg>` — reproduced on this device.

The launcher entry is `PlaceholderActivity` only through G0; G1 replaces it with
`BootstrapActivity`.

### What adb can and cannot see here

- **EPD pen overlays are invisible to `screencap`.** Committed marks and ordinary app UI do
  appear. Live ink is the user's eye, never a screenshot.
- **adb cannot inject stylus ink** — injected events carry toolType UNKNOWN and the engine
  drops them. Finger `input tap` / `input swipe` work normally, so chrome, panels, page-turn
  gestures and persistence are all verifiable; the pencil is not.
- **Text entry works normally** on BOOX (unlike Supernote), so `input text` is available.
- **BOOX spams logcat** hard enough to wrap the buffer in seconds. Use `adb logcat -G 16M`
  plus a **streaming** filtered capture (`logcat -s TAG`) — never `-d` after the fact.
- **`adb push` into `/sdcard/Android/data/<pkg>/files/` fails with `remote fchown failed`, and
  the failed push DELETES the existing target file.** Push to `/data/local/tmp/`, then
  `adb shell cp` into place (`rm` the target first if it exists), then `rm` the temp.
  `adb pull` is fine.

### What does NOT travel between the two ids

Same rule as the Wacom app: `getExternalFilesDir` and `/data/data` are per-id, so the `.dev`
and release installs share nothing. From G1 that means each has **its own recovery key**,
held in EncryptedSharedPreferences under a Keystore key bound to the package and therefore
not copyable at all — a library moved between the two always lands on the unlock gate. There
is no calibration here to carry across: Onyx has no millimetres and no PPI calibration.
