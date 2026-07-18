package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.core.graphics.createBitmap

/**
 * The wet watercolor simulation (Phase 3): a wetness + pigment field confined
 * to a crop of the canvas, stepped at a FIXED timestep by the wet_sim.agsl
 * tick shader through the pooled [GpuRender].
 *
 * State texture: rgb = pigment concentration, a = wetness. The brush deposits
 * STAMPS into the field (no ribbon geometry — hairpin folds and stroke-start
 * seams are structurally impossible); the ticks then move water and pigment.
 *
 * Determinism is the contract that makes undo work: the sequence
 * begin → (stamp* → tick)* replayed with the same inputs yields the same
 * field, so the bake re-runs exactly what the live preview showed. No clocks,
 * no randomness — callers drive ticks by their own fixed-dt schedule.
 */
class WetSim(agsl: String) {

    private val shader = RuntimeShader(agsl)
    private var stateA: Bitmap? = null
    private var stateB: Bitmap? = null
    private var flip = false
    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The crop of the canvas (buffer px) this field covers. */
    var crop: Rect = Rect()
        private set

    /** Ticks run since the last deposit — the calm/dry heuristic. */
    var ticksSinceDeposit = 0
        private set

    /** The current state (read-only; valid until the next tick/begin). */
    val state: Bitmap? get() = if (flip) stateB else stateA

    /** (Re)starts the sim over [cropRect], dropping any previous field. */
    fun begin(cropRect: Rect) {
        crop = Rect(cropRect)
        val w = cropRect.width().coerceAtLeast(1)
        val h = cropRect.height().coerceAtLeast(1)
        stateA = reuse(stateA, w, h)
        stateB = reuse(stateB, w, h)
        flip = false
        ticksSinceDeposit = 0
    }

    /**
     * Deposits one brush stamp at canvas-buffer position ([x], [y]): a soft
     * disc of water carrying pigment. [wetness] in [0,1]; [pigment] scales the
     * colour's concentration (0 = plain water — the water mode).
     */
    fun stamp(x: Float, y: Float, radius: Float, color: Int, wetness: Float, pigment: Float) {
        val dst = state ?: return
        val cx = x - crop.left
        val cy = y - crop.top
        val r = (color shr 16 and 0xFF) / 255f * pigment
        val g = (color shr 8 and 0xFF) / 255f * pigment
        val b = (color and 0xFF) / 255f * pigment
        // Soft-edged stamp with a radial falloff; ADD blending so overlapping
        // stamps pool water and pigment instead of overwriting each other.
        stampPaint.shader = RadialGradient(
            cx, cy, radius.coerceAtLeast(1f),
            intArrayOf(
                android.graphics.Color.argb(
                    (wetness * 255f).toInt().coerceIn(0, 255),
                    (r * 255f).toInt().coerceIn(0, 255),
                    (g * 255f).toInt().coerceIn(0, 255),
                    (b * 255f).toInt().coerceIn(0, 255),
                ),
                0,
            ),
            null, Shader.TileMode.CLAMP,
        )
        stampPaint.blendMode = android.graphics.BlendMode.PLUS
        Canvas(dst).drawCircle(cx, cy, radius.coerceAtLeast(1f), stampPaint)
        ticksSinceDeposit = 0
    }

    /**
     * Advances the field one fixed timestep: one GPU pass of wet_sim.agsl into
     * the other buffer, then swap. [tooth] is the paper's raw tooth field for
     * this crop (r channel, 1 = crest).
     */
    fun tick(tooth: Bitmap) {
        val src = state ?: return
        val dst = (if (flip) stateA else stateB) ?: return
        shader.setInputShader("uState", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("uTooth", BitmapShader(tooth, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
        shader.setFloatUniform("uTexel", 1f / src.width, 1f / src.height)
        shader.setFloatUniform("uDiffuse", DIFFUSE)
        shader.setFloatUniform("uPigDiffuse", PIG_DIFFUSE)
        shader.setFloatUniform("uEvap", EVAP)
        shader.setFloatUniform("uGrain", GRAIN)
        shader.setFloatUniform("uToothDrag", TOOTH_DRAG)
        val paint = Paint().apply { this.shader = this@WetSim.shader }
        GpuRender.renderInto(dst) { c ->
            c.drawRect(0f, 0f, dst.width.toFloat(), dst.height.toFloat(), paint)
        }
        flip = !flip
        ticksSinceDeposit++
    }

    /** Whether the field has calmed enough to dry and bake. */
    val isCalm: Boolean get() = ticksSinceDeposit >= CALM_TICKS

    fun release() {
        stateA?.recycle(); stateA = null
        stateB?.recycle(); stateB = null
    }

    private fun reuse(old: Bitmap?, w: Int, h: Int): Bitmap {
        if (old != null && old.width == w && old.height == h) {
            old.eraseColor(0)
            return old
        }
        old?.recycle()
        return createBitmap(w, h)
    }

    companion object {
        // Fixed timestep and physics constants — deterministic replay depends
        // on these; changing them re-tunes every existing artwork's bake.
        const val TICK_MS = 33L
        const val DIFFUSE = 0.18f
        const val PIG_DIFFUSE = 0.03f
        const val EVAP = 0.012f
        const val GRAIN = 0.5f
        const val TOOTH_DRAG = 0.55f

        /** Evaporation of a full charge takes ~1/EVAP ticks; calm a bit before. */
        const val CALM_TICKS = 60
    }
}
