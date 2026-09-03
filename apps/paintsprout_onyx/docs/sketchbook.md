# The sketchbook screen

> Started in **G3** and grown in **G4**. G6 owns the subsystem docs and will finish this one; what
> is here now is what those two phases were required to write down — the frame-silence ledger, the
> finger vocabulary, the page-swap contract as implemented, and the undo model.

## The frame-silence rule

**No app frame is presented while `paper.isPenActive`.**

This is not a performance guideline on this panel. Frames presented during a live raw contact are
*withheld from the panel* by the ink pipeline, and a later `invalidate()` of identical content is
damage-free — so chrome updated mid-stroke is not merely a wasted refresh, it is **invisible**, and
stays invisible until something unrelated damages that region. The failure presents as a label that
has stopped working rather than one that was drawn at the wrong moment, which is why it survives so
long unfound.

The gate is g-paper's own `PaperView.isPenActive`: open while the pen is writing **or hovering**,
plus a 350 ms tail. Hover counts because an EMR pen is in range between every stroke of a sentence,
so a gate that ignored hover would update between drawings rather than between marks.

`sketchbook/PenIdleGate.kt` is the implementation. Work is keyed, and a second request under the
same key replaces the first — a label that wants to say three things while the pen is down should
say the last of them once, not all three in order.

## The ledger of exceptions

Every deliberate exception to the rule goes in this table, with the reason it is allowed.

| Phase | What presents a frame | Why it is allowed |
|---|---|---|
| G3 | — | **None.** |
| G4 | — | **None.** |

G3's chrome is static by construction: the toolbar changes only on a tap, a tap is a finger, and a
finger arriving while the pen is active is a palm the component has already refused. The gate is
built and wired anyway, so that G4's page turns and undo counters arrive into a screen that already
obeys the rule rather than one that has to be taught it afterwards.

G4 is the phase that could most easily have spent an exception and did not. It brought the two
things on this screen that move on their own — a page swap, which repaints the entire panel, and a
page indicator and a pair of undo arrows that change every time a mark is made — and both are
behind the gate. The chrome goes through `PenIdleGate.run` exactly as G3's one label did. The swap
goes through `PenIdleGate.awaitIdle`, which is the same rule expressed as a wait rather than as a
deferred block: a page swap is three calls into the component that have to happen *together*, so
there is no block to hand a gate, only a moment the sequence must not start before.

`awaitIdle` is not tidiness. `loadStrokes` under a live contact **drops ink** — the frames it
presents are withheld while the pen is down, so the new page arrives invisibly and the marks still
being captured land against a model that was swapped out from under them.

## What the fingers say (G4)

The pen draws and the fingers navigate, and the two never share a gesture. Everything below is
recognised by `sketchbook/PageGestures.kt`, which is an **observer** fed from the Activity's
`dispatchTouchEvent` and **consumes nothing** — the firmware's ink pipeline and the toolbar buttons
still see every event, and the actions are side effects.

| Gesture | What it does | The rule |
|---|---|---|
| One finger, horizontal | Turn the page — left is forward, right is back | `SwipeRule`: horizontal-dominant, ≥ 15 % of the panel width, and either ≥ 2× the platform fling velocity **or** ≥ 40 % of the width |
| One finger, forward, on the last page | Append a page and land on it | Same rule. There is no add-page button; pages are only ever made at the end |
| Two fingers, stationary, tapped twice | Undo | Second pointer down arms it; centroid past `touchSlop` disarms; 4+ fingers disarm; lift within `getLongPressTimeout()`; the pair within `getDoubleTapTimeout()` and `doubleTapSlop` |
| Three fingers, stationary, tapped twice | Redo | The same, plus **the BOOX cancel rule** below |
| One finger anywhere on the chrome | Nothing — the sequence is ignored whole | So a toolbar button and a gesture can never both fire for one touch |

