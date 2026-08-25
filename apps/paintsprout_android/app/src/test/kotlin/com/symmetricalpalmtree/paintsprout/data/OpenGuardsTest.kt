package com.symmetricalpalmtree.paintsprout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenGuardsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a missing file is refused, not created`() {
        val missing = File(tmp.newFolder(), "nope.soil")
        assertThrowsMissing(missing)
        assertFalse("the guard must not bring the file into existence", missing.exists())
    }

    /**
     * An empty file is the dangerous case, not a harmless one: an empty encrypted
     * database "verifies" any passphrase you type, because it was created keyed to
     * whatever was passed.
     */
    @Test
    fun `a zero-byte file counts as missing`() {
        val empty = tmp.newFile("empty.soil")
        assertEquals(0L, empty.length())
        assertThrowsMissing(empty)
        assertFalse(existsAsDatabase(empty))
    }

    @Test
    fun `a real file passes through unchanged`() {
        val real = tmp.newFile("real.soil").apply { writeText("content") }
        assertEquals(real, requireExistingDatabase(real))
        assertTrue(existsAsDatabase(real))
    }

    /** Verification answers false for what it cannot see — never true. */
    @Test
    fun `existsAsDatabase is false for missing and empty`() {
        assertFalse(existsAsDatabase(File(tmp.newFolder(), "nope.soil")))
        assertFalse(existsAsDatabase(tmp.newFile("blank.soil")))
    }

    private fun assertThrowsMissing(file: File) {
        var caught: SoilOpenException? = null
        try {
            requireExistingDatabase(file)
        } catch (e: SoilOpenException) {
            caught = e
        }
        assertTrue("expected DatabaseMissingException", caught is DatabaseMissingException)
        assertEquals(file, (caught as DatabaseMissingException).file)
    }
}
