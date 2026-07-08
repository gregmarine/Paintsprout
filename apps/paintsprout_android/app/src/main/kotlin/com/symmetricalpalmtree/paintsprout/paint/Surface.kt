package com.symmetricalpalmtree.paintsprout.paint

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.annotation.ColorInt
import java.util.Random
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

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

/**
 * User-tweakable parameters for the Watercolor surface. The paper itself is
 * generated per artwork (see [buildSurfaceVisual]'s seed); these shape its
 * character. Defaults reproduce the built-in cold-press sheet.
 */
data class WatercolorParams(
    /** Paper colour at full tone, before the cold-press shading. */
    @param:ColorInt val tint: Int = DEFAULT_TINT,
    /** Cold-press pit strength — smooth hot-press ↔ rough cold-press. */
    val texture: Float = DEFAULT_TEXTURE,
    /** Broad cloudiness, like a hand-made sheet. */
    val mottle: Float = DEFAULT_MOTTLE,
    /** Fine fibre speckle. */
    val grain: Float = DEFAULT_GRAIN,
) {
    companion object {
        const val DEFAULT_TINT: Int = 0xFFFBF9F4.toInt() // soft natural white
        const val DEFAULT_TEXTURE: Float = 0.12f
        const val DEFAULT_MOTTLE: Float = 0.05f
        const val DEFAULT_GRAIN: Float = 0.035f
    }
}

/**
 * User-tweakable parameters for the Wood surface. The board is generated per
 * artwork (see [buildSurfaceVisual]'s seed); these shape its look. Defaults
 * reproduce the built-in rustic aged-oak board.
 */
data class WoodParams(
    /** Base timber colour. */
    @param:ColorInt val tint: Int = DEFAULT_TINT,
    /** Grain contrast — how pronounced the growth rings are. */
    val grain: Float = DEFAULT_GRAIN,
    /** Grain scale — smaller is finer / more zoomed out, larger is coarser. */
    val scale: Float = DEFAULT_SCALE,
    /** Weathering — broad aged light/dark patchiness. */
    val weathering: Float = DEFAULT_WEATHERING,
) {
    companion object {
        const val DEFAULT_TINT: Int = 0xFFBA9C78.toInt() // muted aged oak
        const val DEFAULT_GRAIN: Float = 0.34f
        const val DEFAULT_SCALE: Float = 0.48f
        const val DEFAULT_WEATHERING: Float = 0.09f
    }
}

/**
 * User-tweakable parameters for the Stone (slate) surface. The slab is generated
 * per artwork (see [buildSurfaceVisual]'s seed); these shape its look. Defaults
 * reproduce the built-in cloudy dark-slate face with fine cracks.
 */