**The BOOX cancel rule.** Three fingers never reach `ACTION_UP` on this device: the Onyx SDK claims
three-finger touches for its own system gesture and cancels the sequence out from under the app. A
recogniser that waits for a clean lift waits forever and redo simply does not exist. So an
`ACTION_CANCEL` arriving on an armed, unmoved sequence whose peak pointer count was three **counts
as the tap** (Paper v0's treatment). Every other cancel is an interrupted gesture, and both tap
histories are thrown away so a half-seen tap can never pair with a real one later.

**Everything is pen-gated, three ways**, per `host-responsibilities.md` § Gestures and palm
rejection. On this panel a writing stylus produces *no* MotionEvents — the firmware paints — but the
hand resting beside it produces plenty, so every event the detector sees while the artist is drawing
is a palm by construction.

1. A sequence that begins while `isPenActive`, over chrome, from a stylus tool type, or while the
   screen has stood down is ignored whole.
2. The gate is re-read at finger-**up**, because the palm can land a beat before the pen enters
   hover range.
3. Undo and redo — the two that mutate state — wait one `PEN_ACTIVE_TAIL_MS` **escrow** and are
   dropped if the gate closed meanwhile. A palm micro-tap can complete ~190 ms before the pen is in
   hover range, which no proximity check at up-time can catch.

The page flip fires at the lift with only a re-check and no escrow, deliberately: it is not a
micro-tap a palm produces by accident — getting there takes a horizontal journey across a sixth of
the panel at a flick's speed. Making the artist wait a third of a second to watch the page turn
would be paying the palm tax on the one gesture that does not owe it.

`standDown()` is the screen saying it is busy — a page swap, a delete or a replay in flight, a
dialog up. A second flip arriving into the middle of the first is two page turns racing for one
panel.

## Turning a page (G4)

**`SketchbookActivity.showPage(pageId)` is the only thing in this app that changes what the paper is
showing.** Opening the file, a swipe, a delete and every undo all go through it, so the documented
swap contract is written once and cannot be got wrong in five places.

1. Read everything first, off the main thread: the page's marks, its row (for the recorded page
   size), and the live page list (for the position the indicator shows).
2. `gate.awaitIdle()` — see the ledger above.
3. `paper.clearForContentSwap()` → `paper.setPageSize(w, h)` → `paper.loadStrokes(marks)`, in that
   order with **nothing between them**. `clearForContentSwap` drops the old model without
   repainting, so the pixels already on the panel hold; `setPageSize` hands over the rectangle the
   marks were recorded in, which is what the component registers ink against, so setting it after
   the marks would draw the first frame against the wrong rectangle. Together it is **one** EPD
   refresh and the panel never shows blank. (`clear()` + `loadStrokes` would flash white in between.)
4. `session.currentPageId = pageId` — the session's idea of the open page is written *here*, when
   the page is on the glass, and nowhere else. A mark committing during a swap captures the page id
   at the commit and hands it to `recordMark`, so it lands on the leaf it was drawn on.
5. `session.rememberOpenPage(pageId)` on **every** page shown, not only the last, because a screen
   killed in the background never gets to write anything on the way out.
6. `refreshChrome()`, through the gate.

## Undo (G4)

**Bounded at 100, screen-level, cleared when the sketchbook closes.** g-paper keeps no history by
design, so the record is ours: `UndoRedoStack` over four `Edit` kinds — a mark drawn, a sweep
erased, a page added, a page deleted. Each entry carries the page it happened on, so undo turns back
to that page; a per-page history would silently do nothing on the page the artist happens to be
looking at, which reads as a broken button. Entries hold ids, never geometry — the rows are all
still in the file, stamped rather than deleted.

**The store is mutated first, then the page is shown from the store.** g-paper offers `addStrokes`
and `removeStrokes` for exactly this and they would be faster, but they patch the view independently
of the rows. The two can then disagree, and the artist finds out when the sketchbook reopens looking
different from how they left it, a day later, with nothing to connect it to. The `.soil` is the
source of truth, so a replay writes through `SketchbookSession` and then calls `showPage`.

| Edit | Undo: store | Undo: shows | Redo: store | Redo: shows |
|---|---|---|---|---|
| `Drew` | `hideMarks([markId])` | `pageId` | `restoreMarks([markId])` | `pageId` |
| `Erased` | `restoreMarks(ids)` | `pageId` | `hideMarks(ids)` | `pageId` |
| `AddedPage` | `deletePage(pageId)` → the marks it hid travel with the entry as `hiddenMarkIds` | `shownAfterUndo` | `restorePage(pageId, hiddenMarkIds)` | `pageId` |
| `DeletedPage` | `restorePage(pageId, markIds)`, then `deletePage(replacement)` if there was one (its marks travel as `replacementMarkIds`) | `pageId` | `deletePage(pageId)`, then `restorePage(replacement, replacementMarkIds)` if there was one | `shownAfterDelete` |

