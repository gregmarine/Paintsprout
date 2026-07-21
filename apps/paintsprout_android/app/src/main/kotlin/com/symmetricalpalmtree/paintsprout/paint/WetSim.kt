package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap

/**
 * The wet watercolor simulation (Phase 3): a wetness field and a pigment field
 * confined to a crop of the canvas buffer, stepped at a FIXED timestep by the
 * wet_water/wet_pigment tick shaders through the pooled [GpuRender].
 *
 * The two fields live in separate OPAQUE ping-pong textures (water: r =
 * wetness; pigment: rgb = per-channel absorbance). They cannot share one
 * texture: Android bitmaps are premultiplied, so pigment stored under a
 * wetness alpha would be crushed to zero as the sheet dries — exactly when
 * stranded pigment must persist as the darkened edge. Absorbance (rather than
 * colour-scaled concentration) also keeps dark pigments depositing: pure black
 * carried as `colour x amount` would deposit exactly nothing.
 *
 * The brush deposits STAMPS into the fields (no ribbon geometry — hairpin
 * folds and stroke-start seams are structurally impossible); the ticks then
 * move water and pigment. [presentShader] / [presentToBitmap] turn the pigment
 * field into the premultiplied wash the spectral pigment mixer composites.
 *
 * Determinism is the contract that makes undo work: the sequence
 * begin → (stamp* → tick)* replayed with the same inputs yields the same
 * fields, so the bake re-runs exactly what the live preview showed. No clocks,
 * no randomness — callers drive ticks by their own recorded schedule. Tooth is
 * sampled in CANVAS coordinates (the crop offset is folded into the shader
 * matrix), so a replay over a different crop still reads the same paper.
 */
class WetSim(waterAgsl: String, pigmentAgsl: String, presentAgsl: String) {

    private val waterShader = RuntimeShader(waterAgsl)
    private val pigmentShader = RuntimeShader(pigmentAgsl)
    private val present = RuntimeShader(presentAgsl)

    private var waterA: Bitmap? = null
    private var waterB: Bitmap? = null
    private var pigA: Bitmap? = null
    private var pigB: Bitmap? = null
    private var flip = false
    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val tickPaint = Paint()

    /** The crop of the canvas buffer this field covers. */
    var crop: Rect = Rect()
        private set

    /** Ticks run since the last deposit — the calm/dry heuristic. */
    var ticksSinceDeposit = 0
        private set

    private val water: Bitmap? get() = if (flip) waterB else waterA
    private val pigment: Bitmap? get() = if (flip) pigB else pigA

    /** (Re)starts the sim over [cropRect], dropping any previous fields. */
    fun begin(cropRect: Rect) {
        crop = Rect(cropRect)
        val w = cropRect.width().coerceAtLeast(1)
        val h = cropRect.height().coerceAtLeast(1)
        waterA = reuse(waterA, w, h)
        waterB = reuse(waterB, w, h)
        pigA = reuse(pigA, w, h)
        pigB = reuse(pigB, w, h)
        flip = false
        ticksSinceDeposit = 0
    }

    /**
     * Enlarges the field to [newCrop] (which must contain the current crop),
     * carrying the current state over at its canvas position. The live preview
     * grows the crop as the stroke wanders; the bake replays over the final
     * crop from the start, so the two see slightly different clamp boundaries —
     * negligible, but it is why live == bake is near-exact, not bit-exact.
     */
    fun grow(newCrop: Rect) {
        if (newCrop == crop) return
        val oldWater = water
        val oldPig = pigment
        val ox = (crop.left - newCrop.left).toFloat()
        val oy = (crop.top - newCrop.top).toFloat()
        val w = newCrop.width().coerceAtLeast(1)
        val h = newCrop.height().coerceAtLeast(1)
        val newWaterA = createBitmap(w, h).apply { eraseColor(OPAQUE_ZERO) }
        val newPigA = createBitmap(w, h).apply { eraseColor(OPAQUE_ZERO) }
        if (oldWater != null) Canvas(newWaterA).drawBitmap(oldWater, ox, oy, null)
        if (oldPig != null) Canvas(newPigA).drawBitmap(oldPig, ox, oy, null)
        waterA?.recycle(); waterB?.recycle(); pigA?.recycle(); pigB?.recycle()
        waterA = newWaterA
        pigA = newPigA
        // The B pair is written edge-to-edge by the next tick; no need to clear.
        waterB = createBitmap(w, h)
        pigB = createBitmap(w, h)
        flip = false
        crop = Rect(newCrop)
    }

