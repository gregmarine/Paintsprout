# Crypto

What is encrypted, with what, where the key that does it lives, and the two doors through which a
file may ever be created rather than merely opened. Written at **G6 close-out (2026-09-03)**, after
the six-point data-loss audit walked the source and one real change came out of it — the cold open
of an existing file no longer hands Room a key nobody has checked. The KDoc in `crypto/SoilCrypto.kt`,
`data/NonDestructiveOpenHelperFactory.kt` and `data/index/PaintsproutIndex.kt` is the primary source;
this file distils it and points at the code rather than repeating its reasoning wholesale.

## What is encrypted, and with what

Every file this app writes to disk is encrypted from its first byte — the index and every
sketchbook alike. There is no plaintext mode to opt into and nothing here ever asks the artist to
invent a password. Instead the app mints one on the artist's behalf, the first time it needs one,
and that single string is both the SQLCipher passphrase for every file and the recovery key shown
back to the artist once, on `RecoveryKeyActivity`. Lose the string and every drawing behind it is
lost with it — there is no account, no device fingerprint, no server-side reset standing behind it,
because none of those exist in this app.

`crypto/GlobalKey.mint()` builds the key from 160 bits of `SecureRandom` entropy, formatted in
Crockford's base32 alphabet (`0123456789ABCDEFGHJKMNPQRSTVWXYZ` — no I, L, O or U) as `PSPT-` followed
by eight groups of four characters. The alphabet is chosen for the one thing it has to survive: being
copied by hand onto paper and typed back months later. Leaving out the four confusable letters means
a reader can never be unsure whether a mark on the page was a zero or an O, a one or an I or an l —
and it is what lets `GlobalKey.normalize()` fold a typed "O" back to "0" and "I"/"L" back to "1"
without any risk of mangling a key that was written correctly, since a genuine key never contains
those four letters in the first place.

Every database — `paintsprout.db` and every `Garden/<uuid>.soil` — is opened with **SQLCipher 4's
stock defaults**. `SoilCrypto` never sets `kdf_iter` and never touches the page size, and that
omission is deliberate rather than an oversight: stock defaults are not a convenience the app happens
to be using, they are the file format. A `.soil` pulled off the tablet has to open in an unmodified
`sqlcipher` CLI with nothing but the passphrase string, and the moment any cipher setting is
customised that stops being true. The passphrase becomes key bytes exactly one way, `SoilCrypto.keyBytes()`
— UTF-8, everywhere, forever — because that encoding is itself part of on-disk compatibility, not an
implementation detail free to change later.

Deriving that key from the passphrase costs SQLCipher a real quarter of a million rounds of
PBKDF2-HMAC-SHA512 on every ordinary open, which is milliseconds on a phone and a genuine pause on an
e-ink tablet's CPU. `crypto/RawKeyDerivation` exists to pay that cost once per file rather than once
per open: it runs the identical KDF by hand — PBKDF2-HMAC-SHA512, 256,000 iterations, a 32-byte
output — over the same salt SQLCipher itself uses, which is simply the file's own first 16 bytes,
written in plaintext for exactly this reason. The result is handed to later opens as the
`x'<hex>'` raw-key literal SQLCipher recognises, which skips the KDF entirely and turns an open that
cost hundreds of milliseconds into one that costs tens. `RawKeyDerivation.toHex()` is pinned to
`Locale.ROOT` on purpose — a default-locale digit formatter can emit non-ASCII numerals under some
locales (Eastern Arabic under `ar_EG` was the case that was actually tested), and a key literal built
from those opens nothing, in a way that would only ever reproduce on a device set to that language.
None of these four constants — the KDF, the iteration count, the salt source, the key length — is a
number this app is free to change; changing any of them stops the derived key from matching what a
stock SQLCipher 4 build derives from the same passphrase, which surfaces as "wrong key" against a
perfectly good file.

## Where the passphrase lives, and where it never goes

The passphrase exists in exactly two persistent places and one process-local one, and nowhere else.

`crypto/PassphraseStore` is the device-local cache of the global passphrase, backed by
`EncryptedSharedPreferences` in the file `paintsprout_secure`, reached only through `crypto/SecurePrefs`
— the single door to every `EncryptedSharedPreferences` file this app opens. `SecurePrefs` exists for
two failure modes that are easy to miss until they bite: androidx.security's keyset creation is not
safe against two threads first-creating the same file at once (one mints a keyset, the other clobbers
it, and whatever the loser wrote is sealed under a key that no longer exists), so `SecurePrefs` puts
one lock around construction; and the Keystore can throw transiently in the seconds after boot, so it
retries once rather than turning a cold morning launch into a crash that reads as data loss. What
`PassphraseStore` protects with the Keystore is only the *cache* of the passphrase — the passphrase
itself is what actually encrypts every file, so the split is what keeps the files portable: a pulled
`.soil` opens with nothing but the string, while the copy that spares the artist retyping it can never
leave this device.

