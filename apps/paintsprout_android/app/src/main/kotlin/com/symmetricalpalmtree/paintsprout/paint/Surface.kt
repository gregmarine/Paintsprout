package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import androidx.annotation.ColorInt
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * The base layer you paint on. Procedural, ported from the Flutter `surface.dart`.
 * The `IconData` from the Flutter enum is omitted (a UI-shell concern).
 *
 * Note: the procedural noise uses `java.util.Random`, whose sequence differs from
 * Dart's `math.Random`, so the textures won't be byte-identical to the Flutter
 * build — but the recipes (frequencies, weights, seeds-per-feature) match, so
 * each surface reads the same.
 */
enum class SurfaceKind {
    PAPER, CANVAS, METAL, STONE, WOOD, WATERCOLOR, CHALKBOARD, CONCRETE,

    /** A flat, textureless background in a user-chosen color. No tooth. */
    PLAIN;

    val label: String
        get() = when (this) {
            PAPER -> "Paper"
            CANVAS -> "Canvas"
            METAL -> "Metal"
            STONE -> "Stone"
            WOOD -> "Wood"
            WATERCOLOR -> "Watercolor"
            CHALKBOARD -> "Chalkboard"
            CONCRETE -> "Concrete"
            PLAIN -> "Plain"
        }

    /**
     * Logical px spanned by one tooth texel when the surface breaks up a stroke.
     * Tuned so the tooth's feature size matches the drawn texture.
     */
    val toothScale: Float
        get() = when (this) {
            CANVAS -> 0.375f // 8-texel thread -> 6 buffer px == weave
            METAL -> 1.2f // fine brushed grain
            STONE -> 2.0f // coarse, blotchy
            WOOD -> 1.5f // grain-scaled
            WATERCOLOR -> 1.2f // rough cold-press dimples
            CHALKBOARD -> 1.5f // fine, even
            CONCRETE -> 2.0f // coarse grit
            else -> 1.8f // paper
        }
}

/**
 * User-tweakable parameters for the Canvas surface — "not all canvas is the same".
 * Purely the visual (colour) tile; the paint-grab [buildToothField] tooth is
 * unaffected. The defaults reproduce the built-in whitened linen.
 */
data class CanvasParams(
    /** Linen tint before weave shading is applied. */
    @param:ColorInt val tint: Int = DEFAULT_TINT,
    /** Weave prominence: how much brighter thread crowns sit above the troughs. */
    val weave: Float = DEFAULT_WEAVE,
    /** Fibre-speckle amount sprinkled over the weave. */
    val grain: Float = DEFAULT_GRAIN,
) {
    companion object {
        const val DEFAULT_TINT: Int = 0xFFF5F2EA.toInt() // warm primed white
        const val DEFAULT_WEAVE: Float = 0.28f
        const val DEFAULT_GRAIN: Float = 0.04f
    }
}

/** Surfaces wired into the picker so far. */
val AVAILABLE_SURFACES: List<SurfaceKind> = listOf(
    SurfaceKind.PLAIN,
    SurfaceKind.PAPER,
    SurfaceKind.CANVAS,
    SurfaceKind.WATERCOLOR,
    SurfaceKind.WOOD,
    SurfaceKind.STONE,
    SurfaceKind.CONCRETE,
    SurfaceKind.METAL,
    SurfaceKind.CHALKBOARD,
)

/**
 * Builds the visual (color) layer for a surface at [w]x[h] buffer pixels.
 * The Plain surface is a flat fill in [plainColor]; the rest tile a seamless
 * procedural texture.
 */
fun buildSurfaceVisual(
    kind: SurfaceKind,
    w: Int,
    h: Int,
    @ColorInt plainColor: Int = Color.WHITE,
    canvasParams: CanvasParams = CanvasParams(),
): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    if (kind == SurfaceKind.PLAIN) {
        canvas.drawColor(plainColor)
        return out
    }
    val tile = visualTile(kind, canvasParams)
    val paint = Paint().apply {
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    tile.recycle()
    return out
}

