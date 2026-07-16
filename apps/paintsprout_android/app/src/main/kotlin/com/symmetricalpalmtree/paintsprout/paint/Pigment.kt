package com.symmetricalpalmtree.paintsprout.paint

import androidx.annotation.ColorInt

/**
 * Spectral Kubelka-Munk pigment mixing on the CPU — the scalar counterpart of
 * res/raw/pigment_mix.agsl, which does the same thing per-pixel on the GPU.
 *
 * The shader mixes two *textures*; the mixing tray needs to mix a handful of
 * *colours* on every touch-move, where a hardware-canvas round-trip per mix
 * would be absurd. The two implementations must agree — PigmentTest checks a
 * fixed set of mixes against the shader's own arithmetic.
 *
 * The 38-band spectral core is adapted from spectral.js by Ronald van Wijnen,
 * MIT License, Copyright (c) 2025. The constant tables below are extracted
 * verbatim from pigment_mix.agsl so the two can't drift.
 */
object Pigment {

    const val SPECTRAL_SIZE = 38

    private const val GAMMA = 2.4f
    private const val EPSILON = 1e-16f

    /**
     * Below this total concentration the Kubelka-Munk division is meaningless
     * (every dab is either absent or has no reflectance to contribute), so
     * [mix] falls back to a plain weighted average rather than dividing by ~0.
     */
    private const val MIN_CONCENTRATION = 1e-30f

    /**
     * One pigment in a recipe: an sRGB [color] and how much of it is present.
     *
     * Any sRGB colour can be a pigment — the spectral upsampling lifts it to a
     * reflectance curve — so a named tube, a colour picked off the wheel, and a
     * pixel scraped off the canvas are all the same thing here.
     *
     * @param amount relative quantity. Only ratios matter, not absolute scale:
     *   doubling every amount in a recipe leaves the mixed colour unchanged.
     */
    data class Dab(@param:ColorInt val color: Int, val amount: Float)

    /**
     * The colour a recipe reads as once its pigments are mixed.
     *
     * This generalizes the shader's two-colour `pigmentMix` to N dabs: the
     * Kubelka-Munk mixing loop sums each pigment's K/S weighted by
     * concentration and divides by the total, which extends to any number of
     * terms. Mixing N at once is exact and order-independent — repeatedly
     * mixing pairwise is neither, and would drift as you dab a third colour
     * into a well that already holds two.
     *
     * Returns an opaque colour, or transparent for an empty/weightless recipe.
     * Input alpha is ignored; quantity lives in [Dab.amount].
     */
    @ColorInt
    fun mix(dabs: List<Dab>): Int {
        if (dabs.isEmpty()) return 0
        if (dabs.size == 1) {
            // A lone pigment still round-trips through reflectance so a
            // one-dab well and a two-dab well are on the same footing.
            if (dabs[0].amount <= 0f) return 0
        }

        val n = dabs.size
        val reflectances = arrayOfNulls<FloatArray>(n)
        val concentrations = FloatArray(n)
        var total = 0f

        for (i in 0 until n) {
            val dab = dabs[i]
            val amount = if (dab.amount > 0f) dab.amount else 0f
            val r = linearToReflectance(srgbToLinear(dab.color))
            reflectances[i] = r
            // Weighted by amount squared times reflected luminance, matching
            // pigment_mix.agsl:176-177 — a dark pigment dominates a light one
            // at equal quantity, as real paint does.
            concentrations[i] = amount * amount * reflectanceToXyz(r)[1]
            total += concentrations[i]
        }

        if (total < MIN_CONCENTRATION) return averageColor(dabs)

        val mixed = FloatArray(SPECTRAL_SIZE)
        for (band in 0 until SPECTRAL_SIZE) {
            var ks = 0f
            for (i in 0 until n) {
                ks += ks(reflectances[i]!![band]) * concentrations[i]
            }
            mixed[band] = km(ks / total)
        }

        return xyzToSrgb(reflectanceToXyz(mixed))
    }

    /** Convenience for the common two-pigment case. */
    @ColorInt
    fun mix(@ColorInt a: Int, amountA: Float, @ColorInt b: Int, amountB: Float): Int =
        mix(listOf(Dab(a, amountA), Dab(b, amountB)))