`crypto/KeySession` is the process-RAM copy, set once by `PaintsproutIndex.finishOpen()` the moment
the index has actually opened with the passphrase — never before, since anything reaching for it
before bootstrap has run is a sequencing bug this null is built to make loud. It is deliberately the
least durable home the passphrase has: nothing writes it to an Intent, to prefs, or to disk, and it is
gone the instant the process dies. `data/soil/NewSketchbook.kt`'s `createSketchbook()` reads it as the
one legitimate source of "the key that is already known good" for a brand-new file, and refuses to
create anything if it is null.

`crypto/DerivedKeyStore` is the Keystore-backed cache of *derived raw keys*, one per file id, in the
file `paintsprout_dkeys`. `crypto/KeyMaterial` is the only door onto it — `rawKey()` resolves cheapest
first (process RAM, then `DerivedKeyStore`, then an actual derivation persisted on the way out) and
`invalidate(fileId)` is the only way to forget one. `invalidate` drops the key from **both** tiers at
once, RAM and Keystore together, and the KDoc is explicit about why that has to be one call rather
than two: dropping only the Keystore half leaks the RAM copy for the life of the process, so a
deleted sketchbook's key would quietly outlive the sketchbook, and a file that later reused the name
would keep "opening" with the old file's key and reporting itself corrupt. Paper shipped exactly that
bug once; `KeyMaterial.invalidate` is written the way it is so this app does not repeat it.

The passphrase is never logged, never carried in an `Intent` extra, and never written into any
`SharedPreferences` file other than `PassphraseStore`'s own. `crypto/KeyOpener`'s log lines carry file
ids only. The two places the artist can see the key on screen at all are `RecoveryKeyActivity` (once,
on first mint, with a "Copy" button) and the debug build's overflow menu — both are the artist asking
for it, not a leak.

`library/DebugMenu.kt` exists in two copies, one per source set, and the split is structural rather
than a runtime flag. `src/debug`'s `DebugMenu` arms two tools on the library's overflow control: **Show
recovery key** (reveals and copies the cached passphrase — the alternative on a device with no file
manager worth the name is reinstalling to see the key again, which destroys the very library the
artist is trying to unlock) and **Forget cached key** (clears `PassphraseStore`, `KeyMaterial.clearAll`,
and `KeySession`, then kills the process so the next launch is a genuine cold boot rather than one
that finds the index already open and sails past `UnlockActivity`). `src/release`'s `DebugMenu` is a
different file with the same object name that simply hides the overflow control — the release build
does not contain a control that can reveal the recovery key, however well it might be hidden, because
the only way to be sure of that is for the code to be absent from the source set rather than merely
unreachable inside it.

## The boot state machine

`bootstrap/BootstrapActivity` is the only thing in this app that opens the index, and the manifest
marks it `noHistory` so it is never on the back stack — backing out of the library leaves the app
rather than landing on a boot screen with nothing left to do.

Getting into `paintsprout.db` is not one operation; `PaintsproutIndex.ensureReady()` resolves it to
one of four outcomes by **probing the file's header, never opening it**. `SoilCrypto.probe()` reads
the first 16 bytes only: plain SQLite begins with the literal magic string `"SQLite format 3 "`,
SQLCipher encrypts the entire first page so that magic is simply absent, and a missing, empty,
unreadable or too-short file reads as `Invalid`. A create-capable open asked "is this ours?" would
answer by minting a brand-new empty database exactly where the artist's library used to be if
anything went wrong; a probe that only reads bytes cannot do that.

- **`Invalid`** — no file, or an empty one — is a first launch: `GlobalKey.ensure()` mints or reuses
  the passphrase, the index is created encrypted from its first byte, and the raw key is derived once
  here and cached so no later launch pays for it again.
- **`Encrypted`** is the ordinary case. With no cached passphrase there is nothing to try, so the
  outcome is `NEEDS_UNLOCK` immediately. With one, the cached raw key is verified **against this
  file** before Room is allowed near it; a key that no longer fits — the library restored from
  another install, a debug build's key tried against a release build's file — is dropped rather than
  trusted, and the passphrase is asked to re-derive one. Only if the passphrase itself does not open
  the file does the outcome become `NEEDS_UNLOCK`.
