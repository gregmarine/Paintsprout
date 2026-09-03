package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
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
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivitySketchbookBinding
import kotlinx.coroutines.launch

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
 */
class SketchbookActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySketchbookBinding
    private lateinit var paper: PaperView
    private lateinit var gate: PenIdleGate
    private lateinit var prefs: ToolPrefs

    private val repo by lazy { IndexRepository() }

    private var sketchbookId = ""
    private var session: SketchbookSession? = null
    private var lead = Lead.DEFAULT
    private var tool = Tool.PEN

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

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPencil.setOnClickListener { onPencilTapped() }
        binding.btnEraser.setOnClickListener { selectTool(Tool.ERASER) }
        TooltipCompat.setTooltipText(binding.btnBack, getString(R.string.cd_sketchbook_back))
        TooltipCompat.setTooltipText(binding.btnPencil, getString(R.string.cd_sketchbook_pencil))
        TooltipCompat.setTooltipText(binding.btnEraser, getString(R.string.cd_sketchbook_eraser))
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
        lifecycleScope.launch {
            val opened = try {
                SketchbookSession.open(this@SketchbookActivity, sketchbookId, PaintsproutApplication.scope)
            } catch (e: Exception) {
                // A sketchbook that will not open is never deleted and never quietly skipped. The
                // artist is told, in a dialog rather than a toast, because a toast that is missed on
                // e-ink leaves a blank page that looks like lost work.
                Dialogs.problem(
                    this@SketchbookActivity,
                    getString(R.string.sketchbook_open_failed_title),
                    getString(R.string.sketchbook_open_failed_body),
                ) { finish() }
                return@launch
            }
            if (opened == null) {
                Dialogs.problem(
                    this@SketchbookActivity,
                    getString(R.string.sketchbook_missing_title),
                    getString(R.string.sketchbook_missing_body),
                ) { finish() }
                return@launch
            }
            session = opened
            // The page rect the marks were recorded in, handed over before the marks themselves:
            // it is what the component registers ink against, so setting it afterwards would draw
            // the first frame against the wrong rectangle.
            paper.setPageSize(opened.pageWidth, opened.pageHeight)
            paper.loadStrokes(opened.loadMarks())
            binding.sketchbookName.text = opened.title
            binding.pageIndicator.text = getString(R.string.sketchbook_page_of, 1, opened.pageCount)
            binding.openingOverlay.visibility = View.GONE
            pushExclusionRects()
        }
    }

    private val listener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            session?.recordMark(stroke)
        }

        override fun onStrokesErased(ids: List<String>) {
            session?.recordErase(ids)
        }
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

    override fun onDestroy() {
        gate.cancelAll()
        val closing = session
        session = null
        if (closing != null) {
            // The close has to outlive this Activity: draining the write queue is the difference
            // between a page that reopens as it was left and one missing the last thing drawn on it,
            // and lifecycleScope is cancelled the moment onDestroy returns. The application scope
            // owns it instead, and the index's "last changed" stamp goes with it so the shelf can
            // sort by it.
            PaintsproutApplication.scope.launch {
                closing.close()
                runCatching { repo.touch(closing.sketchbookId) }
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
