package com.symmetricalpalmtree.paintsproutonyx.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What may be typed into the one text field this app has.
 *
 * Worth pinning because the rule is narrower than the storage needs and would otherwise look like an
 * accident to whoever reads it next — a name here is a label in an encrypted index, never a path, so
 * nearly anything would round-trip safely. It is narrow so that a name can be read back off the panel
 * and typed again, which is what makes a library searchable by a person.
 */
class NameRulesTest {

    @Test
    fun `ordinary names are accepted`() {
        assertNull(NameRules.validate("Life drawing"))
        assertNull(NameRules.validate("20260825_094500"))
        assertNull(NameRules.validate("studies-3.v2"))
        assertNull(NameRules.validate("a"))
    }

    @Test
    fun `an empty name is refused`() {
        assertNotNull(NameRules.validate(""))
    }

    @Test
    fun `a name cannot be padded with spaces`() {
        // The caller trims before it gets here, so arriving with one is deliberate — and a name with
        // an invisible edge is not the name it appears to be.
        assertNotNull(NameRules.validate(" studies"))
        assertNotNull(NameRules.validate("studies "))
    }

    @Test
    fun `the path names are reserved`() {
        assertNotNull(NameRules.validate("."))
        assertNotNull(NameRules.validate(".."))
    }

    @Test
    fun `characters that cannot be typed back are refused`() {
        assertNotNull("a slash reads as a path and one day will be treated as one", NameRules.validate("a/b"))
        assertNotNull("a zero-width space is a name nobody can reproduce", NameRules.validate("a​b"))
        assertNotNull(NameRules.validate("café"))
        assertNotNull(NameRules.validate("sketch\nbook"))
    }

    @Test
    fun `a name longer than a card can show is refused`() {
        val atCap = "a".repeat(NameRules.MAX_CHARS)
        assertNull("exactly at the cap is still a name", NameRules.validate(atCap))
        assertNotNull(NameRules.validate(atCap + "a"))
    }

    @Test
    fun `each way of getting it wrong has its own answer`() {
        // The artist sees one dialog and has to know from it what to change. Two different mistakes
        // sharing a sentence would leave them re-reading a name that is fine for the reason given.
        val refusals = listOf("", " x", ".", "a/b", "a".repeat(NameRules.MAX_CHARS + 1))
            .map { bad ->
                val problem = NameRules.validate(bad)
                assertNotNull("\"$bad\" must be refused", problem)
                problem!!
            }
        assertEquals("every refusal names its own reason", refusals.size, refusals.toSet().size)
    }
}
