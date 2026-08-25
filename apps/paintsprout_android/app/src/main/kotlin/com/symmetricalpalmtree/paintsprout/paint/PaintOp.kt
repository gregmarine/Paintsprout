package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.ColorInt

/**
 * One committed edit to the paint layer, kept in order so the whole paint can be
 * rebuilt on demand — replayed for undo/redo (and, later, re-toothed when the
 * surface changes). Ported from the sealed `PaintOp` hierarchy in the Flutter
 * `drawing_canvas.dart`.
 *
 * The magic-wand ops ([FillOp], [EraseOp], [MoveOp]) own their own mask [Bitmap]
 * (an independent copy of the live selection), so they survive the selection
 * being cleared or replaced. Recycle them with [recycle] when an op is dropped.
 */
sealed class PaintOp {
    /**
     * Which layer this edit landed on.
     *
     * Set when the op is committed and carried for the rest of its life, because
     * rebuilding after an undo has to fold each layer from its own ops and
     * nothing else can say which those are. Empty until committed, and ignored by
     * [SurfaceOp], which changes the ground the whole page sits on rather than
     * anything in the stack.
     */
    var layerId: String = ""

    /** Releases any bitmaps this op owns. Called when the op leaves history. */
    open fun recycle() {}
}

/**
 * A drawn stroke (pencil, pen, brush, watercolor, …). If [clip] is set (the
 * stroke was drawn while a magic-wand selection was active), the stroke's effect
 * is masked to that region — a frisket — and the clip replays with the op so the
 * constraint survives surface changes and undo/redo.
 */
class StrokeOp(val stroke: Stroke, val clip: Bitmap? = null) : PaintOp() {
    override fun recycle() {
        clip?.recycle()
    }
}

/**
 * A clipboard paste: the copied ops, replayed in order as **one** step.
 *
 * A composite rather than a run of siblings, and that is the whole reason it
 * exists: a paste of thirty marks that takes thirty presses to undo is not one
 * paste, it is thirty. Everything else about it is delegation — it renders by
 * replaying its children, and it stores as a parent row with theirs beneath it,
 * which is the shape the container already uses for a stroke and its frisket.
 */
class PasteOp(val ops: List<PaintOp>) : PaintOp() {
    override fun recycle() {
        ops.forEach { it.recycle() }
    }
}

/** Fills a magic-wand region with [color], broken up by the surface tooth. */
class FillOp(val mask: Bitmap, @param:ColorInt val color: Int) : PaintOp() {
    override fun recycle() {
        mask.recycle()
    }
}

/** Erases paint (revealing the surface) within a magic-wand region. */
class EraseOp(val mask: Bitmap) : PaintOp() {
    override fun recycle() {
        mask.recycle()
    }
}

/**
 * Lifts the paint inside [sourceMask] and re-lays it under [transform] (a
 * move/scale/rotate in BUFFER coordinates), clearing the original spot. On
 * replay it recomputes the lifted paint from whatever is under [sourceMask] at
 * that point, so it re-tooths and composes correctly through surface changes and
 * undo/redo.
 */
class MoveOp(val sourceMask: Bitmap, val transform: Matrix) : PaintOp() {
    override fun recycle() {
        sourceMask.recycle()
    }
}

/**
 * Records a document-level surface / background-colour change so it sits on the
 * undo timeline alongside strokes. Paint-neutral — it owns no bitmaps and does
 * not touch the paint layer, so a rebuild skips it; the effective surface state
 * is resolved separately by scanning the committed history.
 */
/**
 * A change to how a layer composites, rather than to what is on it.
 *
 * On the timeline because undo should retrace what you did, and turning a layer
 * down is something you did. It lays no pixels, so the fold skips it; the state
 * it describes is re-derived from history the way the surface already is.
 */
class LayerOpacityOp(val opacity: Float) : PaintOp()

/** Companion to [LayerOpacityOp] for the eye. */
class LayerVisibilityOp(val visible: Boolean) : PaintOp()

/**
 * Where something in the stack sits: inside which folder, and how far up from the
 * bottom of what that folder holds.
 *
 * Recorded this way rather than as a position in the whole stack, and the reason
 * is worth keeping. A page with no folders is one set of siblings, and counting
 * up from the bottom of it gives exactly the numbers the stack was recorded with
 * before folders existed — so every step already on a timeline reads correctly
 * under this, with [folder] empty meaning what it has always meant: nothing
 * holds this. Nesting cost the format no migration and it costs the timeline
 * none either.
 */
class StackSpot(val folder: String, val at: Int) {
    companion object {
        /** Not in any folder — loose at the top of the stack. */
        const val LOOSE = ""
    }
}

/**
 * A layer arriving, and a layer going away.
 *
 * The pair that makes deletion survivable. A deleted layer is not erased from
 * the file — its row and every op on it stay exactly where they were, and this
 * step is the only thing that says it is gone. Take the step back and the layer
 * comes back with everything that was on it, because none of it ever left.
 *
 * [spot] is where in the stack it sat, so undoing puts it back where it was
 * rather than on top — and back into the folder it was filed in.
 */
class LayerAddOp(val name: String, val spot: StackSpot) : PaintOp()

