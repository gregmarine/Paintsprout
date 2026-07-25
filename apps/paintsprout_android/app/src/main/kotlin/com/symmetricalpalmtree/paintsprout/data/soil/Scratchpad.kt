package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.Sentinels
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random

/**
 * Somewhere to try something out.
 *
 * The scratchpad is a document in every sense the editor cares about — pages,
 * layers, ops, undo across sessions, its own tray — and in no sense the library
 * cares about: it has no card, no cover, no name and no file. It lives in the
 * index's own `scratchpad` table, which is the universal object row, so the
 * repository, the codecs and the subtree walks are the sketchbook's unchanged.
 * That is what will make "send this page to a sketchbook" a copy between two
 * repositories in Phase 21 rather than a translation layer.
 *
 * There is exactly one, and its root is a sentinel ensured at every launch rather
 * than created by a migration — a migration that inserts data can fail halfway on
 * somebody's device, and this simply runs again.
 */
object Scratchpad {

    /**
     * The tools a scratch page offers.
     *
     * A deliberate subset, not a technical limit: the scratchpad is for trying a
     * mark out, and a rail of thirteen tools is the sketchbook's business. Pencil,
     * pen and brush cover dry, inked and wet; the eraser takes them back; the wand
     * and the lasso select.
     *
     * The shape tools are the pointed omission — a line you plotted with handles
     * is something you meant, and something you meant belongs in a book.
     */
    val TOOLS: List<Tool> =
        listOf(Tool.PENCIL, Tool.PEN, Tool.BRUSH, Tool.ERASER, Tool.WAND, Tool.LASSO)

    /** What the rail falls back to when the current tool is not on offer here. */
    val DEFAULT_TOOL: Tool = Tool.PENCIL

    /**
     * Opens the scratchpad, creating it the first time.
     *
     * [seed] is a parameter so a test gets a reproducible sheet; the app passes a
     * fresh one, which only matters for the very first page — after that the pad
     * has its own pages and their own paper.
     */
    suspend fun open(seed: Long = Random().nextLong()): DocumentSession = withContext(Dispatchers.IO) {
        val repo = repository()
        // Idempotent: the sentinel root, the palette and a first page all get
        // ensured rather than created, so this is also the repair path for a pad
        // that lost its pages to some earlier failure.
        if (repo.pages().isEmpty()) {
            repo.createDocument(
                title = NAME,
                surfaceKind = SurfaceKind.PAPER.name,
                surfaceSeed = seed,
            )
        }
        DocumentSession.on(ScratchpadHome(Sentinels.SCRATCHPAD_ROOT_ID), repo)
    }

    suspend fun repository(): SketchbookRepository = SketchbookRepository(
        store = ObjectTable(IndexGate.awaitConnection(), SchemaSql.SCRATCHPAD_TABLE),
        rootId = Sentinels.SCRATCHPAD_ROOT_ID,
    )

    /** Only ever shown in the editor; never a library card, so never a filename. */
    const val NAME = "Scratchpad"
}
