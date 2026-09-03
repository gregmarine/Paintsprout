# The library

This is the subsystem doc for the shelf: `LibraryActivity` and everything G2 and G5 built around
it — folders, the paginated card grid, covers, pinned, recent, naming, moving, deleting, and the
back handling tying the screen to the device it runs on. It follows `sketchbook.md`'s register:
the decision and the failure it avoids, not a tour of the API. Written at G6 close-out,
2026-09-03, after G2 (the shelf) and G5 (covers, pins and recents) were both checked by Greg's own
hand on the NA5C. By locked decision this app takes Notesprout Paper v0's library design whole —
breadcrumb folders, a paginated card grid, covers, pinned and recent, the long-press sheet — and
everything below is this codebase's own reasoning about that design on this panel, not a
description of Paper's source.

## The shelf

`LibraryActivity` is one screen wearing three shelves (see "Modes, not screens"), built around a
grid that does not scroll. Every scroll on an EPD panel is a full-screen repaint chasing a finger
and settles wherever the gesture's physics stopped it, not where the artist aimed; a page of cards
that simply replaces the last page costs one refresh and lands exactly where it was going. The
price is that the grid must know how many cards fit before drawing any of them, so `LibraryGrid`
is built from an `OnGlobalLayoutListener` on `gridContainer` rather than in `onCreate` — the first
listing waits on a layout pass, because a grid measured against a guess is wrong until the guess
happens to be right.

The breadcrumb bar puts **Up at the far left** and **`GONE` at the root**, not merely invisible:
at the root the trail should start flush with the panel's edge, with nothing standing there to
explain a gap that leads nowhere. `GridGeometry` decides the grid's shape from the container's
*usable* area — width and height with the container's own padding subtracted first. That
subtraction fixes a specific G2 device-walk defect: **a view's `width` includes its own padding**,
and the grid area carries a screen margin either side, so measuring against the whole container
width hands the third column pixels that do not exist and draws it half off the panel — reading as
a card that is simply too big, the wrong thing to go and fix. `GridGeometry` is pure and kept apart
from the view precisely so this question can be pinned down without a device in the room;
`GridGeometryTest` holds the NA5C's own numbers (1860 × 2480 px, density 1.875, 992 dp across).

**Three columns, two rows, six cards a page.** Four columns would have filled the grid exactly with
twelve, and six was chosen anyway at G2's phase-start wizard: a sketchbook is found by looking at
it, and twelve smaller covers buy pagination at the cost of covers too small to recognise a drawing
in — sharper still once G5 made the cover an actual photograph of the last page drawn. Cards keep a
page's proportions (`CARD_ASPECT` = 1.4) rather than stretching to fill leftover height, because a
card that grows to swallow a gap is a card whose cover no longer has a page's shape.

**The grid anchors to the top; the leftover height stays at the foot** — the one geometry decision
here settled by looking at the panel rather than by argument. Centring the block was tried first
and read wrong: a shelf fills from the top down, so the first card belongs in the top-left corner
of the grid area on every page, however few cards it holds. Splitting the slack top and bottom
instead makes the first row sit lower on a half-full last page than on a full one, so the shelf
appears to move under the artist as they page through it. `LibraryGrid.bind` pins the `GridLayout`
to `Gravity.TOP` inside a `MATCH_PARENT`-width container for the same reason width is not wrapped
to fit: a wrapped grid centres its own contents, so a folder holding one sketchbook would put that
card in the screen's middle and a folder holding four would put the first one somewhere else again.

Between Sort, New folder and New sketchbook in the bottom bar sits the pager, weighted to centre in
whatever room the buttons either side leave rather than pinning to one edge. **The empty left end
of the bottom bar is where Pinned and Recent live**, a gap G2 built and left open on purpose for
exactly this; G5 is what filled it.

