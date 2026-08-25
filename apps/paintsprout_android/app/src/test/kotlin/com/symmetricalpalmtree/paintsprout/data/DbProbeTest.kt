package com.symmetricalpalmtree.paintsprout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import kotlin.random.Random

class DbProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Step 3 needs a device; tests say what it would have answered. */
    private val opens: (File) -> Boolean = { true }
    private val doesNotOpen: (File) -> Boolean = { false }

    @Test
    fun `a missing file is invalid`() {
        assertEquals(DbState.INVALID, DbProbe.probe(File(tmp.newFolder(), "gone.soil"), opens))
    }

    /**
     * Empty is INVALID rather than ENCRYPTED, and the distinction carries weight:
     * for the global index, INVALID means "fresh install".
     */
    @Test
    fun `an empty file is invalid, and the opener is never consulted`() {
        var consulted = false
        val state = DbProbe.probe(tmp.newFile("empty.soil")) { consulted = true; true }
        assertEquals(DbState.INVALID, state)
        assertFalse(consulted)
    }

    /**
     * SQLCipher encrypts the first page including the header, so the magic is
     * simply absent. Skipping this check is how a plain driver "successfully"
     * opens an encrypted file and reads garbage.
     */
    @Test
    fun `no sqlite header means encrypted, without opening anything`() {
        val ciphertext = tmp.newFile("cipher.soil")
        ciphertext.writeBytes(Random(7).nextBytes(4096))

        var consulted = false
        val state = DbProbe.probe(ciphertext) { consulted = true; true }
        assertEquals(DbState.ENCRYPTED, state)
        assertFalse("the header alone settles it", consulted)
    }

    @Test
    fun `a real sqlite file carries the magic`() {
        val db = realSqliteFile()
        assertTrue(DbProbe.hasSqliteMagic(db))
        assertEquals(DbState.PLAINTEXT, DbProbe.probe(db, opens))
    }

    /** Header present but the open fails: damaged, or encrypted-then-headered. */
    @Test
    fun `magic without a successful open is encrypted`() {
        assertEquals(DbState.ENCRYPTED, DbProbe.probe(realSqliteFile(), doesNotOpen))
    }

    @Test
    fun `a file shorter than the header is not mistaken for one`() {
        val stub = tmp.newFile("short.soil")
        stub.writeBytes("SQLite".toByteArray())
        assertFalse(DbProbe.hasSqliteMagic(stub))
        assertEquals(DbState.ENCRYPTED, DbProbe.probe(stub, opens))
    }

    /** The terminator is the byte the whole check turns on. */
    @Test
    fun `the magic is fifteen ascii characters and a NUL`() {
        assertEquals(16, DbProbe.MAGIC.size)
        assertEquals(0.toByte(), DbProbe.MAGIC[15])
        assertEquals("SQLite format 3", String(DbProbe.MAGIC, 0, 15, Charsets.US_ASCII))
    }

    @Test
    fun `a header that differs in one byte is refused`() {
        val nearly = tmp.newFile("nearly.soil")
        nearly.writeBytes(DbProbe.MAGIC.copyOf().also { it[15] = ' '.code.toByte() } + ByteArray(64))
        assertFalse(DbProbe.hasSqliteMagic(nearly))
    }

    private fun realSqliteFile(): File {
        val f = File(tmp.newFolder(), "real.soil")
        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE t (a TEXT)") }
        }
        return f
    }
}
