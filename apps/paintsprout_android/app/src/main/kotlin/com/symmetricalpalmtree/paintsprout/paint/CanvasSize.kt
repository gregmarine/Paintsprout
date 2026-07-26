package com.symmetricalpalmtree.paintsprout.paint

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The physical size of the drawing surface. The canvas is drawn 1:1 at true size
 * (no zoom) and centred in the view; anything smaller than the screen sits inside
 * a mat/bevel. [Print] sizes are real inches; [FullScreen] uses the whole panel.
 *
 * Non-square presets are oriented landscape (long side horizontal) to suit the
 * landscape-locked screen — a portrait 5×7 would be taller than the panel.
 *
 * Nothing larger than the screen is ever offered. Drawing is 1:1 with no zoom, so
 * a sheet that does not fit cannot be shown; it would be silently clamped to the
 * panel and go on calling itself by its old name. [offered] is the one list every
 * picker asks, and it never returns a size the screen cannot hold.
 */
sealed interface CanvasSize {

    val label: String

    object FullScreen : CanvasSize {
        override val label: String get() = "Full screen"
    }

    data class Print(val wIn: Float, val hIn: Float, override val label: String) : CanvasSize

    companion object {

        /**
         * The fixed rungs — familiar paper sizes, oriented landscape.
         *
         * Mixed ratios on purpose: 4×6 and 5×7 are what photographs come in, and
         * dropping them to make the list tidy would cost more than the tidiness
         * is worth. The square rungs climb alongside them.
         */
        val PRESETS: List<Print> = listOf(
            Print(4f, 4f, "4 × 4 in"),
            Print(6f, 4f, "4 × 6 in"),
            Print(5f, 5f, "5 × 5 in"),
            Print(7f, 5f, "5 × 7 in"),
            Print(6f, 6f, "6 × 6 in"),
            Print(7f, 7f, "7 × 7 in"),
        )

        /**
         * Shapes that get a "largest that fits" rung of their own, as long ÷ short.
         *
         * Square, the 8×10 shape, and 4:3. The fixed rungs stop at whole inches,
         * which on any real panel leaves a fraction of an inch unused; these put
         * the biggest sheet the screen can actually hold at the end of the list.
         *
         * 4:3 is for a Reflection Frame, whose pixels are not square: 1200×1600 is
         * 1.3333 while its 8 × 10.5 in glass is 1.3125. Matching the pixel ratio
         * rather than the physical one is deliberate — the drawing is made in the
         * shape the frame will store it in, and the frame does the stretching.
         */
        private val MAX_ASPECTS: List<Float> = listOf(1f, 1.25f, 4f / 3f)

        /** Smallest sheet worth offering, in inches, in either direction. */
        private const val MIN_IN = 1f

        /**
         * Every size this screen can show, smallest first.
         *
         * [maxWIn] and [maxHIn] are the panel's own dimensions in inches at the
         * calibrated PPI — the long side and the short side, since the editor is
         * landscape-locked.
         */
        fun offered(maxWIn: Float, maxHIn: Float): List<Print> {
            val fixed = PRESETS.filter { it.wIn <= maxWIn && it.hIn <= maxHIn }
            val maxes = MAX_ASPECTS.mapNotNull { largestFitting(it, maxWIn, maxHIn) }
            // The fixed rungs come first so that a computed maximum landing exactly
            // on one of them — a panel precisely 7 in tall — loses to the rung and
            // its rounder label, rather than appearing twice.
            val seen = mutableSetOf<Pair<Float, Float>>()
            return (fixed + maxes)
                .filter { seen.add(it.wIn to it.hIn) }
                .sortedWith(compareBy({ it.hIn }, { it.wIn }))
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
