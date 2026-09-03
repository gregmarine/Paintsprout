package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.symmetricalpalmtree.gpaper.core.PaperView
import kotlin.math.hypot

/**
 * The fingers' whole vocabulary on the paper: one finger across turns a page, two fingers tapped
 * twice undoes, three fingers tapped twice redoes.
 *
 * **It is an observer and it consumes nothing.** Fed from the Activity's `dispatchTouchEvent`, it
 * looks at every event and returns nothing to anybody — the actions it fires are side effects. A
 * touch listener that swallowed events would be swallowing them from the firmware's ink pipeline
 * and from the toolbar buttons, and the failure would show up as a pencil that intermittently does
 * not draw rather than as a gesture detector doing too much.
 *
 * ## The palm, which is the entire difficulty
 *
 * On this panel a *writing stylus produces no MotionEvents at all* — the firmware paints the ink —
 * but the hand resting beside it produces plenty. So every event this class sees while the artist
 * is drawing is, by construction, not a gesture: it is the heel of a hand. An ungated recogniser
 * would turn the page halfway through a stroke, and worse, poking the view mid-contact drops ink.
 *
 * The gate is g-paper's own `isPenActive`, open while the pen is writing **or hovering**, plus a
 * tail. Three obligations come with it, all of them from `host-responsibilities.md`, and all three
 * are met here:
 *
 *  - **Refuse to arm while the gate is open.** A sequence that begins under a hovering pen is
 *    ignored whole, and so is one that begins on chrome or from a stylus tool type.
 *  - **Re-check at finger-up, not just at down.** The palm can land a beat *before* the pen enters
 *    hover range, so a gesture that was innocent at its start can be a palm by the time it ends.
 *  - **Put anything that changes state into an escrow.** A palm micro-tap can complete about
 *    190 ms before the pen is in hover range, which no proximity check at up-time can catch. Undo
 *    and redo therefore wait one [PaperView.PEN_ACTIVE_TAIL_MS] and are dropped if the gate has
 *    closed by then.
 *
 * The page flip is the one action that fires straight at the lift with only a re-check, and
 * deliberately: it is not a micro-tap a palm can produce by accident. Getting there takes a
 * horizontal journey across a sixth of the panel, at a flick's speed or further still — see
 * [SwipeRule] for the argument. Making the artist wait a third of a second to watch the page turn
 * would be paying the palm tax on the one gesture that does not owe it.
 *
 * ## The BOOX cancel rule
 *
 * Three fingers on this device never reach `ACTION_UP`. The Onyx SDK claims three-finger touches
 * for its own system gesture and cancels the sequence out from under the app, so a recogniser that
 * waits for a clean lift waits forever and redo simply does not exist. Paper v0 met this by reading
 * the cancel itself as the lift: an `ACTION_CANCEL` arriving on an armed, unmoved sequence whose
 * peak count was three counts as the three-finger tap. Any other cancel is a gesture that got
 * interrupted and both tap histories are thrown away, so a half-seen tap can never pair with a real
 * one later.
 *
 * [standDown] is the screen saying it is busy — a page swap or a replay in flight, a dialog up. A
 * second flip arriving into the middle of the first is not a second page turn, it is two page turns
 * racing for one panel.
 */
