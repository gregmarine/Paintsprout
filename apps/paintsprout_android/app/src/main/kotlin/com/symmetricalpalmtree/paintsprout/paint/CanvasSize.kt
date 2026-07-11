package com.symmetricalpalmtree.paintsprout.paint

/**
 * The physical size of the drawing surface. The canvas is drawn 1:1 at true size
 * (no zoom) and centred in the view; anything smaller than the screen sits inside
 * a mat/bevel. [Print] sizes are real inches; [FullScreen] uses the whole panel.
 *
 * Non-square presets are oriented landscape (long side horizontal) to suit the
 * landscape-locked screen — a portrait 5×7 would be taller than the panel.
 */
sealed interface CanvasSize {

    val label: String

    object FullScreen : CanvasSize {
        override val label: String get() = "Full screen"
    }

    data class Print(val wIn: Float, val hIn: Float, override val label: String) : CanvasSize

    companion object {
        /** Common print sizes, oriented landscape. Filtered to what fits the screen. */
        val PRESETS: List<Print> = listOf(
            Print(4f, 4f, "4 × 4 in"),
            Print(6f, 4f, "4 × 6 in"),
            Print(5f, 5f, "5 × 5 in"),
            Print(7f, 5f, "5 × 7 in"),
            Print(10f, 8f, "8 × 10 in"),
        )
    }
}