class LayerDeleteOp(val spot: StackSpot) : PaintOp()

/**
 * A layer or a folder moving in the stack: out of one place, into another.
 *
 * Unlike a deletion this is *not* replayed when a page loads: the rows record
 * the arrangement themselves, so the stack arrives already moved, and applying
 * the steps again would move everything twice. It is here for undo alone, which
 * still works after a reload — the thing is where the op says it ended up, so
 * putting it back where it started needs nothing but the op.
 *
 * A folder moves with everything inside it, so one step covers the folder and
 * its contents however many there are. What is *in* a folder does not move when
 * the folder does; it is still in the folder.
 */
class LayerOrderOp(
    val from: StackSpot,
    val to: StackSpot,
    /**
     * Which thing moved, when it is not the layer this step is filed under.
     *
     * Empty for a layer, which is filed under itself and needs no second name.
     * A folder has no timeline to be filed on, so its move rides under the layer
     * being worked on — and then [PaintOp.layerId] names the wrong thing
     * entirely, which is what this is for.
     */
    val subject: String = "",
) : PaintOp() {
    /** The thing this step is about, whichever way it was filed. */
    val moved: String get() = subject.ifEmpty { layerId }
}

/**
 * A folder arriving, and a folder going away.
 *
 * Not the same event as a layer's, despite the shape, because a folder is a
 * place to keep work rather than work. Deleting one never deletes what is inside
 * it: the contents come out where the folder was, and the step records only that
 * the place is gone. Undo puts the place back and files them in it again.
 *
 * [folderId] rather than [PaintOp.layerId] says which folder, because these are
 * filed on the timeline under whichever layer was being worked on — a folder has
 * no ops of its own to hang them from.
 */
class FolderAddOp(val folderId: String, val name: String, val spot: StackSpot) : PaintOp()

/**
 * [held] is how many things were directly inside the folder when it went.
 *
 * Undo needs it and cannot work it out. The contents were tipped out into the
 * spot the folder occupied, where they are now indistinguishable from whatever
 * was already beside it — so the step has to say how many of the things standing
 * there came out of it. A count is enough rather than a list of ids, because a
 * timeline unwinds in order: by the time this step is undone, everything done
 * after it has been undone already, and the stack is exactly as the deletion
 * left it.
 */
class FolderDeleteOp(
    val folderId: String,
    val name: String,
    val spot: StackSpot,
    val held: Int,
) : PaintOp()

/**
 * How a folder composites — which is to say, how everything inside it does.
 *
 * The layer pair's siblings, and they mean the same thing one level up. A folder
 * holds no pixels: turning it down multiplies onto what it holds rather than
 * flattening them together first, so two half-opaque layers in a folder at half
 * are two quarter-opaque layers and the place they overlap is darker. A folder
 * is a shelf, and a shelf does not change what is on it.
 */
class FolderOpacityOp(val folderId: String, val opacity: Float) : PaintOp()

class FolderVisibilityOp(val folderId: String, val visible: Boolean) : PaintOp()

/**
 * What a layer or a folder is called.
 *
 * On the timeline with everything else, and the rule behind that is worth stating
 * plainly: **if the sketchbook remembers it, undo can take it back.** A name is
 * not paint, but it is not a preference either — it is part of the document,
 * saved in it, carried with it, and the only account of what a layer holds. A
 * rename you did not mean should cost one press to undo, like anything else you
 * did not mean.
 *
 * [subject] names a folder, which has no timeline of its own to be filed on. A
 * layer is filed under itself and leaves it empty, the same arrangement
 * [LayerOrderOp] uses.
 */
class NameOp(val name: String, val subject: String = "") : PaintOp() {
    val named: String get() = subject.ifEmpty { layerId }
}

/**
 * A folder folded shut, or opened again.
 *
 * Here by the same rule, and it is the furthest that rule reaches: shutting a
 * folder changes what you can see of the list rather than what is on the page.
 * It is in the file, though, so it is part of the sketchbook, so it is a step —
 * which does mean folding a folder discards a redo you had been keeping.
 */
class FolderCollapseOp(val folderId: String, val collapsed: Boolean) : PaintOp()

class SurfaceOp(
    val kind: SurfaceKind,
    @param:ColorInt val plainColor: Int,
    /** Canvas customisation in effect at this point (ignored by other surfaces). */
    val canvas: CanvasParams = CanvasParams(),
    /** Watercolor customisation in effect at this point (ignored by others). */
    val watercolor: WatercolorParams = WatercolorParams(),
    /** Wood customisation in effect at this point (ignored by others). */
    val wood: WoodParams = WoodParams(),
    /** Stone customisation in effect at this point (ignored by others). */
    val stone: StoneParams = StoneParams(),
    /** Concrete customisation in effect at this point (ignored by others). */
    val concrete: ConcreteParams = ConcreteParams(),
    /** Metal customisation in effect at this point (ignored by others). */
    val metal: MetalParams = MetalParams(),
    /** Chalkboard customisation in effect at this point (ignored by others). */
    val chalkboard: ChalkboardParams = ChalkboardParams(),
) : PaintOp()