    /**
     * Deposits one brush stamp at canvas-buffer position ([x], [y]): a soft
     * disc of water carrying pigment. [wetness] in [0,1]; [pigment] scales the
     * deposited absorbance (0 = plain water — the water mode). ADD blending, so
     * overlapping stamps pool water and pigment instead of overwriting.
     */
    fun stamp(x: Float, y: Float, radius: Float, @ColorInt color: Int, wetness: Float, pigment: Float) {
        val waterDst = water ?: return
        val pigDst = this.pigment ?: return
        val cx = x - crop.left
        val cy = y - crop.top
        val r = radius.coerceAtLeast(1f)
        drawStamp(waterDst, cx, cy, r, Color.argb((wetness * 255f).toInt().coerceIn(0, 255), 255, 255, 255))
        if (pigment > 0f) {
            // Absorbance: what this colour REMOVES from white, scaled by amount.
            val ar = 255 - Color.red(color)
            val ag = 255 - Color.green(color)
            val ab = 255 - Color.blue(color)
            drawStamp(pigDst, cx, cy, r, Color.argb((pigment * 255f).toInt().coerceIn(0, 255), ar, ag, ab))
        }
        ticksSinceDeposit = 0
    }

    /**
     * Stamps one captured stroke point — THE deposition recipe, shared by the
     * live path and the bake replay so the two cannot drift apart. [scale] is
     * buffer px per canvas px (the supersample). A wetter brush (higher load)
     * lays more water; pigment follows the point's density and drains with the
     * load. [waterMode] deposits water only.
     */
    fun stampPoint(p: StrokePoint, @ColorInt fallbackColor: Int, scale: Float, waterMode: Boolean = false) {
        val color = if (p.color == INHERIT_COLOR) fallbackColor else p.color
        stamp(
            p.position.x * scale, p.position.y * scale, (p.width * scale) / 2f, color,
            wetness = STAMP_WET * (0.4f + 0.6f * p.load),
            pigment = if (waterMode) 0f else STAMP_PIG * p.density * p.load,
        )
    }