    /**
     * Fallback when a recipe carries no reflectance to mix (e.g. every amount
     * is zero). Averages in linear light so it degrades gracefully instead of
     * returning a NaN colour.
     */
    @ColorInt
    private fun averageColor(dabs: List<Dab>): Int {
        var r = 0f; var g = 0f; var b = 0f; var w = 0f
        for (dab in dabs) {
            val amount = if (dab.amount > 0f) dab.amount else 0f
            val lin = srgbToLinear(dab.color)
            r += lin[0] * amount; g += lin[1] * amount; b += lin[2] * amount
            w += amount
        }
        if (w <= 0f) {
            // Weightless: fall back to an unweighted mean so the well still
            // shows something sensible rather than black.
            for (dab in dabs) {
                val lin = srgbToLinear(dab.color)
                r += lin[0]; g += lin[1]; b += lin[2]
            }
            w = dabs.size.toFloat()
        }
        return packSrgb(linearToSrgb(r / w), linearToSrgb(g / w), linearToSrgb(b / w))
    }

    // --- sRGB <-> linear ---------------------------------------------------

    private fun uncompand(x: Float): Float =
        if (x < 0.04045f) x / 12.92f else Math.pow(((x + 0.055f) / 1.055f).toDouble(), GAMMA.toDouble()).toFloat()

    private fun compand(x: Float): Float =
        if (x < 0.0031308f) x * 12.92f
        else 1.055f * Math.pow(x.toDouble(), 1.0 / GAMMA).toFloat() - 0.055f

    /** Unpacks ARGB by hand: android.graphics.Color would break JVM unit tests. */
    private fun srgbToLinear(@ColorInt color: Int): FloatArray = floatArrayOf(
        uncompand(((color shr 16) and 0xFF) / 255f),
        uncompand(((color shr 8) and 0xFF) / 255f),
        uncompand((color and 0xFF) / 255f),
    )

    private fun linearToSrgb(x: Float): Float = compand(x).coerceIn(0f, 1f)

    @ColorInt
    private fun packSrgb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or
            (Math.round(r * 255f) shl 16) or
            (Math.round(g * 255f) shl 8) or
            Math.round(b * 255f)

    // --- spectral ----------------------------------------------------------

    /**
     * Lifts a linear RGB colour to a 38-band reflectance curve by decomposing
     * it into white/cyan/magenta/yellow/red/green/blue primaries (Scott Burns'
     * method, as implemented by spectral.js). Port of
     * spectral_linear_to_reflectance, pigment_mix.agsl:41.
     */
    private fun linearToReflectance(lrgb: FloatArray): FloatArray {
        val white = minOf(lrgb[0], lrgb[1], lrgb[2])
        val lr = lrgb[0] - white
        val lg = lrgb[1] - white
        val lb = lrgb[2] - white

        val cyan = minOf(lg, lb)
        val magenta = minOf(lr, lb)
        val yellow = minOf(lr, lg)

        val red = minOf(maxOf(0f, lr - lb), maxOf(0f, lr - lg))
        val green = minOf(maxOf(0f, lg - lb), maxOf(0f, lg - lr))
        val blue = minOf(maxOf(0f, lb - lg), maxOf(0f, lb - lr))

        val out = FloatArray(SPECTRAL_SIZE)
        for (band in 0 until SPECTRAL_SIZE) {
            val i = band * 7
            out[band] = maxOf(
                EPSILON,
                white * REFLECTANCE[i] +
                    cyan * REFLECTANCE[i + 1] +
                    magenta * REFLECTANCE[i + 2] +
                    yellow * REFLECTANCE[i + 3] +
                    red * REFLECTANCE[i + 4] +
                    green * REFLECTANCE[i + 5] +
                    blue * REFLECTANCE[i + 6],
            )
        }
        return out
    }

    /** Port of spectral_reflectance_to_xyz, pigment_mix.agsl:108. */
    private fun reflectanceToXyz(r: FloatArray): FloatArray {
        var x = 0f; var y = 0f; var z = 0f
        for (band in 0 until SPECTRAL_SIZE) {
            val i = band * 3
            x += r[band] * CIE[i]
            y += r[band] * CIE[i + 1]
            z += r[band] * CIE[i + 2]
        }
        return floatArrayOf(x, y, z)
    }

