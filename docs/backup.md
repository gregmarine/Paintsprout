# Backup

> Destinations and layout, device identity, the needs-backup rule, run ordering, what
> encryption costs a run, where the state lives, the Google Drive REST/OAuth path, the key
> classes, the Backup screen, and the limitations that are known rather than discovered.
>
> **Restore is implemented** — staging-first, replace-all. See [Restore](#restore).

---

## Overview

Backup copies every non-excluded `.soil` sketchbook plus the global index (`paintsprout.db`)
to one or both configured destinations. It is **manual-trigger only** — a "Back up now"
button — and **incremental by timestamp**: a sketchbook is re-copied only when its
`updatedAt` is newer than the last successful backup stamp for that destination.

Manual on purpose. A background sync that decides on its own when to send a folder of
paintings somewhere is not a thing to add quietly, and someone who has just finished a piece
knows better than a scheduler when it is worth copying.

Entry point: **Library → New → Backup…**

---

## Destinations & layout

Two fixed slots — not an arbitrary list:

| Slot | Mechanism | Layout |
|---|---|---|
| **LOCAL** | Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE` + `DocumentFile`) | Files written to the **root** of the chosen tree |
| **DRIVE** | Google Drive REST API v3, hand-rolled, no Play Services | `My Drive / Paintsprout Backups / <deviceFolderName> /` |

A run writes to **every enabled** destination. Either can be on or off independently, and
both can be active at once. A destination that fails records its error and drops out of the
run; the other still goes — a backup where one of two destinations is broken is still a
backup.

**LOCAL** can target internal storage, an SD card, or any SAF-reachable tree including a USB
drive. Because it goes through SAF, no storage permission is ever requested. Google Drive
does not register a `DocumentsProvider` in the SAF picker on these devices, which is exactly
why the DRIVE slot uses the REST API instead.

---

## Device identity

The Drive layout uses a **per-device subfolder** so several devices can share one backup root
without collision. The default name is:

```
<sanitized Build.MODEL>-<8 hex characters>
```

Generated once by `DeviceIdentity.defaultDeviceFolderName()` — each run of anything outside
`[a-zA-Z0-9_-]` collapses to `-`, then the ends are trimmed — stored in
`BackupConfig.deviceFolderName`, and editable on the Backup screen.

A hand-typed name goes through `DeviceIdentity.sanitizeTypedName()` instead, which is
**looser**: only `/ \ : * ? " < > |` become `-`, so "Greg's tablet (no. 2)" survives intact.
Both filters exist for one reason — the name doubles as a folder name, and a path separator
in it writes somewhere else entirely.

**The hardware serial is not used.** `Build.getSerial()` needs a privileged permission and
returns `"UNKNOWN"` on an ordinary sideloaded build, which would give every device the same
folder.

---

## Filename scheme

Backup files are named by **UUID**, never by display name:

- `<sketchbookId>.soil` — one per non-excluded sketchbook
- `paintsprout.db` — the global index

UUID names give stable replace-in-place identity: renaming a sketchbook in the library does
not orphan its backup. Names and folder ancestry come back from the restored index — and each
`.soil` carries its own copy in `sketchbook_meta`, which is what makes a single backed-up file
importable on its own.

---

## The needs-backup rule

A sketchbook needs backing up to destination *X* when **all** of:

- it is not excluded,
- and `lastBackedUp[X] == null` **or** `updatedAt > lastBackedUp[X]`.

The predicate is `BackupPredicates.kt` (`needsBackup(updatedAt, lastBackedUp,
excludeFromBackup)`), on its own so it can be tested without a database, a device, or a folder
to write into. `IndexRepository.sketchbooksNeedingBackup(kind)` applies it to every live
sketchbook row.

Stamps are per destination (`lastBackedUpLocal` / `lastBackedUpDrive`). **A failed copy does
not stamp**, so the next run retries it.

**Where exclusion is set:** long-press a sketchbook in the library → "Leave out of backups" /
"Include in backups".

Neither the exclusion toggle nor a backup stamp moves `updatedAt` — both are declared in
`IndexEdit` as not bumping it. They are policy and bookkeeping, not edits to a painting, and
bumping would re-flag on the spot the very file the run had just finished sending.

---

## Run ordering

Sketchbooks first, index **last** — after every per-sketchbook stamp has been written, so the
backed-up index describes the run that just finished rather than the one before it.

1. **Resolve destinations**, failing per destination rather than aborting the run.
2. **Pre-flight the Drive token** (`DriveAuth.getAccessTokenSilent`), then find-or-create the
   device folder.
3. **Build the work list** — one `(sketchbook, destination)` pair per book needing that
   destination.
4. **Compact pass.** Each book in the list — unique across destinations, so one bound for both
   is handled once — is opened and sealed, running the same seal a document gets on close:
   purge tombstones from prior sessions, drop stale raster caches (75–88% of a document, as
   measured), `VACUUM` only if something went. Compaction preserves `updatedAt`, so the book
   stays flagged and is sent in its now-smaller form. The open/close also **absorbs the WAL**,
   so the `.soil` alone is a complete copy. A book that cannot be opened unattended — its own
   passphrase and not unlocked this session — is skipped here; failure is swallowed, never a
   reason to skip the copy that follows.
5. **Copy each pair**, stamping on success. A book that could not be checkpointed in step 4 has
   its non-empty `-wal` **copied alongside**, and both must land before it is stamped; when the
   WAL *was* absorbed, any stale sidecar at the destination is deleted instead — a fresh
   `.soil` paired with an old `-wal` is corruption on restore. A `.soil` missing from disk
   counts as *skipped*, not *failed*.
6. **Checkpoint the index** (`IndexGate.checkpointAndVacuum`), snapshot it to a local temp and
   **probe the snapshot** before streaming it out. The index never closes during normal use, so
   a copy streamed straight from the live file could be torn by a concurrent write plus an
   auto-checkpoint. If the snapshot or the probe fails, the run falls back to the live file.
7. **Copy `paintsprout.db`** to each destination. LOCAL streams to a `.part` sibling, moves the
   previous file to `.old`, then renames in. DRIVE PATCHes the existing file id.
8. **Stamp `lastRunAt` only if at least one destination succeeded.** A run where everything
   failed must not report "Last backup: just now" — that is the one lie a backup screen cannot
   tell.

---

## What encryption costs a run

Encrypted `.soil` files are copied **as ciphertext** — no prompt, no decryption. SQLCipher
encrypts the whole file, so a byte copy is enough. The index is encrypted too, so a backup is
opaque without the source device's key. Restore never asks for it inline: it stages and
commits the ciphertext as it is, and the unlock gate after the restart is what collects that
library's recovery key.

The one thing encryption costs is **compaction**. A private-passphrase sketchbook cannot be
opened unattended — unless this session already unlocked it, which puts its derived key in
RAM — so it is copied un-compacted, with its `-wal` sidecar. Everything else about the copy is
identical.

A sketchbook with **no** encryption (a real, deliberate state — see `DocumentKeying`) opens
with no key at all and compacts normally.

---

## Where the state lives

All of it is in `paintsprout.db`, in typed columns on the `objects` row — the index has no
generic JSON payload and did not grow one for this.

**Index schema v2** added three columns, by additive `ALTER TABLE` only:

| Column | Holds |
|---|---|
| `params` | the backup config singleton's JSON, and nothing else |
| `lastBackedUpLocal` | epoch ms, or NULL for never |
| `lastBackedUpDrive` | epoch ms, or NULL for never |

Exclusion is **bit 1 of `flags`**, beside bit 0 (encrypted). The two are independent: setting
either must leave the other exactly as it was, since an exclusion that cleared the encrypted
bit would make an encrypted book look plaintext to every reader of the index.

A row from before the migration has NULL in all three, which reads as "never backed up, not
excluded" — exactly right for a library the first run has not seen. `SchemaSqlTest` builds a
v1 table, migrates it, builds a fresh v2 table, and asserts the two shapes are identical;
that shape is what Room validates on open, so it decides whether an upgraded device can open
its library at all.

The settings singleton is one row at `Sentinels.BACKUP_CONFIG_ID`
(`00000000-0000-0000-0000-6261636b7570`, "backup" in hex), type `backup_config`, carrying
`BackupConfig` JSON in `params`. Unlike its sentinel neighbours it is **not** created at
launch — nothing reads it until someone opens the Backup screen, and a device that never does
should carry no row saying so. Unreadable JSON degrades to "no config", never to a crash.

`BackupConfig` fields:

| Field | Type | Purpose |
|---|---|---|
| `deviceId` | `String` | Stable per-install id, minted once |
| `deviceFolderName` | `String` | The Drive subfolder this device owns |
| `localTreeUri` | `String?` | Persisted SAF tree for LOCAL |
| `localEnabled` | `Boolean` | LOCAL slot active |
| `driveTreeUri` | `String?` | Unused; kept so an older row still decodes |
| `driveEnabled` | `Boolean` | DRIVE slot active |
| `driveAccountEmail` | `String?` | Display only, non-secret; null = not connected |
| `lastRunAt` | `Long?` | Epoch ms of the last run that landed something |

---

## The Google Drive path

### Why not SAF

Google Drive does not register a `DocumentsProvider` in the SAF folder picker on these
devices. The DRIVE slot therefore speaks Drive REST v3 directly, over `HttpURLConnection`,
with no Google API client library and no Play Services — the target hardware does not reliably
have the latter, and a sign-in flow depending on it is a backup feature that does not exist on
the hardware it is for.

### OAuth: WebView + PKCE

- **Client type:** Desktop app, created in the Google Cloud Console.
- **Redirect URI:** `http://localhost/oauth2callback`, intercepted in `DriveAuthActivity`'s
  `WebViewClient.shouldOverrideUrlLoading`. Nothing listens there and nothing needs to — the
  authorization code is sitting in the query string of the request we intercept.
- **User agent:** a Chrome UA is set before `loadUrl()`. Google refuses OAuth in a WebView that
  identifies itself as one (`disallowed_useragent`); this is about the string, not the engine.
- **PKCE:** 32 random bytes → SHA-256 → base64url (RFC 7636).
- **`access_type=offline` with `prompt=consent`** is what actually returns a refresh token —
  without the second, Google issues one only on the very first consent and silently omits it
  on every reconnect afterwards.
- **Credentials** come from `System.getenv()` into `BuildConfig` at build time and are **never
  committed**. A build without them compiles fine; the Drive slot says it is not configured.

### Token hygiene

- **Access tokens live in RAM only** — never persisted, never logged, never in an Intent extra.
- The **refresh token** is in `DriveTokenStore`, its own keystore-backed
  `EncryptedSharedPreferences` file (`paintsprout_drive`) — a third file beside the passphrase
  vault and the derived-key cache, for the same reason those two are separate: disconnecting
  Drive must not be able to touch what opens the library.
- Every run calls `DriveAuth.getAccessTokenSilent(context)`. No UI after the first consent.

### The `drive.file` scope

Scope is `https://www.googleapis.com/auth/drive.file` — per-file: the app sees and manages
**only what it created**. It creates its own visible "Paintsprout Backups" folder. There is no
Drive folder picker, and there cannot be one without the full `drive` scope and the annual
third-party security assessment that comes with it. `drive.file` is *sensitive* but not
*restricted*, so no assessment is required.

### Replace-in-place

Drive will happily keep several files with the same name in one folder, so "upload" alone
would grow a new copy of every sketchbook on every run. Each run instead:

1. **searches** for an existing `<uuid>.soil` / `paintsprout.db` by name in the device folder,
2. **PATCHes** its content if found — same file id, same revision history — or **POSTs** to
   create it if genuinely absent.

Folders are resolved **find-or-create every run**, with no cached ids: a user who deletes the
backup folder in Drive gets it quietly recreated rather than a run that fails against an id
pointing at nothing.

Uploads use the **resumable** protocol (initiate → `Location` session URI → streaming PUT),
with `setFixedLengthStreamingMode` so a large `.soil` never sits in memory. Chunked upload with
`Content-Range` and `308 Resume Incomplete` is future work for interrupted uploads over flaky
Wi-Fi.

---

## Google Cloud Console setup (one-time)

Required before the Drive flow will succeed at all.

1. **Create or pick a project** at https://console.cloud.google.com.
2. **Enable the Drive API:** *APIs & Services → Library → Google Drive API → Enable*.
3. **Configure the OAuth consent screen:**
   - User type **External**.
   - App name (e.g. "Paintsprout"), support email, developer contact.
   - **Add scope** `https://www.googleapis.com/auth/drive.file` — listed as *sensitive*, not
     *restricted*.
   - **Publishing status:** for personal or multi-device use, leave it in **Testing** and add
     your Google account under **Test users**. For a public release, publish and go through
     standard verification for the sensitive scope.
4. **Create an OAuth 2.0 Client ID of type _Desktop app_** (*Credentials → Create credentials →
   OAuth client ID → Desktop app*).
   - Authorized redirect URI: `http://localhost/oauth2callback`.
   - Copy the client ID and secret.
5. **Put them in your shell profile** (`~/.zshenv` or `~/.zprofile`):
   ```sh
   export DRIVE_CLIENT_ID="<your-client-id>.apps.googleusercontent.com"
   export DRIVE_CLIENT_SECRET="<your-client-secret>"
   ```
   Source it and rebuild. They reach `BuildConfig.DRIVE_CLIENT_ID` /
   `BuildConfig.DRIVE_CLIENT_SECRET` via `System.getenv()` in `app/build.gradle.kts`. **Never
   commit them.**

---

## Key classes

| Class | Location | Role |
|---|---|---|
| `BackupKind` | `data/backup/BackupKind.kt` | `enum { LOCAL, DRIVE }` |
| `BackupPredicates` | `data/backup/BackupPredicates.kt` | `needsBackup(…)` — the incremental rule, alone and testable |
| `BackupConfig` | `data/backup/BackupConfig.kt` | `@Serializable` settings; `toJson()` / `fromJson()` |
| `BackupConfigStore` | `data/backup/BackupConfigStore.kt` | The singleton row, read and written through `IndexRepository` |
| `BackupResult` / `DestResult` | `data/backup/BackupResult.kt` | Per-destination counts and errors |
| `BackupEngine` | `data/backup/BackupEngine.kt` | One whole run, on `Dispatchers.IO` |
| `DeviceIdentity` | `data/backup/DeviceIdentity.kt` | The generated default name, and the looser filter for a typed one |
| `SafBackupWriter` | `data/backup/SafBackupWriter.kt` | LOCAL writes: `.part` → rename, never a torn replace |
| `SafBackupReader` | `data/backup/SafBackupReader.kt` | LOCAL reads: enumerate device folders, copy files out |
| `DriveAuth` | `data/backup/DriveAuth.kt` | PKCE, the auth URL, the code exchange, the silent refresh |
| `DriveTokenStore` | `data/backup/DriveTokenStore.kt` | The refresh token, in its own keystore-backed file |
| `DriveApiClient` | `data/backup/DriveApiClient.kt` | Drive REST v3 by hand |
| `DriveBackupWriter` | `data/backup/DriveBackupWriter.kt` | The engine's view of the DRIVE slot |
| `RestoreSource` | `data/backup/RestoreSource.kt` | `SafRestoreSource` / `DriveRestoreSource`; `listDevices()` / `fetchInto()` |
| `RestoreEngine` | `data/backup/RestoreEngine.kt` | Staging-first, aside-swap restore, plus `recoverInterrupted()` |
| `BackupSettingsActivity` | `BackupSettingsActivity.kt` | The Backup screen |
| `DriveAuthActivity` | `DriveAuthActivity.kt` | The consent page in a WebView |

---

## The Backup screen

Built in code like every other screen here, reached from **Library → New → Backup…**.

- **This device's folder name** — an editable field and a Save, used as the Drive subfolder.
- **Local backup** — status, "Choose folder…", an on switch. Picking a folder takes the
  persistable read+write permission **and turns the slot on in one step**: someone who has
  just chosen a backup folder has said what they want, and a second switch to find afterwards
  is only a way to think backup is on when it isn't.
- **Google Drive backup** — status, Connect / Disconnect, an on switch. Disconnect forgets the
  refresh token as well as the setting, because "disconnect" has to mean it.
- **Back up now**, enabled when at least one destination is ready, and the last-run line.
- **Restore** — see below.

The run is guarded by an `AtomicBoolean`: two concurrent runs would stamp over each other's
timestamps. Progress goes to a dialog; the summary that follows gives per-destination counts
and every error. The whole thing is wrapped in a `try/catch` — the engine guards each file
copy, but its own index calls can still throw (sealed underneath, a SQLite error), and that
has to end as a message about a failed backup rather than a crash mid-run.

**Debug builds write into a `dev/` subfolder** inside each destination, so a development
device pointed at the same folder as a real one cannot overwrite real backups. Release builds
write to the destination root.

That is the second of two separations, and they are independent. A debug build also carries
the `.dev` application id suffix, so it is a different app with its own
`getExternalFilesDir` — a development install and a shipped one on the same tablet have
**separate libraries** and mint **separate device folder names**, and neither can see the
other's sketchbooks. The `dev/` subfolder is still worth having on top of that, because both
builds can perfectly well be pointed at the same backup folder.

---

## Restore

In-app restore is implemented. It **replaces the entire current library** — not a merge, and
not a per-sketchbook import; importing one `.soil` is what that is for. The user is told so in
those words before anything happens.

**Choosing a backup.** "Restore from a backup…" asks for the source, lists the device folders
found there with their sketchbook counts, then requires an explicit "Replace your library?"
confirmation naming the backup and warning that its recovery key will be needed. Enumeration
is one level deep: a SAF tree counts as a device folder if it directly holds a
`paintsprout.db`, and its immediate subfolders are scanned too; Drive scans the children of
"Paintsprout Backups". Only `*.soil` counts as a sketchbook, so a `.part` or `.old` left by a
killed backup run is never staged as one.

**Staging-first, aside-swap commit.** The same rule `CommitSwap` applies to a single file,
scaled up to the whole library: **never hold zero copies.**

1. Wipe and recreate `cacheDir/restore_staging`.
2. Fetch the backup's index and every `.soil` (plus any `-wal` sidecar) into staging. **Every
   per-file result is checked — one failure aborts the whole restore**, and each file streams
   to a `.part` name then renames, so a dropped connection never stages a truncated file.
   Drive listing is equally strict: a paging failure *after* the first page throws rather than
   returning a short list, because a shortened set would be committed as the entire library.
   The live library is untouched if any of this fails.
3. **Validate and check for room.** Probe the staged index and every staged `.soil` — an
   encrypted file passes, since it cannot be read deeper without the backup's key. Then require
   the staged payload's size **plus 64 MB** free on the library volume, because the commit
   copies the staged set in while the old library still exists aside. Otherwise hard-fail with
   the shortfall named.
4. Seal the index, **rename the live index and `Garden/` aside** into `restore_replaced/`, copy
   the staged garden in, and install the staged index **last** as the commit marker.
5. Only once the index is in place: clear the global passphrase and every cached raw key, drop
   the open-documents set and the last-open pointer, and delete the aside copy.

A failure before step 4 leaves the live library open and untouched. A failure *inside* step 4
rolls the aside copy back and reopens the index, so the app keeps working without a restart —
and so does a cancellation, whose reopen runs under `NonCancellable` because the suspending
call would otherwise be cancelled along with everything else.

**Crash recovery.** A kill mid-commit is repaired at launch by `RestoreEngine.recoverInterrupted`,
called from `IndexGate.open` **before `SwapRecovery`** — the aside holds the whole previous
library, index and garden together, and putting it back is what makes the files the swap repair
looks at exist at all. The installed index is the commit marker, which makes the two cases
distinguishable with no state written anywhere: aside present and no live index means the swap
never finished, so the old library goes home; aside present and live index present means the
commit landed and only the tidying didn't, so the aside is discarded. Either way the leftover
`paintsprout.db.part` is stale.

**Restart into unlock.** The restored index is encrypted under the **backup device's** key,
which is not this one's, so the next launch necessarily lands on the unlock gate. The success
dialog therefore has a single non-cancelable "Restart" that relaunches and hard-exits. Clearing
the cached keys in step 5 is what makes that deterministic — a stale cached key fails
verification and is indistinguishable from corruption by the time SQLite sees it.

---

## Known limitations

- **Renaming the device folder orphans the old one.** Prior backups in it are not migrated.
- **Deleting a sketchbook does not remove its backup file.** The needs-backup sweep skips
  tombstoned rows; it never reaps. Harmless for restore — the restored index simply doesn't
  reference the orphan — but the bytes accumulate. Collecting them is future work.
- **There is no release build yet.** No `signingConfig` is defined, so `assembleRelease`
  produces an unsigned APK that will not install. The `.dev` suffix means a development and a
  shipped build *could* coexist, but until a keystore exists there is nothing to coexist with
  — and everything below about release behaviour is therefore untested. Notesprout is in the
  same state.
- **Debug builds cannot restore from Drive.** Debug backups land in `<deviceFolderName>/dev/`,
  and `DriveRestoreSource` only treats a direct child of "Paintsprout Backups" that itself holds
  a `paintsprout.db` as a device folder. LOCAL restore is unaffected — the picker scans the
  chosen tree *and* one level of subfolders, so `dev/` is offered. Release builds write to the
  destination root and restore normally on both.
- **A sketchbook open in the editor is skipped by the compact pass** and backed up from its last
  sealed state. Backup is launched from the library, where documents are closed, so this is not
  expected in normal use — but live, unflushed edits are not included.
- **Drive needs a Google Cloud project** with the Drive API enabled and a Desktop-app OAuth
  client. One-time, manual, and described above.
- **Drive backups go to an app-created "Paintsprout Backups" folder.** An arbitrary
  pre-existing Drive folder would need the full `drive` scope and Google's restricted-scope
  assessment.
- **SAF writes are slow on large sketchbooks.** Progress updates keep the screen responsive;
  they do not make the copy faster.

---

## Status

Built and unit-tested — the incremental rule, the config round trip in both directions, the
Drive query escaping, the flag independence, and the v1 → v2 migration shape. **Neither the
LOCAL nor the DRIVE path has been exercised on a device yet.** Both round trips want a run on
the Movink 11 before this is trusted with anything.
