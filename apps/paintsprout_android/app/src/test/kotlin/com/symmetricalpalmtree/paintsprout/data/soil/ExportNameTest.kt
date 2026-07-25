package com.symmetricalpalmtree.paintsprout.data.soil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The filename an exported book lands under, on somebody else's storage. Every
 * case here is about what a name stops being once it is a path.
 */
class ExportNameTest {

    private val id = UUID.randomUUID().toString()

    @Test
    fun `an ordinary name keeps its spaces`() {
        assertEquals("Harbour studies.soil", ExportName.of("Harbour studies", id))
    }

    @Test
    fun `path separators do not survive`() {
        assertEquals("etcpasswd.soil", ExportName.of("../../etc/passwd", id))
        assertEquals("CWindows.soil", ExportName.of("C:\\Windows", id))
        assertFalse(ExportName.of("a/b/c", id).contains('/'))
    }

    /** A stripped character leaves a gap; two spaces are not what anyone typed. */
    @Test
    fun `runs left behind by stripping are collapsed`() {
        assertEquals("Studies 2026.soil", ExportName.of("Studies / 2026", id))
        assertEquals("one two.soil", ExportName.of("one @#$ two", id))
    }

    /**
     * A leading dot hides the file on every unix-like system and a trailing one
     * is silently dropped on Windows — either way the name stops being the name.
     */
    @Test
    fun `leading and trailing dots go`() {
        assertEquals("hidden.soil", ExportName.of(".hidden", id))
        assertEquals("trailing.soil", ExportName.of("trailing...", id))
    }

    /** An emoji-only title is a real thing, and `.soil` is not a filename. */
    @Test
    fun `a name that sanitises to nothing falls back to the id`() {
        assertEquals("$id.soil", ExportName.of("🎨🖌️", id))
        assertEquals("$id.soil", ExportName.of("   ", id))
        assertEquals("$id.soil", ExportName.of("", id))
        assertEquals("$id.soil", ExportName.of("...", id))
    }

    @Test
    fun `a very long name is cut, and still ends in the extension`() {
        val long = "a".repeat(400)
        val out = ExportName.of(long, id)
        assertEquals(ExportName.MAX_LENGTH + ".soil".length, out.length)
        assertTrue(out.endsWith(".soil"))
    }

    /** Dots inside a name are ordinary characters — "v1.2 study" is a fine title. */
    @Test
    fun `inner dots and dashes are kept`() {
        assertEquals("v1.2 study-final.soil", ExportName.of("v1.2 study-final", id))
        assertEquals("under_score.soil", ExportName.of("under_score", id))
    }

    @Test
    fun `newlines and control characters are not filenames`() {
        assertEquals("ab.soil", ExportName.of("a\nb", id))
        assertEquals("ab.soil", ExportName.of("a\u0000b", id))
    }
}