- **`Plaintext`** is somebody else's file, or a damaged one, sitting where the index goes. It is
  **never opened, never touched** — the outcome is `FOREIGN_FILE`, which `BootstrapActivity` turns
  into an error dialog and nothing else. The one thing that must not happen here is treating an
  unrecognised file as a broken library and replacing it.

Every other screen that touches the index opens with `core/IndexGuard.ready(this)` as the first line
of `onCreate`. It exists for the one route into a screen that never passed through Bootstrap at all:
Android rebuilding a task's activities on its own after the process was killed in the background,
which on a memory-tight e-ink tablet is not an edge case but what happens when the library is tapped
in Recents a while after it was last used. `IndexGuard.ready()` restarts the task at `BootstrapActivity`
with `NEW_TASK | CLEAR_TASK` and finishes the caller when the index is not open; the caller returns
immediately.

`PaintsproutIndex.unlockAndOpen(context, passphrase)` is what `UnlockActivity` calls with whatever the
artist typed. It verifies the passphrase **read-only, against the file, before anything else is
built** — Room is never handed a key that has not already been shown to fit, because an unverified key
reaching SQLCipher as a file that will not decrypt is indistinguishable from a corrupt one, and a
corrupt-looking file is the one outcome this whole design exists to prevent. A wrong key returns
`false` and leaves the file byte-for-byte as it was; only a verified key goes on to be cached and
handed to Room.

`crypto/AttemptLimiter` backs the unlock screen's lockout, persisted in the same secure prefs file so
that killing the process does not reset it — a lockout anyone can clear by swiping the app away is
decoration, not a lockout. The schedule, carried verbatim from the family the app was modelled on: the
first two misses are free, three or four earn a 30-second wait, five through nine a five-minute wait,
ten or more an hour; a success clears everything. It is deliberately gentle at the front because the
person most likely to mistype a 32-character key twice is its own owner reading their own handwriting.
`AttemptLimiter` stores only failure counts and lockout timestamps, keyed by a constant `"GLOBAL"` —
never a passphrase, never a guess at one.

`UnlockActivity.attempt()` tries the string exactly as typed first, and only if that fails does it try
`GlobalKey.normalize(typed)` — the Crockford fold described above, upper-casing and mapping O→0,
I/L→1. Trying the literal string first means a key that is already correct never depends on the fold
being right.

## Opening a sketchbook

`crypto/KeyOpener.roomFactoryFor()` is the one place a `.soil`'s key is asked for, and it is the same
shape as the index's own resolution: cheapest correct answer first, never a shortcut that skips
verification. It calls `SoilCrypto.requireExisting(file)` first — this path never creates anything.
Then it looks for a cached raw key via `KeyMaterial.peekOrLoad()`; if one is cached, it is **verified
against this exact file** with `SoilCrypto.verifyRawKey()` before it is trusted. A cache can go stale —
the file behind an id replaced, its salt with it — and a stale key is invalidated rather than allowed
to surface as a lockout against a file the artist's real passphrase would happily open. On a cache
miss, or after a stale key is thrown away, the raw key is **derived right here, now**, from the
passphrase — the one KDF this file will ever cost this install — verified the same read-only way,
cached for next time, and only then is the Room open made with it.

State this plainly, because it is the rule the whole crypto stack is built to keep: **Room is never
handed a key that has not first opened the file read-only**, for the index and for every `.soil`
alike. `SoilDatabase.open()`, `PaintsproutIndex.ensureReady()`'s `Encrypted` branches, and
`PaintsproutIndex.unlockAndOpen()` all reach Room only after that verification has already succeeded.

This is new as of G6. Before the audit, the cold path — a cache miss — handed Room the passphrase
directly and let SQLCipher run its own KDF inside the open, while a second derivation warmed the
cache in the background afterward; that was two KDFs paid for one open, and the one open in the whole
app made with a key nobody had checked first. It was survivable in practice only because SQLCipher's
own corruption handler declines to delete a file once a codec is present (see the audit's first
finding below) — a fact about a dependency's default, not a guarantee this app's own code was making.
Deriving first and verifying read-only costs the same wall-clock time, since the KDF was being paid
either way; it also saves the second, redundant derivation, and it is what makes "a key reaches Room
only after it has opened the file read-only" true everywhere rather than true almost everywhere.

## The creation doors

There are exactly two places in the entire app that may bring a new encrypted file into existence,
and every other open is required to prove the file is already there before it touches it.

