package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader

/**
 * Blends a watercolor [wash] onto a [backdrop] through the spectral
 * Kubelka-Munk pigment-mixing AGSL shader (res/raw/pigment_mix.agsl) — so blue
 * under a yellow wash reads green, not muddy grey. The native counterpart of
 * `_mixPigment` in the Flutter `drawing_canvas.dart`.
 *
 * The shader only runs on a hardware canvas, so the actual draw goes through
 * [GpuRender]. Returns a new (mutable) bitmap; does not recycle its inputs.
 */
object PigmentMixer {

    /**
     * @param agsl the shader source (read once from res/raw by the caller).
     * @param washGain multiplier on the wash pigment's weight (1.0 = neutral).
     * @param darkHold how strongly near-neutral darks resist being tinted (0 = none).
     */
    fun mix(
        agsl: String,
        backdrop: Bitmap,
        wash: Bitmap,
        washGain: Float = 1.0f,
        darkHold: Float = 0.0f,
    ): Bitmap {
        val w = backdrop.width
        val h = backdrop.height
        val shader = RuntimeShader(agsl).apply {
            setFloatUniform("uWashGain", washGain)
            setFloatUniform("uDarkHold", darkHold)
            setInputShader("uBackdrop", BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setInputShader("uWash", BitmapShader(wash, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        }
        val paint = Paint().apply { this.shader = shader }
        return GpuRender.renderToBitmap(w, h) { canvas ->
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }
}
