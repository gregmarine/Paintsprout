package com.symmetricalpalmtree.paintsproutonyx.sketchbook

/**
 * **Throwaway.** Opens every sketchbook as a raster page, so the raster experiment's first
 * phase (R0, `RASTER_PLAN.md`) can be drawn on before the host knows what a raster book is.
 * Removed in R2, when the mode is stamped on the sketchbook row and read at open.
 *
 * While it is on, nothing else in the host changes — deliberately. Every mark still fires
 * `onStrokeCommitted`, so its row is still written, undo still replays through the store and
 * shows the page again, and the cover still bakes from rows; the only difference is that the
 * page on the glass keeps pixels rather than objects, and `showPage`'s `loadStrokes` composites
 * the rows into the image on every turn instead of listing them. That is not how a raster book
 * will persist (R2 stores one image per page), but it is exactly what makes R0 measurable: the
 * same rows can be reopened with this off and the two pages `screencap`ed and compared, pixel
 * for pixel, which is the proof that the composite lands the approved graphite unchanged.
 *
 * Known while it is on: the eraser does nothing on the page, because R0 has no pixel eraser
 * and the stroke eraser finds no strokes. R1 is the eraser.
 */
const val RASTER_SWITCH = true
