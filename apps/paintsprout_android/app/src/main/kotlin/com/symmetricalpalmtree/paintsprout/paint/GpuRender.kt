package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import kotlin.math.max

/**
 * Renders draw commands on a **hardware-accelerated** offscreen canvas and reads
 * the result back into a software [Bitmap].
 *
 * This exists because AGSL [android.graphics.RuntimeShader] (the pigment mixer)
 * only runs on a hardware canvas — our ordinary `Canvas(bitmap)` bakes are
 * software. A [RenderNode] records the commands, a [HardwareRenderer] draws them
 * into an [ImageReader]'s surface, and the resulting [HardwareBuffer] is copied
 * back to a mutable ARGB_8888 bitmap the rest of the pipeline can keep
 * compositing onto.
 *
 * One-shot per call (sets up and tears down the GPU objects each time). Watercolor
 * bakes happen on pointer-up, not per frame, so this is acceptable; it can be
 * pooled later if profiling calls for it. Safe to call off the main thread.
 */
object GpuRender {

    /**
     * Gaussian-blurs [src] on the GPU via a [RenderEffect] (premultiplied-correct,
     * unlike a naive software box blur). Returns a new bitmap.
     */
    fun blur(src: Bitmap, radius: Float): Bitmap {
        val r = max(0.1f, radius)
        val effect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
        return renderToBitmap(src.width, src.height, effect) { canvas ->
            canvas.drawBitmap(src, 0f, 0f, null)
        }
    }

    fun renderToBitmap(
        width: Int,
        height: Int,
        effect: RenderEffect? = null,
        draw: (Canvas) -> Unit,
    ): Bitmap {
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        // A RenderEffect (blur) renders to an intermediate layer, so clearing
        // inside that node does NOT clear the backing surface. Put the effect on a
        // child node and let the effect-free ROOT clear the surface to transparent
        // and draw the child over it — otherwise sparse content (a blurred region
        // mask) reads back opaque outside the drawn area.
        val root = RenderNode("root").apply { setPosition(0, 0, width, height) }
        val child = if (effect != null) {
            RenderNode("fx").apply {
                setPosition(0, 0, width, height)
                setRenderEffect(effect)
                val c = beginRecording(width, height)
                draw(c)
                endRecording()
            }
        } else {
            null
        }
        val renderer = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(root)
        }
        try {
            val canvas = root.beginRecording(width, height)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (child != null) canvas.drawRenderNode(child) else draw(canvas)
            root.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            reader.acquireNextImage().use { image ->
                val buffer = requireNotNull(image?.hardwareBuffer) { "no image from GPU render" }
                try {
                    val hw = Bitmap.wrapHardwareBuffer(
                        buffer, ColorSpace.get(ColorSpace.Named.SRGB),
                    ) ?: error("wrapHardwareBuffer returned null")
                    // Mutable copy so later strokes can composite onto it.
                    return hw.copy(Bitmap.Config.ARGB_8888, true).also { hw.recycle() }
                } finally {
                    buffer.close()
                }
            }
        } finally {
            renderer.destroy()
            root.discardDisplayList()
            child?.discardDisplayList()
            reader.close()
        }
    }
}