Three things hold it together:

- **`SoilWriter.perform` rather than `submit`.** A mark that fails to save is logged and dropped —
  there is nothing honest to say mid-stroke — but an undo is a thing the artist asked for and is
  watching. `perform` lets the exception through, and the replay puts the entry back on the stack it
  came off rather than letting the failure be permanent and silent. Same queue, so ordering against
  every mark write still holds.
- **The generation counter.** A replay waits, and across that wait the pen can finish a mark. That
  fresh edit clears the redo side, because there is no going forward from a drawing that has moved
  on — so the replayer snapshots `stack.generation` before it starts and drops the entry it was
  holding if the count changed.
- **Single-flight behind `busy`.** Two replays at once is two page loads racing for one panel.
- **Taking a page away learns something, and the entry carries it.** Ordinarily nothing is drawn on
  a leaf when its add is undone — those marks sit above it on the stack and went first — but a
  history that overflowed past them reaches the undo with marks still alive, and a redo that put the
  leaf back blank would have quietly turned an undo into a delete. So the ids the store hands back
  travel with the entry onto the redo side, entries stay ids-only, and the file is never asked twice.

Deleting the only page leaves **one fresh blank page**: a sketchbook with nothing in it has no way
to grow a leaf, since the only way to add one is to swipe past the last. Undo takes that stand-in
away again when it puts the real page back, which is what `DeletedPage.replacementPageId` is for.

**`incremental_vacuum` is still owed.** G2 recorded the debt against G4 on the assumption that G4
would be the first phase to free pages. It is not: every delete here — a mark, a sweep, a whole page
— is a soft delete, so not one row leaves the file and there is nothing to reclaim. The debt moves
to whichever phase first *hard*-deletes rows.

## What the host does, and what it must not

The host does only the documented host responsibilities
(`~/git/g-paper/docs/host-responsibilities.md`). As of G4 that is:

- `GPaper.create(context)` and add `asView()` to the paper container — never a class named in XML,
  which would hardwire the BOOX engine into a screen that also has to come up on a desk.
- `setPageSize(w, h)` **before** `loadStrokes(...)`: the page rect is what the component registers
  ink against, so setting it after would draw the first frame against the wrong rectangle.
- `clearForContentSwap()` before both of those, on every page swap — never `clear()`, which would
  flash the panel blank in between.
- `setExclusionRects(...)` whenever a bar lays out. Both bars overlay the paper; without this the
  pen inks underneath them — visible in the gaps between buttons and, worse, *saved*.
- `releaseRender()` on the finger interaction that raises the delete-page dialog. The writing
  overlay holds the pixels under it against ordinary app updates, so a dialog raised while it is
  armed does not appear at all. It re-arms on the next pen-down and is a no-op off e-ink, so it is
  called without conditions.
- Lifecycle: `resumeDrawing()` in `onResume` (focus events are not reliable here),
  `releaseForHandoff()` immediately before finishing back to another paper-hosting screen,
  `release()` in `onDestroy`.

**Undo is host-owned, and this host does it the blunt way on purpose.** g-paper's table offers
`addStrokes`/`removeStrokes` as the targeted replay; this screen uses `loadStrokes` through
`showPage` instead, for the source-of-truth reason argued above.

**Engine gaps are fixed in g-paper, never worked around here.** The graphite pencil this screen
draws with is the proof: it is a g-paper `StrokeStyle`, built in that repo's Phase 10 and published
as 0.1.7, not a renderer this app owns.

## Two things with no code to find

- **Turning the pen over erases**, and nothing on the toolbar moves. The BOOX SDK intercepts the
  eraser end at hardware level and g-paper's Onyx engine erases with it whichever tool is armed.
  Worth knowing precisely because there is nothing here to go looking for.
- **The write queue outlives the screen.** `SoilWriter`'s pump runs on the *application* scope, not
  the Activity's: `SketchbookSession.close()` drains the queue before sealing the file, and an
  Activity scope is cancelled the instant `onDestroy` returns — so the call written to save the last
  marks drawn would be the call that threw them away.
