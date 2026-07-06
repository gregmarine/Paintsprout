package com.symmetricalpalmtree.paintsprout.paint

/**
 * One committed edit to the paint layer, kept in order so the whole paint can be
 * rebuilt on demand — replayed for undo/redo (and, later, re-toothed when the
 * surface changes). Ported from the sealed `PaintOp` hierarchy in the Flutter
 * `drawing_canvas.dart`.
 *
 * Only [StrokeOp] exists so far; the magic-wand ops (fill / erase / move) join
 * this hierarchy when the wand is ported.
 */
sealed class PaintOp

/** A drawn stroke (pencil, pen, brush, watercolor, …). */
class StrokeOp(val stroke: Stroke) : PaintOp()
