package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tin holds three pencils that can be told apart. */
class LeadTest {

    @Test
    fun `the leads are ordered fine to broad`() {
        val widths = Lead.entries.map { it.widthPx }
        assertEquals(widths.sorted(), widths)
    }

    @Test
    fun `no two leads draw the same mark`() {
        // Graphite goes down as flecks of a fixed size, so a mark comes out about two px wider than
        // its lead. Two leads closer together than that would look like one pencil in two disguises,
        // and the tin would appear to be lying.
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
        assertEquals(Lead.DEFAULT, Lead.byName("EXTRA_SOFT"))
        assertEquals(Lead.FINE, Lead.byName("FINE"))
    }
}
