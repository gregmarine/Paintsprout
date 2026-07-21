package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.pow

/**
 * Surface-tooth textures and their application, ported from the tooth code in
 * the Flutter `stroke.dart` (`_bakeTooth`, `initGrains`, `toothFor`,
 * `_applyTooth`).
 *
 * The surface supplies a raw tooth field (0..1); a tool's response
 * (floor/bias) bakes that into an alpha texture — `alpha = floor + (1-floor) *
 * pow(tooth, bias)` — that breaks up the tool's mark. A pencil on canvas and a
 * pencil on paper are different textures here.
 */
object ToothCache {

    private const val TILE_SIZE = 128

    // (surface, reacting-tool) -> baked alpha texture.
    private val tooth = HashMap<Pair<SurfaceKind, Tool>, Bitmap>()
    private var initialized = false

    /**
     * Pre-bakes every (surface, reacting-tool) tooth texture so switching
     * surfaces mid-drawing is instant. Idempotent. Cheap enough to run once at
     * startup (~40 small textures).
     */
    @Synchronized
    fun init(size: Int = TILE_SIZE) {
        if (initialized) return
        for (surface in SurfaceKind.entries) {
            if (surface == SurfaceKind.PLAIN) continue // flat, no tooth
            val field = buildToothField(surface, size)
            for (tool in Tool.entries) {
                if (ToolProfile.of(tool).reactsToTooth) {
                    tooth[surface to tool] = bakeTooth(field, tool, size)
                }
            }
        }
        initialized = true
    }

    /** The tooth texture for [tool] on [surface], or null if the tool ignores it. */
    fun toothFor(surface: SurfaceKind, tool: Tool): Bitmap? =
        if (ToolProfile.of(tool).reactsToTooth) tooth[surface to tool] else null

    // Raw tooth fields as tileable bitmaps (r = tooth, 1 = crest) — the wet
    // simulation's flow-resistance input, unshaped by any tool response.
    private val rawField = HashMap<SurfaceKind, Bitmap>()

    /** The surface's RAW tooth field as a tileable bitmap for the wet sim. */
    @Synchronized
    fun rawFieldFor(surface: SurfaceKind, size: Int = TILE_SIZE): Bitmap =
        rawField.getOrPut(surface) {
            val px = IntArray(size * size)
            if (surface == SurfaceKind.PLAIN) {
                px.fill(0xFF808080.toInt()) // flat: uniform mid resistance
            } else {
                val field = buildToothField(surface, size)
                for (i in field.indices) {
                    val v = (field[i] * 255f).toInt().coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }

    /**
     * Bakes a surface's raw tooth [field] through a tool's response into a
     * tileable alpha texture (rgb unused; only alpha matters for the dstIn).
     */
    private fun bakeTooth(field: FloatArray, tool: Tool, size: Int): Bitmap {
        val profile = ToolProfile.of(tool)
        val floor = profile.toothFloor
        val bias = profile.toothBias
        val px = IntArray(size * size)
        for (i in field.indices) {
            val n = field[i].toDouble().pow(bias.toDouble())
            val a = floor + (1 - floor) * n
            val ai = (a * 255).toInt().coerceIn(0, 255)
            px[i] = ai shl 24 // black, varying alpha
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Multiplies whatever is in the current layer by the surface [tooth] (dstIn), so
 * the mark is broken up by the surface roughness. [scale] is canvas px per tooth
 * texel (see [SurfaceKind.toothScale] times any supersample).
 */
fun applyTooth(canvas: Canvas, bounds: RectF, tooth: Bitmap, scale: Float) {
    val shader = BitmapShader(tooth, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
        setLocalMatrix(Matrix().apply { setScale(scale, scale) })
    }
    val paint = Paint().apply {
        this.shader = shader
        blendMode = BlendMode.DST_IN
    }
    canvas.drawRect(bounds, paint)
}
