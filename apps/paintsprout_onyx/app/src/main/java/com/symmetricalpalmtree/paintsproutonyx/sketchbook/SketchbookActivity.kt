package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.paintsproutonyx.PaintsproutApplication
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.core.ActionSheetDialog
import com.symmetricalpalmtree.paintsproutonyx.core.Dialogs
import com.symmetricalpalmtree.paintsproutonyx.core.IndexGuard
import com.symmetricalpalmtree.paintsproutonyx.core.TopGuard
import com.symmetricalpalmtree.paintsproutonyx.data.index.IndexRepository
import com.symmetricalpalmtree.paintsproutonyx.data.prefs.ToolPrefs
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivitySketchbookBinding
import kotlinx.coroutines.launch

private const val TAG = "SketchbookActivity"

/**
 * The page. Everything before this screen was about finding a sketchbook; this is the one that gets
 * drawn in.
 *
 * **The host does host things and nothing else.** g-paper owns the ink — capture, live rendering,
 * the bake, the eraser sweep, palm rejection, the EPD pipeline — and this screen owns the file, the
 * chrome and the lifecycle. The temptation on a panel like this is to reach past the component the
 * first time something looks wrong; the standing rule is that an engine gap is fixed in g-paper and
 * never worked around here, and the pencil this screen draws with is itself the proof: it is a
 * g-paper stroke style, added there in its Phase 10, not a renderer this app owns.
 *
 * ## Three things that are load-bearing
 *
 * **The paper is full-bleed and the chrome floats on it.** A page in this app *is* the panel — it
 * was recorded at the panel's size when the sketchbook was made and is never rescaled — so the bars
 * cannot take height away from it. What stops the pen inking under a toolbar is
 * [PaperView.setExclusionRects], pushed whenever a bar moves, which the component applies to the
 * hardware pen layer *and* filters model-side so the data matches the pixels.
 *
 * **Turning the pen over erases, and nothing on the toolbar moves.** That is not built here — the
 * BOOX SDK intercepts the eraser end at hardware level and g-paper's Onyx engine erases with it
 * whichever tool is armed. It is worth knowing precisely because there is no code here to find when
 * someone goes looking for it.
 *
 * **No frame is presented while the pen is on the paper.** See [PenIdleGate]: frames during a live
 * contact are withheld by the ink pipeline and a later repaint of identical content is damage-free,
 * so chrome updated mid-stroke does not merely waste a refresh, it goes missing.
 *
 * ## The book, and the one door into it
 *
 * A sketchbook has many pages now, and **[showPage] is the only thing in this app that puts one on
 * the glass.** Every route — opening the file, a swipe, a delete, an undo — goes through it, so the
 * documented swap sequence is written once and cannot be got wrong in five places. What that
 * sequence protects against is worth stating: `clear()` followed by `loadStrokes` flashes the panel
 * blank in between, which on e-ink is a full white refresh the artist watches happen, so the swap
 * uses `clearForContentSwap()` instead and the old pixels simply hold until the new page replaces
 * them in one go.
 *
 * **The `.soil` is the source of truth and the screen is a view of it.** Undo never reaches into
 * the paper with `addStrokes`/`removeStrokes` — it changes the file and then reloads the page from
 * the file. The targeted calls are faster and g-paper offers them for exactly this, but they let
 * the picture and the rows drift apart: a view patched independently of the store can disagree with
 * what the sketchbook reopens as, and the artist finds out about it a day later with nothing to
 * connect it to. One extra page load is a cheap price for never having to wonder which of the two
 * is right.
 *
 * ## The lifecycle, which is the part that bites
 *
 * `resumeDrawing()` in `onResume` reclaims the pen pipeline without trusting focus events, which on
 * this device are not reliable. `release()` in `onDestroy` is the final teardown.
 *
 * **`releaseForHandoff()` is deliberately not called anywhere, and that is a fact about arc 1 rather
 * than an omission.** It belongs immediately before launching — or finishing back to — *another*
 * screen that also hosts paper, and there is no such screen: the shelf has no paper on it and this
 * one launches nothing. The moment there is one, this is where the call goes, and the failure it
 * prevents is worth knowing in advance: without it the departing screen's teardown lands ~200 ms
 * *after* the arriving screen reclaimed, and the arriving screen's ink stays invisible until the
 * tool is flipped.
 *
 * ## Why this file is over the line
 *
 * The house rule is no file past about eight hundred lines without a written reason, and this one
 * crossed it in G5. The reason is that nearly everything in it is the *order* of a handful of calls
 * — the swap sequence, the pen-idle waits, the write-then-show of a replay, the write-the-card-then-
 * finish of leaving — and that order is the whole correctness of the screen. Splitting it into a
 * page controller, an undo controller and a lifecycle helper would make each file shorter and put
 * the sequence that matters across three of them, where the next change reorders it without seeing
 * the comment that said not to. What has been lifted out is the pure logic that can be tested on a
 * desk (`PageMath`, `SwipeRule`, `UndoRedoStack`, `PageGestures`, `PenIdleGate`); what stays is the
 * choreography, kept in one place so it can be read top to bottom.
 */
class SketchbookActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySketchbookBinding
    private lateinit var paper: PaperView
    private lateinit var gate: PenIdleGate
    private lateinit var gestures: PageGestures
    private lateinit var prefs: ToolPrefs

    private val repo by lazy { IndexRepository() }
    private val stack = UndoRedoStack()

    private var sketchbookId = ""
    private var session: SketchbookSession? = null
    private var lead = Lead.DEFAULT
    private var tool = Tool.PEN

    /**
     * True while the screen is in the middle of something the artist must not start a second copy
     * of: opening the file, a page swap, a delete, a replay.
     *
     * It is one flag rather than a lock because every one of those things ends in a page being
     * shown, and two of them running at once is two page loads racing for one panel — the marks of
     * one page drawn against the rectangle of another. The gesture detector reads it and stands
     * down; the buttons read it and refuse the tap. Written only on the main thread, which is the
     * only thread that ever asks.
     */
    private var busy = false

    /**
     * True from the moment the artist asked to leave until the screen has gone.
     *
     * Set by [leave] and read by `onDestroy`, so the shelf's card is written exactly once on the
     * ordinary way out — by [leave], before `finish()`, where the shelf can see it — and not a
     * second time by the teardown that follows.
     */
    private var leaving = false

    /** What the page indicator says, recomputed whenever a page is shown and not once per mark. */
    private var pagePosition = 0
    private var pageTotal = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivitySketchbookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sketchbookId = intent.getStringExtra(EXTRA_SKETCHBOOK_ID).orEmpty()
        if (sketchbookId.isEmpty()) {
            finish()
            return
        }

        prefs = ToolPrefs(this)
        lead = prefs.lead

        // The guard goes on the top bar, never on the root: padding the root would inset the paper
        // as well, and the paper is the one thing on this screen that must reach every edge.
        TopGuard.applyInsetPadding(binding.topBar)

        paper = GPaper.create(this)
        binding.paperContainer.addView(paper.asView())
        gate = PenIdleGate(paper)

        paper.penColor = GRAPHITE
        paper.penStyle = StrokeStyle.PENCIL
        paper.penWidth = lead.widthPx
        paper.eraserRadius = ERASER_RADIUS_PX
        paper.setPaperListener(listener)

        gestures = PageGestures(
            host = binding.root,
            isPenActive = { paper.isPenActive },
            standDown = { busy },
            overChrome = ::overChrome,
            listener = fingers,
        )

        binding.btnBack.setOnClickListener { leave() }
        // The system's back gesture is the same departure as the arrow and must take the same road:
        // a plain finish() from the dispatcher would put the shelf up before the card's picture was
        // written. Always enabled — there is nowhere else for back to go from a page.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        binding.btnPencil.setOnClickListener { onPencilTapped() }
        binding.btnEraser.setOnClickListener { selectTool(Tool.ERASER) }
        // A tap on one of these is a finger on chrome, which the gesture observer refuses by
        // `overChrome` — so the button and the two-finger tap can never both fire for one touch.
        binding.btnUndo.setOnClickListener { undo() }
        binding.btnRedo.setOnClickListener { redo() }
        binding.btnTrash.setOnClickListener { askToDeletePage() }
        TooltipCompat.setTooltipText(binding.btnBack, getString(R.string.cd_sketchbook_back))
        TooltipCompat.setTooltipText(binding.btnPencil, getString(R.string.cd_sketchbook_pencil))
        TooltipCompat.setTooltipText(binding.btnEraser, getString(R.string.cd_sketchbook_eraser))
        TooltipCompat.setTooltipText(binding.btnUndo, getString(R.string.cd_sketchbook_undo))
        TooltipCompat.setTooltipText(binding.btnRedo, getString(R.string.cd_sketchbook_redo))
        TooltipCompat.setTooltipText(binding.btnTrash, getString(R.string.cd_sketchbook_delete_page))
        selectTool(Tool.PEN)

        // The bars' rectangles are not known until they have been laid out, and they move when the
        // status-bar inset arrives — which is a second pass, after the first. Listening for layout
        // rather than measuring once is what makes the exclusion match what is actually on screen.
        val onBars = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pushExclusionRects() }
        binding.topBar.addOnLayoutChangeListener(onBars)
        binding.bottomStrip.addOnLayoutChangeListener(onBars)

        openSketchbook()
    }

    // ── The file ─────────────────────────────────────────────────────────────

    private fun openSketchbook() {
        busy = true
        lifecycleScope.launch {
            val opened = try {
                SketchbookSession.open(this@SketchbookActivity, sketchbookId, PaintsproutApplication.scope)
            } catch (e: Exception) {
                // A sketchbook that will not open is never deleted and never quietly skipped. The
                // artist is told, in a dialog rather than a toast, because a toast that is missed on
                // e-ink leaves a blank page that looks like lost work.
                busy = false
                Dialogs.problem(
                    this@SketchbookActivity,
                    getString(R.string.sketchbook_open_failed_title),
                    getString(R.string.sketchbook_open_failed_body),
                ) { finish() }
                return@launch
            }
            if (opened == null) {
                busy = false
                Dialogs.problem(
                    this@SketchbookActivity,
                    getString(R.string.sketchbook_missing_title),
                    getString(R.string.sketchbook_missing_body),
                ) { finish() }
                return@launch
            }
            session = opened
            binding.sketchbookName.text = opened.title
            try {
                showPage(opened.currentPageId)
            } catch (e: Exception) {
                // The file opened and then its first page would not — a row that will not read, a
                // page that has gone. Before G6 this was the one read on the screen with nothing
                // around it, and an exception here took the process down from inside a coroutine,
                // which on the panel is a sketchbook that "crashes when opened" and invites exactly
                // the wrong remedy. The session is set, so the teardown still closes the file
                // properly; the artist gets the same dialog as any other file that will not open.
                Log.e(TAG, "the sketchbook opened but its page would not show", e)
                busy = false
                Dialogs.problem(
                    this@SketchbookActivity,
                    getString(R.string.sketchbook_open_failed_title),
                    getString(R.string.sketchbook_open_failed_body),
                ) { finish() }
                return@launch
            } finally {
                busy = false
                refreshChrome()
            }
            // Not an exception to the frame-silence rule, though it is a frame outside the gate:
            // it runs in the same main-thread continuation as the swap above, and there is no
            // suspension between `awaitIdle` returning inside showPage and this line. The pen
            // cannot have arrived in between. Recorded in the ledger in docs/sketchbook.md.
            binding.openingOverlay.visibility = View.GONE
            pushExclusionRects()
        }
    }

    /**
     * Put a page on the glass. **The only path that ever changes what the paper is showing.**
     *
     * The three calls into the component are the documented page-swap contract and there is
     * deliberately nothing between them: `clearForContentSwap` drops the old model without
     * repainting, so the pixels already on the panel hold; `setPageSize` hands over the rectangle
     * the marks were recorded in, which is what the component registers ink against, so setting it
     * *after* the marks would draw the first frame against the wrong rectangle; `loadStrokes` then
     * swaps the content in. Together that is **one** EPD refresh, and the panel never shows blank
     * in between.
     *
     * Everything the swap needs is read first, off the main thread, so the sequence itself is three
     * synchronous calls with no chance of something landing in the middle of them. And it waits on
     * the pen: `loadStrokes` under a live contact drops ink, because the frames it presents are
     * withheld while the pen is down. That wait is [PenIdleGate.awaitIdle] and it is an obligation,
     * not a nicety.
     */
    private suspend fun showPage(pageId: String) {
        val s = session ?: return
        val marks = s.loadMarks(pageId)
        val row = s.page(pageId)
        val width = SketchbookSession.pageDimension(row?.width)
        val height = SketchbookSession.pageDimension(row?.height)
        val pages = s.livePages().map { it.id }

        gate.awaitIdle()
        paper.clearForContentSwap()
        paper.setPageSize(width, height)
        paper.loadStrokes(marks)

        s.currentPageId = pageId
        // Every page shown, not only the last one. A screen killed in the background never gets to
        // write anything on the way out, so the pointer has to already be right at every moment.
        s.rememberOpenPage(pageId)
        pagePosition = PageMath.positionOf(pages, pageId)
        pageTotal = pages.size
        refreshChrome()
    }

    /**
     * The page number, brought up to date — through the gate, like all chrome.
     *
     * **Nothing is drawn while an operation is in flight.** Every state the bar could show mid-flip
     * is about to be replaced a fraction of a second later, and on this panel showing it and then
     * correcting it spends two full refreshes of the top bar saying nothing. So this stands aside
     * while [busy] and each operation calls it once at the end, when the answer has settled. What
     * makes that safe is that the buttons are protected by refusing the tap rather than by being
     * greyed out and back again.
     *
     * **Undo and redo are never greyed, and that is a fact about the panel, not an oversight.** The
     * moment there is something to undo is the moment a mark has just been made — and while the
     * pen is armed for writing this device does not update the display at all, so the button
     * cannot brighten when the stack fills and cannot dim when it empties. The first version of
     * this screen faded the arrows at 0.3 alpha, and Greg's verdict on the panel was that a state
     * which cannot be redrawn when it changes is a state that lies: an arrow still dim after the
     * first stroke reads as undo being broken, not as the panel being slow. BOOX's own apps draw
     * these as plain black glyphs with no state, for the same reason. So the arrows are always
     * bright, a tap with nothing behind it does nothing, and the page number is the one thing on
     * this bar that changes.
     */
    private fun refreshChrome() {
        // A replay cancelled by the screen closing still runs its `finally`, and a label set on a
        // window that is going away is at best a wasted frame.
        if (busy || isFinishing || isDestroyed) return
        val label = getString(R.string.sketchbook_page_of, pagePosition, pageTotal)
        gate.run(CHROME_KEY) {
            // Assigning identical text still lays the label out again. One comparison keeps a
            // no-op refresh a no-op.
            if (binding.pageIndicator.text != label) binding.pageIndicator.text = label
        }
    }

    private val listener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            val s = session ?: return
            // The page is captured here, at the commit, and travels with the write. A swap already
            // in flight will move `currentPageId` before this write reaches the front of the queue,
            // and a mark filed against the leaf the artist is about to be looking at is a mark on
            // the wrong page.
            val page = s.currentPageId
            s.recordMark(stroke, page)
            stack.record(Edit.Drew(page, stroke.id))
            refreshChrome()
        }

        override fun onStrokesErased(strokeIds: List<String>) {
            if (strokeIds.isEmpty()) return
            val s = session ?: return
            val page = s.currentPageId
            s.recordErase(strokeIds)
            // One sweep of the eraser is one entry, because it was one movement of the hand. An
            // entry per mark would make undoing a broad sweep a matter of tapping until it stops.
            stack.record(Edit.Erased(page, strokeIds))
            refreshChrome()
        }
    }

    // ── Turning pages ────────────────────────────────────────────────────────

    private val fingers = object : PageGestures.Listener {
        override fun onFlipNext() = flip(forward = true)
        override fun onFlipPrevious() = flip(forward = false)
        override fun onUndo() = undo()
        override fun onRedo() = redo()
    }

    /**
     * Forward past the last page **makes a new one**, and lands on it.
     *
     * A real sketchbook has a next leaf until it does not, and the moment it does not you are
     * holding the back cover — there is no gesture for "the book is over". So the swipe simply
     * finds a blank page there. It is recorded as an edit, which means a leaf swiped into by
     * accident costs one undo to take back rather than being a page that is now permanently in the
     * book. That is also the entire mechanism for adding pages: there is no button, and pages are
     * only ever made at the end, which is what keeps page order something nobody maintains.
     *
     * Backward off the front does nothing at all. The other direction has no equivalent — a page
     * before the first one is not a leaf a sketchbook can grow.
     */
    private fun flip(forward: Boolean) {
        if (busy) return
        val s = session ?: return
        busy = true
        lifecycleScope.launch {
            try {
                val pages = s.livePages().map { it.id }
                val at = pages.indexOf(s.currentPageId)
                if (at < 0) return@launch
                if (!forward) {
                    if (at > 0) showPage(pages[at - 1])
                    return@launch
                }
                if (at < pages.lastIndex) {
                    showPage(pages[at + 1])
                    return@launch
                }
                val leftBehind = s.currentPageId
                val fresh = s.appendPage()
                stack.record(Edit.AddedPage(fresh.id, shownAfterUndo = leftBehind))
                runCatching { repo.setPageCount(sketchbookId, pages.size + 1) }
                showPage(fresh.id)
            } catch (e: Exception) {
                Log.e(TAG, "the page would not turn", e)
            } finally {
                busy = false
                refreshChrome()
            }
        }
    }

    // ── Throwing a page away ─────────────────────────────────────────────────

    /**
     * The trash asks first, and names the page it is aimed at by number.
     *
     * [PaperView.releaseRender] before the dialog is a host obligation on an EPD panel, not a
     * flourish: the writing overlay holds the pixels under it against ordinary app updates, so a
     * dialog raised while it is armed simply does not appear. It re-arms on the next pen-down by
     * itself and is a no-op off e-ink, so it is called without conditions.
     */
    private fun askToDeletePage() {
        if (busy) return
        val s = session ?: return
        paper.releaseRender()
        Dialogs.confirm(
            this,
            getString(R.string.delete_page_title, pagePosition, pageTotal),
            getString(R.string.delete_page_body),
            R.string.delete_page_confirm,
        ) { deletePage(s) }
    }

    /**
     * Throw the current page away, marks and all, and land on the leaf behind it.
     *
     * A soft delete, so undo brings it back with everything that was drawn on it — the rows are
     * stamped, never removed, which is why restoring is putting the page back rather than building
     * something that resembles it.
     *
     * **Deleting the only page leaves one fresh blank page.** A sketchbook with nothing in it is
     * not a sketchbook; it is a screen with no paper on it and no way to make any, since the only
     * way to add a leaf is to swipe past the last one and there would be no last one. So the empty
     * book gets a new first page, and undo takes that stand-in away again when it puts the real one
     * back.
     */
    private fun deletePage(s: SketchbookSession) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val pages = s.livePages().map { it.id }
                val victim = s.currentPageId
                val markIds = s.deletePage(victim)
                var replacement: SoilObjectEntity? = null
                var target = PageMath.neighbourAfterRemoving(pages, victim)
                if (target == null) {
                    replacement = s.appendPage()
                    target = replacement.id
                }
                stack.record(Edit.DeletedPage(victim, markIds, replacement?.id, target))
                runCatching { repo.setPageCount(sketchbookId, s.livePageCount()) }
                showPage(target)
            } catch (e: Exception) {
                Log.e(TAG, "the page would not go", e)
            } finally {
                busy = false
                refreshChrome()
            }
        }
    }

    // ── Undo and redo ────────────────────────────────────────────────────────

    private fun undo() = replay(undoing = true)

    private fun redo() = replay(undoing = false)

    /**
     * Take one edit back — or put it back — by changing the file and then showing the page from the
     * file.
     *
     * **The store is mutated first and the picture follows.** g-paper offers `addStrokes` and
     * `removeStrokes` for exactly this and they would be faster, but they patch the view
     * independently of the rows: the two can then disagree, and the artist finds out when the
     * sketchbook reopens looking different from how they left it, a day later, with nothing to
     * connect it to. The `.soil` is the source of truth, so the page is always redrawn from what is
     * actually in it.
     *
     * Single-flight behind [busy]: an undo arriving into the middle of another undo is two page
     * loads racing for one panel.
     *
     * The generation check is the one subtle part. A replay waits — on the write, and again on the
     * page load — and the pen can finish a mark across that wait. That mark clears the redo side,
     * because there is no going forward from a drawing that has moved on. The entry this replay is
     * holding would go straight back onto the side that was just cleared, so it is dropped instead.
     * See [UndoRedoStack.generation].
     *
     * An edit whose write **failed** goes back where it came from. The writer's [SoilWriter.perform]
     * lets the exception through for this reason: an undo that did not reach the file must not
     * pretend it did, and leaving the entry popped would make the failure permanent and silent.
     */
    private fun replay(undoing: Boolean) {
        if (busy) return
        val s = session ?: return
        val edit = (if (undoing) stack.popUndo() else stack.popRedo()) ?: return
        val generation = stack.generation
        busy = true
        lifecycleScope.launch {
            try {
                val (target, replayed) = applyEdit(s, edit, undoing)
                if (edit is Edit.AddedPage || edit is Edit.DeletedPage) {
                    runCatching { repo.setPageCount(sketchbookId, s.livePageCount()) }
                }
                showPage(target)
                if (stack.generation == generation) {
                    if (undoing) stack.pushRedo(replayed) else stack.pushUndo(replayed)
                }
            } catch (e: Exception) {
                Log.e(TAG, "the edit could not be replayed and was put back", e)
                if (undoing) stack.pushUndo(edit) else stack.pushRedo(edit)
            } finally {
                busy = false
                refreshChrome()
            }
        }
    }

    /**
     * Reverse an edit, or re-apply it, and say which page the result has to be shown on — and which
     * entry goes onto the other side of the stack.
     *
     * The page is part of the answer rather than something the caller works out, because for two of
     * the four the page to land on is not the page the edit names: undoing an added leaf takes that
     * leaf away, so the screen goes back to the one before it; redoing a delete takes the page away
     * again, so the screen goes where the delete originally landed.
     *
     * The entry is part of the answer because taking a page away *learns something*: whatever marks
     * were still alive on it went down with it, and the redo has to bring exactly those back.
     * Ordinarily there are none — anything drawn on an added leaf sits above this entry on the stack
     * and was undone first — but a history that overflowed past those marks reaches here with them
     * still on the page, and a redo that put the leaf back blank would have quietly turned an undo
     * into a delete. So the ids the store hands back travel with the entry to the redo side, the way
     * a page delete records its marks at the moment it happens.
     */
    private suspend fun applyEdit(s: SketchbookSession, edit: Edit, undoing: Boolean): Pair<String, Edit> =
        when (edit) {
            is Edit.Drew -> {
                if (undoing) s.hideMarks(listOf(edit.markId)) else s.restoreMarks(listOf(edit.markId))
                edit.pageId to edit
            }
            is Edit.Erased -> {
                if (undoing) s.restoreMarks(edit.markIds) else s.hideMarks(edit.markIds)
                edit.pageId to edit
            }
            is Edit.AddedPage -> if (undoing) {
                val hidden = s.deletePage(edit.pageId)
                edit.shownAfterUndo to edit.copy(hiddenMarkIds = hidden)
            } else {
                s.restorePage(edit.pageId, edit.hiddenMarkIds)
                edit.pageId to edit
            }
            is Edit.DeletedPage -> if (undoing) {
                s.restorePage(edit.pageId, edit.markIds)
                val hidden = edit.replacementPageId?.let { s.deletePage(it) } ?: emptyList()
                edit.pageId to edit.copy(replacementMarkIds = hidden)
            } else {
                s.deletePage(edit.pageId)
                edit.replacementPageId?.let { s.restorePage(it, edit.replacementMarkIds) }
                edit.shownAfterDelete to edit
            }
        }

    // ── Fingers ──────────────────────────────────────────────────────────────

    /**
     * Every touch is shown to the gesture observer on its way to the views, and taken from nobody.
     *
     * `dispatchTouchEvent` rather than a touch listener on the paper, because the observer has to
     * see sequences that begin on the toolbar in order to *ignore* them — a listener attached below
     * the chrome would never be told about a finger that landed on a button, and could not tell a
     * button press from a swipe that started there.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestures.isInitialized) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Did this touch begin on the chrome rather than on the paper?
     *
     * Screen coordinates on both sides. The event's own `x`/`y` are window coordinates and the
     * bars' `left`/`top` are relative to their parent, and on a screen whose window is inset by a
     * status bar those two are not the same origin — a mismatch that shows up as a swipe that works
     * everywhere except near the toolbar, which is exactly where it must not.
     */
    private fun overChrome(ev: MotionEvent): Boolean {
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        return within(binding.topBar, x, y) || within(binding.bottomStrip, x, y)
    }

    private val chromeAt = IntArray(2)

    private fun within(view: View, x: Int, y: Int): Boolean {
        if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) return false
        view.getLocationOnScreen(chromeAt)
        return x >= chromeAt[0] && x < chromeAt[0] + view.width &&
            y >= chromeAt[1] && y < chromeAt[1] + view.height
    }

    // ── Tools ────────────────────────────────────────────────────────────────

    /**
     * The pencil button does two jobs, and which one depends on whether it is already the tool in
     * hand: tapping it from the eraser picks the pencil up, and tapping it again opens the tin.
     *
     * A separate control for the lead would be a third button on a toolbar with three things on it,
     * and it would be a button whose meaning changes depending on a different button — the shelf's
     * sort control taught the same lesson from the other side.
     */
    private fun onPencilTapped() {
        if (tool != Tool.PEN) {
            selectTool(Tool.PEN)
            return
        }
        // A tin with one pencil in it has nothing to choose. Opening a sheet to show the pencil
        // already in hand would be a menu with no decision on it, and on e-ink every needless
        // sheet is a flash. The sheet comes back by itself the moment the tin holds two.
        if (Lead.entries.size < 2) return
        val sheet = ActionSheetDialog(this).title(getString(R.string.lead_sheet_title))
        for (candidate in Lead.entries) {
            sheet.addAction(
                getString(labelOf(candidate)),
                if (candidate == lead) R.drawable.ic_check else null,
            ) { selectLead(candidate) }
        }
        sheet.show()
    }

    private fun selectLead(chosen: Lead) {
        lead = chosen
        prefs.lead = chosen
        paper.penWidth = chosen.widthPx
        selectTool(Tool.PEN)
    }

    private fun selectTool(chosen: Tool) {
        tool = chosen
        paper.tool = chosen
        markSelected(binding.btnPencil, chosen == Tool.PEN)
        markSelected(binding.btnEraser, chosen == Tool.ERASER)
    }

    /**
     * Which tool is in hand, shown by weight rather than by colour.
     *
     * There is no colour in this app's chrome and no elevation on this panel to raise a button with,
     * so the selected state is the button's own pressed ring — the same shape a tap already draws,
     * left standing. A tick or a filled square would be a second vocabulary for a thing the button
     * can say itself.
     */
    private fun markSelected(button: ImageButton, selected: Boolean) {
        button.isSelected = selected
    }

    // ── Chrome the pen must not draw on ──────────────────────────────────────

    /**
     * Hand the component the rectangles the chrome occupies, in the paper view's own coordinates.
     *
     * Both bars overlay the paper, so without this the pen would ink underneath them — visible in
     * the gaps between buttons, and worse, saved: the mark is real data on the page, it is simply
     * hidden behind a toolbar until the toolbar is not there any more.
     */
    private fun pushExclusionRects() {
        if (!::paper.isInitialized) return
        val rects = listOf(binding.topBar, binding.bottomStrip)
            .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            .map { Rect(it.left, it.top, it.right, it.bottom) }
        paper.setExclusionRects(rects)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        paper.resumeDrawing()
    }

    /**
     * Take the cover when the screen leaves the panel, whether or not it is coming back.
     *
     * A backgrounded screen on this device may simply never see `onDestroy` — BOOX kills background
     * processes as a matter of routine — so waiting for the close to make the cover means a
     * sketchbook the artist left by going to the launcher keeps whatever picture it had from the
     * last time it happened to be closed properly. The cover is a picture of the page that was on
     * the glass, and this is the moment it stops being on the glass.
     *
     * Skipped while finishing, because `onDestroy` is a heartbeat away and does the same thing
     * properly ordered against the close. Doing both would bake the same page twice for one
     * departure — a full-page render each, on the way out of a screen.
     *
     * And since G6, skipped by the session itself when nothing has changed: a Home press on a page
     * that was only looked at costs one queued read of a key, not a full-page render. See
     * [SketchbookSession.renderCover] for why that decision has to be made on the write queue.
     */
    override fun onStop() {
        super.onStop()
        val s = session
        if (isFinishing || s == null) return
        // Not lifecycleScope: this outlives the stop, and on the kill path it is racing a teardown
        // it may not win. `renderCover` is queued behind the marks still being written, so a
        // sketchbook that closes underneath it either finishes it first (the close drains the queue)
        // or refuses it outright, which arrives here as a failure and leaves the old cover alone.
        PaintsproutApplication.scope.launch { updateShelfCard(s) }
    }

    /**
     * Write everything the shelf's card shows — page count, cover, last-edit stamp — to the index.
     *
     * Page count, cover and stamp are all questions only the open file can answer, so this is called
     * while the session is still open and before it is closed. The cover goes through the write
     * queue, which puts it behind every mark still waiting to be written: the picture is of the page
     * as it will reopen. The count and the stamp are read *after* it comes back for the same reason
     * — by then every write that was queued has landed, so both are of the file as it will reopen
     * too, rather than a page short of it.
     *
     * A cover that fails is skipped rather than stored as nothing, so the shelf keeps the picture it
     * had — a stale cover instead of a blank one. A blank page stores nothing on purpose: the card's
     * white frame is the picture. And a card the session says is unchanged writes nothing at all,
     * which is what turns a Home press on a page that was only looked at from a full-page render
     * into a queued read of a key.
     *
     * The key is recorded only once all three writes have taken. A card the index refused any part
     * of is a card still owed, and the next departure makes it again rather than assuming it.
     *
     * The stamp is `touchIfNewer` with the *file's* last edit, not a plain touch with now. Opening a
     * sketchbook and closing it without drawing used to move it to the top of "Last worked on",
     * which made that sort a record of what had been looked at.
     */
    private suspend fun updateShelfCard(s: SketchbookSession) {
        val outcome = runCatching { s.renderCover() }.getOrNull()
        if (outcome is CardOutcome.Unchanged) {
            // Ids only in the log, never a title. This line is what a device walk reads to prove
            // that leaving a page that was only looked at rendered nothing.
            Log.d(TAG, "shelf card unchanged for ${s.sketchbookId}; nothing written")
            return
        }
        val pages = runCatching { s.livePageCount() }.getOrNull()
        val lastEdit = runCatching { s.lastEditAt() }.getOrNull()
        val pagesWritten = pages != null && runCatching { repo.setPageCount(s.sketchbookId, pages) }.isSuccess
        val stampWritten = lastEdit != null && runCatching { repo.touchIfNewer(s.sketchbookId, lastEdit) }.isSuccess
        // A null outcome is the queue refusing the read — the sketchbook closed underneath this —
        // and the count and stamp have still been written from whatever the file would answer.
        // There is no key to record for it, and nothing to store for the cover.
        if (outcome !is CardOutcome.Fresh) return
        val coverWritten = when (val cover = outcome.cover) {
            is CoverSnapshot.Cover.Image -> runCatching { repo.setCover(s.sketchbookId, cover.bytes) }.isSuccess
            CoverSnapshot.Cover.Blank -> runCatching { repo.setCover(s.sketchbookId, null) }.isSuccess
            CoverSnapshot.Cover.Failed -> false
        }
        if (pagesWritten && stampWritten && coverWritten) {
            s.cardWritten(outcome.key)
            Log.d(TAG, "shelf card written for ${s.sketchbookId} (${outcome.cover.javaClass.simpleName})")
        } else {
            Log.w(TAG, "shelf card for ${s.sketchbookId} only partly written; it will be made again")
        }
    }

    /**
     * The ordinary way out: write the card, then go.
     *
     * **The card is written before `finish()`, not by the teardown after it, because of the order
     * Android resumes screens in.** Back pauses this screen, *resumes the shelf*, and only then
     * stops and destroys this one — so a cover written from `onDestroy` lands after the shelf has
     * already listed, and the card the artist backs out onto is the one from last time. That was
     * the first thing the panel showed in G5: a tree drawn and closed, and a blank card behind it
     * until the shelf was next relisted. The wait is a full-page render, a few hundred
     * milliseconds behind the last marks in the queue, spent between the tap and the shelf
     * appearing; a second tap in that time does nothing rather than starting a second render.
     *
     * Both the arrow and the system back gesture come here, and the teardown that follows knows
     * from [leaving] that the card is already done and only closes the file.
     */
    private fun leave() {
        if (leaving) return
        leaving = true
        lifecycleScope.launch {
            session?.let { updateShelfCard(it) }
            finish()
        }
    }

    override fun onDestroy() {
        // A screen the index guard turned away never built any of this, and tearing down what was
        // never made is a crash on the one path that exists to avoid one.
        if (IndexGuard.bounced(this) || !::paper.isInitialized) {
            super.onDestroy()
            return
        }
        gestures.cancelAll()
        gate.cancelAll()
        // The history is a memory of this sitting, not a second copy of the file. What is on disk
        // is the drawing; how the artist got to it is something they stop needing here.
        stack.clear()
        val closing = session
        session = null
        if (closing != null) {
            // The close has to outlive this Activity: letting the write queue empty is the
            // difference between a page that reopens as it was left and one missing the last thing
            // drawn on it, and lifecycleScope is cancelled the moment onDestroy returns. The
            // application scope owns it instead.
            //
            // The shelf's card is normally already written by this point — [leave] does it before
            // finish(), while the shelf can still see it. This is the path for every other way a
            // screen ends (the task swiped away, a configuration change, the system finishing it),
            // where nothing has written the card yet and this is the last chance, taken before the
            // close because the file has to be open to be asked.
            val cardDone = leaving
            PaintsproutApplication.scope.launch {
                if (!cardDone) updateShelfCard(closing)
                closing.close()
            }
        }
        paper.release()
        super.onDestroy()
    }

    private fun labelOf(l: Lead): Int = when (l) {
        Lead.HAIRLINE -> R.string.lead_hairline
    }

    companion object {
        private const val EXTRA_SKETCHBOOK_ID = "sketchbookId"

        /** One key for the whole bar: it says one thing, and the newest version of it is the one. */
        private const val CHROME_KEY = "chrome"

        /**
         * The colour of the graphite. A #2 pencil pressed as hard as it will go is a dark grey with
         * a sheen on it, never black — black is ink, and the first hairline capture read as a fine
         * pen at the heavy end for exactly that reason. This is where the tone of the whole tin is
         * set: the grain's own darkness levels fall away from it towards the paper, so a barely
         * touched stroke lands paler than this and nothing lands darker.
         *
         * A starting point, not a measurement, and on a Kaleido panel it is what the panel makes of
         * it: sixteen greys behind a colour filter, so the number that looks like HB here is found by
         * looking, not by reading it off a chart. The live line is the firmware's plain line in this
         * same colour; whether the panel draws a grey hairline live or dithers it is the panel's call.
         */
        private const val GRAPHITE = 0xFF505050.toInt()

        /**
         * How near the sweep has to pass for a mark to come out, in px.
         *
         * Arc 1 erases whole marks rather than rubbing pixels away, so this is not "how much rubber
         * is on the paper" — a larger radius does not take *more* off a mark, it takes marks that
         * were not aimed at. Which is exactly why there is no size control for it: there is no
         * setting here an artist would want, and offering one would promise a rubbing eraser this is
         * not. About a millimetre and a half on this panel: enough to pick one line out of a cluster,
         * forgiving enough to sweep with.
         */
        private const val ERASER_RADIUS_PX = 18f

        fun intent(context: Context, sketchbookId: String): Intent =
            Intent(context, SketchbookActivity::class.java)
                .putExtra(EXTRA_SKETCHBOOK_ID, sketchbookId)
    }
}
