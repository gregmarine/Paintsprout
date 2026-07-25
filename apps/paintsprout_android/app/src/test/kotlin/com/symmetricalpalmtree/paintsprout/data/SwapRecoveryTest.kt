package com.symmetricalpalmtree.paintsprout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Recovery is tested against the writer that produces the states, not against
 * states invented by hand: [CommitSwap] is interrupted at each of its cut points
 * and [SwapRecovery] has to finish the job correctly.
 */
class SwapRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private val id = UUID.randomUUID().toString()

    private fun real() = SoilFiles.soilFile(root, id)

    private fun seed(content: String): File = real().apply { writeText(content) }

    private fun temp(content: String): File =
        SoilFiles.tempOf(real()).apply { writeText(content) }

    private fun setUpRoot() {
        root = tmp.newFolder()
        SoilFiles.garden(root)
    }

    // --- The three interrupted states ---------------------------------------

    @Test
    fun `a completed swap leaves the new content and drops the aside`() {
        setUpRoot()
        seed("old")
        CommitSwap.commit(real(), temp("new"))

        assertEquals("new", real().readText())
        assertFalse(SoilFiles.asideOf(real()).exists())
        assertFalse(SoilFiles.tempOf(real()).exists())
        assertTrue(SwapRecovery.repair(real()).isEmpty())
    }

    /**
     * Killed between the two renames. The replacement never committed, so the
     * original wins — and it is sitting there under the aside name, which is the
     * entire point of never holding zero copies.
     */
    @Test
    fun `killed after the aside rename restores the original`() {
        setUpRoot()
        seed("old")
        interrupt(CommitSwap.SwapPoint.AFTER_ASIDE_RENAME, "new")

        assertFalse("the real name is empty at the moment of the kill", real().exists())
        assertTrue(SoilFiles.asideOf(real()).exists())

        val actions = SwapRecovery.repair(real())

        assertEquals("old", real().readText())
        assertTrue(actions.any { it is SwapRecovery.AsideRestored })
        assertFalse(SoilFiles.asideOf(real()).exists())
        assertFalse("the uncommitted replacement is debris", SoilFiles.tempOf(real()).exists())
    }

    @Test
    fun `killed after the temp rename keeps the new content and drops the aside`() {
        setUpRoot()
        seed("old")
        interrupt(CommitSwap.SwapPoint.AFTER_TEMP_RENAME, "new")

        assertEquals("new", real().readText())
        assertTrue(SoilFiles.asideOf(real()).exists())

        val actions = SwapRecovery.repair(real())

        assertEquals("new", real().readText())
        assertTrue(actions.any { it is SwapRecovery.StaleAsideDropped })
        assertFalse(SoilFiles.asideOf(real()).exists())
    }

    /** An older build's delete-then-rename window: only a verified temp survives. */
    @Test
    fun `a lone temp is installed`() {
        setUpRoot()
        temp("recovered")

        val actions = SwapRecovery.repair(real())

        assertEquals("recovered", real().readText())
        assertTrue(actions.any { it is SwapRecovery.TempInstalled })
    }

    /** An import that never committed. Nothing verified it; nothing points at it. */
    @Test
    fun `a lone install is dropped`() {
        setUpRoot()
        SoilFiles.installOf(real()).writeText("half an import")

        val actions = SwapRecovery.repair(real())

        assertFalse(real().exists())
        assertFalse(SoilFiles.installOf(real()).exists())
        assertTrue(actions.any { it is SwapRecovery.StaleInstallDropped })
    }

    /**
     * The ghost-file case: a create-capable open fabricated a zero-byte stub at
     * the real name. It must not be allowed to outrank an aside holding the data.
     */
    @Test
    fun `an empty stub does not outrank an aside`() {
        setUpRoot()
        seed("old")
        interrupt(CommitSwap.SwapPoint.AFTER_ASIDE_RENAME, "new")
        real().createNewFile() // the stub

        assertTrue(real().exists())
        SwapRecovery.repair(real())

        assertEquals("old", real().readText())
    }

    @Test
    fun `nothing on disk means nothing to do`() {
        setUpRoot()
        assertEquals(emptyList<SwapRecovery.Action>(), SwapRecovery.repair(real()))
        assertFalse("repair must not create anything", real().exists())
    }

    // --- Sidecars -----------------------------------------------------------

    /**
     * A restored database keeps its own WAL and never inherits a stranger's — a
     * fresh main file paired with an old sidecar restores as corruption.
     */
    @Test
    fun `sidecars travel with the file and orphans at the destination are removed`() {
        setUpRoot()
        seed("old")
        interrupt(CommitSwap.SwapPoint.AFTER_ASIDE_RENAME, "new")

        val aside = SoilFiles.asideOf(real())
        File(aside.path + "-wal").writeText("its own wal")
        File(real().path + "-wal").writeText("orphan from a file that is gone")

        SwapRecovery.repair(real())

        assertEquals("its own wal", File(real().path + "-wal").readText())
        assertFalse(File(aside.path + "-wal").exists())
    }

    // --- The sweep ----------------------------------------------------------

    @Test
    fun `repairAll covers the index and every document, and nothing else`() {
        setUpRoot()
        val garden = SoilFiles.garden(root)
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        File(garden, "$a.soil").writeText("a")
        File(garden, "$b.soil.old.bak").writeText("b recovered")
        File(garden, "stray.txt").writeText("not ours")
        File(garden, "$a.soil-wal").writeText("wal")

        val seen = mutableListOf<String>()
        SwapRecovery.repairAll(root) { base -> seen += base.name; emptyList() }

        assertEquals(
            listOf("paintsprout.db", "$a.soil", "$b.soil").sorted(),
            seen.sorted(),
        )
        assertEquals("the index is repaired first", "paintsprout.db", seen.first())
    }

    @Test
    fun `repairAll actually restores an aside in the garden`() {
        setUpRoot()
        val garden = SoilFiles.garden(root)
        File(garden, "$id.soil.old.bak").writeText("recovered")

        SwapRecovery.repairAll(root)

        assertEquals("recovered", real().readText())
    }

    /** Guard per file, not per pass: one bad entry must not strand the rest. */
    @Test
    fun `a file that cannot be repaired does not stop the sweep`() {
        setUpRoot()
        val garden = SoilFiles.garden(root)
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        File(garden, "$a.soil").writeText("a")
        File(garden, "$b.soil").writeText("b")

        val visited = mutableListOf<String>()
        val actions = SwapRecovery.repairAll(root) { base ->
            visited += base.name
            if (base.name == "$a.soil") throw IllegalStateException("boom")
            emptyList()
        }

        assertEquals(3, visited.size)
        val failures = actions.filterIsInstance<SwapRecovery.RepairFailed>()
        assertEquals(1, failures.size)
        assertEquals("$a.soil", failures.single().file.name)
    }

    // --- The swap's own guarantees ------------------------------------------

    @Test
    fun `a first install needs no aside`() {
        setUpRoot()
        CommitSwap.commit(real(), temp("brand new"))
        assertEquals("brand new", real().readText())
        assertFalse(SoilFiles.asideOf(real()).exists())
    }

    @Test
    fun `committing a missing temp is refused before anything is touched`() {
        setUpRoot()
        seed("old")
        var caught: Throwable? = null
        try {
            CommitSwap.commit(real(), SoilFiles.tempOf(real()))
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is DatabaseMissingException)
        assertEquals("old", real().readText())
    }

    /** At no cut point does the data exist under zero names. */
    @Test
    fun `a copy is always reachable under a name recovery knows`() {
        for (point in CommitSwap.SwapPoint.values()) {
            setUpRoot()
            seed("old")
            interrupt(point, "new")

            val names = listOf(real(), SoilFiles.asideOf(real()), SoilFiles.tempOf(real()))
                .filter { existsAsDatabase(it) }
            assertTrue("nothing survives $point", names.isNotEmpty())

            SwapRecovery.repair(real())
            assertTrue("$point left no database", existsAsDatabase(real()))
        }
    }

    private fun interrupt(point: CommitSwap.SwapPoint, content: String) {
        try {
            CommitSwap.commit(real(), temp(content), point)
        } catch (e: CommitSwap.SwapInterrupted) {
            // The point of the exercise.
        }
    }
}
