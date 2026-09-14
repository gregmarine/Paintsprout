package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilSchema
import com.symmetricalpalmtree.paintsproutonyx.library.NameRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a bake is going to make, checked before anything makes it.
 *
 * The bake is the one act in this app that turns one of the artist's sketchbooks into another, and
 * it cannot be undone once the marks are graphite — so as much of it as can be decided without a
 * tablet is decided in [RasterBake] and pinned here. What is left for the device to prove is the
 * drawing itself: whether the copy's pages *look* like the original's is a matter for the panel and
 * the eye, and no assertion in this file has an opinion about it.
 *
 * The rules that matter are the ones a mistake in would be silent. A page copied at the wrong size,
 * a book that opens on the wrong leaf, a copy that takes its original's name — none of those looks
 * like a bug when it happens, they look like the artist misremembering.
 */
class RasterBakeTest {

    private val now = 1_700_000_000_000L

    private fun page(
        id: String,
        order: Int,
        width: Float? = 1860f,
        height: Float? = 2480f,
    ) = SoilObjectEntity(
        id = id,
        parentId = "book-source",
        type = SoilSchema.TYPE_PAGE,
        order = order,
        createdAt = 1L,
        updatedAt = 2L,
        refId = "",
        width = width,
        height = height,
    )

    private fun book(openOn: String?) = SoilObjectEntity(
        id = "book-source",
        parentId = SoilSchema.ROOT_PARENT,
        type = SoilSchema.TYPE_SKETCHBOOK,
        createdAt = 1L,
        updatedAt = 2L,
        text = "Studies",
        refId = openOn,
        flags = RasterRows.flagsFor(false),
    )

    private fun stroke(id: String) = Stroke(
        id = id,
        points = (0 until 8).map {
            StrokePoint(x = 10f + it * 3.5f, y = 20f - it * 1.25f, pressure = 0.4f, tilt = 0f)
        },
        color = 0xFF505050.toInt(),
        width = Lead.DEFAULT.widthPx,
        style = StrokeStyle.PENCIL,
    )

    /** Three leaves, the middle one blank, the book left open on the last. */
    private fun source(
        openOn: String? = "page-c",
        pages: List<SoilObjectEntity> = listOf(page("page-a", 0), page("page-b", 1), page("page-c", 5)),
        marks: Map<String, List<Stroke>> = mapOf(
            "page-a" to listOf(stroke("mark-1"), stroke("mark-2")),
            "page-c" to listOf(stroke("mark-3")),
        ),
    ) = RasterBake.Source(book = book(openOn), pages = pages, marksByPage = marks)

    /** Deterministic ids, so a test can say "the same plan twice" and mean it. */
    private val mint: (String) -> String = { "new-$it" }

    private fun plan(source: RasterBake.Source = source(), name: String = "Studies raster") =
        RasterBake.plan(source, name, "book-copy", mint, now)

    // ── The pages ────────────────────────────────────────────────────────────

    @Test
    fun `every live page becomes one leaf of the copy, in order`() {
        val plan = plan()
        assertEquals(3, plan.pages.size)
        assertEquals(listOf("page-a", "page-b", "page-c"), plan.pages.map { it.sourcePageId })
    }

    @Test
    fun `a leaf keeps its place in the stack and its size, and its paper is nothing`() {
        val plan = plan()
        // The `order` values are copied, not renumbered: the gaps between leaves are room for a
        // page to be inserted later, and closing them would change where the next insert lands.
        assertEquals(listOf(0, 1, 5), plan.pages.map { it.row.order })
        for (leaf in plan.pages) {
            assertEquals(1860f, leaf.row.width!!, 0f)
            assertEquals(2480f, leaf.row.height!!, 0f)
            assertEquals("", leaf.row.refId)
            assertEquals(SoilSchema.TYPE_PAGE, leaf.row.type)
            assertEquals("book-copy", leaf.row.parentId)
            assertEquals(now, leaf.row.createdAt)
            assertEquals(now, leaf.row.updatedAt)
            assertNull(leaf.row.deletedAt)
        }
    }

    @Test
    fun `a page with nothing on it carries no marks, and a drawn one carries all of them in order`() {
        val plan = plan()
        assertEquals(listOf("mark-1", "mark-2"), plan.pages[0].marks.map { it.id })
        assertTrue(plan.pages[1].marks.isEmpty())
        assertEquals(listOf("mark-3"), plan.pages[2].marks.map { it.id })
    }

