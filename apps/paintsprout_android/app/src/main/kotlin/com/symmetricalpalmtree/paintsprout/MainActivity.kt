package com.symmetricalpalmtree.paintsprout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.symmetricalpalmtree.paintsprout.databinding.ActivityMainBinding
import com.symmetricalpalmtree.paintsprout.paint.AVAILABLE_SURFACES
import com.symmetricalpalmtree.paintsprout.paint.Calibration
import com.symmetricalpalmtree.paintsprout.paint.Layer
import com.symmetricalpalmtree.paintsprout.paint.CanvasParams
import com.symmetricalpalmtree.paintsprout.paint.CanvasSize
import com.symmetricalpalmtree.paintsprout.paint.ChalkboardParams
import com.symmetricalpalmtree.paintsprout.paint.ConcreteParams
import com.symmetricalpalmtree.paintsprout.paint.MetalParams
import com.symmetricalpalmtree.paintsprout.paint.PageTurn
import com.symmetricalpalmtree.paintsprout.paint.Pot
import com.symmetricalpalmtree.paintsprout.paint.StoneParams
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.SurfaceOp
import com.symmetricalpalmtree.paintsprout.paint.buildSurfaceVisual
import com.symmetricalpalmtree.paintsprout.paint.Tool
import com.symmetricalpalmtree.paintsprout.paint.Tray
import com.symmetricalpalmtree.paintsprout.paint.WatercolorParams
import com.symmetricalpalmtree.paintsprout.paint.WoodParams
import com.symmetricalpalmtree.paintsprout.data.LastOpen
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.soil.DocumentSession
import com.symmetricalpalmtree.paintsprout.data.soil.PageTransfer
import com.symmetricalpalmtree.paintsprout.data.soil.Scratchpad
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Hosts [PaintCanvasView] behind a floating tool rail — the native counterpart
 * of the Flutter reference's `CanvasScreen` + `_ToolRail`. The rail's buttons are
 * built in code: a loop over the tools, then context-sensitive color / size /
 * surface / selection / history / save actions. Landscape-locked and immersive.
 *
 * Undo/redo map straight to the canvas's own op history (paint ops). Surface and
 * plain-colour changes are not yet on that timeline (a known gap vs. Flutter,
 * which snapshots the whole document).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var tool = Focus.DEFAULT_TOOL
    private var color = Focus.COLOR
    private var surfaceIndex = AVAILABLE_SURFACES.indexOf(Focus.SURFACE).coerceAtLeast(0)
    private var plainColor = Color.WHITE
    private var canvasSize: CanvasSize = CanvasSize.FullScreen
    private var canvasParams = CanvasParams()
    private var watercolorParams = WatercolorParams()
    private var woodParams = WoodParams()
    private var stoneParams = StoneParams()
    private var concreteParams = ConcreteParams()
    private var metalParams = MetalParams()
    private var chalkboardParams = ChalkboardParams()
    /** The palette. Pots and the mixing well; see [TrayView]. */
    private val tray = Tray()
    private var trayOut = false
    private var trayHiddenX = 0f

    private var hasSelection = false
    private var hasPendingLine = false
    private var hasPendingArc = false
    private var hasPendingPolyline = false
    private var hasPendingPolyarc = false

    // Magic-wand settings (Flutter defaults).
    private var wandTolerance = 0.15f
    private var wandEdgeSensitivity = 0.5f
    private var wandGap = 3

    // Each tool remembers its own base size, in millimetres. Converted to pixels
    // at the current PPI when pushed to the canvas, so a size is a real physical
    // width on any calibrated screen.
    private val sizes = Tool.values().associateWith { it.defaultSizeMm }.toMutableMap()

    private val calibrationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val ppi = result.data?.getFloatExtra(CalibrationActivity.EXTRA_PPI, 0f) ?: 0f
                if (ppi > 0f) {
                    // Sizes are stored in mm; re-push at the new PPI so brush widths
                    // stay their real physical size.
                    applySizeToCanvas()
                    Snackbar.make(
                        binding.root, "Screen calibrated: ${ppi.roundToInt()} PPI",
                        Snackbar.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    // Rail views kept for state updates.
    private val toolButtons = mutableMapOf<Tool, ImageButton>()
    private lateinit var colorBtn: ImageButton
    private lateinit var sizeBtn: TextView
    private lateinit var toleranceBtn: TextView
    private lateinit var waterBtn: ImageButton
    private var waterMode = false
    private lateinit var surfaceBtn: ImageButton
    private lateinit var fillBtn: ImageButton
    private lateinit var eraseBtn: ImageButton
    private lateinit var deselectBtn: ImageButton
    private lateinit var lineDoneBtn: ImageButton
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private lateinit var pagesBtn: TextView
    private lateinit var layersBtn: ImageButton
    private lateinit var scratchBtn: ImageButton
    private lateinit var canvasSizeBtn: ImageButton
    private lateinit var copyBtn: ImageButton
    private lateinit var pasteBtn: ImageButton

    /**
     * How many marks are on the clipboard, as far as the rail knows.
     *
     * Cached rather than queried while drawing the rail: the clipboard lives in
     * the index database, and `updateRail` runs on every stroke, every undo and
     * every tool change. Refreshed when it can actually have changed.
     */
    private var clipboardCount = 0

    /** The document being painted into, once it has finished opening. */
    private var session: DocumentSession? = null

    /**
     * Whether that document is the scratchpad rather than a sketchbook.
     *
     * The editor is otherwise the same screen — same canvas, same tray, same
     * pages — so this drives only what the rail offers and where the way out
     * leads. It is set before the rail is next drawn and never read by anything
     * that writes.
     */
    private var isScratchpad = false

    /** Outlives this screen, so a flush is never cut short by leaving it. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        buildRail()
        setupTray()
        binding.btnShowRail.setOnClickListener { setRailVisible(true) }
        binding.layerAdd.setOnClickListener { addLayer() }
        binding.canvas.onLayersChanged = { refreshLayers() }
        applyOrientation()

        binding.canvas.tool = tool
        binding.canvas.strokeColor = color
        applySizeToCanvas()
        binding.canvas.setInitialSurface(
            currentSurface(), plainColor, canvasParams, watercolorParams, woodParams, stoneParams,
            concreteParams, metalParams, chalkboardParams,
        )
        applyWandSettings()
        binding.canvas.onHistoryChanged = {
            // Undo/redo may have reverted the surface — mirror it back into the rail.
            syncSurfaceFromCanvas()
            updateRail()
        }
        binding.canvas.onSelectionChanged = {
            hasSelection = it
            updateRail()
        }
        binding.canvas.onLineChanged = {
            hasPendingLine = it
            updateRail()
        }
        binding.canvas.onArcChanged = {
            hasPendingArc = it
            updateRail()
        }
        binding.canvas.onPolylineChanged = {
            hasPendingPolyline = it
            updateRail()
        }
        binding.canvas.onPolyarcChanged = {
            hasPendingPolyarc = it
            updateRail()
        }
        updateRail()
    }

    override fun onStart() {
        super.onStart()
        val displays = getSystemService(android.hardware.display.DisplayManager::class.java)
        displays?.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        applyOrientation()
        // Opened here rather than in onCreate: the document is sealed whenever the
        // editor is not in front of the user, so coming back has to reopen it.
        if (session == null) attachDocument()
    }

    /**
     * Seals the document on the way out.
     *
     * A `.soil` must not be left with a `-wal` beside it — a file browser should
     * show sketchbooks and nothing else — so the file is genuinely closed when the
     * editor stops, not merely flushed. The snapshots are taken here, on the main
     * thread, while those bitmaps are certainly still alive; the writing happens
     * on a scope that outlives this screen.
     */
    override fun onStop() {
        super.onStop()
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.unregisterDisplayListener(displayListener)
        val open = session ?: return
        session = null
        detachCanvasHooks()

        val paint = if (open.isDirty) binding.canvas.paintSnapshot() else null
        val cover = if (open.isDirty) binding.canvas.coverSnapshot() else null
        applicationScope.launch { open.close(paint, cover) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Binds the canvas to a document on disk.
     *
     * Until the library screen exists there is nothing to choose from, so this
     * opens whatever was last open and creates a sketchbook if there isn't one.
     * Painting before it resolves is fine: the ops that arrive first are queued in
     * the canvas's own history and this only starts recording from the moment it
     * attaches — Phase 12 is what makes the two halves meet on load.
     */
    private fun attachDocument() {
        lifecycleScope.launch {
            val pointer = LastOpen.load(this@MainActivity)
            val wantsScratch = pointer?.kind == LastOpen.Kind.SCRATCHPAD
            // Before the open, not after: the rail should already be the
            // scratchpad's while the document is still being read off disk.
            isScratchpad = wantsScratch
            constrainTools()
            val opened = runCatching {
                if (wantsScratch) {
                    Scratchpad.open()
                } else {
                    DocumentSession.openExisting(this@MainActivity, pointer?.documentId)
                }
            }.getOrNull()

            // Nothing to edit — the book this pointer named has been deleted, or
            // was never there. The library is the answer to that; minting a
            // replacement book is not.
            if (opened == null) {
                LastOpen.clear(this@MainActivity)
                openLibrary()
                finish()
                return@launch
            }

            session = opened
            LastOpen.save(
                this@MainActivity,
                if (wantsScratch) {
                    LastOpen.Pointer(LastOpen.Kind.SCRATCHPAD, null, opened.pageId)
                } else {
                    LastOpen.Pointer(LastOpen.Kind.SKETCHBOOK, opened.documentId, opened.pageId)
                },
            )

            // Load before wiring the hooks, so restoring a page does not read back
            // as a fresh burst of edits to write straight out again.
            runCatching { opened.load() }.getOrNull()?.let(::applyPage)

            binding.canvas.onOpCommitted = { opened.record(it) }
            binding.canvas.onUndone = { layer -> opened.recordUndo(layer) }
            binding.canvas.onRedone = { layer -> opened.recordRedo(layer) }
            binding.canvas.onBrushLoadChanged = { recordPalette() }
            binding.canvas.onPageTurn = ::turnPage
            refreshPageLabel()
            refreshClipboard()
        }
    }

    /** "3/8" — which page of how many, shown on the rail's Pages button. */
    private var pageLabel = "–"

    /**
     * The page strip.
     *
     * A dialog rather than a permanent strip: the canvas is drawn at true physical
     * size and centred, and permanently giving up an edge of the screen to
     * navigation would shrink the sheet on the smallest devices this targets.
     */
    private fun showPages() {
        val open = session ?: return
        lifecycleScope.launch {
            val pages = open.pages()
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Pages")
                .setView(
                    android.widget.HorizontalScrollView(this@MainActivity).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(row)
                    },
                )
                .setNeutralButton("Add page") { _, _ -> addPage() }
                .setNegativeButton("Close", null)
                .create()

            pages.forEach { page -> row.addView(pageCard(page, pages.size, dialog)) }
            dialog.show()
        }
    }

    private fun pageCard(
        page: DocumentSession.PageInfo,
        total: Int,
        dialog: androidx.appcompat.app.AlertDialog,
    ): View {
        val thumb = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(84))
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEFEDE7.toInt())
            page.thumbnail?.let(::setImageBitmap)
        }
        val label = TextView(this).apply {
            text = "${page.index + 1}"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (page.isCurrent) 0xFF2E7D32.toInt() else 0xFF6B7075.toInt())
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            // The page you are on is outlined, because a strip of thumbnails with
            // nothing marked is a strip you have to count along.
            if (page.isCurrent) setBackgroundColor(0x1A2E7D32)
            addView(thumb)
            addView(label)
            setOnClickListener {
                dialog.dismiss()
                switchToPage(page.id)
            }
            setOnLongClickListener {
                dialog.dismiss()
                pageActions(page, total)
                true
            }
        }
    }

    private fun pageActions(page: DocumentSession.PageInfo, total: Int) {
        val open = session ?: return
        val actions = buildList {
            add("Duplicate")
            if (page.index > 0) add("Move left")
            if (page.index < total - 1) add("Move right")
            if (total > 1) add("Delete")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Page ${page.index + 1}")
            .setItems(actions.toTypedArray()) { _, which ->
                lifecycleScope.launch {
                    when (actions[which]) {
                        "Duplicate" -> open.duplicatePage(page.id, binding.canvas.paintSnapshot())?.let(::applyPage)
                        "Move left" -> open.movePage(page.id, page.index - 1)
                        "Move right" -> open.movePage(page.id, page.index + 1)
                        "Delete" -> open.deletePage(page.id)?.let(::applyPage)
                    }
                    refreshPageLabel()
                }
            }
            .show()
    }

    private fun addPage() {
        val open = session ?: return
        lifecycleScope.launch {
            // A new page starts on the paper you are already using — the same
            // reasoning as a real sketchbook, where the next sheet is the same
            // stock as this one.
            val surface = SurfaceOp(
                currentSurface(), plainColor, canvasParams, watercolorParams, woodParams,
                stoneParams, concreteParams, metalParams, chalkboardParams,
            )
            open.addPage(surface, java.util.Random().nextLong(), binding.canvas.paintSnapshot())
                ?.let(::applyPage)
            refreshPageLabel()
        }
    }

    private fun switchToPage(id: String) {
        val open = session ?: return
        lifecycleScope.launch {
            open.switchTo(id, binding.canvas.paintSnapshot())?.let(::applyPage)
            refreshPageLabel()
        }
    }

    /**
     * Prev/next, driven by a finger swept across the sheet.
     *
     * Stops at the covers rather than wrapping: a sketchbook has a first page and
     * a last one, and arriving at page 1 by swiping past page 40 would be a
     * teleport, not a page turn.
     */
    private fun turnPage(direction: PageTurn) {
        val open = session ?: return
        lifecycleScope.launch {
            val pages = open.pages()
            val here = pages.indexOfFirst { it.isCurrent }
            if (here < 0) return@launch
            val there = if (direction == PageTurn.FORWARD) here + 1 else here - 1
            val target = pages.getOrNull(there) ?: return@launch
            open.switchTo(target.id, binding.canvas.paintSnapshot())?.let(::applyPage)
            refreshPageLabel()
        }
    }

    /**
     * Copies what the selection wholly encloses.
     *
     * The count is reported because a copy that took nothing looks exactly like
     * one that worked: whole ops are copied, so a selection whose strokes all
     * cross its edge encloses no *marks* however much paint it covers.
     */
    private fun copySelection() {
        val open = session ?: return
        val (mask, scale) = binding.canvas.selectionSnapshot() ?: return
        lifecycleScope.launch {
            val count = runCatching { open.copySelection(mask, scale) }.getOrDefault(0)
            mask.recycle()
            clipboardCount = count
            updateRail()
            Snackbar.make(
                binding.root,
                if (count > 0) {
                    resources.getQuantityString(R.plurals.editor_copied, count, count)
                } else {
                    getString(R.string.editor_copied_nothing)
                },
                Snackbar.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Pastes the clipboard onto this page, where the marks were.
     *
     * Told rather than shown, because a paste back onto the page it came from
     * lands exactly on top of the original and is otherwise invisible.
     */
    private fun paste() {
        val open = session ?: return
        lifecycleScope.launch {
            val ops = runCatching { open.clipboardOps() }.getOrDefault(emptyList())
            if (ops.isEmpty()) {
                clipboardCount = 0
                updateRail()
                return@launch
            }
            binding.canvas.pasteOps(ops)
            Snackbar.make(
                binding.root,
                resources.getQuantityString(R.plurals.editor_pasted, ops.size, ops.size),
                Snackbar.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Sends this page somewhere else — a copy, never a move.
     *
     * The page stays where it is. "Send" reads as though it leaves, and it would
     * be a strange thing to do to somebody's only copy of a drawing; what this
     * does is put a duplicate on the shelf they asked for.
     */
    private fun sendPage() {
        val open = session ?: return
        val destinations = buildList {
            if (!isScratchpad) add(getString(R.string.send_to_scratchpad))
            add(getString(R.string.send_to_sketchbook))
            add(getString(R.string.send_to_new_sketchbook))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.send_title)
            .setItems(destinations.toTypedArray()) { _, which ->
                when (destinations[which]) {
                    getString(R.string.send_to_scratchpad) -> sendToScratchpad(open)
                    getString(R.string.send_to_sketchbook) -> pickSketchbook(open)
                    else -> promptNewSketchbook(open)
                }
            }
            .show()
    }

    private fun sendToScratchpad(open: DocumentSession) {
        lifecycleScope.launch {
            // Flushed first: a page is sent as it stands on disk, and the last
            // stroke may still be sitting in the debounce window.
            runCatching { open.flush() }
            val ok = runCatching { PageTransfer.toScratchpad(open.repo, open.pageId) }.getOrDefault(false)
            toast(if (ok) getString(R.string.send_done, Scratchpad.NAME) else getString(R.string.send_failed))
        }
    }

    /**
     * The books this page could go to — every one except the one it is already
     * in, which is what Duplicate Page is for and what an open file cannot
     * safely be.
     */
    private fun pickSketchbook(open: DocumentSession) {
        lifecycleScope.launch {
            val index = IndexGate.awaitReady()
            val books = index.allSketchbooks().filter { it.id != open.documentId }
            if (books.isEmpty()) {
                toast(getString(R.string.send_no_books))
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.send_to_sketchbook)
                .setItems(books.map { it.name }.toTypedArray()) { _, which ->
                    val book = books[which]
                    lifecycleScope.launch {
                        runCatching { open.flush() }
                        val ok = runCatching {
                            PageTransfer.toSketchbook(this@MainActivity, open.repo, open.pageId, book.id)
                        }.getOrDefault(false)
                        toast(if (ok) getString(R.string.send_done, book.name) else getString(R.string.send_failed))
                    }
                }
                .show()
        }
    }

    private fun promptNewSketchbook(open: DocumentSession) {
        val input = EditText(this).apply {
            hint = getString(R.string.library_name_hint)
            setText(getString(R.string.library_default_name))
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.send_to_new_sketchbook)
            .setView(FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
            .setPositiveButton(R.string.library_create) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.library_default_name) }
                lifecycleScope.launch {
                    runCatching { open.flush() }
                    val book = runCatching {
                        // The new book takes this page's size, so the drawing
                        // arrives at the size it was drawn.
                        PageTransfer.toNewSketchbook(
                            this@MainActivity, open.repo, open.pageId, name, canvasSize,
                        )
                    }.getOrNull()
                    toast(
                        if (book != null) getString(R.string.send_done, book.name)
                        else getString(R.string.send_failed),
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) =
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

    private fun refreshClipboard() {
        val open = session ?: return
        lifecycleScope.launch {
            clipboardCount = runCatching { open.clipboardCount() }.getOrDefault(0)
            updateRail()
        }
    }

    private fun refreshPageLabel() {
        val open = session ?: return
        lifecycleScope.launch {
            val pages = open.pages()
            val index = pages.indexOfFirst { it.isCurrent }
            pageLabel = if (index >= 0) "${index + 1}/${pages.size}" else "${pages.size}"
            updateRail()
        }
    }

    /** Puts a saved page back: its paper, its palette, and its whole history. */
    private fun applyPage(page: DocumentSession.PageSnapshot) {
        // Size first: the buffers have to be the right shape before any pixels or
        // ops land on them.
        canvasSize = page.canvasSize
        binding.canvas.restoreCanvasSize(page.canvasSize)

        val s = page.surface
        surfaceIndex = AVAILABLE_SURFACES.indexOf(s.kind).coerceAtLeast(0)
        plainColor = s.plainColor
        canvasParams = s.canvas
        watercolorParams = s.watercolor
        woodParams = s.wood
        stoneParams = s.stone
        concreteParams = s.concrete
        metalParams = s.metal
        chalkboardParams = s.chalkboard

        // The seed is the sheet's own: it is what makes this page's paper this
        // paper, and regenerating it would quietly change the artwork's ground.
        page.surfaceSeed?.let { binding.canvas.restoreSurfaceSeed(it) }
        binding.canvas.setInitialSurface(
            s.kind, s.plainColor, s.canvas, s.watercolor, s.wood, s.stone,
            s.concrete, s.metal, s.chalkboard,
        )

        tray.restorePots(page.pots)
        tray.restoreMixture(page.mixture)
        binding.tray.tray = tray
        if (!page.load.recipe.isEmpty) {
            binding.canvas.loadBrush(page.load)
            // A page remembers the colour it was last painted with. While the rail
            // offers no way to change colour, that memory must not become one:
            // restoring it would put a colour on a locked palette through the back
            // door, and it would arrive looking like a bug.
            if (Focus.SHOW_COLOR) color = page.load.color
        }
        if (!Focus.SHOW_COLOR) onColorChanged(Focus.COLOR)

        // The stack before the paint: restore() folds ops into layers, so the
        // layers have to be there to be folded into.
        if (page.layers.isNotEmpty()) binding.canvas.restoreLayers(page.layers, page.activeLayer)
        binding.canvas.restore(page.committed, page.undone, page.cachedPaint)
        refreshLayers()
        updateRail()
    }

    /**
     * Snapshots the tray into the document.
     *
     * Called on every change including the brush picking up colour mid-stroke;
     * the session keeps only the latest and writes it with the next batch, so a
     * dirty brush costs one write rather than hundreds.
     */
    private fun recordPalette() {
        session?.recordPalette(tray.pots, tray.mixture, binding.canvas.brushLoad)
    }

    /**
     * Flushes early, before the seal.
     *
     * `onStop` does the real work, but a process can be killed between pause and
     * stop — and this is also the last callback a *force*-stop respects on some
     * devices. Flushing here narrows the window in which recent strokes exist only
     * in memory to the debounce interval.
     */
    override fun onPause() {
        super.onPause()
        val open = session ?: return
        applicationScope.launch { open.flush() }
    }

    // --- Layers ---------------------------------------------------------------

    private fun toggleLayerPanel() {
        val showing = binding.layerPanel.visibility == View.VISIBLE
        binding.layerPanel.visibility = if (showing) View.GONE else View.VISIBLE
        if (!showing) refreshLayers()
        updateRail()
    }

    /**
     * Redraws the list from the canvas.
     *
     * Rebuilt wholesale rather than diffed: a page holds a handful of layers, and
     * a list that is regenerated cannot drift out of step with what it describes.
     */
    private fun refreshLayers() {
        val list = binding.layerList
        list.removeAllViews()
        // Topmost first, because that is the order they are stacked on the page
        // and reading them upside-down from how they are drawn is a puzzle.
        for (i in binding.canvas.layerCount - 1 downTo 0) {
            list.addView(layerRow(i))
        }
    }

    private fun layerRow(index: Int): View {
        val canvas = binding.canvas
        val selected = index == canvas.activeLayerIndex
        val visible = canvas.layerVisibleAt(index)

        val eye = iconButton(
            if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off,
            getString(if (visible) R.string.layers_hide else R.string.layers_reveal),
        ) { setLayerVisible(index, !visible) }

        val name = TextView(this).apply {
            text = canvas.layerNameAt(index)
            textSize = 14f
            // A layer that cannot be drawn on should not look like one that can.
            alpha = if (visible) 1f else 0.45f
            setTextColor(if (selected) 0xFF1B5E20.toInt() else 0xFF37474F.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val percent = TextView(this).apply {
            text = percentOf(canvas.layerOpacityAt(index))
            textSize = 12f
            gravity = Gravity.END
            setTextColor(0xFF6B7075.toInt())
            width = dp(38)
            isClickable = true
            background = rippleBg()
            // Typed, because a slider is for judging by eye and a number you
            // already know is faster said than found.
            setOnClickListener { typeOpacity(index) }
        }

        val handle = iconButton(R.drawable.ic_drag, getString(R.string.layers_reorder)) {}
        attachDragHandle(handle, index)

        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = 100f
            value = (canvas.layerOpacityAt(index) * 100f).coerceIn(0f, 100f)
            addOnChangeListener { _, v, fromUser ->
                if (!fromUser) return@addOnChangeListener
                // Straight to the canvas on every tick: the point of a slider is
                // watching the page answer while you move it.
                canvas.setLayerOpacity(index, v / 100f)
                percent.text = percentOf(v / 100f)
            }
            addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = Unit

                // One step on release — a drag is one decision, not a hundred.
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    canvas.commitLayerOpacity(index)
                    persistLayerState(index)
                }
            })
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(eye)
            addView(name)
            addView(percent)
            addView(handle)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = if (selected) selectedBg() else rippleBg()
            addView(top)
            addView(slider)
            setOnClickListener {
                if (canvas.selectLayer(index)) refreshLayers() else toast(getString(R.string.layers_hidden))
            }
            setOnLongClickListener { deleteLayer(index); true }
        }
    }

    private fun percentOf(opacity: Float): String = "${(opacity * 100f).roundToInt()}%"

    private fun setLayerVisible(index: Int, visible: Boolean) {
        binding.canvas.setLayerVisible(index, visible)
        persistLayerState(index)
        refreshLayers()
    }

    private fun persistLayerState(index: Int) {
        val open = session ?: return
        val id = binding.canvas.layerIdAt(index)
        val visible = binding.canvas.layerVisibleAt(index)
        val opacity = binding.canvas.layerOpacityAt(index)
        lifecycleScope.launch { open.recordLayerState(id, visible, opacity) }
    }

    private fun typeOpacity(index: Int) {
        val field = inchField(binding.canvas.layerOpacityAt(index) * 100f)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.layers_opacity)
            .setView(vbox(fieldRow(getString(R.string.layers_opacity), field)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_size_use) { _, _ ->
                val pct = field.inches(binding.canvas.layerOpacityAt(index) * 100f).coerceIn(0f, 100f)
                binding.canvas.setLayerOpacity(index, pct / 100f)
                binding.canvas.commitLayerOpacity(index)
                persistLayerState(index)
                refreshLayers()
            }
            .show()
    }

    /**
     * Drag to reorder.
     *
     * The handle owns the gesture rather than the row, so a drag can never be
     * mistaken for the tap that selects. Rows are a uniform height, so where the
     * finger is tells you which position it is over without measuring anything.
     */
    private fun attachDragHandle(handle: View, index: Int) {
        val list = binding.layerList
        var startY = 0f
        var from = index
        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    from = index
                    v.parent?.parent?.let { (it as? View)?.alpha = 0.6f }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.parent?.let { (it as? View)?.alpha = 1f }
                    val rowHeight = (list.getChildAt(0)?.height ?: 1).coerceAtLeast(1)
                    val moved = ((event.rawY - startY) / rowHeight).roundToInt()
                    if (moved != 0) {
                        // The list reads top-down but the stack is bottom-first,
                        // so a row dragged down moves *down* the stack.
                        val count = binding.canvas.layerCount
                        val target = (from - moved).coerceIn(0, count - 1)
                        if (binding.canvas.moveLayer(from, target)) {
                            session?.let { open ->
                                lifecycleScope.launch {
                                    open.recordLayerOrder(binding.canvas.layerIdsInOrder())
                                }
                            }
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun addLayer() {
        val open = session ?: return
        if (binding.canvas.layerCount >= Layer.MAX_PER_PAGE) {
            toast(getString(R.string.layers_full))
            return
        }
        lifecycleScope.launch {
            val name = getString(R.string.layers_default_name, binding.canvas.layerCount + 1)
            // Written first: a layer the canvas knows about but the file does not
            // is paint with nowhere to be filed.
            val id = open.addLayer(name) ?: run { toast(getString(R.string.layers_full)); return@launch }
            binding.canvas.addLayer(id, name)
            refreshLayers()
        }
    }

    private fun deleteLayer(index: Int) {
        val open = session ?: return
        if (binding.canvas.layerCount <= 1) {
            toast(getString(R.string.layers_last))
            return
        }
        val id = binding.canvas.layerIdAt(index)
        val name = binding.canvas.layerNameAt(index)
        // Undo does not reach this yet, and everything on the layer goes with it.
        // A step that cannot be taken back should at least be asked twice.
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.layers_delete_title, name))
            .setMessage(R.string.layers_delete_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.layers_delete_confirm) { _, _ ->
                lifecycleScope.launch {
                    binding.canvas.removeLayer(index).forEach { it.recycle() }
                    open.deleteLayer(id)
                    refreshLayers()
                }
            }
            .show()
    }

    // --- Which way up ---------------------------------------------------------

    /**
     * The display rotation at which the sheet lies square on the glass.
     *
     * The panel's natural orientation is portrait, and its landscape is
     * `ROTATION_90` (`mLandscapeRotation`, confirmed on the Movink 14 Pro). That
     * is the drawing's home: every other rotation is measured from here and undone
     * on [ActivityMainBinding.deviceFrame], so the sheet never moves.
     */
    private val homeRotation = android.view.Surface.ROTATION_90

    /** Quarter turns the tablet is from [homeRotation]; the glyphs turn back by this. */
    private var chromeQuarter = -1

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        // A 180° flip changes no dimension and so raises no configuration change;
        // the display rotation is the only thing that reports it.
        override fun onDisplayChanged(displayId: Int) = applyOrientation()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    /**
     * Pins the desk to the glass and turns the glyphs back to face the artist.
     *
     * The frame is always the panel's landscape geometry — long side first —
     * whatever shape the window currently is. Rotated by the opposite of however
     * far the tablet has been turned, it lands back exactly over the panel.
     */
    private fun applyOrientation() {
        val rot = display?.rotation ?: return
        val quarter = ((rot - homeRotation) + 4) % 4

        val bounds = windowManager.currentWindowMetrics.bounds
        val long = maxOf(bounds.width(), bounds.height())
        val short = minOf(bounds.width(), bounds.height())
        val frame = binding.deviceFrame
        val lp = frame.layoutParams
        if (lp.width != long || lp.height != short) {
            lp.width = long
            lp.height = short
            frame.layoutParams = lp
        }
        frame.rotation = -quarter * 90f

        if (quarter != chromeQuarter) {
            val first = chromeQuarter < 0
            chromeQuarter = quarter
            faceTheArtist(quarter, animate = !first)
        }
    }

    /**
     * Turns the buttons to face the artist, leaving the rail where it lies.
     *
     * Every rail button is a 44×44 square, so a quarter turn about its own centre
     * stays inside its own bounds — nothing re-measures, nothing clips. The
     * dividers are skipped: they are plain rules, and turning one would stand it
     * on end.
     */
    private fun faceTheArtist(quarter: Int, animate: Boolean) {
        val angle = quarter * 90f
        val turn = { v: View ->
            if (animate) v.animate().rotation(angle).setDuration(180).start() else v.rotation = angle
        }
        for (i in 0 until binding.rail.childCount) {
            val v = binding.rail.getChildAt(i)
            if (v is ImageButton || v is TextView) turn(v)
        }
        turn(binding.btnShowRail)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // --- Rail construction --------------------------------------------------

    /**
     * Builds every control, adds only the ones in scope.
     *
     * The out-of-scope buttons are still *constructed* — `updateRail` reads them
     * on every stroke and a half-built rail would be a crash waiting for a tool
     * change — but they never reach the layout. What [Focus] leaves out is not
     * disabled or greyed: it is simply not there.
     */
    private fun buildRail() {
        val rail = binding.rail
        for (t in Focus.TOOLS) {
            val b = iconButton(toolIcon(t), t.label) { onToolChanged(t) }
            toolButtons[t] = b
            rail.addView(b)
        }
        rail.addView(divider())

        colorBtn = iconButton(0, "Color") { pickColor("Stroke color", color) { onColorChanged(it) } }
        if (Focus.SHOW_COLOR) rail.addView(colorBtn)

        sizeBtn = textButton("Size") { pickSize() }
        if (Focus.SHOW_SIZE) rail.addView(sizeBtn)

        waterBtn = iconButton(R.drawable.ic_water_mode, "Clean water") {
            waterMode = !waterMode
            binding.canvas.waterMode = waterMode
            updateRail()
        }
        if (Focus.SHOW_WATER_MODE) rail.addView(waterBtn)
        toleranceBtn = textButton("Wand tolerance") { pickWand() }
        if (Focus.SHOW_WAND_TOLERANCE) rail.addView(toleranceBtn)

        surfaceBtn = iconButton(surfaceIcon(currentSurface()), "Surface") { pickSurface() }
        if (Focus.SHOW_SURFACE) rail.addView(surfaceBtn)

        fillBtn = iconButton(R.drawable.ic_fill, "Fill selection") { binding.canvas.fillSelection(color) }
        eraseBtn = iconButton(R.drawable.ic_erase_sel, "Erase inside selection") { binding.canvas.deleteSelection() }
        deselectBtn = iconButton(R.drawable.ic_deselect, "Deselect") { binding.canvas.clearSelection() }
        copyBtn = iconButton(R.drawable.ic_copy, "Copy selection") { copySelection() }
        pasteBtn = iconButton(R.drawable.ic_paste, "Paste") { paste() }
        if (Focus.SHOW_SELECTION_ACTIONS) {
            rail.addView(fillBtn)
            rail.addView(eraseBtn)
            rail.addView(copyBtn)
            rail.addView(deselectBtn)
        }
        if (Focus.SHOW_PASTE) rail.addView(pasteBtn)

        lineDoneBtn = iconButton(R.drawable.ic_done, "Finish shape") { binding.canvas.commitPendingShape() }
        if (Focus.SHOW_SHAPE_COMMIT) rail.addView(lineDoneBtn)

        undoBtn = iconButton(R.drawable.ic_undo, "Undo") { binding.canvas.undo() }
        redoBtn = iconButton(R.drawable.ic_redo, "Redo") { binding.canvas.redo() }
        if (Focus.SHOW_UNDO_REDO) {
            rail.addView(divider())
            rail.addView(undoBtn)
            rail.addView(redoBtn)
        }

        layersBtn = iconButton(R.drawable.ic_layers, getString(R.string.layers_show)) { toggleLayerPanel() }
        if (Focus.SHOW_LAYERS) rail.addView(layersBtn)

        rail.addView(divider())
        pagesBtn = textButton("Pages") { showPages() }
        if (Focus.SHOW_PAGES) rail.addView(pagesBtn)
        scratchBtn = iconButton(R.drawable.ic_scratchpad, "Scratchpad") { toggleScratchpad() }
        if (Focus.SHOW_SCRATCHPAD) rail.addView(scratchBtn)
        if (Focus.SHOW_SEND_PAGE) rail.addView(iconButton(R.drawable.ic_send, "Send page") { sendPage() })
        if (Focus.SHOW_LIBRARY) rail.addView(iconButton(R.drawable.ic_library, "Library") { openLibrary() })
        if (Focus.SHOW_SAVE_PNG) rail.addView(iconButton(R.drawable.ic_save, "Save PNG") { save() })
        canvasSizeBtn = iconButton(R.drawable.ic_canvas_size, "Canvas size") { pickCanvasSize() }
        if (Focus.SHOW_CANVAS_SIZE) rail.addView(canvasSizeBtn)
        if (Focus.SHOW_CALIBRATE) {
            rail.addView(iconButton(R.drawable.ic_calibrate, "Calibrate screen") { openCalibration() })
        }
        if (Focus.SHOW_CLEAR) rail.addView(iconButton(R.drawable.ic_clear, "Clear") { confirmClear() })
        if (Focus.SHOW_HIDE_RAIL) {
            rail.addView(iconButton(R.drawable.ic_hide, "Hide toolbar") { setRailVisible(false) })
        }
    }

    /**
     * Keeps the rail honest about where the user is.
     *
     * A tool that is not on offer here cannot simply be hidden: it might be the
     * one already selected, and a rail with nothing lit while the pen still draws
     * lines is worse than no restriction at all. So the selection moves too.
     */
    private fun constrainTools() {
        if (tool !in Focus.toolsFor(isScratchpad)) onToolChanged(Focus.DEFAULT_TOOL)
        updateRail()
    }

    /**
     * Into the scratchpad, or back out to the book you were in.
     *
     * Both directions go through the same door, because "somewhere to try
     * something out" is only useful if getting back is as cheap as getting there.
     * With no book to return to — a fresh install, or a library that has been
     * emptied — the way back is the library itself.
     */
    private fun toggleScratchpad() {
        if (isScratchpad) {
            val book = LastOpen.lastBook(this)
            if (book == null) {
                openLibrary()
                return
            }
            LastOpen.save(this, book)
        } else {
            LastOpen.save(this, LastOpen.Pointer(LastOpen.Kind.SCRATCHPAD, null, null))
        }
        reopenDocument()
    }

    /**
     * Closes what is open and attaches whatever [LastOpen] now points at.
     *
     * The seal has to finish before the next document opens: the two can be the
     * same database — the scratchpad and the index are — and a flush racing an
     * open is how a page loses its last stroke.
     */
    private fun reopenDocument() {
        val open = session ?: return
        session = null
        detachCanvasHooks()
        val paint = if (open.isDirty) binding.canvas.paintSnapshot() else null
        val cover = if (open.isDirty) binding.canvas.coverSnapshot() else null
        // The seal runs on the scope that outlives this screen and is *joined*
        // rather than fired off: leaving mid-switch must not skip it, and the next
        // document must not open on top of it.
        val sealed = applicationScope.launch { runCatching { open.close(paint, cover) } }
        lifecycleScope.launch {
            sealed.join()
            attachDocument()
        }
    }

    private fun detachCanvasHooks() {
        binding.canvas.onOpCommitted = null
        binding.canvas.onUndone = null
        binding.canvas.onRedone = null
        binding.canvas.onBrushLoadChanged = null
    }

    private fun updateRail() {
        val offered = Focus.toolsFor(isScratchpad)
        for ((t, b) in toolButtons) {
            // The scratchpad offers a subset, and so does the current scope; the
            // buttons for the rest are not there rather than disabled, because a
            // rail of greyed-out tools reads as something broken.
            b.visibility = if (t in offered) View.VISIBLE else View.GONE
            b.background = if (t == tool) selectedBg() else rippleBg()
        }
        scratchBtn.background = if (isScratchpad) selectedBg() else rippleBg()
        // A scratch page is always the screen it is drawn on: there is no book for
        // a print size to belong to, and nothing to print it at.
        canvasSizeBtn.visibility = if (isScratchpad) View.GONE else View.VISIBLE

        // The colour is moot while the brush carries clean water.
        colorBtn.visibility =
            if (tool == Tool.ERASER || (tool == Tool.WATERCOLOR && waterMode)) View.GONE else View.VISIBLE
        colorBtn.setImageDrawable(swatchDrawable(color))
        // iconButton tints every icon slate so the tool glyphs match; the swatch
        // *is* the colour, so it must not be tinted or it always reads dark.
        colorBtn.imageTintList = null

        waterBtn.visibility = if (tool == Tool.WATERCOLOR) View.VISIBLE else View.GONE
        waterBtn.background = if (waterMode) selectedBg() else rippleBg()

        // Neither selector has a size; only the wand has a tolerance to set.
        sizeBtn.visibility = if (tool.isSelector) View.GONE else View.VISIBLE
        sizeBtn.text = formatMm(sizes[tool] ?: tool.defaultSizeMm)
        toleranceBtn.visibility = if (tool == Tool.WAND) View.VISIBLE else View.GONE
        toleranceBtn.text = "${(wandTolerance * 100).roundToInt()}%"

        surfaceBtn.setImageResource(surfaceIcon(currentSurface()))

        val selVis = if (hasSelection) View.VISIBLE else View.GONE
        fillBtn.visibility = selVis
        eraseBtn.visibility = selVis
        deselectBtn.visibility = selVis
        copyBtn.visibility = selVis
        // Paste does not need a selection — it needs something on the clipboard,
        // which may have been put there in another book, or last week.
        pasteBtn.visibility = if (clipboardCount > 0) View.VISIBLE else View.GONE
        fillBtn.imageTintList = android.content.res.ColorStateList.valueOf(color)

        lineDoneBtn.visibility =
            if (hasPendingLine || hasPendingArc || hasPendingPolyline || hasPendingPolyarc)
                View.VISIBLE else View.GONE

        setEnabled(undoBtn, binding.canvas.canUndo)
        setEnabled(redoBtn, binding.canvas.canRedo)
        pagesBtn.text = pageLabel
    }

    private fun onToolChanged(t: Tool) {
        tool = t
        binding.canvas.tool = t
        applySizeToCanvas()
        updateRail()
    }

    /** Pushes the current tool's stored mm size to the canvas as pixels at this PPI. */
    private fun applySizeToCanvas() {
        val mm = sizes[tool] ?: tool.defaultSizeMm
        val ppi = Calibration.effectivePpi(this)
        binding.canvas.baseSize = Calibration.mmToPx(mm, ppi)
        // The brush spends paint per real mm² covered, so it needs the same
        // physical scale the sizes use.
        binding.canvas.pxPerMm = Calibration.mmToPx(1f, ppi)
    }

    /** Compact mm label for the rail button: "0.5", "4", "12.5". */
    private fun formatMm(mm: Float): String =
        if (mm >= 10f || mm == mm.roundToInt().toFloat()) {
            mm.roundToInt().toString()
        } else {
            String.format("%.1f", mm)
        }

    private fun onColorChanged(c: Int) {
        color = c
        binding.canvas.strokeColor = c
        updateRail()
    }

    private fun setRailVisible(visible: Boolean) {
        binding.railCard.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnShowRail.visibility = if (visible) View.GONE else View.VISIBLE
    }

    // --- Mixing tray --------------------------------------------------------

    /**
     * The palette is a docked panel rather than a dialog (every other picker is
     * one) because you mix a colour *while* painting — a modal would put the
     * canvas away every time you reach for the palette.
     */
    private fun setupTray() {
        // A palette with one colour on it is furniture. The panel is dismissed
        // outright rather than parked off-screen, so its tab is not left sitting
        // on the edge of the sheet inviting a pull that opens nothing useful.
        if (!Focus.SHOW_TRAY) {
            binding.trayPanel.visibility = View.GONE
            return
        }
        binding.tray.tray = tray
        binding.tray.onLoadBrush = { load ->
            // Straight to the canvas, keeping the mixture: going via
            // onColorChanged would recharge the brush with one flat pigment and
            // discard the recipe just mixed.
            binding.canvas.loadBrush(load)
            color = load.color
            updateRail()
            recordPalette()
        }
        binding.tray.onAddPot = {
            pickColor("Add a pigment", color) { c ->
                tray.addPot(Pot(namePot(c), c, custom = true))
                binding.tray.tray = tray
                recordPalette()
            }
        }
        binding.tray.onMixtureChanged = { recordPalette() }

        // Park the palette off-screen until it's pulled out, leaving the tab.
        binding.trayPanel.doOnLayout {
            trayHiddenX = binding.trayCard.width.toFloat() +
                (binding.trayCard.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd?.toFloat().orZero()
            binding.trayPanel.translationX = trayHiddenX
            updateTrayTab()
        }

        attachTrayTabGesture()
    }

    /**
     * The tab both taps and drags: a tap toggles, a drag follows the finger and
     * snaps to whichever side it was heading for.
     */
    private fun attachTrayTabGesture() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var startX = 0f
        var dragging = false

        binding.trayTab.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    startX = binding.trayPanel.translationX
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (!dragging && Math.abs(dx) > slop) dragging = true
                    if (dragging) {
                        binding.trayPanel.translationX = (startX + dx).coerceIn(0f, trayHiddenX)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        // Snap to whichever side it's closer to.
                        setTrayOut(binding.trayPanel.translationX < trayHiddenX / 2f)
                    } else {
                        setTrayOut(!trayOut)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setTrayOut(out: Boolean) {
        trayOut = out
        binding.trayPanel.animate()
            .translationX(if (out) 0f else trayHiddenX)
            .setDuration(180)
            .start()
        updateTrayTab()
    }

    private fun updateTrayTab() {
        // Chevron points the way the palette will travel.
        binding.trayTabIcon.setImageResource(if (trayOut) R.drawable.ic_show else R.drawable.ic_hide)
    }

    /** Labels a wheel colour by its nearest named pigment, so pots stay nameable. */
    private fun namePot(@androidx.annotation.ColorInt c: Int): String {
        val nearest = Tray.STANDARD_POTS.minByOrNull { pot ->
            val dr = ((pot.color shr 16) and 0xFF) - ((c shr 16) and 0xFF)
            val dg = ((pot.color shr 8) and 0xFF) - ((c shr 8) and 0xFF)
            val db = (pot.color and 0xFF) - (c and 0xFF)
            dr * dr + dg * dg + db * db
        }
        return nearest?.let { "Mixed (near ${it.name})" } ?: "Mixed"
    }

    private fun Float?.orZero(): Float = this ?: 0f

    // --- Pickers ------------------------------------------------------------

    private fun pickSize() {
        var working = (sizes[tool] ?: tool.defaultSizeMm).coerceIn(SIZE_MIN_MM, SIZE_MAX_MM)
        val ppi = Calibration.effectivePpi(this)
        val label = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
        }
        val pxHint = hint("")
        fun refresh() {
            label.text = String.format("%.1f mm", working)
            pxHint.text = "≈ ${Calibration.mmToPx(working, ppi).roundToInt()} px on screen" +
                if (Calibration.isCalibrated(this)) "" else "  ·  screen not calibrated"
        }
        refresh()
        val slider = Slider(this).apply {
            valueFrom = SIZE_MIN_MM
            valueTo = SIZE_MAX_MM
            value = working
            addOnChangeListener { _, v, _ ->
                working = v
                refresh()
            }
        }
        val content = vbox(label, slider, pxHint)
        MaterialAlertDialogBuilder(this)
            .setTitle("${tool.label} size (mm)")
            .setView(content)
            .setPositiveButton("Done") { _, _ ->
                sizes[tool] = working
                applySizeToCanvas()
                updateRail()
            }
            .show()
    }

    private fun pickWand() {
        var tol = wandTolerance
        var edge = wandEdgeSensitivity
        var gap = wandGap.toFloat()
        val tolLabel = TextView(this)
        val edgeLabel = TextView(this)
        val gapLabel = TextView(this)
        fun refresh() {
            tolLabel.text = "Tolerance  ${(tol * 100).roundToInt()}%"
            edgeLabel.text = "Edge sensitivity  ${(edge * 100).roundToInt()}%"
            gapLabel.text = "Close gaps  ${gap.roundToInt()} px"
        }
        refresh()
        val tolSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 1f; value = tol
            addOnChangeListener { _, v, _ -> tol = v; refresh() }
        }
        val edgeSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 1f; value = edge
            addOnChangeListener { _, v, _ -> edge = v; refresh() }
        }
        val gapSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 8f; stepSize = 1f; value = gap
            addOnChangeListener { _, v, _ -> gap = v; refresh() }
        }
        val content = vbox(
            tolLabel, tolSlider, hint("Higher = matches a wider range of colors."),
            edgeLabel, edgeSlider, hint("Higher = fainter lines (soft pencil) stop the fill."),
            gapLabel, gapSlider, hint("Bridges holes in a grainy/broken boundary."),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Magic wand")
            .setView(content)
            .setPositiveButton("Done") { _, _ ->
                wandTolerance = tol
                wandEdgeSensitivity = edge
                wandGap = gap.roundToInt()
                applyWandSettings()
                updateRail()
            }
            .show()
    }

    private fun pickSurface() {
        val labels = AVAILABLE_SURFACES.map { it.label }.toTypedArray()
        val current = surfaceIndex
        MaterialAlertDialogBuilder(this)
            .setTitle("Surface")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                onSurfaceChosen(which)
            }
            .show()
    }

    private fun onSurfaceChosen(index: Int) {
        when (val kind = AVAILABLE_SURFACES[index]) {
            SurfaceKind.PLAIN ->
                // Pick the background first, then commit the surface + colour as one op.
                pickColor("Background color", plainColor) { c ->
                    plainColor = c
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.PLAIN, c)
                    updateRail()
                }
            SurfaceKind.CANVAS ->
                // Dial in the weave first, then commit surface + params as one op.
                customizeCanvas(canvasParams) { params ->
                    canvasParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.CANVAS, plainColor, params)
                    updateRail()
                }
            SurfaceKind.WATERCOLOR ->
                // Dial in the paper first, then commit surface + params as one op.
                customizeWatercolor(watercolorParams) { params ->
                    watercolorParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.WATERCOLOR, plainColor, watercolor = params)
                    updateRail()
                }
            SurfaceKind.WOOD ->
                // Dial in the board first, then commit surface + params as one op.
                customizeWood(woodParams) { params ->
                    woodParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.WOOD, plainColor, wood = params)
                    updateRail()
                }
            SurfaceKind.STONE ->
                // Dial in the slab first, then commit surface + params as one op.
                customizeStone(stoneParams) { params ->
                    stoneParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.STONE, plainColor, stone = params)
                    updateRail()
                }
            SurfaceKind.CONCRETE ->
                // Dial in the slab first, then commit surface + params as one op.
                customizeConcrete(concreteParams) { params ->
                    concreteParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.CONCRETE, plainColor, concrete = params)
                    updateRail()
                }
            SurfaceKind.METAL ->
                // Dial in the sheet first, then commit surface + params as one op.
                customizeMetal(metalParams) { params ->
                    metalParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.METAL, plainColor, metal = params)
                    updateRail()
                }
            SurfaceKind.CHALKBOARD ->
                // Dial in the board first, then commit surface + params as one op.
                customizeChalkboard(chalkboardParams) { params ->
                    chalkboardParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.CHALKBOARD, plainColor, chalkboard = params)
                    updateRail()
                }
            else -> {
                surfaceIndex = index
                binding.canvas.commitSurfaceChange(kind, plainColor)
                updateRail()
            }
        }
    }

    /** Mirrors the canvas's current surface/background into the rail state. */
    private fun syncSurfaceFromCanvas() {
        surfaceIndex = AVAILABLE_SURFACES.indexOf(binding.canvas.surface).coerceAtLeast(0)
        plainColor = binding.canvas.plainColor
        canvasParams = binding.canvas.canvasParams
        watercolorParams = binding.canvas.watercolorParams
        woodParams = binding.canvas.woodParams
        stoneParams = binding.canvas.stoneParams
        concreteParams = binding.canvas.concreteParams
        metalParams = binding.canvas.metalParams
        chalkboardParams = binding.canvas.chalkboardParams
    }

    /** HSV colour wheel + brightness slider, with swatch quick-picks. */
    private fun pickColor(title: String, initial: Int, onUse: (Int) -> Unit) {
        var working = initial or (0xFF shl 24)
        val preview = View(this)
        val wheel = ColorWheelView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(4)
            }
        }
        val valueSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 1f
        }
        fun show(c: Int) {
            working = c
            preview.background = previewSwatch(c)
        }
        wheel.setColor(working)
        valueSlider.value = FloatArray(3).also { Color.colorToHSV(working, it) }[2]

        wheel.onColorChanged = { c -> show(c) }
        valueSlider.addOnChangeListener { _, v, _ ->
            wheel.setValue(v)
            show(wheel.color)
        }

        val grid = GridLayout(this).apply {
            columnCount = 9
            setPadding(0, dp(8), 0, dp(4))
        }
        for (c in SWATCHES) {
            grid.addView(swatchCell(c) {
                wheel.setColor(c)
                valueSlider.value = FloatArray(3).also { Color.colorToHSV(c, it) }[2]
                show(c)
            })
        }
        preview.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
        preview.background = previewSwatch(working)

        val content = vbox(wheel, labelled("V", valueSlider), preview, grid)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(working) }
            .show()
    }

    /**
     * Canvas customisation — "not all canvas is the same". A live weave preview
     * over tint / weave / grain controls; visual only (the tooth is unchanged).
     * Mirrors [pickColor]: dial it in, then the caller commits it as one op.
     */
    private fun customizeCanvas(initial: CanvasParams, onUse: (CanvasParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var weave = initial.weave
        var grain = initial.grain

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(SurfaceKind.CANVAS, w, h, Color.WHITE, CanvasParams(tint, weave, grain))
        }
        val tintRow = colorRow("Tint", "Canvas tint", tint) { c -> tint = c; preview.refresh() }
        val weaveSlider = Slider(this).apply {
            valueFrom = 0.05f; valueTo = 0.45f; value = weave.coerceIn(0.05f, 0.45f)
            addOnChangeListener { _, v, _ -> weave = v; preview.refresh() }
        }
        val grainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.10f; value = grain.coerceIn(0f, 0.10f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }

        val content = vbox(preview, tintRow, sliderRow("Weave", weaveSlider), sliderRow("Grain", grainSlider))
        MaterialAlertDialogBuilder(this)
            .setTitle("Canvas")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeCanvas(CanvasParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(CanvasParams(tint, weave, grain)) }
            .show()
    }

    /**
     * Watercolor customisation. The paper itself is fixed per artwork (its seed);
     * these controls shape its character. Live preview is a true-scale swatch — a
     * small window onto the sheet, not the whole buffer, so slider drags stay snappy.
     */
    private fun customizeWatercolor(initial: WatercolorParams, onUse: (WatercolorParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var texture = initial.texture
        var mottle = initial.mottle
        var grain = initial.grain
        val seed = binding.canvas.surfaceSeed // preview the actual sheet

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.WATERCOLOR, w, h,
                seed = seed,
                watercolorParams = WatercolorParams(tint, texture, mottle, grain),
            )
        }
        val tintRow = colorRow("Tint", "Paper tint", tint) { c -> tint = c; preview.refresh() }
        val textureSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.25f; value = texture.coerceIn(0f, 0.25f)
            addOnChangeListener { _, v, _ -> texture = v; preview.refresh() }
        }
        val mottleSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.15f; value = mottle.coerceIn(0f, 0.15f)
            addOnChangeListener { _, v, _ -> mottle = v; preview.refresh() }
        }
        val grainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.08f; value = grain.coerceIn(0f, 0.08f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Texture", textureSlider),
            sliderRow("Mottle", mottleSlider),
            sliderRow("Grain", grainSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Watercolor paper")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeWatercolor(WatercolorParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(WatercolorParams(tint, texture, mottle, grain)) }
            .show()
    }

    /**
     * Wood customisation. The board is fixed per artwork (its seed); these controls
     * shape its look. Live preview is a true-scale window onto the actual board.
     */
    private fun customizeWood(initial: WoodParams, onUse: (WoodParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var grain = initial.grain
        var scale = initial.scale
        var weathering = initial.weathering
        val seed = binding.canvas.surfaceSeed // preview the actual board

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.WOOD, w, h,
                seed = seed,
                woodParams = WoodParams(tint, grain, scale, weathering),
            )
        }
        val tintRow = colorRow("Tint", "Wood tint", tint) { c -> tint = c; preview.refresh() }
        val grainSlider = Slider(this).apply {
            valueFrom = 0.10f; valueTo = 0.60f; value = grain.coerceIn(0.10f, 0.60f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }
        // Smaller scale = finer / more zoomed out; larger = coarser grain.
        val scaleSlider = Slider(this).apply {
            valueFrom = 0.30f; valueTo = 1.00f; value = scale.coerceIn(0.30f, 1.00f)
            addOnChangeListener { _, v, _ -> scale = v; preview.refresh() }
        }
        val weatherSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.20f; value = weathering.coerceIn(0f, 0.20f)
            addOnChangeListener { _, v, _ -> weathering = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Grain", grainSlider),
            sliderRow("Scale", scaleSlider),
            sliderRow("Weather", weatherSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Wood")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeWood(WoodParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(WoodParams(tint, grain, scale, weathering)) }
            .show()
    }

    /**
     * Stone (slate) customisation. The slab is fixed per artwork (its seed); these
     * controls shape its look. Live preview is a true-scale window onto the actual slab.
     */
    private fun customizeStone(initial: StoneParams, onUse: (StoneParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var mottle = initial.mottle
        var cracks = initial.cracks
        var crackContrast = initial.crackContrast
        var grain = initial.grain
        val seed = binding.canvas.surfaceSeed // preview the actual slab

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.STONE, w, h,
                seed = seed,
                stoneParams = StoneParams(tint, mottle, cracks, crackContrast, grain),
            )
        }
        val tintRow = colorRow("Tint", "Slate tint", tint) { c -> tint = c; preview.refresh() }
        val mottleSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.50f; value = mottle.coerceIn(0f, 0.50f)
            addOnChangeListener { _, v, _ -> mottle = v; preview.refresh() }
        }
        val cracksSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.0f; value = cracks.coerceIn(0f, 2.0f)
            addOnChangeListener { _, v, _ -> cracks = v; preview.refresh() }
        }
        val contrastSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.0f; value = crackContrast.coerceIn(0f, 2.0f)
            addOnChangeListener { _, v, _ -> crackContrast = v; preview.refresh() }
        }
        val grainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.20f; value = grain.coerceIn(0f, 0.20f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Mottle", mottleSlider),
            sliderRow("Cracks", cracksSlider),
            sliderRow("Crack contrast", contrastSlider),
            sliderRow("Grain", grainSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Stone")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeStone(StoneParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ ->
                onUse(StoneParams(tint, mottle, cracks, crackContrast, grain))
            }
            .show()
    }

    /**
     * Concrete customisation. The slab is fixed per artwork (its seed); these
     * controls shape its look. Live preview is a true-scale window onto the actual slab.
     */
    private fun customizeConcrete(initial: ConcreteParams, onUse: (ConcreteParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var staining = initial.staining
        var pores = initial.pores
        var grit = initial.grit
        val seed = binding.canvas.surfaceSeed // preview the actual slab

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.CONCRETE, w, h,
                seed = seed,
                concreteParams = ConcreteParams(tint, staining, pores, grit),
            )
        }
        val tintRow = colorRow("Tint", "Cement tint", tint) { c -> tint = c; preview.refresh() }
        val stainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.45f; value = staining.coerceIn(0f, 0.45f)
            addOnChangeListener { _, v, _ -> staining = v; preview.refresh() }
        }
        val poresSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.5f; value = pores.coerceIn(0f, 2.5f)
            addOnChangeListener { _, v, _ -> pores = v; preview.refresh() }
        }
        val gritSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.30f; value = grit.coerceIn(0f, 0.30f)
            addOnChangeListener { _, v, _ -> grit = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Staining", stainSlider),
            sliderRow("Pores", poresSlider),
            sliderRow("Grit", gritSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Concrete")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeConcrete(ConcreteParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(ConcreteParams(tint, staining, pores, grit)) }
            .show()
    }

    /**
     * Metal customisation. The sheet is fixed per artwork (its seed); these
     * controls shape its look. Live preview is a true-scale window onto the actual sheet.
     */
    private fun customizeMetal(initial: MetalParams, onUse: (MetalParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var grain = initial.grain
        var sheen = initial.sheen
        var scratches = initial.scratches
        val seed = binding.canvas.surfaceSeed // preview the actual sheet

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.METAL, w, h,
                seed = seed,
                metalParams = MetalParams(tint, grain, sheen, scratches),
            )
        }
        val tintRow = colorRow("Tint", "Metal tint", tint) { c -> tint = c; preview.refresh() }
        val grainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.35f; value = grain.coerceIn(0f, 0.35f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }
        val sheenSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.20f; value = sheen.coerceIn(0f, 0.20f)
            addOnChangeListener { _, v, _ -> sheen = v; preview.refresh() }
        }
        val scratchSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.5f; value = scratches.coerceIn(0f, 2.5f)
            addOnChangeListener { _, v, _ -> scratches = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Grain", grainSlider),
            sliderRow("Sheen", sheenSlider),
            sliderRow("Scratches", scratchSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Metal")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeMetal(MetalParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(MetalParams(tint, grain, sheen, scratches)) }
            .show()
    }

    /**
     * Chalkboard customisation. The board is fixed per artwork (its seed); these
     * controls shape its look. Live preview is a true-scale window onto the actual board.
     */
    private fun customizeChalkboard(initial: ChalkboardParams, onUse: (ChalkboardParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var ghosting = initial.ghosting
        var dust = initial.dust
        val seed = binding.canvas.surfaceSeed // preview the actual board

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.CHALKBOARD, w, h,
                seed = seed,
                chalkboardParams = ChalkboardParams(tint, ghosting, dust),
            )
        }
        val tintRow = colorRow("Tint", "Board tint", tint) { c -> tint = c; preview.refresh() }
        val ghostSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.5f; value = ghosting.coerceIn(0f, 2.5f)
            addOnChangeListener { _, v, _ -> ghosting = v; preview.refresh() }
        }
        val dustSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.5f; value = dust.coerceIn(0f, 2.5f)
            addOnChangeListener { _, v, _ -> dust = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Ghosting", ghostSlider),
            sliderRow("Dust", dustSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Chalkboard")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeChalkboard(ChalkboardParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(ChalkboardParams(tint, ghosting, dust)) }
            .show()
    }

    /**
     * A live surface swatch. Renders [render] at the view's OWN pixel size — true
     * 1:1, no scaling — on resize and on every [refresh]. Fixed 300dp square,
     * centred, with a hairline border so a near-white surface still reads.
     */
    private inner class SurfacePreview(
        private val render: (Int, Int) -> Bitmap,
    ) : View(this@MainActivity) {
        private var bmp: Bitmap? = null
        private val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = 0x33000000
        }

        init {
            layoutParams = LinearLayout.LayoutParams(dp(300), dp(300)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        }

        fun refresh() {
            if (width <= 0 || height <= 0) return
            val old = bmp
            bmp = render(width, height)
            old?.recycle()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            refresh()
        }

        override fun onDraw(canvas: Canvas) {
            bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            canvas.drawRect(0.5f, 0.5f, width - 0.5f, height - 0.5f, border)
        }
    }

    /** A tappable label + colour swatch that opens [pickColor]; reports picks to [onPicked]. */
    private fun colorRow(label: String, title: String, initial: Int, onPicked: (Int) -> Unit): View {
        var current = initial
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(28))
            background = previewSwatch(current)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
            isClickable = true
            addView(TextView(context).apply { text = label; width = dp(64) })
            addView(swatch)
            setOnClickListener {
                pickColor(title, current) { c ->
                    current = c
                    swatch.background = previewSwatch(c)
                    onPicked(c)
                }
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear canvas?")
            .setMessage("This erases everything. There is no undo.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> binding.canvas.clear() }
            .show()
    }

    /**
     * Back to the library.
     *
     * The document seals itself on the way out — `onStop` does that — so there is
     * nothing to save here beyond letting the screen go.
     */
    private fun openLibrary() {
        startActivity(Intent(this@MainActivity, LibraryActivity::class.java))
    }

    private fun save() {
        binding.canvas.savePng { result ->
            val msg = result.fold(
                onSuccess = { "Saved to $it" },
                onFailure = { "Save failed: ${it.message}" },
            )
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun openCalibration() {
        calibrationLauncher.launch(Intent(this, CalibrationActivity::class.java))
    }

    // --- Canvas size --------------------------------------------------------

    /** Full screen + every print size that fits the calibrated screen + Custom. */
    private fun pickCanvasSize() {
        val ppi = Calibration.effectivePpi(this)
        val vw = binding.canvas.width
        val vh = binding.canvas.height
        val options = buildList<CanvasSize> {
            add(CanvasSize.FullScreen)
            addAll(
                CanvasSize.offered(
                    Calibration.pxToIn(vw.toFloat(), ppi),
                    Calibration.pxToIn(vh.toFloat(), ppi),
                ),
            )
        }
        val labels = (options.map { it.label } + "Custom…").toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Canvas size")
            .setSingleChoiceItems(labels, options.indexOf(canvasSize)) { dialog, which ->
                dialog.dismiss()
                if (which == options.size) pickCustomCanvasSize(ppi, vw, vh)
                else chooseCanvasSize(options[which])
            }
            .show()
    }

    /** Applies [size], confirming first if it would clear existing work. */
    private fun chooseCanvasSize(size: CanvasSize) {
        if (size == canvasSize) return
        val apply = {
            canvasSize = size
            binding.canvas.applyCanvasSize(size)
            updateRail()
        }
        if (binding.canvas.canUndo) {
            MaterialAlertDialogBuilder(this)
                .setTitle("New canvas size?")
                .setMessage("Changing the size starts a fresh sheet — your current drawing will be cleared.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("New sheet") { _, _ -> apply() }
                .show()
        } else {
            apply()
        }
    }

    private fun pickCustomCanvasSize(ppi: Float, vw: Int, vh: Int) {
        val maxW = Calibration.pxToIn(vw.toFloat(), ppi)
        val maxH = Calibration.pxToIn(vh.toFloat(), ppi)
        val w = ((canvasSize as? CanvasSize.Print)?.wIn ?: 6f).coerceIn(1f, maxW)
        val h = ((canvasSize as? CanvasSize.Print)?.hIn ?: 4f).coerceIn(1f, maxH)
        val label = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER }
        val wField = inchField(w)
        val hField = inchField(h)
        fun typed() = CanvasSize.custom(wField.inches(w), hField.inches(h), maxW, maxH)
        // The label is the size that will actually be made — clamped and truncated.
        // A preview that rounds up, or that shows a number the screen will refuse,
        // is a preview of a sheet you cannot have.
        fun refresh() { label.text = typed().label }
        refresh()
        wField.onEdit(::refresh)
        hField.onEdit(::refresh)
        val content = vbox(
            label,
            fieldRow("Width", wField),
            fieldRow("Height", hField),
            hint("Capped to what fits the screen (${CanvasSize.custom(maxW, maxH).label})."),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Custom canvas")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> chooseCanvasSize(typed()) }
            .show()
    }

    private fun applyWandSettings() {
        binding.canvas.wandTolerance = wandTolerance
        binding.canvas.wandEdgeSensitivity = wandEdgeSensitivity
        binding.canvas.wandGap = wandGap
    }

    private fun currentSurface() = AVAILABLE_SURFACES[surfaceIndex]

    // --- View helpers -------------------------------------------------------

    private fun iconButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            if (iconRes != 0) setImageResource(iconRes)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = rippleBg()
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF37474F.toInt())
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    private fun textButton(desc: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            gravity = Gravity.CENTER
            setTextColor(0xFF37474F.toInt())
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rippleBg()
            isClickable = true
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(1)).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        }
        setBackgroundColor(0x22000000)
    }

    private fun swatchCell(color: Int, onClick: () -> Unit): View = View(this).apply {
        layoutParams = GridLayout.LayoutParams().apply {
            width = dp(34); height = dp(34)
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
        background = swatchDrawable(color)
        setOnClickListener { onClick() }
    }

    /** A rounded-rectangle colour chip for the picker's preview bar. */
    private fun previewSwatch(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6).toFloat()
        setColor(color)
        setStroke(dp(1), 0x33000000)
    }

    private fun swatchDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), 0x33000000)
        setSize(dp(24), dp(24))
    }

    private fun selectedBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(0x333DA35A)
    }

    /** A fresh borderless-ripple background (each view needs its own instance). */
    private fun rippleBg(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return ResourcesCompat.getDrawable(resources, outValue.resourceId, theme)
    }

    private fun labelled(name: String, slider: Slider): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = name; width = dp(20) })
        addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** A slider preceded by a fixed-width text label (wider than [labelled]). */
    private fun sliderRow(name: String, slider: Slider): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = name; width = dp(64) })
        addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0x8A000000.toInt())
        setPadding(0, 0, 0, dp(8))
    }

    private fun vbox(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(0))
        for (v in views) addView(v)
    }

    private fun setEnabled(b: ImageButton, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.3f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun toolIcon(t: Tool): Int = when (t) {
        Tool.PENCIL -> R.drawable.ic_tool_pencil
        Tool.PEN -> R.drawable.ic_tool_pen
        Tool.LINE -> R.drawable.ic_tool_line
        Tool.ARC -> R.drawable.ic_tool_arc
        Tool.POLYLINE -> R.drawable.ic_tool_polyline
        Tool.POLYARC -> R.drawable.ic_tool_polyarc
        Tool.BRUSH -> R.drawable.ic_tool_brush
        Tool.WATERCOLOR -> R.drawable.ic_tool_watercolor
        Tool.MARKER -> R.drawable.ic_tool_marker
        Tool.SPRAY -> R.drawable.ic_tool_spray
        Tool.ERASER -> R.drawable.ic_tool_eraser
        Tool.WAND -> R.drawable.ic_tool_wand
        Tool.LASSO -> R.drawable.ic_tool_lasso
    }

    private fun surfaceIcon(s: SurfaceKind): Int = when (s) {
        SurfaceKind.PAPER -> R.drawable.ic_surface_paper
        SurfaceKind.CANVAS -> R.drawable.ic_surface_canvas
        SurfaceKind.METAL -> R.drawable.ic_surface_metal
        SurfaceKind.STONE -> R.drawable.ic_surface_stone
        SurfaceKind.WOOD -> R.drawable.ic_surface_wood
        SurfaceKind.WATERCOLOR -> R.drawable.ic_surface_watercolor
        SurfaceKind.CHALKBOARD -> R.drawable.ic_surface_chalkboard
        SurfaceKind.CONCRETE -> R.drawable.ic_surface_concrete
        SurfaceKind.PLAIN -> R.drawable.ic_surface_plain
    }

    private companion object {
        // Brush/tool size range in millimetres (physical mark width).
        const val SIZE_MIN_MM = 0.1f
        const val SIZE_MAX_MM = 40f

        // Material palette, matching the Flutter reference's swatch list.
        val SWATCHES = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt(),
            0xFF795548.toInt(), 0xFFF44336.toInt(), 0xFFFF5722.toInt(),
            0xFFFF9800.toInt(), 0xFFFFC107.toInt(), 0xFFFFEB3B.toInt(),
            0xFF8BC34A.toInt(), 0xFF4CAF50.toInt(), 0xFF009688.toInt(),
            0xFF00BCD4.toInt(), 0xFF03A9F4.toInt(), 0xFF2196F3.toInt(),
            0xFF3F51B5.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(),
        )
    }
}