class PageGestures(
    private val host: View,
    private val isPenActive: () -> Boolean,
    private val standDown: () -> Boolean,
    private val overChrome: (MotionEvent) -> Boolean,
    private val listener: Listener,
) {

    /** No-op defaults, so a host overrides only the gestures it has an answer for. */
    interface Listener {
        fun onFlipNext() {}
        fun onFlipPrevious() {}
        fun onUndo() {}
        fun onRedo() {}
    }

    private val vc = ViewConfiguration.get(host.context)
    private val touchSlop = vc.scaledTouchSlop
    private val doubleTapSlop = vc.scaledDoubleTapSlop
    private val minFlingVelocity = vc.scaledMinimumFlingVelocity.toFloat()
    private val width get() = host.resources.displayMetrics.widthPixels.toFloat()

    /** Set on the first down. While true the whole sequence is ignored — stylus, chrome, or gated. */
    private var ignoreSequence = false

    // ── One finger across the page ──────────────────────────────────────────────
    private var swipeActive = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var tracker: VelocityTracker? = null

    // ── Multi-finger stationary double-tap ──────────────────────────────────────
    private var peakFingers = 1
    private var tapArmed = false
    private var tapMoved = false
    private var tapDownTime = 0L
    private var centroidX = 0f
    private var centroidY = 0f
    private var twoTapTime = 0L
    private var twoTapX = 0f
    private var twoTapY = 0f
    private var threeTapTime = 0L
    private var threeTapX = 0f
    private var threeTapY = 0f

    fun onTouchEvent(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoreSequence = standDown() || isPenActive() || overChrome(ev) || isStylus(ev)
            if (ignoreSequence) {
                cancelAll()
                return
            }
        } else if (ignoreSequence) {
            return
        }
        // The screen going busy part-way through abandons whatever was in progress. A swipe that
        // began before a page swap started is a swipe aimed at a page that is no longer there.
        if (standDown()) {
            cancelAll()
            ignoreSequence = true
            return
        }
        handleSwipe(ev)
        handleMultiTap(ev)
    }

    private fun gateOpen(): Boolean = !isPenActive() && !standDown()

    private fun isStylus(ev: MotionEvent): Boolean {
        val type = ev.getToolType(0)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    /**
     * Hold a state-changing tap for the pen's tail and drop it if the gate closed meanwhile.
     *
     * This is the one defence against the palm micro-tap: a hand landing and lifting before the pen
     * has come into hover range looks, at the moment it lifts, exactly like a deliberate finger.
     * Only the pen arriving a fraction of a second later tells the two apart, so the answer is to
     * wait that fraction of a second before believing it.
     */
    private fun escrow(action: () -> Unit) {
        host.postDelayed({ if (gateOpen()) action() }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    // ── One finger across the page ──────────────────────────────────────────────

    private fun handleSwipe(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeActive = true
                swipeStartX = ev.x
                swipeStartY = ev.y
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            // A second finger means this was never a one-finger swipe. There is no two-finger flip
            // in this app — two fingers are the undo tap — so the swipe is simply abandoned rather
            // than committed early.
            MotionEvent.ACTION_POINTER_DOWN -> clearSwipe()
            MotionEvent.ACTION_MOVE -> if (swipeActive) tracker?.addMovement(ev)
            MotionEvent.ACTION_UP -> {
                val t = tracker
                if (swipeActive && t != null) {
                    t.addMovement(ev)
                    t.computeCurrentVelocity(VELOCITY_UNITS_PER_SECOND)
                    evaluateFlip(ev.x - swipeStartX, ev.y - swipeStartY, t.getXVelocity(0))
                }
                clearSwipe()
            }
            MotionEvent.ACTION_CANCEL -> clearSwipe()
        }
    }

    private fun evaluateFlip(dx: Float, dy: Float, vx: Float) {
        val flip = SwipeRule.evaluate(dx, dy, vx, width, minFlingVelocity)
        if (flip == SwipeRule.Flip.NONE) return
        // The gate is re-read here, at the lift, and not only at the down: the palm can land before
        // the pen enters hover range, so a sequence that began looking like a finger can be a hand
        // by the time it ends.
        if (!gateOpen()) return
        if (flip == SwipeRule.Flip.NEXT) listener.onFlipNext() else listener.onFlipPrevious()
    }

    private fun clearSwipe() {
        swipeActive = false
        tracker?.recycle()
        tracker = null
    }

    // ── Two and three fingers, tapped twice ─────────────────────────────────────

    private fun handleMultiTap(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                peakFingers = 1
                tapArmed = false
                tapMoved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val count = ev.pointerCount
                if (count > peakFingers) peakFingers = count
                // Four fingers is a hand laid flat on the paper, not a tap with one extra finger on
                // it. Disarming rather than clamping means a palm that happens to present four
                // contacts can never resolve into the three-finger redo on its way back up.
                if (count >= 4) {
                    tapArmed = false
                    tapMoved = true
                    return
                }
                if (!tapArmed) {
                    tapArmed = true
                    tapDownTime = ev.eventTime
                }
                recordCentroid(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (tapArmed && !tapMoved && ev.pointerCount >= 2) {
                    val cx = averageX(ev)
                    val cy = averageY(ev)
                    if (hypot((cx - centroidX).toDouble(), (cy - centroidY).toDouble()) > touchSlop) {
                        tapMoved = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val quick = ev.eventTime - tapDownTime <= ViewConfiguration.getLongPressTimeout()
                if (tapArmed && !tapMoved && quick) evaluateTap(ev.eventTime, peakFingers)
                tapArmed = false
                tapMoved = false
            }
            MotionEvent.ACTION_CANCEL -> {
                // The BOOX rule. Three fingers never reach ACTION_UP on this device — the Onyx SDK
                // claims them for its own gesture and cancels ours — so an armed, unmoved,
                // three-finger sequence that is cancelled *is* the tap. Every other cancel is a
                // gesture that was interrupted, and both histories go: a half-seen tap that stayed
                // in the record would pair with a real one later and fire something nobody asked for.
                if (tapArmed && !tapMoved && peakFingers == 3) {
                    evaluateTap(SystemClock.uptimeMillis(), 3)
                } else {
                    twoTapTime = 0L
                    threeTapTime = 0L
                }
                tapArmed = false
                tapMoved = false
            }
        }
    }

    /**
     * The second of two taps close together in time and place fires; the first is only remembered.
     *
     * A double-tap rather than a single one because two fingers touching the paper at once is
     * something a hand does by accident all day — resting, shifting, picking the tablet up. Asking
     * for it twice in a third of a second, in the same place, is a thing a hand does not do without
     * meaning to.
     */
    private fun evaluateTap(now: Long, fingers: Int) {
        when (fingers) {
            2 -> {
                if (pairsWith(twoTapTime, twoTapX, twoTapY, now)) {
                    twoTapTime = 0L
                    escrow { listener.onUndo() }
                } else {
                    twoTapTime = now
                    twoTapX = centroidX
                    twoTapY = centroidY
                }
            }
            3 -> {
                if (pairsWith(threeTapTime, threeTapX, threeTapY, now)) {
                    threeTapTime = 0L
                    escrow { listener.onRedo() }
                } else {
                    threeTapTime = now
                    threeTapX = centroidX
                    threeTapY = centroidY
                }
            }
        }
    }

    private fun pairsWith(lastTime: Long, lastX: Float, lastY: Float, now: Long): Boolean {
        if (lastTime == 0L) return false
        if (now - lastTime > ViewConfiguration.getDoubleTapTimeout()) return false
        return hypot((centroidX - lastX).toDouble(), (centroidY - lastY).toDouble()) <= doubleTapSlop
    }

    private fun recordCentroid(ev: MotionEvent) {
        centroidX = averageX(ev)
        centroidY = averageY(ev)
    }

    private fun averageX(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getX(i)
        return sum / ev.pointerCount
    }

    private fun averageY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }

    /** Everything in flight goes. The screen is going away, or has stood down. */
    fun cancelAll() {
        clearSwipe()
        tapArmed = false
        tapMoved = false
        twoTapTime = 0L
        threeTapTime = 0L
    }

    private companion object {
        /** VelocityTracker's units argument: pixels per second, which is what [SwipeRule] expects. */
        const val VELOCITY_UNITS_PER_SECOND = 1000
    }
}