private fun visualTile(kind: SurfaceKind, canvas: CanvasParams): Bitmap = when (kind) {
    SurfaceKind.CANVAS -> canvasTile(canvas)
    SurfaceKind.METAL -> metalTile()
    SurfaceKind.STONE -> stoneTile()
    SurfaceKind.WOOD -> woodTile()
    SurfaceKind.WATERCOLOR -> watercolorTile()
    SurfaceKind.CHALKBOARD -> chalkboardTile()
    SurfaceKind.CONCRETE -> concreteTile()
    else -> paperTile() // paper + fallback
}

/**
 * Raw surface tooth: how strongly each texel catches dry media, in [0, 1].
 * 1 = a raised crest that grabs graphite; 0 = a recessed groove that stays bare.
 */
fun buildToothField(kind: SurfaceKind, size: Int): FloatArray {
    val field = FloatArray(size * size)
    when (kind) {
        SurfaceKind.CANVAS -> canvasTooth(field, size)
        SurfaceKind.METAL -> metalTooth(field, size)
        SurfaceKind.STONE -> stoneTooth(field, size)
        SurfaceKind.WOOD -> woodTooth(field, size)
        SurfaceKind.WATERCOLOR -> watercolorTooth(field, size)
        SurfaceKind.CHALKBOARD -> chalkboardTooth(field, size)
        SurfaceKind.CONCRETE -> concreteTooth(field, size)
        else -> paperTooth(field, size)
    }
    return field
}

// --- Tooth fields -----------------------------------------------------------

private fun paperTooth(f: FloatArray, size: Int) {
    val rnd = Random(7) // matches the original pencil-grain seed
    for (i in f.indices) f[i] = rnd.nextDouble().toFloat()
}

private fun canvasTooth(f: FloatArray, size: Int) {
    val period = 8.0 // texels per thread: the weave
    val gritCell = 4
    val gridN = size / gritCell
    val gritRnd = Random(5)
    val grit = DoubleArray(gridN * gridN) { gritRnd.nextDouble() }
    for (y in 0 until size) {
        for (x in 0 until size) {
            val warp = 0.5 - 0.5 * cos(2 * PI * x / period)
            val weft = 0.5 - 0.5 * cos(2 * PI * y / period)
            val over = ((x / period).toInt() + (y / period).toInt()) % 2 == 0
            val crown = if (over) warp else weft
            val g = grit[(y / gritCell) * gridN + (x / gritCell)]
            val t = (0.12 + 0.88 * crown) * (0.45 + 0.55 * g)
            f[y * size + x] = t.coerceIn(0.0, 1.0).toFloat()
        }
    }
}

private fun metalTooth(f: FloatArray, size: Int) {
    val streak = noiseGrid(48, 6, 121)
    val rnd = Random(122)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val s = noise2(streak, 48, 6, x.toDouble() / size * 48, y.toDouble() / size * 6)
            val t = 0.80 + 0.15 * s + 0.05 * rnd.nextDouble()
            f[y * size + x] = t.coerceIn(0.0, 1.0).toFloat()
        }
    }
}

private fun stoneTooth(f: FloatArray, size: Int) {
    val big = noiseGrid(6, 6, 131)
    val mid = noiseGrid(20, 20, 132)
    val rnd = Random(133)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val b = noise2(big, 6, 6, x.toDouble() / size * 6, y.toDouble() / size * 6)
            val m = noise2(mid, 20, 20, x.toDouble() / size * 20, y.toDouble() / size * 20)
            val base = 0.55 * b + 0.3 * m + 0.15 * rnd.nextDouble()
            f[y * size + x] = (0.25 + 0.75 * base).coerceIn(0.0, 1.0).toFloat()
        }
    }
}

private fun woodTooth(f: FloatArray, size: Int) {
    val wave = noiseGrid(4, 8, 141)
    val rnd = Random(142)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val disp = noise2(wave, 4, 8, x.toDouble() / size * 4, y.toDouble() / size * 8)
            val g = 0.5 - 0.5 * cos(2 * PI * (7 * x.toDouble() / size + 0.35 * disp))
            val line = g.pow(2.2)
            val t = (0.45 + 0.4 * (1 - line)) * (0.75 + 0.25 * rnd.nextDouble())
            f[y * size + x] = t.coerceIn(0.0, 1.0).toFloat()
        }
    }
}

