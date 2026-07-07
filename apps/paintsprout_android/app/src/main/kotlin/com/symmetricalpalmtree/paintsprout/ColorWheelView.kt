package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.createBitmap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * An HSV colour wheel: hue around the circumference, saturation from the centre
 * (white) to the rim (full). Brightness (value) is supplied separately via
 * [setValue] — the wheel dims to reflect it. Dragging picks hue + saturation and
 * fires [onColorChanged] with the resulting ARGB colour.
 *
 * The wheel itself is a static [SweepGradient] (hue) with a white [RadialGradient]
 * (saturation) laid over it, baked into a bitmap once per size. Only the thumb and
 * the value-dim overlay are redrawn per frame.
 */
class ColorWheelView(context: Context) : View(context) {

    var onColorChanged: ((Int) -> Unit)? = null

    private var hue = 0f          // 0..360
    private var sat = 0f          // 0..1
    private var value = 1f        // 0..1

    private var wheel: Bitmap? = null
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
    }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        color = 0x55000000
    }

    /** The current ARGB colour. */
    val color: Int get() = Color.HSVToColor(floatArrayOf(hue, sat, value))

    /** Sets the wheel to [argb] (its hue + saturation; value is kept on the slider). */
    fun setColor(argb: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        invalidate()
    }

    /** Sets brightness (0..1); redraws the dim overlay. Does not fire the callback. */
    fun setValue(v: Float) {
        value = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Square: the smaller of the two given dimensions.
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val side = if (h in 1 until w) h else w
        setMeasuredDimension(side, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - dp(2f)
        wheel?.recycle()
        wheel = if (w > 0 && h > 0) buildWheel(w, h) else null
    }

    private fun buildWheel(w: Int, h: Int): Bitmap {
        val bmp = createBitmap(w, h)
        val c = Canvas(bmp)
        val hueShader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN,
                Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
            ),
            null,
        )
        val satShader = RadialGradient(
            cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        c.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = hueShader })
        c.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = satShader })
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        val w = wheel ?: return
        canvas.drawBitmap(w, 0f, 0f, wheelPaint)
        // Dim to reflect brightness: black over the disc at (1 - value) alpha.
        if (value < 1f) {
            dimPaint.color = Color.argb(((1f - value) * 255f).toInt(), 0, 0, 0)
            canvas.drawCircle(cx, cy, radius, dimPaint)
        }
        // Thumb at the current hue/saturation.
        val ang = Math.toRadians(hue.toDouble())
        val tx = cx + (cos(ang) * sat * radius).toFloat()
        val ty = cy + (sin(ang) * sat * radius).toFloat()
        thumbFill.color = color
        canvas.drawCircle(tx, ty, dp(9f), thumbShadow)
        canvas.drawCircle(tx, ty, dp(9f), thumbFill)
        canvas.drawCircle(tx, ty, dp(9f), thumbRing)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = event.x - cx
                val dy = event.y - cy
                val dist = hypot(dx, dy)
                sat = (dist / radius).coerceIn(0f, 1f)
                var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                if (deg < 0f) deg += 360f
                hue = deg
                invalidate()
                onColorChanged?.invoke(color)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
