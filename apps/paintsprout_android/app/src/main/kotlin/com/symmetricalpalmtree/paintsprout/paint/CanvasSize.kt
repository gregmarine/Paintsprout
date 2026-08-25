package com.symmetricalpalmtree.paintsprout.paint

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The size of the drawing surface.
 *
 * Three kinds, because there are three honest answers to "how big is this?".
 * [Print] is a sheet of paper: real inches, drawn 1:1 at true size and centred in
 * a mat. [FullScreen] is the panel itself. [Frame] is an electronic-ink art frame
 * — a fixed pixel grid that hangs on a wall at a fixed physical size, which is
 * neither a print nor a screenful and cannot be described as either.
 *
 * Every non-square size is oriented landscape (long side horizontal) to suit the
 * landscape-locked screen — a portrait 5×7 would be taller than the panel. That
 * includes the [Frame]s, whose panels are *specified* portrait; the datasheet's
 * orientation is not the glass's, and a sheet that stood up when everything
 * beside it lay down read as a bug.
 *
 * No [Print] larger than the screen is ever offered. Drawing is 1:1 with no zoom,
 * so a sheet that does not fit cannot be shown; it would be silently clamped to
 * the panel and go on calling itself by its old name. [choices] is the one list
 * every picker asks, and it never returns a print the screen cannot hold.
 */
sealed interface CanvasSize {

    val label: String

    object FullScreen : CanvasSize {
        override val label: String get() = "Full screen"
    }

    data class Print(val wIn: Float, val hIn: Float, override val label: String) : CanvasSize

    /**
     * An e-ink art frame: [pxW]×[pxH] pixels of glass, [longIn] inches along its
     * long side.
     *
     * The buffer is exactly the frame's pixel grid, so what is painted is what the
     * frame stores — no resample stands between the two. The *display* size is the
     * frame's physical size ([displayPx]), so the sheet on the tablet is the object
     * on the wall, shrunk only when the panel is too small to hold it. One canvas
     * pixel is therefore usually larger than one screen pixel: a 7.3 in frame is
     * 127 dpi against the Movink's 243, and seeing its pixels at their real
     * coarseness is the point rather than a defect.
     *
     * The physical shape follows the *pixel* aspect, not the glass. A 13.3 in
     * Spectra is 1200×1600 (1.3333) on 8 × 10.5 in glass (1.3125) — its pixels are
     * not square. Matching the pixel ratio keeps one scale factor between canvas
     * and screen, costs 1.5% of shape, and leaves the stretching to the frame,
     * which is going to do it either way.
     */
    data class Frame(
        val pxW: Int,
        val pxH: Int,
        val longIn: Float,
        override val label: String,
    ) : CanvasSize {

        /** The short side in inches, from the pixel aspect. */
        val shortIn: Float get() = longIn * min(pxW, pxH) / max(pxW, pxH)

        /**
         * The resolution a PNG of this canvas carries — the frame's own, never the
         * screen's. A frame shrunk to fit the panel is still the size it is on the
         * wall, and the file has to say so.
         */
        val dpi: Float get() = max(pxW, pxH) / longIn

        /**
         * How large to draw this frame on a [maxWPx]×[maxHPx] panel at [ppi]:
         * its physical size on the glass, shrunk if the panel cannot hold it.
         *
         * The 13.3 always shrinks — even lying down its short side is 7.88 in
         * against 7.41 in of the tallest panel here. A [Print] in that position is
         * simply not offered, because a print claims to be true size and a
         * shrunken one would be lying; a frame claims only to be the frame, so a
         * smaller view of it is a view rather than a falsehood. Its buffer, its
         * file's DPI and the pixels the frame receives are all unchanged by how
         * large it is drawn.
         *
         * The shape comes from the pixel aspect, not the glass — see the class
         * note — which is what keeps one scale factor between canvas and screen.
         */
        fun displayPx(ppi: Float, maxWPx: Int, maxHPx: Int): Pair<Int, Int> {
            // Per canvas pixel, from the long side, so this reads the same
            // whichever way round the frame is declared.
            val perPx = longIn * ppi / max(pxW, pxH)
            val wantW = pxW * perPx
            val wantH = pxH * perPx
            val shrink = minOf(1f, maxWPx / wantW, maxHPx / wantH)
            return (wantW * shrink).roundToInt().coerceIn(1, maxWPx) to
                (wantH * shrink).roundToInt().coerceIn(1, maxHPx)
        }
    }

