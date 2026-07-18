package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.symmetricalpalmtree.paintsprout.paint.BrushLoad
import com.symmetricalpalmtree.paintsprout.paint.Pot
import com.symmetricalpalmtree.paintsprout.paint.Tray
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A painter's palette: pots of pigment around the rim, a well in the middle
 * where they mix. Drag (or tap) a pot to dab it into the well, tap the well to
 * load the brush from it.
 *
 * The colour in the well is not blended in RGB — it is mixed spectrally, so
 * ultramarine and cadmium yellow make green here exactly as they would on the
 * canvas. See [com.symmetricalpalmtree.paintsprout.paint.Pigment].
 *
 * All state lives in [tray]; this view only draws it and turns touches into
 * dabs.
 */
class TrayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var tray: Tray = Tray()
        set(value) {
            field = value
            layoutSlots()
            invalidate()
        }

    /** Fired when the well is tapped, with what the brush picked up. */
    var onLoadBrush: ((BrushLoad) -> Unit)? = null

    /** Fired when the "+" pot is tapped — the host opens the colour wheel. */
    var onAddPot: (() -> Unit)? = null

    /** Fired whenever the well's contents change, so the host can relabel. */
    var onMixtureChanged: (() -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFF5F5F5F.toInt()
    }

    private val wipeIcon = ResourcesCompat.getDrawable(resources, R.drawable.ic_clear, null)

    /** Where a pot or control sits once the palette has been laid out. */
    private class Slot(
        val cx: Float,
        val cy: Float,
        val r: Float,
        val pot: Pot? = null,
        val add: Boolean = false,
        val wipe: Boolean = false,
    )

    private var slots = emptyList<Slot>()
    private var wellX = 0f
    private var wellY = 0f
    private var wellR = 0f

    // Drag state: a blob of pigment travelling from a pot toward the well.
    private var dragPot: Pot? = null
    private var dragX = 0f
    private var dragY = 0f
    private var dragOverWell = false
    private var pressedSlot: Slot? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutSlots()
    }

    /**
     * Pots ring the palette; the well takes the middle. Two control slots join
     * the ring — one to add a colour off the wheel, one to wipe the well —
     * so every affordance is a thing you can touch on the palette itself.
     */
    private fun layoutSlots() {
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(6f)

        wellX = cx
        wellY = cy - dp(6f)
        wellR = radius * 0.30f
        labelPaint.textSize = dp(11f)

        val ringR = radius * 0.775f
        val potR = radius * 0.105f

        val entries = tray.pots.map { Slot(0f, 0f, 0f, pot = it) } +
            Slot(0f, 0f, 0f, add = true) +
            Slot(0f, 0f, 0f, wipe = true)

        val step = (2.0 * Math.PI / entries.size).toFloat()
        // Start at the top and go clockwise, so the palette reads like a clock.
        val start = (-Math.PI / 2.0).toFloat()

        slots = entries.mapIndexed { i, e ->
            val a = start + step * i
            Slot(
                cx = cx + ringR * cos(a),
                cy = wellY + ringR * sin(a),
                r = potR,
                pot = e.pot,
                add = e.add,
                wipe = e.wipe,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slots.isEmpty()) return

        drawPaletteBody(canvas)
        drawWell(canvas)
        for (slot in slots) drawSlot(canvas, slot)
        drawLabel(canvas)
        drawDragBlob(canvas)
    }

    private fun drawPaletteBody(canvas: Canvas) {
        val r = min(width, height) / 2f - dp(3f)
        fillPaint.color = 0xFFFDFAF6.toInt()
        canvas.drawCircle(width / 2f, wellY, r, fillPaint)
        strokePaint.color = 0x22000000
        strokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(width / 2f, wellY, r, strokePaint)
    }

    /** The mixing well: empty reads as a recess, mixed shows the spectral result. */
    private fun drawWell(canvas: Canvas) {
        val mixed = tray.mixedColor

        if (mixed == null) {
            fillPaint.color = 0xFFF1EBE3.toInt()
            canvas.drawCircle(wellX, wellY, wellR, fillPaint)
        } else {
            fillPaint.color = mixed
            canvas.drawCircle(wellX, wellY, wellR, fillPaint)
        }

        strokePaint.color = if (dragOverWell) 0xFF2E7D32.toInt() else 0x33000000
        strokePaint.strokeWidth = if (dragOverWell) dp(2.5f) else dp(1f)
        canvas.drawCircle(wellX, wellY, wellR, strokePaint)
    }

    private fun drawSlot(canvas: Canvas, slot: Slot) {
        when {
            slot.pot != null -> {
                fillPaint.color = slot.pot.color
                canvas.drawCircle(slot.cx, slot.cy, slot.r, fillPaint)
                strokePaint.color = 0x40000000
                strokePaint.strokeWidth = dp(1f)
                canvas.drawCircle(slot.cx, slot.cy, slot.r, strokePaint)
                if (slot.pot.custom) {
                    // A ring marks a colour mixed on the wheel rather than a tube.
                    strokePaint.color = 0x99FFFFFF.toInt()
                    canvas.drawCircle(slot.cx, slot.cy, slot.r * 0.62f, strokePaint)
                }
            }
            slot.add -> {
                fillPaint.color = 0xFFEFEFEF.toInt()
                canvas.drawCircle(slot.cx, slot.cy, slot.r, fillPaint)
                strokePaint.color = 0x40000000
                strokePaint.strokeWidth = dp(1f)
                canvas.drawCircle(slot.cx, slot.cy, slot.r, strokePaint)
                strokePaint.color = 0xFF666666.toInt()
                strokePaint.strokeWidth = dp(1.6f)
                val arm = slot.r * 0.45f
                canvas.drawLine(slot.cx - arm, slot.cy, slot.cx + arm, slot.cy, strokePaint)
                canvas.drawLine(slot.cx, slot.cy - arm, slot.cx, slot.cy + arm, strokePaint)
            }
            slot.wipe -> {
                fillPaint.color = 0xFFEFEFEF.toInt()
                canvas.drawCircle(slot.cx, slot.cy, slot.r, fillPaint)
                strokePaint.color = 0x40000000
                strokePaint.strokeWidth = dp(1f)
                canvas.drawCircle(slot.cx, slot.cy, slot.r, strokePaint)
                wipeIcon?.let {
                    val s = (slot.r * 0.62f).toInt()
                    it.setBounds(
                        (slot.cx - s).toInt(), (slot.cy - s).toInt(),
                        (slot.cx + s).toInt(), (slot.cy + s).toInt(),
                    )
                    it.setTint(if (tray.mixture.isEmpty) 0xFFBBBBBB.toInt() else 0xFF666666.toInt())
                    it.draw(canvas)
                }
            }
        }
    }

    /** Names what's in the well — the point is knowing what you mixed. */
    private fun drawLabel(canvas: Canvas) {
        val y = height - dp(6f)
        canvas.drawText(wellLabel(), width / 2f, y, labelPaint)
    }

    private fun wellLabel(): String {
        val dragging = dragPot
        if (dragging != null) return dragging.name
        if (tray.mixture.isEmpty) return "Well is clean"
        val n = tray.mixture.pigmentCount
        return if (n == 1) "1 pigment — tap to load" else "$n pigments — tap to load"
    }

    private fun drawDragBlob(canvas: Canvas) {
        val pot = dragPot ?: return
        fillPaint.color = pot.color
        canvas.drawCircle(dragX, dragY, dp(14f), fillPaint)
        strokePaint.color = 0x55000000
        strokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(dragX, dragY, dp(14f), strokePaint)
    }

    private fun slotAt(x: Float, y: Float): Slot? =
        // Generous hit radius: the pots are small and fingers are not.
        slots.firstOrNull { hypot(x - it.cx, y - it.cy) <= it.r * 1.6f }

    private fun inWell(x: Float, y: Float): Boolean = hypot(x - wellX, y - wellY) <= wellR

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The palette is inside a draggable panel; don't let the pull
                // tab's gesture handling steal a dab halfway through.
                parent?.requestDisallowInterceptTouchEvent(true)
                val slot = slotAt(x, y)
                pressedSlot = slot
                if (slot?.pot != null) {
                    dragPot = slot.pot
                    dragX = x
                    dragY = y
                    dragOverWell = false
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragPot != null) {
                    dragX = x
                    dragY = y
                    dragOverWell = inWell(x, y)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val pot = dragPot
                val pressed = pressedSlot
                dragPot = null
                dragOverWell = false

                when {
                    // Dragged a pot into the well, or just tapped it — both dab.
                    // Tapping is the forgiving path; dragging is the physical one.
                    pot != null && (inWell(x, y) || slotAt(x, y)?.pot == pot) -> {
                        tray.dab(pot)
                        onMixtureChanged?.invoke()
                    }
                    pressed?.add == true && slotAt(x, y)?.add == true -> onAddPot?.invoke()
                    pressed?.wipe == true && slotAt(x, y)?.wipe == true -> {
                        tray.clearMixture()
                        onMixtureChanged?.invoke()
                    }
                    pressed == null && inWell(x, y) && !tray.mixture.isEmpty -> {
                        onLoadBrush?.invoke(tray.loadBrush())
                    }
                }
                pressedSlot = null
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragPot = null
                dragOverWell = false
                pressedSlot = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
