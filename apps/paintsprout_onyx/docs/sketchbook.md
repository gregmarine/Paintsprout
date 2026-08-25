# The sketchbook screen

> Started in **G3**. G6 owns the subsystem docs and will grow this one; what is here now is the part
> G3 was required to write down, which is the frame-silence ledger.

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

G3's chrome is static by construction: the toolbar changes only on a tap, a tap is a finger, and a
finger arriving while the pen is active is a palm the component has already refused. The gate is
built and wired anyway, so that G4's page turns and undo counters arrive into a screen that already
obeys the rule rather than one that has to be taught it afterwards.

## What the host does, and what it must not

The host does only the documented host responsibilities
(`~/git/g-paper/docs/host-responsibilities.md`). In G3 that is:

- `GPaper.create(context)` and add `asView()` to the paper container — never a class named in XML,
  which would hardwire the BOOX engine into a screen that also has to come up on a desk.
- `setPageSize(w, h)` **before** `loadStrokes(...)`: the page rect is what the component registers
  ink against, so setting it after would draw the first frame against the wrong rectangle.
- `setExclusionRects(...)` whenever a bar lays out. Both bars overlay the paper; without this the
  pen inks underneath them — visible in the gaps between buttons and, worse, *saved*.
- Lifecycle: `resumeDrawing()` in `onResume` (focus events are not reliable here),
  `releaseForHandoff()` immediately before finishing back to another paper-hosting screen,
  `release()` in `onDestroy`.

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
