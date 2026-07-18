package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import com.symmetricalpalmtree.paintsprout.paint.StrokePoint
import com.symmetricalpalmtree.paintsprout.paint.StrokeRenderer
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.Tool

/**
 * Low-latency wet ink: a transparent [SurfaceView] overlay driven by
 * [CanvasFrontBufferedRenderer]. Finalized chunks of the active stroke render
 * straight into the display's front buffer, skipping the ~2-3 frame compositor
 * pipeline a plain View pays — the single biggest cut to pen-to-ink distance.
 *
 * Only FINALIZED geometry may enter: the buffer is persistent, so the
 * provisional tail and predicted points (which change next frame) stay on the
 * classic preview path. On pen-up the stroke hands off to the normal bake and
 * the overlay clears.
 */
class FrontInkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    /** A finalized run of points, snapshotted for the render thread. */
    class Chunk(
        val tool: Tool,
        val color: Int,
        val points: List<StrokePoint>,
        val surface: SurfaceKind,
    )

    // Where the sheet sits inside the view (canvas-local drawing coordinates).
    private var offsetX = 0f
    private var offsetY = 0f
    private var sheetW = 0f
    private var sheetH = 0f

    private var renderer: CanvasFrontBufferedRenderer<Chunk>? = null

    private val callback = object : CanvasFrontBufferedRenderer.Callback<Chunk> {
        override fun onDrawFrontBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            param: Chunk,
        ) = drawChunk(canvas, param)

        override fun onDrawMultiBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            params: Collection<Chunk>,
        ) {
            for (c in params) drawChunk(c = c, canvas = canvas)
        }
    }

    init {
        // On top of the window: media-overlay Z would sit BELOW the view
        // hierarchy, and the hole the hierarchy punches for it would swallow
        // the canvas view. Transparent pixels let everything show through.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // A fresh surface's content is undefined (reads as opaque black);
        // clear it to transparent the moment it exists.
        holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                renderer?.clear()
            }

            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) {
                renderer?.clear()
            }

            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
        })
    }

    /** Matches the overlay's drawing space to the sheet inside the canvas view. */
    fun configureSheet(left: Float, top: Float, width: Float, height: Float) {
        offsetX = left
        offsetY = top
        sheetW = width
        sheetH = height
    }

    val isReady: Boolean get() = renderer?.isValid() == true

    /** Renders one finalized chunk into the front buffer. */
    fun renderChunk(chunk: Chunk) {
        renderer?.renderFrontBufferedLayer(chunk)
    }

    /** Drops all wet ink (call once the classic path has taken over drawing). */
    fun clearInk() {
        renderer?.clear()
    }

    private fun drawChunk(canvas: Canvas, c: Chunk) {
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.clipRect(0f, 0f, sheetW, sheetH)
        StrokeRenderer.paintChunk(canvas, c.tool, c.color, c.points, c.surface)
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (renderer == null) renderer = CanvasFrontBufferedRenderer(this, callback)
    }

    override fun onDetachedFromWindow() {
        renderer?.release(true)
        renderer = null
        super.onDetachedFromWindow()
    }
}