    /** Port of spectral_xyz_to_srgb, pigment_mix.agsl:94. */
    @ColorInt
    private fun xyzToSrgb(xyz: FloatArray): Int {
        val x = xyz[0]; val y = xyz[1]; val z = xyz[2]
        val r = 3.2409699419045200f * x - 1.537383177570090f * y - 0.4986107602930030f * z
        val g = -0.9692436362808790f * x + 1.875967501507720f * y + 0.0415550574071756f * z
        val b = 0.0556300796969936f * x - 0.203976958888976f * y + 1.0569715142428700f * z
        return packSrgb(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b))
    }

    private fun ks(r: Float): Float = (1f - r) * (1f - r) / (2f * r)

    /**
     * Kubelka-Munk in its stable reciprocal form. The algebraically equivalent
     * `1 + ks - sqrt(ks*ks + 2*ks)` catastrophically cancels in 32-bit float
     * for near-black pigment (where ks is huge) and speckles the result with
     * NaN. spectral.js gets away with the direct form only because JS runs in
     * doubles. Port of spectral_KM, pigment_mix.agsl:157 — keep this form.
     */
    private fun km(ks: Float): Float =
        1f / (1f + ks + Math.sqrt((ks * ks + 2f * ks).toDouble()).toFloat())

    // --- constants (extracted verbatim from res/raw/pigment_mix.agsl) -------

    // w, c, m, y, r, g, b coefficients per band.
    private val REFLECTANCE = floatArrayOf(
        1.0011607271876400f, 0.9705850013229620f, 0.9906735573199880f, 0.0210523371789306f, 0.0315605737777207f, 0.0095560747554212f, 0.9794047525020140f,
        1.0011606515972800f, 0.9705924981434250f, 0.9906715249619790f, 0.0210564627517414f, 0.0315520718330149f, 0.0095581580120851f, 0.9794007068431300f,
        1.0011603192274700f, 0.9706253487298910f, 0.9906625823534210f, 0.0210746178695038f, 0.0315148215513658f, 0.0095673245444588f, 0.9793829034702610f,
        1.0011586727078900f, 0.9707868061190170f, 0.9906181076447950f, 0.0211649058448753f, 0.0313318044982702f, 0.0096129126297349f, 0.9792943649455940f,
        1.0011525984455200f, 0.9713686732282480f, 0.9904514808787100f, 0.0215027957272504f, 0.0306729857725527f, 0.0097837090401843f, 0.9789630146085700f,
        1.0011325252899800f, 0.9731632306212520f, 0.9898710814002040f, 0.0226738799041561f, 0.0286480476989607f, 0.0103786227058710f, 0.9778144666940430f,
        1.0010850066332700f, 0.9767402231587650f, 0.9882866087596400f, 0.0258235649693629f, 0.0246450407045709f, 0.0120026452378567f, 0.9747243211338360f,
        1.0009968788945300f, 0.9815876054913770f, 0.9842906927975040f, 0.0334879385639851f, 0.0192960753663651f, 0.0160977721473922f, 0.9671984823439730f,
        1.0008652515227400f, 0.9862802656529490f, 0.9739349056253060f, 0.0519069663740307f, 0.0142066612220556f, 0.0267061902231680f, 0.9490796575305750f,
        1.0006962900094000f, 0.9899491476891340f, 0.9418178384601450f, 0.1007490148334730f, 0.0102942608878609f, 0.0595555440185881f, 0.9008501289409770f,
        1.0005049611488800f, 0.9924927015384200f, 0.8173903261951560f, 0.2391298997068470f, 0.0076191460521811f, 0.1860398265328260f, 0.7631504454622400f,
        1.0003080818799200f, 0.9941456804052560f, 0.4324728050657290f, 0.5348043122727480f, 0.0058980410835420f, 0.5705798201161590f, 0.4659221716493190f,
        1.0001196660201300f, 0.9951839750332120f, 0.1384539782588700f, 0.7978075786430300f, 0.0048233247781713f, 0.8614677684002920f, 0.2012632804510050f,
        0.9999527659684070f, 0.9957567501108180f, 0.0537347216940033f, 0.9114498940673840f, 0.0042298748350633f, 0.9458790897676580f, 0.0877524413419623f,
        0.9998218368992970f, 0.9959128182867100f, 0.0292174996673231f, 0.9537979630045070f, 0.0040599171299341f, 0.9704654864743050f, 0.0457176793291679f,
        0.9997386095575930f, 0.9956061578345280f, 0.0213136517508590f, 0.9712416154654290f, 0.0043533695594676f, 0.9784136302844500f, 0.0284706050521843f,
        0.9997095516396120f, 0.9945976009618540f, 0.0201349530181136f, 0.9793031238075880f, 0.0053434425970201f, 0.9795890314112240f, 0.0205271767569850f,
        0.9997319302106270f, 0.9922157154923700f, 0.0241323096280662f, 0.9833801195075750f, 0.0076917201010463f, 0.9755335369086320f, 0.0165302792310211f,
        0.9997994363461950f, 0.9862364527832490f, 0.0372236145223627f, 0.9854612465677550f, 0.0135969795736536f, 0.9622887553978130f, 0.0145135107212858f,
        0.9999003303166710f, 0.9679433372645410f, 0.0760506552706601f, 0.9864350469766050f, 0.0316975442661115f, 0.9231215745131200f, 0.0136003508637687f,
        1.0000204065261100f, 0.8912850042449430f, 0.2053754719423990f, 0.9867382506701410f, 0.1078611963552490f, 0.7934340189431110f, 0.0133604258769571f,
        1.0001447879365800f, 0.5362024778620530f, 0.5412689034604390f, 0.9866178824450320f, 0.4638126031687040f, 0.4592701359024290f, 0.0135488943145680f,
        1.0002599790341200f, 0.1541081190018780f, 0.8158416850864860f, 0.9862777767586430f, 0.8470554052720110f, 0.1855741036663030f, 0.0139594356366992f,
        1.0003557969708900f, 0.0574575093228929f, 0.9128177041239760f, 0.9858605924440560f, 0.9431854093939180f, 0.0881774959955372f, 0.0144434255753570f,
        1.0004275378026900f, 0.0315349873107007f, 0.9463398301669620f, 0.9854749276762100f, 0.9688621506965580f, 0.0543630228766700f, 0.0148854440621406f,
        1.0004762334488800f, 0.0222633920086335f, 0.9599276963319910f, 0.9851769347655580f, 0.9780306674736030f, 0.0406288447060719f, 0.0152254296999746f,
        1.0005072096750800f, 0.0182022841492439f, 0.9662605952303120f, 0.9849715740141810f, 0.9820436438543060f, 0.0342215204316970f, 0.0154592848180209f,
        1.0005251915637300f, 0.0162990559732640f, 0.9693259700584240f, 0.9848463034157120f, 0.9839236237187070f, 0.0311185790956966f, 0.0156018026485961f,
        1.0005350960689600f, 0.0153656239334613f, 0.9708545367213990f, 0.9847753518111990f, 0.9848454841543820f, 0.0295708898336134f, 0.0156824871281936f,
        1.0005402209748200f, 0.0149111568733976f, 0.9716050665281280f, 0.9847380666252650f, 0.9852942758145960f, 0.0288108739348928f, 0.0157248764360615f,
        1.0005427281678400f, 0.0146954339898235f, 0.9719627697573920f, 0.9847196483117650f, 0.9855072952198250f, 0.0284486271324597f, 0.0157458108784121f,
        1.0005438956908700f, 0.0145964146717719f, 0.9721272722745090f, 0.9847110233919390f, 0.9856050715398370f, 0.0282820301724731f, 0.0157556123350225f,
        1.0005444821215100f, 0.0145470156699655f, 0.9722094177458120f, 0.9847066833006760f, 0.9856538499335780f, 0.0281988376490237f, 0.0157605443964911f,
        1.0005447695999200f, 0.0145228771899495f, 0.9722495776784240f, 0.9847045543930910f, 0.9856776850338830f, 0.0281581655342037f, 0.0157629637515278f,
        1.0005448988776200f, 0.0145120341118965f, 0.9722676219987420f, 0.9847035963093700f, 0.9856883918061220f, 0.0281398910216386f, 0.0157640525629106f,
        1.0005449625468900f, 0.0145066940939832f, 0.9722765094621500f, 0.9847031240775520f, 0.9856936646900310f, 0.0281308901665811f, 0.0157645892329510f,
        1.0005449892705800f, 0.0145044507314479f, 0.9722802433068740f, 0.9847029256150900f, 0.9856958798482050f, 0.0281271086805816f, 0.0157648147772649f,
        1.0005449969930000f, 0.0145038009464639f, 0.9722813248265600f, 0.9847028681227950f, 0.9856965214637620f, 0.0281260133612096f, 0.0157648801149616f,
    )

    // CIE X, Y, Z weights per band.
    private val CIE = floatArrayOf(
        0.0000646919989576f, 0.0000018442894440f, 0.0003050171476380f,
        0.0002194098998132f, 0.0000062053235865f, 0.0010368066663574f,
        0.0011205743509343f, 0.0000310096046799f, 0.0053131363323992f,
        0.0037666134117111f, 0.0001047483849269f, 0.0179543925899536f,
        0.0118805536037990f, 0.0003536405299538f, 0.0570775815345485f,
        0.0232864424191771f, 0.0009514714056444f, 0.1136516189362870f,
        0.0345594181969747f, 0.0022822631748318f, 0.1733587261835500f,
        0.0372237901162006f, 0.0042073290434730f, 0.1962065755586570f,
        0.0324183761091486f, 0.0066887983719014f, 0.1860823707062960f,
        0.0212332056093810f, 0.0098883960193565f, 0.1399504753832070f,
        0.0104909907685421f, 0.0152494514496311f, 0.0891745294268649f,
        0.0032958375797931f, 0.0214183109449723f, 0.0478962113517075f,
        0.0005070351633801f, 0.0334229301575068f, 0.0281456253957952f,
        0.0009486742057141f, 0.0513100134918512f, 0.0161376622950514f,
        0.0062737180998318f, 0.0704020839399490f, 0.0077591019215214f,
        0.0168646241897775f, 0.0878387072603517f, 0.0042961483736618f,
        0.0286896490259810f, 0.0942490536184085f, 0.0020055092122156f,
        0.0426748124691731f, 0.0979566702718931f, 0.0008614711098802f,
        0.0562547481311377f, 0.0941521856862608f, 0.0003690387177652f,
        0.0694703972677158f, 0.0867810237486753f, 0.0001914287288574f,
        0.0830531516998291f, 0.0788565338632013f, 0.0001495555858975f,
        0.0861260963002257f, 0.0635267026203555f, 0.0000923109285104f,
        0.0904661376847769f, 0.0537414167568200f, 0.0000681349182337f,
        0.0850038650591277f, 0.0426460643574120f, 0.0000288263655696f,
        0.0709066691074488f, 0.0316173492792708f, 0.0000157671820553f,
        0.0506288916373645f, 0.0208852059213910f, 0.0000039406041027f,
        0.0354739618852640f, 0.0138601101360152f, 0.0000015840125870f,
        0.0214682102597065f, 0.0081026402038399f, 0.0000000000000000f,
        0.0125164567619117f, 0.0046301022588030f, 0.0000000000000000f,
        0.0068045816390165f, 0.0024913800051319f, 0.0000000000000000f,
        0.0034645657946526f, 0.0012593033677378f, 0.0000000000000000f,
        0.0014976097506959f, 0.0005416465221680f, 0.0000000000000000f,
        0.0007697004809280f, 0.0002779528920067f, 0.0000000000000000f,
        0.0004073680581315f, 0.0001471080673854f, 0.0000000000000000f,
        0.0001690104031614f, 0.0000610327472927f, 0.0000000000000000f,
        0.0000952245150365f, 0.0000343873229523f, 0.0000000000000000f,
        0.0000490309872958f, 0.0000177059860053f, 0.0000000000000000f,
        0.0000199961492222f, 0.0000072209749130f, 0.0000000000000000f,
    )
}
