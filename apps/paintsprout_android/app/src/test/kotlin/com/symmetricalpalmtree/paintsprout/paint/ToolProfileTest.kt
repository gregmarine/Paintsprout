package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProfileTest {

    @Test
    fun onlyPenAndEraserAreNonDynamic() {
        assertFalse(Tool.PEN.isDynamic)
        assertFalse(Tool.ERASER.isDynamic)
        for (t in listOf(Tool.PENCIL, Tool.BRUSH, Tool.WATERCOLOR, Tool.MARKER, Tool.SPRAY, Tool.WAND)) {
            assertTrue("$t should be dynamic", t.isDynamic)
        }
    }

    @Test
    fun onlyWandIsNonDrawing() {
        assertFalse(Tool.WAND.isDrawing)
        for (t in Tool.entries.filter { it != Tool.WAND }) {
            assertTrue("$t should be a drawing tool", t.isDrawing)
        }
    }

    @Test
    fun defaultSizesMatchReference() {
        assertEquals(1f, Tool.PENCIL.defaultSize, 0f)
        assertEquals(3f, Tool.PEN.defaultSize, 0f)
        assertEquals(18f, Tool.BRUSH.defaultSize, 0f)
        assertEquals(26f, Tool.WATERCOLOR.defaultSize, 0f)
        assertEquals(4f, Tool.MARKER.defaultSize, 0f)
        assertEquals(28f, Tool.SPRAY.defaultSize, 0f)
        assertEquals(24f, Tool.ERASER.defaultSize, 0f)
        assertEquals(1f, Tool.WAND.defaultSize, 0f)
    }

    @Test
    fun renderStyleMapping() {
        assertEquals(RenderStyle.GRAIN, ToolProfile.of(Tool.PENCIL).renderStyle)
        assertEquals(RenderStyle.GRAIN, ToolProfile.of(Tool.MARKER).renderStyle)
        assertEquals(RenderStyle.SOLID, ToolProfile.of(Tool.PEN).renderStyle)
        assertEquals(RenderStyle.SOLID, ToolProfile.of(Tool.ERASER).renderStyle)
        assertEquals(RenderStyle.BRISTLE, ToolProfile.of(Tool.BRUSH).renderStyle)
        assertEquals(RenderStyle.WASH, ToolProfile.of(Tool.WATERCOLOR).renderStyle)
        assertEquals(RenderStyle.SOFT, ToolProfile.of(Tool.SPRAY).renderStyle)
    }

    @Test
    fun wandBorrowsThePenProfile() {
        assertEquals(ToolProfile.of(Tool.PEN), ToolProfile.of(Tool.WAND))
    }

    @Test
    fun reactsToToothWhenFloorBelowOne() {
        // Every current tool has a tooth floor < 1, so all react to the tooth.
        for (t in Tool.entries) {
            val p = ToolProfile.of(t)
            assertEquals("$t reactsToTooth", p.toothFloor < 1.0f, p.reactsToTooth)
            assertTrue("$t should react to tooth", p.reactsToTooth)
        }
    }

    @Test
    fun keyProfileValuesMatchReference() {
        val brush = ToolProfile.of(Tool.BRUSH)
        assertEquals(0.35f, brush.minPressureFactor, 0f)
        assertEquals(2.2f, brush.maxPressureFactor, 0f)
        assertTrue(brush.pressureAffectsWidth)

        val pencil = ToolProfile.of(Tool.PENCIL)
        assertTrue(pencil.pressureAffectsDensity)
        assertFalse(pencil.pressureAffectsWidth)
        assertEquals(0.1f, pencil.minDensity, 0f)
        assertEquals(0.95f, pencil.maxDensity, 0f)
        assertEquals(16.0f, pencil.tiltGain, 0f)
        assertEquals(0.0f, pencil.toothFloor, 0f)

        val watercolor = ToolProfile.of(Tool.WATERCOLOR)
        assertEquals(0.5f, watercolor.opacity, 0f)
    }
}