**The e-ink design system, as adopted.** G0's locked answer took Notesprout's e-ink system
wholesale — mono palette, no Material, no elevation or ripple, 1 dp inkBlack borders, Tabler
outline vocabulary — written fresh rather than copied, since it is the proven answer for this panel
and arc 1 is greyscale regardless. `colors.xml` has four colours — black, white, a light ink for text the
reader is meant *not* to read, and a border grey; e-ink has too few stable grey levels for
mid-grey to read as hierarchy, so anything informative stays full `inkBlack` and gets smaller
rather than greyer. `themes.xml` starts from `Theme.AppCompat.Light.NoActionBar`, not a Material
theme, because Material's components arrive wanting elevation, ripples and tonal surfaces — all
three rendering as smeared grey or a full-screen refresh on EPD — and starting from AppCompat means
never switching them off one widget at a time; `stateListAnimator` is `@null` everywhere a button
appears for the same reason. `ActionSheetDialog` builds the long-press and sort sheets in code with
Tabler outline icons at stroke 2 in a column that is always reserved, so a sort sheet's labels never
shuffle sideways depending on which option is ticked.

**The pressed ring is the only feedback**, and dialog buttons needed it added by hand. G2's walk
found every `AlertDialog` shipping with SHOUTING BUTTONS — AppCompat builds dialog buttons from
`buttonBarPositiveButtonStyle`, not the `android:`-prefixed name the theme had been setting, so the
style was resolved by nobody; `themes.xml` now sets both spellings. The same pass showed Cancel and
Create, drawn transparent, unbordered and barely padded, reading as one unbroken run of text rather
than a choice — worst on a dialog, where the last thing read before an irreversible action should
not be ambiguous about where it ends. `Widget.Paintsprout.DialogButton` gives dialog buttons real
padding, a gap, and the app's own pressed ring, which costs nothing at rest and hands back the tap
feedback the missing ripple took away — load-bearing on a button waiting on a database read, since a
tap already registered looks identical to one that was missed. **Paper carries this exact defect,
unfixed**; found here first, fixing it there belongs to that repo.

