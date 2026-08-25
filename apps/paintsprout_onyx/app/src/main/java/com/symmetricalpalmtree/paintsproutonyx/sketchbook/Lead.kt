package com.symmetricalpalmtree.paintsproutonyx.sketchbook

/**
 * The pencils in the tin.
 *
 * Three leads, and discrete on purpose: real pencils come in sizes and you pick one up, you do not
 * dial one. A slider would give the artist a width no hand holding a real pencil has, which is the
 * kind of power this app defers rather than backlogs. The range that matters most is the one inside
 * a single lead anyway — pressing harder darkens a mark all the way from a ghost to solid black
 * without the width moving at all.
 *
 * **The numbers are px, and they are px for good.** Arc 1 has no millimetres and no calibration, so
 * nothing here is converted from a physical size at runtime and nothing asks the panel how big it is.
 * They were *chosen* against the one panel this app runs on, which measured ≈ 304.8 dpi in G0 — so a
 * millimetre is about twelve of these px, and the three leads land near 0.4 mm, 0.7 mm and 1.2 mm:
 * a fine mechanical lead, a sharpened HB, and a blunt soft one. That reasoning is written down here
 * rather than built into the app, because building it in is calibration, and calibration is a later
 * arc.
 *
 * The odd-looking gaps between them are deliberate. Graphite is laid down as flecks of a fixed size,
 * so a mark comes out about two px wider than its lead — which means two leads three px apart look
 * nearly identical on paper, and the tin would appear to hold one pencil in three disguises. These
 * are spaced to be told apart at a glance.
 */
enum class Lead(val widthPx: Float) {
    FINE(3f),
    MEDIUM(6.5f),
    BROAD(12f),
    ;

    companion object {
        val DEFAULT = MEDIUM

        /** A stored name that no longer exists reads back as the default rather than throwing. */
        fun byName(name: String?): Lead =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