private fun watercolorTooth(f: FloatArray, size: Int) {
    val dimple = noiseGrid(22, 22, 151)
    val gritCell = 4
    val gridN = size / gritCell
    val gritRnd = Random(153)
    val grit = DoubleArray(gridN * gridN) { gritRnd.nextDouble() }
    for (y in 0 until size) {
        for (x in 0 until size) {
            val d = noise2(dimple, 22, 22, x.toDouble() / size * 22, y.toDouble() / size * 22)
            val g = grit[(y / gritCell) * gridN + (x / gritCell)]
            val t = (0.1 + 0.9 * d) * (0.45 + 0.55 * g)
            f[y * size + x] = t.coerceIn(0.0, 1.0).toFloat()
        }
    }
}

private fun chalkboardTooth(f: FloatArray, size: Int) {
    val rnd = Random(161)
    for (i in f.indices) f[i] = (0.45 + 0.55 * rnd.nextDouble()).toFloat()
}

private fun concreteTooth(f: FloatArray, size: Int) {
    val stain = noiseGrid(6, 6, 171)
    val rnd = Random(172)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val s = noise2(stain, 6, 6, x.toDouble() / size * 6, y.toDouble() / size * 6)
            val t = 0.2 + 0.8 * (0.4 * s + 0.6 * rnd.nextDouble())
            f[y * size + x] = t.coerceIn(0.0, 1.0).toFloat()
        }
    }
}

// --- Visual tiles -----------------------------------------------------------