data class StoneParams(
    /** Base slate colour. */
    @param:ColorInt val tint: Int = DEFAULT_TINT,
    /** Mottle — how pronounced the cloudy light/dark blotching is. */
    val mottle: Float = DEFAULT_MOTTLE,
    /** Crack density — how many hairline fractures cross the slab. */
    val cracks: Float = DEFAULT_CRACKS,
    /** Crack contrast — how dark / visible the fractures are. */
    val crackContrast: Float = DEFAULT_CRACK_CONTRAST,
    /** Grain — matte per-pixel grit / fleck strength. */
    val grain: Float = DEFAULT_GRAIN,
) {
    companion object {
        const val DEFAULT_TINT: Int = 0xFF6B7075.toInt() // dark slate grey
        const val DEFAULT_MOTTLE: Float = 0.24f
        const val DEFAULT_CRACKS: Float = 1.0f
        const val DEFAULT_CRACK_CONTRAST: Float = 1.0f
        const val DEFAULT_GRAIN: Float = 0.09f
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
    seed: Long = 0L,
    watercolorParams: WatercolorParams = WatercolorParams(),
    woodParams: WoodParams = WoodParams(),
    stoneParams: StoneParams = StoneParams(),
): Bitmap {
    // Organic surfaces are drawn across the whole buffer (no tiling, so no visible
    // repeat) and are fully determined by [seed] — one seed per artwork.
    if (kind == SurfaceKind.WATERCOLOR) return watercolorFull(w, h, seed, watercolorParams)
    if (kind == SurfaceKind.WOOD) return woodFull(w, h, seed, woodParams)
    if (kind == SurfaceKind.STONE) return stoneFull(w, h, seed, stoneParams)
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

/**
 * Stone across the WHOLE buffer — not a tile — so it never repeats. Fully
 * determined by [seed]: one rock face per artwork.
 *
 * Slate: a dark blue-grey face with irregular cloudy mottling, a fine wispy network
 * of light/dark fracture veins, and a green/warm/cool colour drift — natural riven
 * stone, not ruled bands. Half res + upscale, plus a full-res matte grit pass.
 */
private fun stoneFull(w: Int, h: Int, seed: Long, p: StoneParams): Bitmap {
    val baseR = ((p.tint ushr 16) and 0xFF) / 255.0
    val baseG = ((p.tint ushr 8) and 0xFF) / 255.0
    val baseB = (p.tint and 0xFF) / 255.0
    val mottle = p.mottle.toDouble()
    val grain = p.grain.toDouble()
    val scale = 2
    val lw = (w + scale - 1) / scale
    val lh = (h + scale - 1) / scale
    // Irregular cloudy mottling (multi-octave), gently warped so it never reads as
    // smooth ruled bands — the blotchy slate face.
    val cloudA = seededGrid(lw, lh, 360.0 / scale, 300.0 / scale, seed + 80)
    val cloudB = seededGrid(lw, lh, 140.0 / scale, 120.0 / scale, seed + 81)
    val cloudC = seededGrid(lw, lh, 55.0 / scale, 48.0 / scale, seed + 82)
    val warp = seededGrid(lw, lh, 300.0 / scale, 280.0 / scale, seed + 86)
    val temp = seededGrid(lw, lh, 300.0 / scale, 260.0 / scale, seed + 83) // warm/cool drift
    val hue = seededGrid(lw, lh, 500.0 / scale, 420.0 / scale, seed + 87) // green drift
    val ampW = 44.0 / scale
    val px = IntArray(lw * lh)
    IntStream.range(0, lh).parallel().forEach { y ->
        var i = y * lw
        for (x in 0 until lw) {
            val ws = warp.sample(x, y) - 0.5
            val cloud = 0.55 * (cloudA.sampleF(x + ampW * 0.4 * ws, y + ampW * ws) - 0.5) +
                0.30 * (cloudB.sample(x, y) - 0.5) +
                0.15 * (cloudC.sample(x, y) - 0.5)
            val t = temp.sample(x, y) - 0.5
            val hg = hue.sample(x, y) - 0.5
            val shade = 0.86 + mottle * cloud
            px[i++] = argb(
                baseR * shade * (1.0 + 0.10 * t),
                baseG * shade * (1.0 + 0.06 * hg),
                baseB * shade * (1.0 - 0.08 * t),
            )
        }
    }
    // Upscale, then a quiet full-res grit pass — slate is matte, so gentle per-pixel
    // variation with only the occasional darker fleck (no granite sparkle).
    val small = Bitmap.createBitmap(px, lw, lh, Bitmap.Config.ARGB_8888)
    val scaled = Bitmap.createScaledBitmap(small, w, h, /* filter = */ true)
    small.recycle()
    val out = IntArray(w * h)
    scaled.getPixels(out, 0, w, 0, 0, w, h)
    scaled.recycle()
    IntStream.range(0, h).parallel().forEach { yy ->
        var j = yy * w
        for (xx in 0 until w) {
            val g = hashGrain(xx, yy, seed)
            var f = 1.0 + g * grain
            if (g < -0.46) f -= 0.14 // occasional dark fleck
            out[j] = scalePixel(out[j], f)
            j++
        }
    }
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, w, 0, 0, w, h)
    // A few sparse, open fracture lines drawn as seeded random walks (NOT ridged
    // noise, which always closes into puzzle loops). Thin, faint, occasionally
    // branching — the hairline cracks of a natural slate face.
    drawSlateCracks(Canvas(result), w, h, seed, p.cracks, p.crackContrast)
    return result
}

/**
 * Draws a handful of fine, meandering slate cracks over [canvas]. Deterministic
 * from [seed]. Each crack is a random walk with slight directional drift that may
 * fork; strokes are thin and low-alpha so they read as hairlines, not gouges. A
 * faint light edge gives the crack a little depth against the matte face.
 */
private fun drawSlateCracks(
    canvas: Canvas, w: Int, h: Int, seed: Long, density: Float, contrast: Float,
) {
    if (density <= 0f || contrast <= 0f) return
    val rnd = Random(seed * 6364136223846793005L + 1442695040888963407L)
    val diag = Math.hypot(w.toDouble(), h.toDouble())
    val step = diag * 0.010
    val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val base = ((w.toLong() * h) / 300000L).toInt().coerceIn(7, 20)
    val count = (base * density).toInt().coerceIn(0, 48)
    repeat(count) {
        drawCrack(
            canvas, paint, rnd, contrast, w, h, diag, step,
            rnd.nextDouble() * w, rnd.nextDouble() * h,
            rnd.nextDouble() * 2 * PI,
            diag * (0.10 + 0.30 * rnd.nextDouble()),
            depth = 0,
        )
    }
}

private fun drawCrack(
    canvas: Canvas, paint: Paint, rnd: Random, contrast: Float,
    w: Int, h: Int, diag: Double, step: Double,
    sx: Double, sy: Double, angle0: Double, length: Double, depth: Int,
) {
    var x = sx; var y = sy; var ang = angle0
    val path = Path()
    path.moveTo(x.toFloat(), y.toFloat())
    var traveled = 0.0
    val branches = ArrayList<DoubleArray>()
    while (traveled < length) {
        ang += (rnd.nextDouble() - 0.5) * 0.6 // gentle drift, no sharp turns
        x += cos(ang) * step
        y += sin(ang) * step
        traveled += step
        path.lineTo(x.toFloat(), y.toFloat())
        if (depth < 2 && rnd.nextDouble() < 0.035) {
            branches.add(doubleArrayOf(x, y, ang + if (rnd.nextBoolean()) 0.7 else -0.7))
        }
        if (x < -step || x > w + step || y < -step || y > h + step) break
    }
    // Faint light edge first (depth), then the darker crack core over it.
    paint.color = 0xFFFFFFFF.toInt()
    paint.alpha = ((20 + rnd.nextInt(16)) * contrast).toInt().coerceIn(0, 255)
    paint.strokeWidth = (diag * 0.0018).toFloat().coerceAtLeast(1.4f)
    canvas.drawPath(path, paint)
    paint.color = 0xFF000000.toInt()
    paint.alpha = ((60 + rnd.nextInt(40)) * contrast).toInt().coerceIn(0, 255)
    paint.strokeWidth = (diag * 0.0010).toFloat().coerceAtLeast(1.0f)
    canvas.drawPath(path, paint)
    for (b in branches) {
        drawCrack(canvas, paint, rnd, contrast, w, h, diag, step, b[0], b[1], b[2], length * 0.45, depth + 1)
    }
}

/** Smoothstep from 0 at [a] to 1 at [b]. */
private fun smoothstep(a: Double, b: Double, x: Double): Double {
    val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}

/** Multiplies an opaque ARGB pixel's RGB by [f], clamped. */
private fun scalePixel(c: Int, f: Double): Int {
    val r = (((c ushr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
    val g = (((c ushr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
    val b = ((c and 0xFF) * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Wood across the WHOLE buffer — not a tile — so the grain never repeats. Fully
 * determined by [seed]: one board per artwork.
 *
 * Flat-sawn plank: grain runs down the board as near-vertical lines, given long
 * gentle sweeps (not busy waves) by low-frequency warp. A per-region "weight"
 * field varies each band's darkness AND width — a few bold latewood lines among
 * many faint ones, which is what actually reads as timber — while a spacing field
 * opens and closes the grain. Longitudinal streaks and broad tonal zones break it
 * up; latewood warms to brown. Built at half resolution + upscale, across cores.
 */
private fun woodFull(w: Int, h: Int, seed: Long, p: WoodParams): Bitmap {
    val baseR = Color.red(p.tint) / 255.0
    val baseG = Color.green(p.tint) / 255.0
    val baseB = Color.blue(p.tint) / 255.0
    val scale = 2
    val lw = (w + scale - 1) / scale
    val lh = (h + scale - 1) / scale
    // Grain zoom: <1 shrinks every feature together (zoomed out, finer grain). Scales
    // the band spacing, warp and all cell sizes; curv/ky follow so arches stay in
    // proportion (ring*z with curv,ky fixed shrinks arches the same in both axes).
    val z = p.scale.toDouble() // grain zoom (<1 = finer / zoomed out)
    val s = z / scale // combined feature scale in lowres px
    val pith = seededGrid(lw, lh, 5000.0, 520.0 * s, seed + 65) // gently meandering apex
    val warpB = seededGrid(lw, lh, 320.0 * s, 240.0 * s, seed + 61) // undulation
    val warpC = seededGrid(lw, lh, 150.0 * s, 180.0 * s, seed + 67) // spacing irregularity
    val weight = seededGrid(lw, lh, 280.0 * s, 220.0 * s, seed + 62) // per-region boldness
    val zone = seededGrid(lw, lh, 560.0 * s, 440.0 * s, seed + 63) // heart/sap tone
    val stain = seededGrid(lw, lh, 900.0 * s, 720.0 * s, seed + 68) // broad aged patchiness
    val grit = seededGrid(lw, lh, 40.0 * s, 40.0 * s, seed + 69) // weathered surface roughness
    val streak = seededGrid(lw, lh, 7.0 * s, 340.0 * s, seed + 64) // longitudinal fibre
    val ring = 42.0 * s // constant band spacing (constant => contours can't close into loops)
    val curv = 0.40 / lw // quadratic arch curvature: nested apex-up parabolas
    val ky = 0.42 // vertical advance -> arches stack up the board
    val ampB = 14.0 * s; val ampC = 13.0 * s
    val px = IntArray(lw * lh)
    IntStream.range(0, lh).parallel().forEach { y ->
        var i = y * lw
        for (x in 0 until lw) {
            val pithX = lw * 0.5 + (pith.sample(x, y) - 0.5) * lw * 0.30
            val dx = x - pithX
            val warp = ampB * (warpB.sample(x, y) - 0.5) + ampC * (warpC.sample(x, y) - 0.5)
            // Smooth cathedral field: parabolic arches (dx^2) advancing up the board.
            val field = dx * dx * curv + y * ky + warp
            val band = 0.5 - 0.5 * cos(2 * PI * field / ring)
            val wgt = weight.sample(x, y) // 0..1: region grain boldness
            // Soft, mostly-faint grain: a few defined rings among many whispers (rustic).
            val figure = (band.pow(1.8 + 2.8 * wgt) * (0.06 + 1.1 * wgt)).coerceIn(0.0, 1.0)
            val zoneTone = zone.sample(x, y) - 0.5
            val weather = stain.sample(x, y) - 0.5 // broad aged light/dark patches
            val roughness = grit.sample(x, y) - 0.5 // weathered, matte surface
            val streaks = streak.sample(x, y) - 0.5
            val fleck = hashGrain(x, y, seed) * 0.035 // fine matte speckle
            val shade = 0.93 - p.grain * figure + p.weathering * weather + 0.045 * zoneTone +
                0.035 * roughness + 0.03 * streaks + fleck
            val warm = 1.0 - 0.13 * figure // dark grain leans a little browner
            px[i++] = argb(baseR * shade, baseG * shade, baseB * shade * warm)
        }
    }
    val small = Bitmap.createBitmap(px, lw, lh, Bitmap.Config.ARGB_8888)
    val full = Bitmap.createScaledBitmap(small, w, h, /* filter = */ true)
    small.recycle()
    return full
}

/**
 * Cold-press watercolor paper across the WHOLE buffer — not a repeating tile — so
 * the pitting never reads as a pattern. Fully determined by [seed]: the same seed
 * reproduces the same sheet every render, while a new artwork (new seed) gets a
 * fresh, unique sheet.
 *
 * The texture is layered value noise (fBm) at pixel-fixed feature sizes, so the
 * paper looks the same physical scale at any buffer resolution: a broad tonal
 * mottle like hand-made paper, two octaves of cold-press pits, and fine grain.
 */
private fun watercolorFull(w: Int, h: Int, seed: Long, p: WatercolorParams): Bitmap {
    val baseR = Color.red(p.tint) / 255.0
    val baseG = Color.green(p.tint) / 255.0
    val baseB = Color.blue(p.tint) / 255.0
    val texture = p.texture.toDouble()
    val mottleAmt = p.mottle.toDouble()
    val grainAmt = p.grain.toDouble()
    // The paper is soft, so build the field at half resolution (a quarter of the
    // pixels) and let a filtered upscale smooth it back — visually identical, far
    // cheaper. Feature sizes are given in output px and scaled down to match.
    val scale = 2
    val lw = (w + scale - 1) / scale
    val lh = (h + scale - 1) / scale
    val mottle = seededGrid(lw, lh, 240.0 / scale, seed + 50) // large soft cloudiness
    val dimpleA = seededGrid(lw, lh, 14.0 / scale, seed + 51) // primary cold-press pits
    val dimpleB = seededGrid(lw, lh, 6.0 / scale, seed + 52) // finer secondary pits
    val px = IntArray(lw * lh)
    // Rows are independent (grain is a pure per-pixel hash, not a running stream),
    // so fan them out across all cores.
    IntStream.range(0, lh).parallel().forEach { y ->
        var i = y * lw
        for (x in 0 until lw) {
            val m = mottle.sample(x, y)
            val dimple = 0.65 * dimpleA.sample(x, y) + 0.35 * dimpleB.sample(x, y)
            val grain = hashGrain(x, y, seed) * grainAmt
            // Texture rides both sides of the base: hollows shadow, crests catch light.
            val shade = 0.90 +
                mottleAmt * (m - 0.5) + // gentle cloud
                texture * (dimple - 0.5) + // cold-press pits
                grain
            px[i++] = argb(baseR * shade, baseG * shade, baseB * shade)
        }
    }
    val small = Bitmap.createBitmap(px, lw, lh, Bitmap.Config.ARGB_8888)
    val full = Bitmap.createScaledBitmap(small, w, h, /* filter = */ true)
    small.recycle()
    return full
}

/**
 * A value-noise grid covering the buffer, with cells [cellX]×[cellY] logical px
 * (anisotropic — wood grain wants features stretched along the board). Sized with
 * a 2-cell margin so sampling anywhere in the buffer never needs to wrap, letting
 * [sample] skip the modulo the general [noise2] pays.
 */
private class Grid(val g: DoubleArray, val gx: Int, val cellX: Double, val cellY: Double) {
    fun sample(x: Int, y: Int): Double {
        val u = x / cellX; val v = y / cellY
        val x0 = u.toInt(); val y0 = v.toInt() // u,v >= 0, so toInt() == floor
        val fx = u - x0; val fy = v - y0
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val i = y0 * gx + x0
        val a = g[i]; val b = g[i + 1]
        val c = g[i + gx]; val d = g[i + gx + 1]
        val top = a + (b - a) * sx
        return top + ((c + (d - c) * sx) - top) * sy
    }

    /** Like [sample] but at fractional, possibly domain-warped coords (indices clamped). */
    fun sampleF(fx: Double, fy: Double): Double {
        val gy = g.size / gx
        val u = fx / cellX; val v = fy / cellY
        val x0 = kotlin.math.floor(u).toInt().coerceIn(0, gx - 2)
        val y0 = kotlin.math.floor(v).toInt().coerceIn(0, gy - 2)
        val fxr = (u - x0).coerceIn(0.0, 1.0); val fyr = (v - y0).coerceIn(0.0, 1.0)
        val sx = fxr * fxr * (3 - 2 * fxr); val sy = fyr * fyr * (3 - 2 * fyr)
        val i = y0 * gx + x0
        val a = g[i]; val b = g[i + 1]
        val c = g[i + gx]; val d = g[i + gx + 1]
        val top = a + (b - a) * sx
        return top + ((c + (d - c) * sx) - top) * sy
    }
}

private fun seededGrid(w: Int, h: Int, cellX: Double, cellY: Double, seed: Long): Grid {
    val gx = kotlin.math.ceil(w / cellX).toInt() + 2
    val gy = kotlin.math.ceil(h / cellY).toInt() + 2
    val rnd = Random(seed)
    return Grid(DoubleArray(gx * gy) { rnd.nextDouble() }, gx, cellX, cellY)
}

/** Square-cell convenience for isotropic textures. */
private fun seededGrid(w: Int, h: Int, cellPx: Double, seed: Long): Grid =
    seededGrid(w, h, cellPx, cellPx, seed)

/** Cheap, order-independent per-pixel speckle in [-0.5, 0.5) (parallel-safe). */
private fun hashGrain(x: Int, y: Int, seed: Long): Double {
    var h = (x * 0x1000193) xor (y * 0x1B873593) xor seed.toInt()
    h = (h xor (h ushr 15)) * 0x85EBCA6B.toInt()
    h = (h xor (h ushr 13)) * 0xC2B2AE35.toInt()
    h = h xor (h ushr 16)
    return ((h ushr 8) and 0xFFFF).toDouble() / 65535.0 - 0.5
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
