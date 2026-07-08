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
class SurfaceOp(
    val kind: SurfaceKind,
    @param:ColorInt val plainColor: Int,
    /** Canvas customisation in effect at this point (ignored by other surfaces). */
    val canvas: CanvasParams = CanvasParams(),
    /** Watercolor customisation in effect at this point (ignored by others). */
    val watercolor: WatercolorParams = WatercolorParams(),
    /** Wood customisation in effect at this point (ignored by others). */
    val wood: WoodParams = WoodParams(),
) : PaintOp()