    @Test
    fun `nothing in the copy carries an id from the book it was copied from`() {
        val plan = plan()
        val sourceIds = setOf("book-source", "page-a", "page-b", "page-c")
        val copyIds = plan.pages.map { it.row.id } + plan.book.id
        assertEquals(copyIds.size, copyIds.toSet().size)
        for (id in copyIds) assertTrue("$id came from the source", id !in sourceIds)
        assertEquals("book-copy", plan.book.id)
    }

    // ── The book ─────────────────────────────────────────────────────────────

    @Test
    fun `the copy is a raster book, named, and at the root of its own file`() {
        val plan = plan(name = "Studies raster")
        assertEquals(RasterRows.flagsFor(true), plan.book.flags)
        assertTrue(RasterRows.isRaster(plan.book.flags))
        assertEquals("Studies raster", plan.book.text)
        assertEquals(SoilSchema.ROOT_PARENT, plan.book.parentId)
        assertEquals(SoilSchema.TYPE_SKETCHBOOK, plan.book.type)
        assertEquals(now, plan.book.createdAt)
        assertEquals(now, plan.book.updatedAt)
    }

    @Test
    fun `the copy opens on the leaf the original was left open on`() {
        val plan = plan()
        assertEquals(plan.pages[2].row.id, plan.book.refId)
    }

    @Test
    fun `a pointer at a page that is not there falls to the first leaf`() {
        // A page thrown away since the book was last closed, or a file from before there were
        // pointers at all. Landing on nothing is not an option; the first leaf is what
        // SketchbookSession.open falls to and this agrees with it.
        val gone = plan(source(openOn = "page-gone"))
        assertEquals(gone.pages.first().row.id, gone.book.refId)
        val never = plan(source(openOn = null))
        assertEquals(never.pages.first().row.id, never.book.refId)
    }

    @Test(expected = IllegalStateException::class)
    fun `a book with no leaves is not one this app can copy`() {
        plan(source(pages = emptyList(), marks = emptyMap()))
    }

    @Test
    fun `the same source planned twice is the same plan`() {
        assertEquals(plan(), plan())
    }

    // ── The name ─────────────────────────────────────────────────────────────

    private val pattern: (String) -> String = { "$it raster" }

    /**
     * `copyName` suspends because the only thing that knows whether a name is free is the index.
     * Nothing in these tests goes near a database, so `runBlocking` on the test thread is the whole
     * of what is needed — the rule about never blocking is a rule about the UI thread, and this is
     * a JVM test with no panel to hold up.
     */
    private fun copyName(sourceName: String, taken: (String) -> Boolean): String =
        runBlocking { RasterBake.copyName(sourceName, pattern) { taken(it) } }

    @Test
    fun `the first copy has no number on it`() {
        assertEquals("Studies raster", copyName("Studies") { false })
    }

    @Test
    fun `a name already on the shelf counts upward from two`() {
        val taken = setOf("Studies raster", "Studies raster 2")
        assertEquals("Studies raster 3", copyName("Studies") { it in taken })
    }

    @Test
    fun `a name at the limit is trimmed at its tail, never at its suffix`() {
        val long = "a".repeat(NameRules.MAX_CHARS)
        val name = copyName(long) { false }
        assertTrue(name.length <= NameRules.MAX_CHARS)
        assertTrue("the suffix is the information and must survive", name.endsWith(" raster"))
        assertNull(NameRules.validate(name))
    }

    @Test
    fun `a trimmed name still finds one nobody has taken`() {
        val long = "a".repeat(NameRules.MAX_CHARS)
        val first = copyName(long) { false }
        val second = copyName(long) { it == first }
        assertNotEquals(first, second)
        assertTrue(second.length <= NameRules.MAX_CHARS)
        assertTrue(second.endsWith(" raster 2"))
        assertNull(NameRules.validate(second))
    }

    @Test
    fun `cutting a name never leaves a gap in front of the suffix`() {
        // The stem is trimmed after every cut, so a name cut in the middle of a space does not
        // produce "Life drawing  raster" — two spaces read as a typo nobody can find the source of.
        val name = copyName("${"b".repeat(NameRules.MAX_CHARS - 2)} c") { false }
        assertTrue(!name.contains("  "))
        assertNull(NameRules.validate(name))
    }

    // ── The fingerprint ──────────────────────────────────────────────────────

    @Test
    fun `a digest is sixteen hex characters and the same every time`() {
        val bytes = ByteArray(64) { it.toByte() }
        val first = RasterBake.digest(bytes)
        assertEquals(16, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertEquals(first, RasterBake.digest(bytes))
    }

    @Test
    fun `two different pictures do not have the same fingerprint`() {
        val a = ByteArray(64) { it.toByte() }
        val b = ByteArray(64) { it.toByte() }.also { it[63] = 99 }
        assertNotEquals(RasterBake.digest(a), RasterBake.digest(b))
    }
}
