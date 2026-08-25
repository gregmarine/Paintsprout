package com.symmetricalpalmtree.paintsproutonyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM harness itself, proved by asserting the two facts G0 actually fixed.
 *
 * There is no logic here yet to test, and a test suite that does not exist until
 * there is something worth testing has a way of not existing when there is. So
 * this stands in: it fails if the test source set stops compiling or stops
 * running, which is the only thing it is here to notice. G1 gives it real
 * company — codec round-trips, KDF vectors, schema constants.
 */
class ScaffoldSmokeTest {

    @Test
    fun `the app id is the onyx package`() {
        // Unit tests run against the debug variant, whose id carries the `.dev`
        // suffix that keeps the two installs apart on the tablet. Drop it, and
        // what is left has to be the shipped id.
        val shipped = BuildConfig.APPLICATION_ID.removeSuffix(".dev")
        assertEquals("com.symmetricalpalmtree.paintsproutonyx", shipped)
    }

    @Test
    fun `the version names the arc`() {
        assertTrue(
            "expected a 0.1.0-onyx version, got ${BuildConfig.VERSION_NAME}",
            BuildConfig.VERSION_NAME.startsWith("0.1.0-onyx"),
        )
    }
}