private fun paperTile(): Bitmap {
    val size = 256
    val baseR = 0xF6 / 255.0; val baseG = 0xF1 / 255.0; val baseB = 0xE7 / 255.0
    val rnd = Random(11)
    val px = IntArray(size * size)
    for (i in px.indices) {
        val n = rnd.nextDouble()
        val v = 0.92 + 0.08 * n
        px[i] = argb(baseR * v, baseG * v, baseB * v)
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun canvasTile(p: CanvasParams): Bitmap {
    val size = 24
    val half = size / 2.0 / 2.0 // 6px individual thread
    val baseR = Color.red(p.tint) / 255.0
    val baseG = Color.green(p.tint) / 255.0
    val baseB = Color.blue(p.tint) / 255.0
    val floor = 0.72 // darkest thread trough, as a fraction of the tint
    val weave = p.weave.toDouble()
    val grain = p.grain.toDouble()
    val rnd = Random(3)
    val px = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val warpCrown = 0.5 - 0.5 * cos(2 * PI * x / half)
            val weftCrown = 0.5 - 0.5 * cos(2 * PI * y / half)
            val over = ((x / half).toInt() + (y / half).toInt()) % 2 == 0
            val crown = if (over) warpCrown else weftCrown
            val shade = floor + weave * crown + (rnd.nextDouble() - 0.5) * grain
            px[y * size + x] = argb(baseR * shade, baseG * shade, baseB * shade)
        }
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun metalTile(): Bitmap {
    val size = 128
    val baseR = 0.72; val baseG = 0.74; val baseB = 0.78
    val streak = noiseGrid(64, 6, 21)
    val rnd = Random(22)
    val px = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val s = noise2(streak, 64, 6, x.toDouble() / size * 64, y.toDouble() / size * 6)
            val sparkle = (rnd.nextDouble() - 0.5) * 0.05
            val shade = 0.88 + 0.12 * s + sparkle
            px[y * size + x] = argb(baseR * shade, baseG * shade, baseB * shade)
        }
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun stoneTile(): Bitmap {
    val size = 256
    val baseR = 0.55; val baseG = 0.54; val baseB = 0.52
    val big = noiseGrid(5, 5, 31)
    val mid = noiseGrid(16, 16, 32)
    val rnd = Random(33)
    val px = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val b = noise2(big, 5, 5, x.toDouble() / size * 5, y.toDouble() / size * 5)
            val m = noise2(mid, 16, 16, x.toDouble() / size * 16, y.toDouble() / size * 16)
            val grain = (rnd.nextDouble() - 0.5) * 0.06
            val shade = 0.72 + 0.34 * b + 0.12 * (m - 0.5) + grain
            px[y * size + x] = argb(baseR * shade, baseG * shade, baseB * shade)
        }
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun woodTile(): Bitmap {
    val size = 256
    val baseR = 0.80; val baseG = 0.66; val baseB = 0.46
    val wave = noiseGrid(4, 8, 41)
    val rnd = Random(42)
    val px = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val disp = noise2(wave, 4, 8, x.toDouble() / size * 4, y.toDouble() / size * 8)
            val g = 0.5 - 0.5 * cos(2 * PI * (7 * x.toDouble() / size + 0.35 * disp))
            val line = g.pow(2.2)
            val grain = (rnd.nextDouble() - 0.5) * 0.05
            val shade = 0.9 - 0.28 * line + 0.06 * (disp - 0.5) + grain
            px[y * size + x] = argb(baseR * shade, baseG * shade, baseB * shade)
        }
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun watercolorTile(): Bitmap {
    val size = 256
    val baseR = 0.98; val baseG = 0.97; val baseB = 0.93
    val dimple = noiseGrid(27, 27, 51)
    val fine = noiseGrid(61, 61, 52)
    val rnd = Random(53)
    val px = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val d = noise2(dimple, 27, 27, x.toDouble() / size * 27, y.toDouble() / size * 27)
            val fn = noise2(fine, 61, 61, x.toDouble() / size * 61, y.toDouble() / size * 61)
            val grain = (rnd.nextDouble() - 0.5) * 0.03
            val shade = 0.87 + 0.12 * d + 0.06 * (fn - 0.5) + grain
            px[y * size + x] = argb(baseR * shade, baseG * shade, baseB * shade)
        }
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun chalkboardTile(): Bitmap {
    val size = 128
    val baseR = 0.17; val baseG = 0.23; val baseB = 0.20
    val dust = noiseGrid(10, 10, 61)
    val rnd = Random(62)
    val px = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val d = noise2(dust, 10, 10, x.toDouble() / size * 10, y.toDouble() / size * 10)
            val grain = (rnd.nextDouble() - 0.5) * 0.04
            val shade = 0.92 + 0.16 * d + grain
            px[y * size + x] = argb(baseR * shade, baseG * shade, baseB * shade)
        }
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun concreteTile(): Bitmap {
    val size = 256
    val baseR = 0.67; val baseG = 0.66; val baseB = 0.63
    val stain = noiseGrid(6, 6, 71)
    val rnd = Random(72)
    val px = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val s = noise2(stain, 6, 6, x.toDouble() / size * 6, y.toDouble() / size * 6)
            val speck = rnd.nextDouble()
            var shade = 0.84 + 0.14 * s + (speck - 0.5) * 0.10
            if (speck > 0.985) shade -= 0.25 // occasional dark aggregate fleck
            px[y * size + x] = argb(baseR * shade, baseG * shade, baseB * shade)
        }
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

// --- Noise + pixel helpers --------------------------------------------------

/** A [gx]x[gy] grid of random cells for tileable value noise. */
private fun noiseGrid(gx: Int, gy: Int, seed: Int): DoubleArray {
    val rnd = Random(seed.toLong())
    return DoubleArray(gx * gy) { rnd.nextDouble() }
}

/**
 * Samples [g] (a [gx]x[gy] grid) at ([u], [v]) in cell units with smoothstep
 * bilinear interpolation. Wrapping the cell indices keeps the result seamless.
 */
private fun noise2(g: DoubleArray, gx: Int, gy: Int, u: Double, v: Double): Double {
    val x0 = kotlin.math.floor(u).toInt()
    val y0 = kotlin.math.floor(v).toInt()
    val fx = u - x0
    val fy = v - y0
    val x0i = Math.floorMod(x0, gx)
    val y0i = Math.floorMod(y0, gy)
    val x1i = Math.floorMod(x0 + 1, gx)
    val y1i = Math.floorMod(y0 + 1, gy)
    fun s(t: Double) = t * t * (3 - 2 * t)
    val a = g[y0i * gx + x0i]; val b = g[y0i * gx + x1i]
    val c = g[y1i * gx + x0i]; val d = g[y1i * gx + x1i]
    val top = a + (b - a) * s(fx)
    val bot = c + (d - c) * s(fx)
    return top + (bot - top) * s(fy)
}

/** Packs float [0,1] r/g/b into an opaque ARGB int. */
private fun argb(r: Double, g: Double, b: Double): Int {
    val ri = (r * 255).toInt().coerceIn(0, 255)
    val gi = (g * 255).toInt().coerceIn(0, 255)
    val bi = (b * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
}
