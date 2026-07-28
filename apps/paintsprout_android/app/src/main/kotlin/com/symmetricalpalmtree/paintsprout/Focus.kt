package com.symmetricalpalmtree.paintsprout

import android.graphics.Color
import com.symmetricalpalmtree.paintsprout.data.soil.Scratchpad
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.Tool

/**
 * What the editor is currently for.
 *
 * The app can do a great deal. A drawing, at any one moment, needs very little
 * of it — and a rail carrying every capability at once is a rail you read
 * instead of a tool you reach for. So this file names the small set the work in
 * hand actually calls for, and everything outside it stays out of sight.
 *
 * Nothing here removes a feature. The pen still draws, the tray still mixes, the
 * wand still selects; they are simply not offered. Bringing one back is one line
 * changed in this file and nowhere else — which is the point. A feature returns
 * when the drawing asks for it, and this file is the record of when each one
 * did.
 *
 * The current scope is a graphite drawing on paper: pencil and eraser, black,
 * with the pages and the undo stack that any sustained piece needs. No colour to
 * choose, no ground to choose, no second medium to be tempted by.
 */
object Focus {

    /**
     * The tools on offer, in rail order.
     *
     * The eraser earns its place beside the pencil rather than doubling the
     * scope: graphite is a medium you take back out again, and undo only ever
     * removes the stroke you just made — not the corner of one you made twenty
     * strokes ago.
     */
    val TOOLS: List<Tool> = listOf(Tool.PENCIL, Tool.ERASER)

    /** What the rail falls back to when the selected tool is not on offer. */
    val DEFAULT_TOOL: Tool = Tool.PENCIL

    /**
     * The one colour, while there is no way to choose one.
     *
     * Enforced rather than merely defaulted: a page carries the brush load it
     * was last painted with, and restoring one would otherwise reintroduce a
     * colour through a door the rail has closed.
     */
    @Suppress("MagicNumber")
    val COLOR: Int = Color.BLACK

    /**
     * The ground every new page starts on.
     *
     * A fresh sketchbook already opens on paper, so this changes nothing today —
     * it is here so that the surface the drawing assumes is stated somewhere
     * rather than being an accident of a default two files away.
     */
    val SURFACE: SurfaceKind = SurfaceKind.PAPER

    // --- What the rail offers ------------------------------------------------
    //
    // Each flag is one control. `false` means built but never shown.

    /** The stroke colour swatch. Off: everything is [COLOR]. */
    const val SHOW_COLOR = false

    /** The mm size button. On: a pencil is a lead weight you change. */
    const val SHOW_SIZE = true

    /** The surface picker. Off: every page is [SURFACE]. */
    const val SHOW_SURFACE = false

    /** The watercolor brush's clean-water toggle. Moot without the brush. */
    const val SHOW_WATER_MODE = false

    /** The magic wand's tolerance. Moot without the wand. */
    const val SHOW_WAND_TOLERANCE = false

    /**
     * Fill / erase-inside / copy / deselect.
     *
     * These already appear only when something is selected, and with both
     * selectors gone nothing can select — but a flag that depends on another
     * feature's absence for its own is a flag waiting to surprise someone.
     */
    const val SHOW_SELECTION_ACTIONS = false

    /**
     * Paste.
     *
     * Unlike its siblings this one does *not* follow from hiding the selectors:
     * the clipboard lives in the index database and outlives the page, the book
     * and the phase, so a copy made weeks ago would still light this up.
     */
    const val SHOW_PASTE = false

    /** The line/arc/polyline/polyarc commit button. Moot without those tools. */
    const val SHOW_SHAPE_COMMIT = false

    // --- Beyond the marks ----------------------------------------------------

    const val SHOW_UNDO_REDO = true

    /** The page strip: switch pages, add one, and long-press to delete or reorder. */
    const val SHOW_PAGES = true

    /** Somewhere to try a mark out without spending a page of the book on it. */
    const val SHOW_SCRATCHPAD = true

    /** The way back to the shelf. */
    const val SHOW_LIBRARY = true

    /** Export to the gallery, DPI-stamped — progress shots of a piece in flight. */
    const val SHOW_SAVE_PNG = true

    /** The sheet's true print size. A piece has physical dimensions. */
    const val SHOW_CANVAS_SIZE = true

    // Calibration used to be a flag here, shown on the rail and turned back on
    // whenever a new tablet appeared. It is not a rail control: it says nothing
    // about what the drawing is for, and hiding it made the one setting the whole
    // 1:1 goal rests on reachable only by editing this file and rebuilding —
    // which a release build cannot be talked into at all, since `run-as` refuses
    // a non-debuggable package. It lives on the library screen now, always
    // visible, because a tablet that has never been measured has no way to say so.

    /** Back to bare paper, behind a confirm. Undoable. */
    const val SHOW_CLEAR = true

    /** Collapse the rail, so the sheet is the only thing on screen. */
    const val SHOW_HIDE_RAIL = true

    /** Sending a page to another book. Nothing to send it to yet. */
    const val SHOW_SEND_PAGE = false

    /** The mixing tray. A palette is for colour, and there is no colour. */
    const val SHOW_TRAY = false

    /**
     * The layers panel.
     *
     * The one piece of chrome that is not glued to the glass: the tools stay
     * where they were put, but a list is something you read, so it stays on your
     * right whichever way the tablet is turned.
     */
    const val SHOW_LAYERS = true

    /**
     * The tools on offer in a given document.
     *
     * The scratchpad names its own subset for its own reasons; this scope names
     * one for the drawing's. Where they disagree the answer is the narrower of
     * the two — a feature is in scope only if *both* say so.
     */
    fun toolsFor(isScratchpad: Boolean): List<Tool> =
        if (isScratchpad) TOOLS.filter { it in Scratchpad.TOOLS } else TOOLS
}
