package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.paintsprout.paint.Calibration
import com.symmetricalpalmtree.paintsprout.paint.Calibration.Reference
import kotlin.math.min

/**
 * The interactive part of screen calibration. Draws a reference outline at TRUE
 * device pixels (no scaling — the whole point is a physical match) anchored at a
 * fixed top-left origin, and lets the user drag its far handle until the outline
 * matches a physical object laid on the glass. The resulting pixels-per-real-unit
 * is reported back as a PPI through [onPpiChanged].
 *
 * [ppi] is the single source of truth: the outline's on-screen span is *derived*
 * from it each frame (`span = mmToPx(referenceLength, ppi)`), and a drag inverts
 * that to recover a new ppi. Switching [reference] or [rulerLengthMm] keeps ppi
 * and re-clamps it so the outline still fits.
 */
class CalibrationView(context: Context) : View(context) {

    var onPpiChanged: ((Float) -> Unit)? = null

    var reference: Reference = Calibration.DEFAULT_REFERENCE
        set(value) {
            field = value
            clampPpi()
            invalidate()
        }

    /** Ruler-only: the real length the bar represents, in mm. */
    var rulerLengthMm: Float = Calibration.DEFAULT_REFERENCE.longMm
        set(value) {
            field = value.coerceAtLeast(10f)
            clampPpi()
            invalidate()
        }

    var ppi: Float = 300f
        private set

    private var dragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val bg = Paint().apply { color = 0xFF1B2327.toInt() }
    private val guideFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFC107 }
    private val guideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFFFFC107.toInt()
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xFFECEFF1.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFECEFF1.toInt()
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt()
        textSize = dp(13f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
    }

    private val originX get() = dp(48f)
    private val originY get() = if (reference.isRuler) dp(96f) else dp(56f)
    private val handleR get() = dp(16f)
    private val reserveBottom get() = dp(200f) // room for the control card below

    /** Sets the starting ppi (e.g. the reported/effective value) and clamps it to fit. */
    fun setInitialPpi(value: Float) {
        ppi = value
        clampPpi()
        invalidate()
    }

    private fun longMm(): Float = if (reference.isRuler) rulerLengthMm else reference.longMm

    private fun minSpanPx(): Float = dp(140f)

    private fun maxSpanPx(): Float {
        if (width == 0 || height == 0) return dp(600f)
        val maxByWidth = width - originX - dp(28f)
        return if (reference.isRuler) {
            maxByWidth
        } else {
            val maxByHeight = (height - originY - reserveBottom) / reference.aspect
            min(maxByWidth, maxByHeight)
        }.coerceAtLeast(minSpanPx())
    }

    private fun clampPpi() {
        val inches = longMm() / Calibration.MM_PER_IN
        if (inches <= 0f) return
        val lo = minSpanPx() / inches
        val hi = maxSpanPx() / inches
        ppi = ppi.coerceIn(min(lo, hi), maxOf(lo, hi))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPpi()
    }

    private fun currentSpanPx(): Float = Calibration.mmToPx(longMm(), ppi)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val span = currentSpanPx()
        if (reference.isRuler) drawRuler(canvas, span) else drawCard(canvas, span)
    }

    private fun drawCard(canvas: Canvas, span: Float) {
        val left = originX
        val top = originY
        val right = left + span
        val bottom = top + span * reference.aspect
        canvas.drawRect(left, top, right, bottom, guideFill)
        // The stroke sits just OUTSIDE the measured rect, so its INNER edge is the
        // calibrated size: nestle the card inside and the outline frames it exactly.
        val hw = guideStroke.strokeWidth / 2f
        canvas.drawRect(left - hw, top - hw, right + hw, bottom + hw, guideStroke)
        canvas.drawText(
            "Nestle your ${reference.label.lowercase()} inside the outline with your stylus, until it frames the card",
            left, top - dp(12f), hintPaint,
        )
        drawHandle(canvas, right + hw, bottom + hw)
    }

    private fun drawRuler(canvas: Canvas, span: Float) {
        val baseY = originY + dp(34f)
        val startX = originX
        val endX = originX + span
        canvas.drawLine(startX, baseY, endX, baseY, guideStroke)
        canvas.drawText(
            "Lay a ruler along the line; drag the handle with your stylus, then set the length",
            startX, originY - dp(56f), hintPaint,
        )

        // Metric ticks below the baseline; a label at every cm.
        val pxPerMm = ppi / Calibration.MM_PER_IN
        var mm = 0
        var x = startX
        while (x <= endX + 0.5f) {
            val len = when {
                mm % 10 == 0 -> dp(16f)
                mm % 5 == 0 -> dp(11f)
                else -> dp(6f)
            }
            canvas.drawLine(x, baseY, x, baseY + len, tickPaint)
            if (mm % 10 == 0) canvas.drawText("${mm / 10}", x, baseY + len + dp(14f), labelPaint)
            mm++
            x = startX + mm * pxPerMm
        }

        // Imperial ticks above the baseline (eighths), a label at every inch.
        val pxPerEighth = ppi / 8f
        var e = 0
        var xi = startX
        while (xi <= endX + 0.5f) {
            val len = when {
                e % 8 == 0 -> dp(16f)
                e % 4 == 0 -> dp(11f)
                e % 2 == 0 -> dp(8f)
                else -> dp(5f)
            }
            canvas.drawLine(xi, baseY, xi, baseY - len, tickPaint)
            if (e % 8 == 0) canvas.drawText("${e / 8}\"", xi, baseY - len - dp(6f), labelPaint)
            e++
            xi = startX + e * pxPerEighth
        }

        drawHandle(canvas, endX, baseY)
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, handleR, handleFill)
        canvas.drawCircle(cx, cy, handleR, handleRing)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Only the stylus (or a mouse) drives the handle, so a resting palm
                // or the physical card laid on the glass can't nudge it. Grab anywhere
                // from the origin rightward — the handle sits at the far edge.
                val idx = event.actionIndex
                if (isDragTool(event.getToolType(idx)) && event.getX(idx) > originX) {
                    activePointerId = event.getPointerId(idx)
                    dragging = true
                    updateFromX(event.getX(idx))
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val idx = event.findPointerIndex(activePointerId)
                if (idx >= 0) {
                    updateFromX(event.getX(idx))
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP ->
                if (event.getPointerId(event.actionIndex) == activePointerId) endDrag()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return super.onTouchEvent(event)
    }

    /** Stylus, eraser end, or mouse — never a bare finger/palm. */
    private fun isDragTool(toolType: Int): Boolean = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS,
        MotionEvent.TOOL_TYPE_ERASER,
        MotionEvent.TOOL_TYPE_MOUSE -> true
        else -> false
    }

    private fun endDrag() {
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun updateFromX(x: Float) {
        val span = (x - originX).coerceIn(minSpanPx(), maxSpanPx())
        val inches = longMm() / Calibration.MM_PER_IN
        if (inches <= 0f) return
        ppi = span / inches
        invalidate()
        onPpiChanged?.invoke(ppi)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
