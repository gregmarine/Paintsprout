package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import android.os.Handler
import android.os.Looper
import com.symmetricalpalmtree.gpaper.core.PaperView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Nothing on this screen repaints while the pen is on the paper.
 *
 * This is the frame-silence rule, and on this panel it is not a performance nicety. Frames presented
 * during a live raw contact are **withheld from the panel** by the ink pipeline, and a later
 * `invalidate()` of identical content is damage-free — so chrome updated mid-stroke is not merely
 * wasteful, it is *invisible*, and stays invisible until something unrelated happens to damage that
 * region. The failure looks like a label that stopped working rather than one that was drawn at the
 * wrong moment, which is why it survives so long unfound.
 *
 * The gate is g-paper's own [PaperView.isPenActive], which is open while the pen is writing **or
 * hovering**, plus a tail. Hover counts because an EMR pen is in range between every stroke of a
 * sentence; waiting for a pen that is merely hovering is the difference between updating between
 * strokes and updating between drawings.
 *
 * Work is **keyed**, and the key is what makes this a gate rather than a queue. A label that wants
 * to say three different things while the pen is down should say the last of them once, not all
 * three in order — so a second request under the same key replaces the first. Anything genuinely
 * cumulative belongs in the model, not here.
 *
 * ## The ledger
 *
 * Every deliberate exception to the rule is written down here and in `docs/sketchbook.md`. As of G4
 * there are still **none**, and the phase that could most easily have added one did not. G3's
 * chrome was static by construction — the toolbar changed only on a tap, and a finger arriving
 * while the pen is active is a palm the component has already refused. G4 brought the two things
 * that genuinely move on their own: a page swap, which repaints the whole panel, and a page
 * indicator that changes on every turn. Both go through here. The swap waits on [awaitIdle] before
 * it touches the paper at all; the label goes through [run] exactly as G3's one label did. The undo
 * arrows carry no state at all — see `SketchbookActivity.refreshChrome` for why a button that
 * changes when a mark is made cannot be drawn on this panel.
 */
class PenIdleGate(private val paper: PaperView) {

    private val handler = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Runnable>()

    /**
     * Run [block] on the main thread as soon as the pen is neither writing nor hovering.
     *
     * Immediately when the gate is already closed, so the common case — a tap on a toolbar, with no
     * pen anywhere near the glass — costs nothing and does not flicker a frame later.
     */
    fun run(key: String, block: () -> Unit) {
        pending.remove(key)?.let { handler.removeCallbacks(it) }
        if (!paper.isPenActive) {
            block()
            return
        }
        lateinit var retry: Runnable
        retry = Runnable {
            if (paper.isPenActive) {
                handler.postDelayed(retry, PaperView.PEN_ACTIVE_TAIL_MS)
            } else {
                pending.remove(key)
                block()
            }
        }
        pending[key] = retry
        handler.postDelayed(retry, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    /**
     * Suspend until the pen is neither writing nor hovering. Returns at once when it already is.
     *
     * [run] is for chrome, which can wait a beat and then say the last thing it wanted to say. This
     * is for the things that cannot be phrased that way — a page swap is a sequence of three calls
     * into the component that has to happen *together*, so there is no block to hand a gate; what
     * there is, is a moment the sequence must not start before.
     *
     * It is not a nicety and not a tidiness. `loadStrokes` under a live contact drops ink: the
     * frames it presents are withheld while the pen is down, so the new page arrives invisibly and
     * the marks still being captured land against a model that has already been swapped out from
     * under them. Waiting is the whole of the fix, and the wait is short — the gate closes about a
     * third of a second after the pen leaves the glass.
     */
    suspend fun awaitIdle() = withContext(Dispatchers.Main) {
        while (paper.isPenActive) delay(PaperView.PEN_ACTIVE_TAIL_MS)
    }

    /** Drop everything waiting. The screen is going away and none of it is worth a frame now. */
    fun cancelAll() {
        pending.values.forEach { handler.removeCallbacks(it) }
        pending.clear()
    }
}
