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
 * A layer arriving, and a layer going away.
 *
 * The pair that makes deletion survivable. A deleted layer is not erased from
 * the file — its row and every op on it stay exactly where they were, and this
 * step is the only thing that says it is gone. Take the step back and the layer
 * comes back with everything that was on it, because none of it ever left.
 *
 * [at] is where in the stack it sat, counting from the bottom, so undoing puts
 * it back where it was rather than on top.
 */
class LayerAddOp(val name: String, val at: Int) : PaintOp()

class LayerDeleteOp(val at: Int) : PaintOp()

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