    companion object {

        /**
         * The fixed rungs — familiar paper sizes, oriented landscape.
         *
         * 4×6 and 5×7 are what photographs come in; 4×4 is the square alongside
         * them. 4×6 is also the 2:3 of [MAX_ASPECT], one rung down.
         */
        val PRESETS: List<Print> = listOf(
            Print(4f, 4f, "4 × 4 in"),
            Print(6f, 4f, "4 × 6 in"),
            Print(7f, 5f, "5 × 7 in"),
        )

        /**
         * The e-ink frames, landscape like the print rungs and labelled short ×
         * long like them too — the panels are *specified* portrait, which is a
         * fact about their datasheets rather than about the glass. Physical sizes
         * are the panels' own, not a print size.
         */
        val FRAMES: List<Frame> = listOf(
            Frame(800, 480, 6.3f, "Spectra 6 7.3 in (480 × 800)"),
            Frame(1600, 1200, 10.5f, "Spectra 6 13.3 in (1200 × 1600)"),
        )

        /**
         * The shape that gets a "largest that fits" rung of its own, as long ÷ short.
         *
         * 2:3 — the print ratio, the one 4×6, 6×9, 8×12 and every poster share. The
         * fixed rungs stop at whole inches, which on any real panel leaves a
         * fraction of an inch unused; this puts the biggest 2:3 sheet the screen
         * can actually hold at the end of the list. Nothing to do with the frames,
         * whose shapes are 3:5 and 3:4.
         */
        private const val MAX_ASPECT: Float = 1.5f

        /** Smallest sheet worth offering, in inches, in either direction. */
        private const val MIN_IN = 1f

        /**
         * Assumed resolution for a frame this build has never heard of — see
         * [frameOf]. Between the two Spectras' 127 and 152 dpi.
         */
        private const val UNKNOWN_FRAME_DPI = 140f

        /**
         * Everything this screen can be asked for, in picker order: the print rungs
         * smallest first, then the frames, then the largest 2:3 sheet that fits,
         * then the panel itself.
         *
         * [maxWIn] and [maxHIn] are the panel's own dimensions in inches at the
         * calibrated PPI — the long side and the short side, since the editor is
         * landscape-locked. The frames are offered whatever the panel measures:
         * unlike a print, a frame that does not fit is shrunk to the screen rather
         * than withheld, because it is not claiming to be true size.
         */
        fun choices(maxWIn: Float, maxHIn: Float): List<CanvasSize> {
            val fixed = PRESETS.filter { it.wIn <= maxWIn && it.hIn <= maxHIn }
            val biggest = largestFitting(MAX_ASPECT, maxWIn, maxHIn)
                // A maximum landing exactly on a rung — a panel precisely 4 in tall
                // — loses to the rung and its rounder label rather than appearing
                // twice.
                ?.takeIf { m -> fixed.none { it.wIn == m.wIn && it.hIn == m.hIn } }
            return fixed + FRAMES + listOfNotNull(biggest) + FullScreen
        }

        /**
         * The biggest sheet of shape [longOverShort] that fits, or null if even
         * [MIN_IN] does not.
         *
         * Both sides round DOWN. `fits` is a raw float comparison, so a sheet that
         * overruns the panel by a hundredth of an inch is excluded exactly as
         * firmly as one that overruns it by a foot — rounding the short side up
         * would produce a size that never appears anywhere.
         */
        fun largestFitting(longOverShort: Float, maxWIn: Float, maxHIn: Float): Print? {
            if (longOverShort < 1f || maxWIn <= 0f || maxHIn <= 0f) return null
            val short = floor2(min(maxHIn, maxWIn / longOverShort))
            val long = floor2(short * longOverShort)
            if (short < MIN_IN || long < MIN_IN) return null
            return Print(long, short, "${num(short)} × ${num(long)} in")
        }

        /**
         * A hand-set size, from sliders already capped to the panel.
         *
         * Truncated to tenths rather than rounded, for the same reason the maxima
         * are: a slider pushed to the end sits at the panel's exact width, and
         * rounding 11.8666 to the nearest tenth gives 11.9 — a sheet wider than
         * the screen, which is then quietly clamped and goes on calling itself
         * 11.9. Rounding the honest way up is how a size starts lying about itself.
         *
         * Labelled short × long like the presets, whichever way round the sliders
         * were left.
         */
        fun custom(wIn: Float, hIn: Float): Print {
            val w = floor1(wIn)
            val h = floor1(hIn)
            return Print(w, h, "${num(min(w, h))} × ${num(max(w, h))} in")
        }

        /**
         * A typed size, held to the panel.
         *
         * Typing has no end stop the way a slider does, so the clamp lives here
         * instead: whatever is entered, what comes back fits. The caller shows the
         * result rather than rewriting the field, so a half-typed "1" on its way to
         * "10" is not snatched away mid-keystroke.
         */
        fun custom(wIn: Float, hIn: Float, maxWIn: Float, maxHIn: Float): Print =
            custom(
                wIn.coerceIn(min(MIN_IN, maxWIn), maxWIn),
                hIn.coerceIn(min(MIN_IN, maxHIn), maxHIn),
            )

        /** A stored print size, wearing its preset's label if it matches one. */
        fun printOf(wIn: Float, hIn: Float): Print =
            PRESETS.firstOrNull { it.wIn == wIn && it.hIn == hIn }
                ?: Print(wIn, hIn, "${num(min(wIn, hIn))} × ${num(max(wIn, hIn))} in")

        /**
         * A stored frame, by its pixel grid.
         *
         * Matched on the two numbers rather than on their order, so a document
         * written while the frames stood portrait comes back as the same frame
         * lying down. It is the same panel either way, and a book that opened as
         * an unknown grid at a guessed size — because the app changed its mind
         * about which way up a frame hangs — would be the app losing a document
         * over its own housekeeping.
         *
         * A grid this build does not know is a document from a later one. Its
         * pixels are kept exactly — that is the part the artwork was painted into
         * — and only the physical size is guessed, at [UNKNOWN_FRAME_DPI]; a frame
         * drawn a little large is recoverable, a buffer of the wrong shape is not.
         */
        fun frameOf(pxW: Int, pxH: Int): Frame =
            FRAMES.firstOrNull {
                min(it.pxW, it.pxH) == min(pxW, pxH) && max(it.pxW, it.pxH) == max(pxW, pxH)
            } ?: Frame(pxW, pxH, max(pxW, pxH) / UNKNOWN_FRAME_DPI, "$pxW × $pxH px")

        /** Truncates to tenths — never up, see [custom]. */
        private fun floor1(x: Float): Float =
            (floor(x.toDouble() * 10.0 + 1e-4) / 10.0).toFloat()

        /**
         * Truncates to hundredths — never up, see [largestFitting].
         *
         * The nudge is for float representation, not for rounding: 7.41f is really
         * 7.40999984, and truncating that lands on 7.40, so a square sheet came out
         * 7.41 × 7.4 with one side a hundredth short of the other. It is a millionth
         * of an inch — a four-thousandth of a pixel here — far too small to push
         * anything past the panel, and far larger than the error it absorbs.
         */
        private fun floor2(x: Float): Float =
            (floor(x.toDouble() * 100.0 + 1e-4) / 100.0).toFloat()

        /** "7", "7.5", "9.26" — trailing zeros are noise in a size label. */
        private fun num(v: Float): String =
            String.format("%.2f", v).trimEnd('0').trimEnd('.')
    }
}
