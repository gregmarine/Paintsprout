package com.symmetricalpalmtree.paintsprout.paint

import com.symmetricalpalmtree.paintsprout.paint.Pigment.Dab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PigmentTest {

    private val blue = 0xFF0000FF.toInt()
    private val yellow = 0xFFFFFF00.toInt()
    private val red = 0xFFFF0000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val ultramarine = 0xFF1B1BB3.toInt()
    private val cadmiumYellow = 0xFFFFD300.toInt()

    /**
     * Expected values come from an independent implementation of the same
     * algorithm in Python doubles (see the phase notes). The tolerance absorbs
     * float-vs-double drift; anything larger is a real disagreement.
     */
    private val tolerance = 2

    private fun assertColorNear(expected: Int, actual: Int, label: String) {
        for ((shift, channel) in listOf(16 to "red", 8 to "green", 0 to "blue")) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue(
                "$label: $channel expected $e but was $a (expected #${hex(expected)}, was #${hex(actual)})",
                Math.abs(e - a) <= tolerance,
            )
        }
        assertEquals("$label: alpha", 0xFF, (actual ushr 24) and 0xFF)
    }

    private fun hex(c: Int) = String.format("%06X", c and 0xFFFFFF)

    /** The whole point of the pigment engine, and of the tray. */
    @Test
    fun blueAndYellowMakeGreen() {
        val mixed = Pigment.mix(blue, 1f, yellow, 1f)

        val r = (mixed shr 16) and 0xFF
        val g = (mixed shr 8) and 0xFF
        val b = mixed and 0xFF
        assertTrue("green ($g) should dominate red ($r)", g > r + 40)
        assertTrue("green ($g) should dominate blue ($b)", g > b + 40)

        assertColorNear(0xFF398F54.toInt(), mixed, "blue + yellow")
    }

    @Test
    fun realPigmentsMakeAConvincingGreen() {
        assertColorNear(
            0xFF6A8A42.toInt(),
            Pigment.mix(ultramarine, 1f, cadmiumYellow, 1f),
            "ultramarine + cadmium yellow",
        )
    }

    /** Pins the Kotlin port against the shader's arithmetic. */
    @Test
    fun matchesReferenceImplementation() {
        val cases = listOf(
            Triple("blue 3 : yellow 1", listOf(Dab(blue, 3f), Dab(yellow, 1f)), 0xFF005B62.toInt()),
            Triple("blue + yellow + red", listOf(Dab(blue, 1f), Dab(yellow, 1f), Dab(red, 1f)), 0xFF803B2D.toInt()),
            Triple("red + white", listOf(Dab(red, 1f), Dab(white, 1f)), 0xFFFF424A.toInt()),
        )
        for ((label, dabs, expected) in cases) {
            assertColorNear(expected, Pigment.mix(dabs), label)
        }
    }

    /** A lone pigment must come back out as itself, not shifted by the round trip. */
    @Test
    fun singlePigmentRoundTrips() {
        for ((color, label) in listOf(
            blue to "blue", yellow to "yellow", red to "red",
            white to "white", black to "black", ultramarine to "ultramarine",
        )) {
            assertColorNear(color, Pigment.mix(listOf(Dab(color, 1f))), "$label alone")
        }
    }

    /**
     * Mixing N at once is what makes the well order-independent — dabbing blue
     * then yellow must equal yellow then blue.
     */
    @Test
    fun mixIsOrderIndependent() {
        val forward = Pigment.mix(listOf(Dab(blue, 1f), Dab(yellow, 1f), Dab(red, 0.5f)))
        val reverse = Pigment.mix(listOf(Dab(red, 0.5f), Dab(yellow, 1f), Dab(blue, 1f)))
        assertEquals(forward, reverse)
    }

    /** Only ratios matter: a bigger well of the same recipe is the same colour. */
    @Test
    fun mixIsScaleInvariant() {
        val small = Pigment.mix(listOf(Dab(blue, 1f), Dab(yellow, 1f)))
        val large = Pigment.mix(listOf(Dab(blue, 20f), Dab(yellow, 20f)))
        assertColorNear(small, large, "scaled recipe")
    }

    /** More of one pigment must move the mix toward it, monotonically. */
    @Test
    fun moreOfAPigmentPullsTheMixTowardIt() {
        val blueish = (Pigment.mix(blue, 4f, yellow, 1f) and 0xFF)
        val even = (Pigment.mix(blue, 1f, yellow, 1f) and 0xFF)
        val yellowish = (Pigment.mix(blue, 1f, yellow, 4f) and 0xFF)
        assertTrue("blue channel should fall as yellow takes over: $blueish > $even > $yellowish",
            blueish > even && even > yellowish)
    }

    /**
     * Near-black pigment drives K/S huge; the direct Kubelka-Munk form
     * cancels catastrophically in float and speckles NaN. Guards the stable
     * reciprocal form.
     */
    @Test
    fun blackPigmentDoesNotProduceNaN() {
        for (dabs in listOf(
            listOf(Dab(black, 1f), Dab(black, 1f)),
            listOf(Dab(black, 1f), Dab(blue, 1f)),
            listOf(Dab(black, 0.001f), Dab(black, 0.001f)),
        )) {
            val mixed = Pigment.mix(dabs)
            for (shift in listOf(16, 8, 0)) {
                val v = (mixed shr shift) and 0xFF
                assertTrue("channel out of range: $v", v in 0..255)
            }
        }
        assertColorNear(black, Pigment.mix(listOf(Dab(black, 1f), Dab(black, 1f))), "black + black")
    }

    @Test
    fun emptyRecipeIsTransparent() {
        assertEquals(0, Pigment.mix(emptyList()))
    }

    /** A well holding nothing at all shouldn't render as an accidental black. */
    @Test
    fun weightlessRecipeFallsBackToAnAverage() {
        val mixed = Pigment.mix(listOf(Dab(white, 0f), Dab(white, 0f)))
        assertColorNear(white, mixed, "weightless white")
    }
}
