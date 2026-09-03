package com.symmetricalpalmtree.paintsproutonyx.sketchbook

/**
 * The pencil in the tin. One, for now, and the thinnest one there is.
 *
 * There were three — a fine mechanical lead, a sharpened HB and a blunt soft one, at 3.9, 8.45 and
 * 15.6 px — each the width drawn upright, with the flank of the lead broadening the mark several
 * times over as the pencil was laid down. An evening's sketching with that tin rejected it whole:
 * the marks were far too broad in an ordinary grip, the lean was named as part of the cause, and
 * neither the live ink nor the bake read as pencil. The artist's request was to step back to the
 * basics of pencil sketching on this panel — no tilt, a stroke as thin as it will go, pressure kept
 * — and find out whether there is a happy middle ground there at all.
 *
 * So the tin holds the hairline. **1.2 px is 0.10 mm on this panel** (≈304.8 dpi, measured in G0,
 * so a millimetre is about twelve px), and 0.10 mm is the setting the artist reads off BOOX's own
 * Notes app for the width wanted. Whether the firmware will draw 1.2 px as thin as that is for the
 * panel to show.
 *
 * It is the width of the mark, and since g-paper 0.1.24 it is also exactly the number the panel is
 * handed: the live style is the firmware's plain even line, which draws what it is given, so the
 * 1.3× charcoal overdraw and the leads' matching scale-up are both gone. A fleck of graphite is
 * capped at the lead's width in the same release, so the bake is a hairline too and not a hairline
 * wearing a 1.6 px coat — that one was caught by rendering the lead to a PNG before it went near the
 * panel, the same way the first tin's flaws were.
 *
 * Still px, and px for good: arc 1 has no millimetres and no calibration, so the physical reasoning
 * lives in this comment and nothing here asks the panel how big it is.
 *
 * Kept as an enum with one entry rather than a constant, because the tin is a decision about
 * *which pencils exist*, and a shelf of them may come back once the hairline has been judged.
 * `ToolPrefs` still stores the name; a name stored by the three-lead build reads back as this one.
 */
enum class Lead(val widthPx: Float) {
    HAIRLINE(1.2f),
    ;

    companion object {
        val DEFAULT = HAIRLINE

        /** A stored name that no longer exists reads back as the default rather than throwing. */
        fun byName(name: String?): Lead =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