The first is `crypto/SoilCrypto.createRaw()`, reached in exactly one way: `data/soil/SoilDatabase.create()`
calls it from inside `stampAutoVacuum()`, to make the raw connection that stamps `PRAGMA auto_vacuum =
INCREMENTAL` onto the file's very first page before Room ever opens it (SQLite only accepts that
pragma while a database holds no tables, so it has to happen here or not at all). `SoilDatabase.create()`
in turn is called from exactly one place, `data/soil/NewSketchbook.kt`'s `createSketchbook()` — the
new-sketchbook flow, and nothing else. The second creation door is `PaintsproutIndex.ensureReady()`'s
`Invalid` branch, which creates the index itself on a genuine first launch.

Both doors refuse to run over an existing non-empty file — `createRaw()` and `SoilDatabase.create()`
each open with `require(!file.exists() || file.length() == 0L)` — because creation is never a repair:
a "create" that fixes an unopenable file fixes it by destroying it. Every non-creating open, in turn,
calls `SoilCrypto.requireExisting()` first: `SoilDatabase.open()`, `KeyOpener.roomFactoryFor()`,
`SoilCrypto.openRaw()` and `openRawKey()` all throw `SoilLockedException` rather than silently
proceeding against a file that is not there — the underlying primitives are themselves create-capable,
and pointed at a missing path unguarded they would happily mint an empty database that then
masquerades as the real one.

`createSketchbook()` writes in a deliberate order: the `.soil` file first — created, filled with its
sketchbook row, page row and meta row, sealed — and the index row **last**, only after the file is
known good. The KDoc calls this "the whole thing": the other order, claiming a card on the shelf and
then trying to write the file behind it, leaves a card that opens onto nothing if anything at all goes
wrong in between, and there is no worse thing a library can do than lie about what it holds. The
opposite failure — a file with no card — is survivable by comparison: an orphan in `Garden/` costs
disk space and nothing else, because nothing enumerates that directory and the index is the only
thing that says what exists.

When any step of a create fails, `discardHalfMadeSketchbook()` cleans up: it deletes the `.soil` and
its sidecars and calls `KeyMaterial.invalidate()` for the sketchbook's id. This is safe to do only
here, because the file was made moments ago by this same call and no card has ever pointed at it, so
nothing in it was ever anything the artist drew. The KDoc notes one accepted loose end: the raw key
for a new file is warmed on a background scope, so a slow warm can finish *after* the discard and put
a stale entry back in `DerivedKeyStore`. It is harmless — the id is a fresh UUID that no file will
ever carry again, so the stray key is never tried against anything — and it is a better trade than
blocking a failed create on a quarter of a million hash rounds just to guarantee the cleanup runs
first.

## Data-loss audit (G6, walked 2026-09-03)

The close-out audit, walking Paper's six-point data-loss checklist against this source rather than
assuming any of it still held. Every claim below was verified against the code as it stands, not
carried over on trust.

1. **Every open path wrapped.** All Room factories are built only in `SoilCrypto.roomFactory()` /
   `roomFactoryRawKey()`, each wrapped in `NonDestructiveOpenHelperFactory`. `Room.databaseBuilder`
   appears at exactly two sites in the whole app — `PaintsproutIndex.build()` and
   `SoilDatabase.build()` — and both take a factory from `SoilCrypto`. The only raw, non-Room opens
   are `SoilCrypto.openRaw()`/`openRawKey()`, which are exists-guarded and used solely by the
   read-only `verify*` helpers, plus `createRaw()`. **Finding:** the wrapper's `onCorruption` override
   is unreachable under SQLCipher — verified by reading `sqlcipher-android` 4.6.1's bytecode:
   `SupportHelper` builds an inner `SQLiteOpenHelper` that forwards the androidx callback's
   onCreate/onUpgrade/onDowngrade/onOpen/onConfigure and passes `null` as the `DatabaseErrorHandler`,
   never calling `Callback.onCorruption`. A null handler means SQLCipher's own
   `DefaultDatabaseErrorHandler`, whose `onCorruption` logs and then returns immediately when
   `SQLiteDatabase.hasCodec()` is true — so with the codec present it deletes nothing. That codec
   guard, not the wrapper, is what actually spares a file from a mis-keyed open; G1's md5 check across
   a wrong-key attempt is the measurement of it. The wrapper is kept anyway, as the belt for any
   future non-SQLCipher factory, and the rule that makes neither one load-bearing is the verified-key
   rule under "Opening a sketchbook" above. The comment in `NonDestructiveOpenHelperFactory` now says this in full; the
   claim is about 4.6.1 specifically and must be re-checked on any bump.