`windowLightStatusBar` is set explicitly rather than left to the panel's default — stated, so a
correct-looking screen is not merely correct by the panel's accident, and the first time this app
meets a stock launcher the clock is not white on white. `core/TopGuard.kt` is the real guard: BOOX draws a status bar over the window
top (Supernote's guard is zero), so a layout starting at y = 0 either sits under the bar or puts
tappable chrome against the top edge, where a tap pulls the shade down instead of firing.
`applyInsetPadding` reads each side's base padding once and *adds* the system-bar inset to it on
every delivery, rather than assigning the inset directly — the obvious version silently discards
whatever padding a screen's own layout had already asked for, invisible on a bare screen and a real
bug on any screen with a top bar, exactly the kind that hides behind its own fix.
`NewSketchbookActivity` passes `followIme = true` so the keyboard's height is added to the bottom
padding rather than panning the window, since a panned window on e-ink redraws everything to move.

## Modes, not screens

`BrowseMode` is `NORMAL`, `PINNED`, or `RECENTS`. Pinned and Recent are **modes of the one library
screen**, not screens of their own: grid, pagination, covers and the long-press sheet are identical
across all three, and only the card source (`ShelfListing.cards`) and the top bar's text change.
Two more Activities would be two more copies of the paging arithmetic, the sheet and the cover
loading — three places to fix the next thing found wrong with a card.

In a mode, `renderChrome()` swaps the breadcrumb for a title and a `✕`, because a mode is not a
place in the folder tree — there is no trail to draw and no folder above it to climb to. New folder
and New sketchbook stand down, going `INVISIBLE` rather than `GONE`: they hold the right end of the
bottom bar, and letting them collapse would slide the pager sideways every time a mode opened or
closed — a pager moving under the thumb turning pages is judged worse than two idle, untappable
buttons. There is no honest answer to "in which folder" for a pinned or recent sketchbook, so
nothing tries to invent one — Pinned and Recent are views of the library, not places in it.

**Sort applies to Pinned and never to Recent — and the sheet still opens, ticks and saves in
both.** `ShelfListing.pinned()` sorts pinned sketchbooks by the shelf's current field, because Sort
sits in the bar the whole time Pinned is open and ignoring it would read as a broken button.
`ShelfListing.recent()` never sorts: "the order *is* the information" — Recent is a record of what
happened, not a set of cards to be arranged, and re-sorting it by name would leave a screen called
"Recent" that says nothing about recency. `showSortSheet()` still ticks and writes the choice to
`LibraryPrefs` even in Recent — that looks like a bug and is argued as not one: the choice takes
effect the moment the mode closes, so the tap is deferred rather than wasted. It is never disabled
or hidden to signal that it currently does nothing, because nothing in this app's chrome is ever
disabled or hidden to signal state.

**Mode buttons carry no "on" state**, an instance of the wider toolbar rule from
`apps/paintsprout_onyx/CLAUDE.md`: while the pen is armed the panel does not update the display, so
a button whose look is supposed to change when its state changes cannot be trusted to have been
redrawn at the moment it did. The concrete story is G4's undo arrows, faded once when the stack was
empty and read on the panel as broken; they now stay always bright and a tap with nothing behind it
does nothing, as BOOX's own apps do it. `wireBars()` applies the same logic to `btnPinned` and
`btnRecents`: tapping the button for the mode already showing puts it away again, because with no
highlighted-glyph state to read, the tap that opened a mode has to be the tap that closes it.

**The mode and the folder both survive relaunch.** `LibraryPrefs.mode` and `.folderId` are written
in `onPause`. `folderId` is left exactly where it was *underneath* a mode rather than replaced by
it — a mode sits on top of the folder the artist was standing in, so closing it has to return them
there, not walk them one level further up.

## Cards and covers

`ShelfListing` is where all three shelves' cards come from, split out of `LibraryActivity` because
this repo does not let a file pass roughly eight hundred lines without a written reason. The three
shelves are deliberately **not** one parameterised query with a filter: they are three different
ideas about what a library is for, and folding them together would hide that Pinned has no folders
in it and Recent is not sorted at all. `Sorting`'s tie-break is always the name, never the id,
because two sketchbooks touched in the same millisecond would otherwise come back in whatever order
SQLite happened to walk the rows and *change* order between two refreshes of an unchanged screen —
called out in its own KDoc as "the single most unsettling thing a library can do."

`CoverLoader` is `ShelfListing`'s sibling for the same file-size reason, and owns exactly the
"which thread, how much memory" questions:

- **The listing never reads a cover; a card does, one at a time, after the listing.** A whole-row
  listing would pull every cover in the library out of the encrypted file to lay out six cards, so
  covers for the up-to-six cards about to be drawn are read after the listing, by id.
- **The cache holds stored bytes, not decoded bitmaps.** A stored cover is tens of kilobytes of
  WEBP; decoded, roughly two megabytes. Caching bitmaps would mean a library paged end to end holds
  a full bitmap per sketchbook ever shown — how a shelf becomes the thing that runs the device out
  of memory. Bytes make revisiting a page free of the expensive half (the encrypted read) while
  still paying the cheap half (decoding at most six small images) again.
- **A blob is data from a file, not a promise.** `decode()` reads header dimensions first, then
  picks an `inSampleSize` so nothing wider than `MAX_EDGE` (1024 px) is ever allocated at full
  size — a cover this app made decodes untouched at sample 1; the bound exists for a cover it did
  not make, since this app is not the only thing that will ever write to a `.soil` or an index.

**Covers are read before the card is bound, never painted in afterwards.** The grid is torn down
and rebuilt on every bind (six views is not worth a recycler, and holding views across a changed
listing is how a card shows a deleted sketchbook's name), so binding bare and then again with
pictures would be two full-panel repaints for one page turn — a visible flash of empty shelf before
the real one.

**A cover is a bake of the rows, not a picture of the screen.** `CoverSnapshot` renders the last
page shown through `StrokeRasterizer` offline at full size, then box-averages it down by exactly
three to 620 × 827. Rendering straight at a third would put a 1.2 px hairline and single-fleck
grain below one pixel, and bilinear downscale can step over a one-pixel line entirely. It runs on
the application scope behind the write queue, at `onStop` and again at `onDestroy`, never on the UI
thread and never off the live view — `renderToBitmap()` is main-thread, sees only whatever page is
loaded, and is gone by the time the close runs. See `docs/sketchbook.md` for the bake itself.

**Why the cover shows on the very first listing after backing out.** The first version baked
correctly on close and still drew a blank card, appearing only on the *next* listing, because
Android resumes the shelf before destroying the sketchbook screen (pause → resume caller → stop →
destroy) — a cover written from `onDestroy` lands after the shelf has already listed. Fixed with a
`leave()` step run *before* `finish()`: both the back arrow and the system back gesture funnel
through it, writing page count, cover and last-edit stamp while the screen is still up; `onDestroy`
learns from a `leaving` flag that the card is already done. `onStop` still snapshots independently
for a background kill. G4walk showing its cover on the very first listing after backing out is the
proof this actually closes the gap.

**The pin badge** sits on its own small white island rather than straight on the cover, because a
badge in plain `inkBlack` over a dark passage of the drawing would simply disappear. It is shown
even on the Pinned shelf itself, where the answer is trivially "yes" for every card, because a badge
that vanished there would make a card look different depending on how the artist got to it.

**"N pages"** — `ShelfListing.metaLine()` — is what a card says under its name on the shelf and in
Pinned; in Recent, `folderLine()` overwrites the same slot with "In *Folder*" instead, because the
card carries the finished sentence the caller chose, not raw parts a card would need to know a
mode to interpret.

**The placeholder for a blank page is not a placeholder.** The cover frame's background is
`paperWhite`, and a sketchbook with no stored cover simply shows that empty white rectangle — the
honest picture of a sketchbook nobody has drawn in yet, not an emblem standing in for one, which is
why the frame's shape was built in G2 before there was anything to put in it. It is also the
fallback when a cover fails to decode: "a blank page is a far better lie than a broken-image glyph
on a shelf."

## Pinned and Recent

`IndexRepository` keeps Pinned as a real index list — `ListIds.PINNED_LIST_ID`, type `LIST` — with
each pinned sketchbook a `LIST_ITEM` row pointing at it. `ensurePinnedListExists()` is idempotent
and called on the read path, never as a migration: a library that has never pinned anything simply
has no such row yet. `pin()` no-ops if already pinned, so a double-pin never creates a second edge
that would draw the card twice and leave the second copy unpinnable by tapping the first.
`pinnedSketchbooks()` filters against what is actually alive. `pinnedIds()` is one query for a whole
page of badges rather than one lookup per card. **`deleteSketchbook` scrubs the pinned edges before
stamping the row** — reversed, Pinned would hold an edge to a row it may no longer show, forcing
every future read to filter dead entries forever rather than never having the edge at all.

`RecentsList` (pure, no Android, checked entirely by `RecentsListTest`) holds the rules, kept apart
from storage for the same reason `Sorting` and `GridGeometry` are pure: the failures here are
silent — a duplicate, an entry that never falls off, a drifting order — none of which crash or lose
a drawing, all of which end with a "Recent" shelf the artist stops trusting. `MAX` is 20: Recent is
for "the thing I was just in," not for browsing, and a longer list starts competing with the
library itself. `record()` removes any earlier entry for the same id before adding the new one, so
opening one sketchbook repeatedly cannot fill the whole shelf with copies of itself.

`RecentsPrefs` stores only ids and timestamps, in its own file, rewritten on every open. Ordinary
`SharedPreferences` is plain unencrypted XML, and a recents file listing actual titles would be a
plaintext record of what the artist has been drawing lately, sitting outside the encrypted index
the library exists to keep shut. Pruning happens on the read path (`ShelfListing.recent()`), the
one moment the prefs file and the index are both open and can be compared — the list has no way to
hear about a delete on its own — and writes nothing when nothing changed.

**"In *folder*" under each recent card** comes from one read of parent-folder names for the whole
listing, the same "one read per page" discipline as covers and badges. The fallback, "In Library,"
is more than generic: the delete sweep stamps children before parents specifically so a live
sketchbook cannot lose its folder through this app's own paths, so an unnameable parent means a
file edited from outside or a half-applied restore — the root is where anything with no living
folder above it has to be looked for.

**Opening is not work, and does not move `updatedAt`.** `openSketchbook()` writes to `RecentsPrefs`,
never the index: `updatedAt` is what "last worked on" sorts by, a promise about actual work, and a
shelf that files everything looked-at as everything worked-on is a shelf the artist eventually
cannot navigate. The close carries the `.soil` file's own last-edit time forward through
`touchIfNewer` (forward only, never `now`) rather than bumping the index stamp on every close —
before G5, every close bumped it, which meant looking at an old sketchbook filed it as the newest
work, collapsing Recent and "last worked on" into the same shelf under two names.

## Making, naming, moving, deleting

`NewSketchbookActivity` is a whole screen for one text field, not a dialog: a dialog with the
keyboard up on BOOX is a small window sandwiched between a dimmed background and half a screen of
keys redrawn every keystroke, where a full screen gives the field room to sit where it is typed and
the naming rule room to be visible while the artist breaks it. The default name is a timestamp,
"the one default that is never already taken and never means anything misleading," and arrives
selected — a fix for a real G2 defect where the field opened unfocused, `selectAll()` did nothing,
and the first tap put a caret at the end of a default the artist had to delete by hand. The fix is
posted rather than called immediately, since the field cannot take focus before layout and asking
too early fails silently.

`NameRules.validate()` returns a `@StringRes` id rather than a literal, kept callable and testable
without a `Context`. The rule is narrower than storage requires — a name lives in the encrypted
index, never a filesystem path — because a name carrying a right-to-left mark, a zero-width space
or a combining accent renders as something the artist cannot type back on a BOOX keyboard, and a
library is a place you find things by name. `.` and `..` are refused against future export, even
though nothing dangerous happens today. `MAX_CHARS` (64) is a card's problem, not a database's:
past that a name is ellipsised everywhere it appears. **The duplicate check is `COLLATE NOCASE`**,
matching the shelf's own case-insensitive sort — "Studies" and "studies" coexisting in one folder
is the exact confusion the check exists to prevent.

**The create order is file first, card last.** `data/soil/NewSketchbook.kt` seals the `.soil` file
— sketchbook row, page row, meta row — *before* `IndexRepository.createSketchbook` writes the index
row that puts a card on the shelf. The reverse order would leave a card that opens onto nothing the
moment anything fails in between, and there is no worse thing a library can do than lie about what
it holds; a file with no card, by contrast, is survivable — an orphan under `Garden/` costs disk and
nothing else. The whole create runs under one `try` so any failure, index write included, deletes
the half-made file and invalidates the just-cached key. See `docs/data.md` for the `.soil` format.

**Rename: the same name is a change of mind, not an edit.** `showRenameDialog` checks the name
against the original first and simply dismisses if unchanged — no duplicate check, no `repo.rename`
call, no stamp moved.

`FolderPickerActivity` reuses `LibraryGrid` for a folder-only browse — "choosing a folder should
look like the place it is choosing from" — and refuses two moves in the UI: a name already taken in
the destination, and a **folder moved into itself or its own descendant**
(`isSelfOrDescendant`). The second guard matters more: it is not a mistake the artist could notice
by looking at the result, since the folder and everything in it would simply stop being reachable
from the root — still on disk, still gone.

**Deleting a sketchbook is a soft delete of the row, plus a real removal of the file.**
`deleteSketchbook` scrubs pinned edges then stamps the row; the file, its sidecars, and its cached
key are left to the caller (`LibraryActivity.discard()`) on purpose — an index that deleted files
behind its caller's back would turn every soft delete into a hard one, and a key left cached under a
dead id is one that will one day be tried against whatever next claims that id and reported as
corruption, "a bug this family has actually shipped once."

**Deleting a folder counts everything inside it, all the way down, before it asks.** A shallow,
one-level count would tell someone deleting a folder holding a folder holding thirty drawn-in
sketchbooks that "1 folder" is going with it — the confirmation is the only warning there is, so its
number has to be true. `deleteFolderRecursive` **stamps children before parents**: each stamp is its
own statement, and this device kills background processes routinely, so a kill partway through must
still leave a coherent shelf. Stamped parent-first, an interrupted delete would leave a *deleted*
folder holding *live* contents that no listing ever walks down to again — still there, unreachable.
Deepest-first, whatever is still alive at any instant still has a living parent all the way to the
root. The confirmation sentence names what actually goes ("2 sketchbooks and 1 folder inside it go
with it, for good"), which a one-level count would have gotten silently wrong for nested contents.

**Toast vs. dialog.** A toast only confirms something that already happened; anything explaining why
a tap *did not* work is a problem dialog, never a toast, because a missed toast on this panel reads
as "broken." Every name-validation, duplicate-name, and move refusal in this subsystem goes through
`Dialogs.problem`; the only toasts in the whole library subsystem are `DebugMenu`'s "key copied" and
"forgotten" confirmations, each of which confirms something that already happened and explains
nothing that failed.

## Back

Every back path here — `LibraryActivity.backCallback`, `FolderPickerActivity.backCallback` — is
registered through `onBackPressedDispatcher`, never an `Activity.onBackPressed()` override:
targeting SDK 35 on Android 15 means predictive back is on by default, so the framework never calls
`onBackPressed` at all. An override there compiles, reads as correct, and is dead code — back from
three folders deep would walk straight out of the app with nothing in the source saying so. The
callback is armed only while there is somewhere to go — a mode to close or a folder to climb — via
`canGoBack()`; disabled at the root with no mode open, the press falls through to the system and
correctly leaves the app. `LibraryActivity`'s callback closes a mode before it climbs a folder,
because a mode sits on top of whatever folder the artist was standing in — leaving the app from
inside Pinned because back skipped the mode would be the same class of fault as leaving from three
folders down.

**This cannot be checked from a desk.** An injected `KEYCODE_BACK` does not reach any app on this
device, this one or the system settings, so an adb-driven walk would silently pass even if the
callback were never wired up — logcat can at best confirm an `OnBackInvokedCallback` was
registered, not that its gating or ordering behave on a real gesture. Both were checked by Greg's
own hand at G2 close-out: back walks up a folder at a time and only leaves the app at the root.

## What the agent can and cannot verify

- **Finger taps and swipes work.** Every button, the pager, the long-press sheet, folder
  navigation, and page turning in the picker are agent-verifiable by adb.
- **Text entry works** — unlike Supernote, typing into the naming and rename fields behaves
  normally under adb, so those flows and their duplicate-name dialogs can be walked by an agent.
- **Back cannot be verified from a desk**; the callback ordering and gating need a thumb.
- **The pen cannot be simulated** — adb cannot inject stylus ink, so "drawing in a sketchbook" as a
  device-walk step means opening one, not actually inking it, without a human hand.
- **`monkey` does not reliably foreground this app**; a walk must launch with `am start -n
  <pkg>/<Activity>` and confirm `dumpsys activity activities | grep mResumedActivity` before
  trusting any screencap.
- **`install -r` immediately followed by `am start` can race package finalization**, leaving the
  package disabled — mistaken for an app defect during G2's first walk; `pm enable <pkg>` fixes it.
- **A device agent's failures need reproducing by hand before they are believed, exactly like its
  passes.** Both G1's and G2's first device walks reported critical defects in flows that worked
  correctly when re-run by hand; G2's ran out of budget after two of eighteen steps and reported the
  documented install race and an already-fixed focus bug as new "critical findings." The corollary:
  a walk this long does not fit one short agent pass — cut it into several, or drive it by hand.

## Debug tools

`library/DebugMenu.kt` exists as two complete files, one per source set, rather than one file gated
by a runtime flag. The debug version wires the shelf's overflow button to a sheet offering **Show
recovery key** (reads the cached global passphrase and offers copy-to-clipboard — the only
alternative on a device with no real file manager is reinstalling, which destroys the library being
unlocked) and **Forget cached key** (clears the cached passphrase and every derived raw key, shows a
toast, then kills the process after a 400 ms delay timed so the toast actually reaches the panel
first — a message with no frame never happened here, and it is the only confirmation the tap did
anything). Killing the process, not merely clearing memory, matters because the index is already
open in this process; without a kill, a relaunch would find it ready and sail straight past Unlock.

**The release build does not contain either action**, not merely hide it — the release `DebugMenu`
is a distinct, minimal file whose `install()` sets the overflow button `GONE` and wires nothing. Its
own KDoc states the reasoning as an absolute: a build that ships cannot contain a control that
reveals the recovery key however well hidden, and the only way to be sure of that is for the code to
be absent from the source set rather than merely unreachable in it — a runtime flag guard would
leave the reveal-key logic compiled into every release APK.
