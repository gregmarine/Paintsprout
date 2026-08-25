package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import android.os.Handler
import android.os.Looper
import com.symmetricalpalmtree.gpaper.core.PaperView

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
 * Every deliberate exception to the rule is written down here and in `docs/sketchbook.md`. As of G3
 * there are **none**: the sketchbook's chrome is static by construction — the toolbar changes only
 * on a tap, and a tap is a finger, and a finger arriving while the pen is active is a palm the
 * component has already refused. The gate exists anyway, wired to the one label that will move, so
 * that G4's page turns and undo counters arrive into a screen that already obeys the rule rather
 * than one that has to be taught it afterwards.
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

    /** Drop everything waiting. The screen is going away and none of it is worth a frame now. */
    fun cancelAll() {
        pending.values.forEach { handler.removeCallbacks(it) }
        pending.clear()
    }
}