2. **No create-capable open outside the named entry points.** Creation is `SoilCrypto.createRaw()` →
   `SoilDatabase.create()` → `createSketchbook()`, and `PaintsproutIndex.ensureReady()`'s `Invalid`
   branch — nothing else. Both `require(!file.exists() || length == 0)`. Every non-creating open calls
   `requireExisting()` first (`SoilDatabase.open()`, `KeyOpener.roomFactoryFor()`, `openRaw()`,
   `openRawKey()`); the index's `Encrypted` branches reach Room only after `verifyRawKey()` /
   `verifyPassphrase()`, both of which are false — never true — for a missing file. Noted, not fixed:
   a file of 1–15 bytes probes as `Invalid` and would be created over, but nothing that short can be a
   real database, so nothing of value is ever lost by that gap.
3. **A missing file never loops into unlock.** A missing `.soil` → `SketchbookSession.open()` returns
   `null` → `SketchbookActivity` shows the "not here" problem dialog and finishes; an empty `.soil` →
   `requireExisting()` throws `SoilLockedException` → the "would not open" dialog and finish. A
   missing index → probe returns `Invalid` → *create* (first launch, reusing the stored passphrase if
   there is one) — never `Unlock`. `NEEDS_UNLOCK` is reachable only for an existing `Encrypted` file
   that neither the cached key nor the stored passphrase opens. Noted: an index deleted out of band
   while `Garden/` still holds files yields an empty shelf standing over intact orphans; nothing
   enumerates the Garden, so those files are safe and simply invisible.
4. **Delete invalidates the key cache.** `LibraryActivity.discard()` deletes the `.soil` and its
   `-wal`/`-shm`/`-journal` sidecars and calls `KeyMaterial.invalidate()`, which drops both the RAM map
   and the Keystore entry; it runs for a single delete and for every sketchbook returned by
   `deleteFolderRecursive()`, and in both cases the index row is stamped **before** the file goes — a
   kill in between the two leaves an orphan file on disk, never a card that opens onto nothing.
   `discardHalfMadeSketchbook()` does the equivalent for a failed create. Noted: a background `warm()`
   finishing after an invalidate can re-cache a key under a dead UUID; harmless, since no file will
   ever carry that id again.
5. **No passphrase in logs, intents or ordinary prefs.** Every `Log.*` call in the crypto and data
   layers was checked: `KeyOpener` logs file ids only, no log line anywhere carries key material or a
   passphrase, and exception messages carry file names, never keys. Intent extras across the app are
   sketchbook ids, folder ids, item types and display names, carried only between this app's own
   activities (`exported="false"` everywhere but the launcher, which takes no extras at all) — never a
   passphrase. The passphrase lives in `KeySession` (process RAM) and `PassphraseStore`
   (`EncryptedSharedPreferences`) only; `AttemptLimiter` shares that same secure file and stores only
   counts and timestamps. Accepted surface, not a gap: the recovery-key screen and the debug menu copy
   the key to the clipboard, but only at the artist's own request.
6. **No display names in prefs.** `LibraryPrefs` stores a folder id and enum `.name`s (sort field,
   sort order, browse mode); `RecentsPrefs` stores `RecentEntry(id, at)` as JSON; `ToolPrefs` stores a
   lead enum name. Display names are resolved from the encrypted index at read time, never cached
   anywhere that is not itself encrypted.

## What to re-check on a bump

- **SQLCipher version.** The bytecode claim in finding 1 — that `SupportHelper` never calls
  `Callback.onCorruption` and that `DefaultDatabaseErrorHandler` declines to delete when a codec is
  present — is about `sqlcipher-android` 4.6.1 specifically. Re-read `SupportHelper`'s constructor and
  `DefaultDatabaseErrorHandler.onCorruption` before trusting any of it against a later build.
- **androidx.security.** `SecurePrefs` depends on `EncryptedSharedPreferences`'s keyset-creation
  behaviour and the Keystore's post-boot transient; a version bump is worth re-testing the double-init
  race and the post-boot retry against, not merely trusting to keep working.
- **Room's corruption path.** `NonDestructiveOpenHelperFactory`'s override exists for the day a
  factory here is not SQLCipher's own — a plaintext Room open for a fixture, a library swap, a cache —
  at which point it is `FrameworkSQLiteOpenHelper`, not the codec guard, standing between a mis-keyed
  open and a deleted file. Confirm that path still calls `Callback.onCorruption` the way this file
  assumes whenever Room itself is bumped.
