package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayTest {

    private val eps = 1e-4f

    private val blue = 0xFF0000FF.toInt()
    private val yellow = 0xFFFFFF00.toInt()
    private val red = 0xFFFF0000.toInt()

    private val bluePot = Pot("Test Blue", blue)
    private val yellowPot = Pot("Test Yellow", yellow)

    // --- Recipe ------------------------------------------------------------

    /** Continuous pickup would otherwise grow the recipe once per sample. */
    @Test
    fun repeatedDabsOfOnePigmentCoalesce() {
        var recipe = Recipe.EMPTY
        repeat(1000) { recipe = recipe.plus(blue, 0.01f) }

        assertEquals(1, recipe.pigmentCount)
        assertEquals(10f, recipe.total, 1e-2f)
    }

    @Test
    fun distinctPigmentsAccumulateSeparately() {
        val recipe = Recipe.of(blue, 1f).plus(yellow, 2f).plus(blue, 0.5f)

        assertEquals(2, recipe.pigmentCount)
        assertEquals(1.5f, recipe.amountOf(blue), eps)
        assertEquals(2f, recipe.amountOf(yellow), eps)
        assertEquals(3.5f, recipe.total, eps)
    }

    /**
     * The property the whole load/volume unification rests on: scaling a
     * recipe changes how much there is, never what colour it is.
     */
    @Test
    fun scalingChangesQuantityNotColour() {
        val recipe = Recipe.of(blue, 1f).plus(yellow, 1f)
        val drained = recipe.scaledTo(0.1f)

        assertEquals(0.1f, drained.total, eps)
        assertEquals(recipe.color, drained.color)
        assertEquals(0.05f, drained.amountOf(blue), eps)
        assertEquals(0.05f, drained.amountOf(yellow), eps)
    }

    @Test
    fun mixingTwoPigmentsInAWellReadsAsTheirMix() {
        val recipe = Recipe.of(blue, 1f).plus(yellow, 1f)
        assertEquals(Pigment.mix(blue, 1f, yellow, 1f), recipe.color)
    }

    @Test
    fun emptyRecipeStaysEmpty() {
        assertTrue(Recipe.EMPTY.isEmpty)
        assertTrue(Recipe.EMPTY.plus(blue, 0f).isEmpty)
        assertTrue(Recipe.EMPTY.scaledTo(5f).isEmpty)
        assertEquals(0f, Recipe.EMPTY.total, eps)
    }

    @Test
    fun recipesCombine() {
        val combined = Recipe.of(blue, 1f).plus(Recipe.of(yellow, 2f).plus(blue, 1f))

        assertEquals(2f, combined.amountOf(blue), eps)
        assertEquals(2f, combined.amountOf(yellow), eps)
    }

    // --- BrushLoad ---------------------------------------------------------

    @Test
    fun aFreshBrushIsFull() {
        val load = BrushLoad.of(blue)

        assertEquals(1f, load.fill, eps)
        assertEquals(blue, load.color)
        assertFalse(load.isDry)
    }

    /** A drying brush fades; it must not drift in hue as it goes. */
    @Test
    fun depositingDrainsTheBrushWithoutChangingItsColour() {
        val full = BrushLoad(Recipe.of(blue, 0.5f).plus(yellow, 0.5f))
        val half = full.deposit(0.5f)

        assertEquals(0.5f, half.fill, eps)
        assertEquals(0.5f, half.volume, eps)
        assertEquals(full.color, half.color)
    }

    @Test
    fun aBrushRunsDry() {
        val load = BrushLoad.of(blue).deposit(0.9f).deposit(0.2f)

        assertTrue(load.isDry)
        assertEquals(0f, load.fill, eps)
        assertTrue(load.recipe.isEmpty)
    }

    @Test
    fun depositingMoreThanIsLeftDoesNotGoNegative() {
        val load = BrushLoad.of(blue).deposit(10f)

        assertEquals(0f, load.volume, eps)
        assertTrue(load.isDry)
    }

    /** The dirty brush: dragging a blue brush through yellow turns it green. */
    @Test
    fun contaminatingTheLoadShiftsItsColour() {
        val clean = BrushLoad.of(blue)
        // Swap half the load for yellow -> equal blue and yellow -> green.
        val dirty = clean.contaminate(yellow, 0.5f)

        assertNotEquals(clean.color, dirty.color)
        assertEquals(Pigment.mix(blue, 1f, yellow, 1f), dirty.color)

        val g = (dirty.color shr 8) and 0xFF
        val r = (dirty.color shr 16) and 0xFF
        assertTrue("a blue brush dragged through yellow should read green, was #${"%06X".format(dirty.color and 0xFFFFFF)}", g > r + 40)
    }

    /** Contamination swaps paint, it doesn't add any — a dirty brush doesn't refill. */
    @Test
    fun contaminatingKeepsTheVolumeConstant() {
        val load = BrushLoad.of(blue).deposit(0.4f) // 0.6 left
        val before = load.volume
        val dirty = load.contaminate(red, 0.5f)

        assertEquals(before, dirty.volume, eps)
    }

    /** Dragging through the colour it already carries changes nothing. */
    @Test
    fun draggingThroughItsOwnColourIsANoOp() {
        val load = BrushLoad.of(blue).deposit(0.3f)
        val same = load.contaminate(blue, 0.4f)

        assertEquals(load.color, same.color)
        assertEquals(load.volume, same.volume, eps)
        assertEquals(1, same.recipe.pigmentCount)
    }

    /**
     * The brush forgets: dragging through red the whole way, each pass swaps a
     * little of the load for red, so red steadily overtakes the blue it started
     * with.
     */
    @Test
    fun aBrushGraduallyForgetsWhatItStartedWith() {
        var load = BrushLoad.of(blue)

        repeat(40) { load = load.contaminate(red, 0.1f) }

        assertTrue(
            "red should have overtaken blue: red=${load.recipe.amountOf(red)} blue=${load.recipe.amountOf(blue)}",
            load.recipe.amountOf(red) > load.recipe.amountOf(blue) * 5,
        )
    }

    /** Contaminating through many colours can't grow the recipe without bound. */
    @Test
    fun contaminationDoesNotGrowTheRecipeUnbounded() {
        var load = BrushLoad.of(blue)
        // Drag through a long, ever-changing wash.
        repeat(200) { i ->
            val c = 0xFF000000.toInt() or (i * 7 and 0xFFFFFF)
            load = load.contaminate(c, 0.05f)
        }
        assertTrue("recipe stayed bounded, was ${load.recipe.pigmentCount}", load.recipe.pigmentCount <= 12)
    }

    // --- Tray --------------------------------------------------------------

    @Test
    fun aFreshTrayHasACleanWell() {
        val tray = Tray()

        assertTrue(tray.mixture.isEmpty)
        assertNull(tray.mixedColor)
    }

    @Test
    fun dabbingTwoPotsMixesThemInTheWell() {
        val tray = Tray()
        tray.dab(bluePot)
        tray.dab(yellowPot)

        assertEquals(2, tray.mixture.pigmentCount)
        assertEquals(Pigment.mix(blue, 1f, yellow, 1f), tray.mixedColor)
    }

    /** More of one pot pulls the well toward it. */
    @Test
    fun dabbingMoreOfAPotShiftsTheWell() {
        val tray = Tray()
        tray.dab(bluePot, 1f)
        tray.dab(yellowPot, 1f)
        val even = tray.mixedColor!!

        tray.dab(yellowPot, 3f)
        val yellower = tray.mixedColor!!

        assertTrue(
            "well should move toward yellow",
            ((yellower shr 8) and 0xFF) > ((even shr 8) and 0xFF),
        )
    }

    @Test
    fun clearingWipesTheWell() {
        val tray = Tray()
        tray.dab(bluePot)
        tray.clearMixture()

        assertTrue(tray.mixture.isEmpty)
        assertNull(tray.mixedColor)
    }

    @Test
    fun loadingTheBrushTakesTheWellsColourAtFullCapacity() {
        val tray = Tray()
        tray.dab(bluePot)
        tray.dab(yellowPot)

        val load = tray.loadBrush()

        assertEquals(tray.mixedColor, load.color)
        assertEquals(1f, load.fill, eps)
        assertEquals(BrushLoad.DEFAULT_CAPACITY, load.volume, eps)
    }

    /** A palette holds more than one brushful — you shouldn't re-mix each stroke. */
    @Test
    fun loadingDoesNotConsumeTheWell() {
        val tray = Tray()
        tray.dab(bluePot, 2f)
        val before = tray.mixture

        tray.loadBrush()
        tray.loadBrush()

        assertEquals(before, tray.mixture)
        assertEquals(2f, tray.mixture.total, eps)
    }

    @Test
    fun loadingFromACleanWellGivesADryBrush() {
        assertTrue(Tray().loadBrush().isDry)
    }

    @Test
    fun aColourFromTheWheelCanJoinTheRim() {
        val tray = Tray()
        val custom = Pot("Custom", 0xFF123456.toInt(), custom = true)
        tray.addPot(custom)

        assertTrue(tray.pots.contains(custom))
        tray.dab(custom)
        assertEquals(0xFF123456.toInt(), tray.mixedColor)

        assertTrue(tray.removePot(custom))
        assertFalse(tray.pots.contains(custom))
    }

    @Test
    fun theStandardPotsIncludeTheBacklogsNamedPigments() {
        val names = Tray.STANDARD_POTS.map { it.name }

        for (expected in listOf("Ultramarine Blue", "Cadmium Yellow", "Quinacridone Magenta", "Phthalo Green")) {
            assertTrue("missing $expected", names.contains(expected))
        }
        assertTrue("standard pots should all be opaque", Tray.STANDARD_POTS.all { (it.color ushr 24) == 0xFF })
        assertFalse("standard pots aren't custom", Tray.STANDARD_POTS.any { it.custom })
    }

    /** The headline, end to end through the real palette. */
    @Test
    fun ultramarineAndCadmiumYellowMixToGreenOnTheTray() {
        val tray = Tray()
        tray.dab(Tray.STANDARD_POTS.first { it.name == "Ultramarine Blue" })
        tray.dab(Tray.STANDARD_POTS.first { it.name == "Cadmium Yellow" })

        val mixed = tray.mixedColor!!
        val r = (mixed shr 16) and 0xFF
        val g = (mixed shr 8) and 0xFF
        val b = mixed and 0xFF

        assertTrue("expected green, got #${"%06X".format(mixed and 0xFFFFFF)}", g > r && g > b)
    }
}
