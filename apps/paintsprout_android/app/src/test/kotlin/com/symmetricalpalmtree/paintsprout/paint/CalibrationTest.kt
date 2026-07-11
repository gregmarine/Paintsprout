package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationTest {

    private val eps = 1e-3f

    @Test
    fun mmToPxUsesKnownDensity() {
        // 254 PPI = exactly 10 px per mm, so 5 mm -> 50 px.
        assertEquals(50f, Calibration.mmToPx(5f, 254f), eps)
        // 1 inch at 300 PPI -> 300 px.
        assertEquals(300f, Calibration.mmToPx(Calibration.MM_PER_IN, 300f), eps)
    }

    @Test
    fun pxToMmIsTheInverse() {
        for (mm in listOf(0.3f, 0.5f, 2f, 6f, 18.7f)) {
            val px = Calibration.mmToPx(mm, 229.5f)
            assertEquals(mm, Calibration.pxToMm(px, 229.5f), eps)
        }
    }

    @Test
    fun inchHelpersMatchPpi() {
        assertEquals(229.5f, Calibration.inToPx(1f, 229.5f), eps)
        assertEquals(2f, Calibration.pxToIn(600f, 300f), eps)
    }
}