    /**
     * Advances the fields one fixed timestep: a water pass and a pigment pass
     * (both reading the pre-tick water) into the other buffers, then swap.
     * [tooth] is the paper's raw tileable tooth field; [toothScale] its canvas
     * px per texel — sampling is canvas-anchored via the crop offset.
     */
    fun tick(tooth: Bitmap, toothScale: Float) {
        val waterSrc = water ?: return
        val pigSrc = pigment ?: return
        val waterDst = (if (flip) waterA else waterB) ?: return
        val pigDst = (if (flip) pigA else pigB) ?: return

        val toothShader = BitmapShader(tooth, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply {
                setScale(toothScale, toothScale)
                postTranslate(-crop.left.toFloat(), -crop.top.toFloat())
            })
        }
        val waterSrcShader = BitmapShader(waterSrc, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        waterShader.setInputShader("uWater", waterSrcShader)
        waterShader.setInputShader("uTooth", toothShader)
        waterShader.setFloatUniform("uDiffuse", DIFFUSE)
        waterShader.setFloatUniform("uEvap", EVAP)
        waterShader.setFloatUniform("uToothDrag", TOOTH_DRAG)
        tickPaint.shader = waterShader
        GpuRender.renderInto(waterDst) { c ->
            c.drawRect(0f, 0f, waterDst.width.toFloat(), waterDst.height.toFloat(), tickPaint)
        }

        pigmentShader.setInputShader("uPig", BitmapShader(pigSrc, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        pigmentShader.setInputShader("uWater", waterSrcShader)
        pigmentShader.setInputShader("uTooth", toothShader)
        pigmentShader.setFloatUniform("uPigDiffuse", PIG_DIFFUSE)
        pigmentShader.setFloatUniform("uFlux", FLUX)
        pigmentShader.setFloatUniform("uGrain", GRAIN)
        pigmentShader.setFloatUniform("uToothDrag", TOOTH_DRAG)
        tickPaint.shader = pigmentShader
        GpuRender.renderInto(pigDst) { c ->
            c.drawRect(0f, 0f, pigDst.width.toFloat(), pigDst.height.toFloat(), tickPaint)
        }

        flip = !flip
        ticksSinceDeposit++
    }

    /** Whether the field has calmed enough to dry and bake. */
    val isCalm: Boolean get() = ticksSinceDeposit >= CALM_TICKS

    /**
     * The pigment field presented as a premultiplied wash, anchored in canvas
     * buffer coordinates — pluggable straight into the pigment mixer's uWash.
     * Valid until the next tick/grow/begin.
     */
    fun presentShader(): RuntimeShader {
        val pig = pigment ?: throw IllegalStateException("present before begin")
        present.setInputShader(
            "uPig",
            BitmapShader(pig, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(crop.left.toFloat(), crop.top.toFloat()) })
            },
        )
        present.setFloatUniform("uK", PRESENT_K)
        return present
    }

    /** [presentShader] rendered into a crop-sized bitmap (for the bake mixer). */
    fun presentToBitmap(): Bitmap {
        val shader = presentShader()
        val paint = Paint().apply { this.shader = shader }
        val w = crop.width().coerceAtLeast(1)
        val h = crop.height().coerceAtLeast(1)
        val l = crop.left.toFloat()
        val t = crop.top.toFloat()
        return GpuRender.renderToBitmap(w, h) { c ->
            c.translate(-l, -t)
            c.drawRect(l, t, l + w, t + h, paint)
        }
    }

    fun release() {
        waterA?.recycle(); waterA = null
        waterB?.recycle(); waterB = null
        pigA?.recycle(); pigA = null
        pigB?.recycle(); pigB = null
    }

    private fun drawStamp(dst: Bitmap, cx: Float, cy: Float, r: Float, @ColorInt color: Int) {
        stampPaint.shader = RadialGradient(
            cx, cy, r, intArrayOf(color, color and 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
        Canvas(dst).drawCircle(cx, cy, r, stampPaint)
    }

    private fun reuse(old: Bitmap?, w: Int, h: Int): Bitmap {
        if (old != null && old.width == w && old.height == h) {
            old.eraseColor(OPAQUE_ZERO)
            return old
        }
        old?.recycle()
        return createBitmap(w, h).apply { eraseColor(OPAQUE_ZERO) }
    }

    companion object {
        /** Opaque black: zero wetness / zero absorbance, premultiplication-proof. */
        private const val OPAQUE_ZERO = 0xFF000000.toInt()

        // Fixed timestep and physics constants — deterministic replay depends
        // on these; changing them re-tunes every existing artwork's bake.
        const val TICK_MS = 33L
        const val DIFFUSE = 0.18f
        const val FLUX = 0.18f
        const val PIG_DIFFUSE = 0.03f
        const val EVAP = 0.012f
        const val GRAIN = 0.5f
        const val TOOTH_DRAG = 0.55f

        /** Water laid per stamp (scaled by brush load). */
        const val STAMP_WET = 0.55f

        /** Absorbance laid per stamp (scaled by density and load). */
        const val STAMP_PIG = 0.12f

        /** Opacity a fully saturated wash presents at: 1 - exp(-K). */
        const val PRESENT_K = 1.4f

        /** Evaporation of a full charge takes ~1/EVAP ticks; calm a bit before. */
        const val CALM_TICKS = 60
    }
}
