package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.util.AttributeSet
import android.view.View

/**
 * Proof-of-concept canvas that renders an AGSL [RuntimeShader].
 *
 * This exists to validate the native rendering pipeline the whole port depends
 * on: Android's AGSL is SkSL-derived, the same lineage as Flutter's fragment
 * shaders, so `shaders/pigment_mix.frag` in the Flutter reference is expected to
 * port here with only minor syntax changes. The shader below is a stand-in — a
 * simple left-to-right blend between two pigments — until that real
 * Kubelka-Munk mixing shader is ported.
 *
 * Requires API 33 (AGSL / RuntimeShader). The app's minSdk is 33, so no runtime
 * guard is needed.
 */
class PigmentCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val shader = RuntimeShader(AGSL)
    private val paint = Paint()

    init {
        // Two pigments to blend across the canvas — a warm cadmium-ish red and a
        // cool ultramarine-ish blue. Values are linear-ish RGB in [0, 1].
        shader.setFloatUniform("pigmentA", 0.86f, 0.16f, 0.12f)
        shader.setFloatUniform("pigmentB", 0.13f, 0.24f, 0.72f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader.setFloatUniform("iResolution", w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private companion object {
        // AGSL: half4 main(float2 fragCoord) is the fragment entry point.
        val AGSL = """
            uniform float2 iResolution;
            uniform half3 pigmentA;
            uniform half3 pigmentB;

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / iResolution;
                // Left-to-right blend between the two pigments.
                half3 c = mix(pigmentA, pigmentB, half(uv.x));
                // Subtle top-to-bottom shading, so it is visibly a live shader
                // rather than a static gradient asset.
                c *= half(0.82 + 0.18 * (1.0 - uv.y));
                return half4(c, 1.0);
            }
        """.trimIndent()
    }
}
