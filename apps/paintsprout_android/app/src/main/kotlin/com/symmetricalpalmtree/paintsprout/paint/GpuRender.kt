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
 * The [HardwareRenderer] + [ImageReader] are **pooled** per buffer size and
 * reused across calls (creating them is expensive, and a watercolor bake — plus
 * the live wet preview — issues several renders back to back). All access is
 * serialized behind [lock], so it is safe to call from the bake threads.
 */
object GpuRender {

    private val lock = Any()
    private val root = RenderNode("root")
    private var reader: ImageReader? = null
    private var renderer: HardwareRenderer? = null
    private var poolW = 0
    private var poolH = 0

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

    /**
     * Like [renderToBitmap] but copies the result into the caller-owned [dst]
     * (which must be [dst].width × [dst].height) instead of allocating a fresh
     * bitmap. Meant for the per-frame live watercolor backdrop, where allocating
     * (and GCing) a bitmap every frame would stutter. [dst] must be mutable.
     */
    fun renderInto(dst: Bitmap, effect: RenderEffect? = null, draw: (Canvas) -> Unit) =
        synchronized(lock) {
            renderInternal(dst.width, dst.height, effect, draw) { hw ->
                Canvas(dst).apply {
                    drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    drawBitmap(hw, 0f, 0f, null)
                }
            }
        }

    fun renderToBitmap(
        width: Int,
        height: Int,
        effect: RenderEffect? = null,
        draw: (Canvas) -> Unit,
    ): Bitmap = synchronized(lock) {
        renderInternal(width, height, effect, draw) { hw ->
            hw.copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    private inline fun <R> renderInternal(
        width: Int,
        height: Int,
        effect: RenderEffect?,
        draw: (Canvas) -> Unit,
        consume: (Bitmap) -> R,
    ): R {
        val (reader, renderer) = ensurePool(width, height)
        root.setPosition(0, 0, width, height)

        // A RenderEffect (blur) renders to an intermediate layer, so clearing
        // inside that node does NOT clear the backing surface. Put the effect on a
        // child node and let the effect-free ROOT clear the surface to transparent
        // and draw the child over it — otherwise sparse content (a blurred region
        // mask) reads back opaque outside the drawn area.
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
                    // The caller either copies to a fresh mutable bitmap or blits
                    // into a reused one; either way the wrapped buffer is transient.
                    return consume(hw).also { hw.recycle() }
                } finally {
                    buffer.close()
                }
            }
        } finally {
            root.discardDisplayList()
            child?.discardDisplayList()
        }
    }

    /** Releases the pooled GPU objects (call when the canvas is torn down). */
    fun release() = synchronized(lock) {
        renderer?.destroy()
        reader?.close()
        renderer = null
        reader = null
        poolW = 0
        poolH = 0
    }

    private fun ensurePool(width: Int, height: Int): Pair<ImageReader, HardwareRenderer> {
        val r = reader
        val rr = renderer
        if (r != null && rr != null && poolW == width && poolH == height) return r to rr
        renderer?.destroy()
        reader?.close()
        val newReader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val newRenderer = HardwareRenderer().apply {
            setSurface(newReader.surface)
            setContentRoot(root)
        }
        reader = newReader
        renderer = newRenderer
        poolW = width
        poolH = height
        return newReader to newRenderer
    }
}
