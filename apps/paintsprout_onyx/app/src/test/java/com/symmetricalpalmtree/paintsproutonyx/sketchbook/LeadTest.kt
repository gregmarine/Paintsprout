package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tin holds one pencil, the hairline, and any name an older tin stored still opens it. */
class LeadTest {

    @Test
    fun `the tin holds the hairline and nothing broader`() {
        assertEquals(listOf(Lead.HAIRLINE), Lead.entries)
        // 0.10 mm at the panel's ≈12 px per mm. The number the artist named, not a derivation.
        assertEquals(1.2f, Lead.HAIRLINE.widthPx, 0f)
        assertEquals(Lead.HAIRLINE, Lead.DEFAULT)
    }

    @Test
    fun `the leads are ordered fine to broad`() {
        // Vacuous with one lead; it is here for the day the tin fills up again.
        val widths = Lead.entries.map { it.widthPx }
        assertEquals(widths.sorted(), widths)
    }

    @Test
    fun `no two leads draw the same mark`() {
        // Graphite goes down as flecks up to 1.6 px, so a lead above that comes out about two px
        // wider than its number. Two leads closer together than that would look like one pencil in
        // two disguises, and the tin would appear to be lying. (A hairline is exempt: since g-paper
        // 0.1.24 a fleck is capped at the lead's width, so it bakes at its own size.)
        val flecked = Lead.entries.map { it.widthPx + 2f }
        for (i in 1 until flecked.size) {
            assertTrue(
                "${Lead.entries[i - 1]} and ${Lead.entries[i]} are too close to tell apart",
                flecked[i] - flecked[i - 1] >= 3f,
            )
        }
    }

    @Test
    fun `a stored lead from a build that knew a different one reads back as the default`() {
        assertEquals(Lead.DEFAULT, Lead.byName(null))
        assertEquals(Lead.DEFAULT, Lead.byName(""))
        // The three-lead tin's names, all of which a device out there has in its prefs.
        assertEquals(Lead.DEFAULT, Lead.byName("FINE"))
        assertEquals(Lead.DEFAULT, Lead.byName("MEDIUM"))
        assertEquals(Lead.DEFAULT, Lead.byName("BROAD"))
        assertEquals(Lead.HAIRLINE, Lead.byName("HAIRLINE"))
    }
}
