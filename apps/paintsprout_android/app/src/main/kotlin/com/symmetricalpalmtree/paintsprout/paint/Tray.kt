package com.symmetricalpalmtree.paintsprout.paint

import androidx.annotation.ColorInt

/**
 * How much of each pigment is present, independent of where it sits — the
 * shared currency of the tray, the mixing well, and the brush.
 *
 * Amounts are relative: only ratios affect [color] (see Pigment.mix), so a
 * recipe scaled up or down reads as exactly the same colour. That property is
 * what lets a brush's load and its remaining volume be the same object.
 *
 * Pigments coalesce by colour, so dragging through a well — or dragging a dirty
 * brush across the canvas, thousands of samples deep — can't grow the recipe
 * without bound.
 */
class Recipe private constructor(
    private val amounts: Map<Int, Float>,
    knownColor: Int? = null,
) {

    /**
     * Memoised rather than recomputed: mixing is a 38-band spectral operation,
     * and a draining brush asks for its colour on every stylus sample.
     */
    private var colorCache: Int? = knownColor

    /** The colour this recipe reads as once mixed. */
    @get:ColorInt
    val color: Int
        get() = colorCache ?: Pigment.mix(dabs).also { colorCache = it }

    /** How much pigment there is in total, in whatever unit the caller dabs in. */
    val total: Float by lazy { amounts.values.sum() }

    val dabs: List<Pigment.Dab> by lazy {
        amounts.map { (color, amount) -> Pigment.Dab(color, amount) }
    }

    val isEmpty: Boolean get() = amounts.isEmpty()

    /** How many distinct pigments are in play — what the UI labels a mix by. */
    val pigmentCount: Int get() = amounts.size

    /** Adds pigment, merging into an existing entry of the same colour. */
    fun plus(@ColorInt color: Int, amount: Float): Recipe {
        if (amount <= 0f) return this
        val next = LinkedHashMap(amounts)
        next[color] = (next[color] ?: 0f) + amount
        return Recipe(next)
    }

    fun plus(other: Recipe): Recipe {
        if (other.isEmpty) return this
        val next = LinkedHashMap(amounts)
        for ((color, amount) in other.amounts) {
            next[color] = (next[color] ?: 0f) + amount
        }
        return Recipe(next)
    }

    /**
     * The same mixture in a different quantity — every pigment scaled by the
     * same factor, so [color] is untouched. Used to drain a brush as it paints.
     */
    fun scaledTo(newTotal: Float): Recipe {
        if (newTotal <= 0f) return EMPTY
        val current = total
        if (current <= 0f) return EMPTY
        val factor = newTotal / current
        // Scaling every pigment by the same factor cannot change the colour, so
        // carry the memo across. Without this a draining brush re-runs the
        // spectral mix on every sample to arrive at the colour it already had.
        return Recipe(amounts.mapValues { it.value * factor }, colorCache)
    }

    /**
     * Keeps at most [MAX_PIGMENTS], and drops only genuinely dead traces.
     * Contaminating a brush over and over leaves a long tail of vanishing
     * entries (each contamination scales the old ones down); without this the
     * recipe map — and the cost of mixing it — would grow the longer a dirty
     * brush is dragged.
     *
     * The trace floor is deliberately far below a single pickup's share: a
     * dirty brush takes on a new colour a little at a time, and the same colour
     * coalesces and builds up over many steps, so pruning at the per-step share
     * would cull each pickup before it could accumulate and the brush would
     * never get dirty. The floor only removes what has decayed to nothing; the
     * count cap does the real bounding.
     */
    fun withoutTraces(minFraction: Float = TRACE_FRACTION): Recipe {
        if (amounts.size <= 1) return this
        val t = total
        if (t <= 0f) return this
        var kept: Map<Int, Float> = amounts.filterValues { it >= t * minFraction }
        if (kept.isEmpty()) return this // never prune everything away
        if (kept.size > MAX_PIGMENTS) {
            kept = kept.entries.sortedByDescending { it.value }
                .take(MAX_PIGMENTS).associate { it.key to it.value }
        }
        return if (kept.size == amounts.size) this else Recipe(LinkedHashMap(kept))
    }

    /** How much of [color] is present, for tests and debugging. */
    fun amountOf(@ColorInt color: Int): Float = amounts[color] ?: 0f

    override fun equals(other: Any?): Boolean = other is Recipe && other.amounts == amounts
    override fun hashCode(): Int = amounts.hashCode()
    override fun toString(): String =
        if (isEmpty) "Recipe(empty)"
        else "Recipe(" + amounts.entries.joinToString {
            "#%06X".format(it.key and 0xFFFFFF) + "=" + "%.3f".format(it.value)
        } + ")"

    companion object {
        val EMPTY = Recipe(emptyMap())

        /**
         * A pigment below this share of the total has decayed to nothing and is
         * dropped. Far below a single pickup's share (see [withoutTraces]) so
         * contamination can accumulate rather than being culled each step.
         */
        const val TRACE_FRACTION = 0.001f

        /** Most pigments a recipe carries at once — bounds the cost of mixing it. */
        const val MAX_PIGMENTS = 8

        fun of(@ColorInt color: Int, amount: Float = 1f): Recipe = EMPTY.plus(color, amount)
    }
}

/**
 * A pigment you can dab from — a tube on the tray.
 *
 * Any sRGB colour works as a pigment (the spectral upsampling lifts it to a
 * reflectance curve), so a named tube and a colour mixed on the wheel are the
 * same kind of thing; [name] is the only difference.
 *
 * These are real pigment names with plausible sRGB values, NOT measured
 * Kubelka-Munk coefficients — the engine derives reflectance from sRGB. Real
 * measured coefficients would behave more truthfully still, and are a separate
 * job.
 */
data class Pot(val name: String, @param:ColorInt val color: Int, val custom: Boolean = false)

/**
 * What the brush is carrying.
 *
 * The load's [recipe] doubles as its volume: [Recipe.total] is how much paint
 * is left, and because scaling a recipe doesn't change its colour, draining the
 * brush fades the mark without shifting its hue. Dragging through paint
 * [contaminate]s the load — it swaps a fraction of the mixture for what the
 * brush picked up, shifting its colour without refilling it — so a dirty brush
 * gradually becomes the colours it has been through, the way a real one does.
 *
 * @param capacity what a full brush holds; [fill] is measured against it.
 */
data class BrushLoad(val recipe: Recipe, val capacity: Float = DEFAULT_CAPACITY) {

    /** Paint remaining. */
    val volume: Float get() = recipe.total

    /** Remaining paint as a fraction of a full brush, for fading the mark out. */
    val fill: Float get() = if (capacity <= 0f) 0f else (volume / capacity).coerceIn(0f, 1f)

    @get:ColorInt
    val color: Int get() = recipe.color

    val isDry: Boolean get() = volume <= DRY_THRESHOLD

    /**
     * Lays down [amount] of paint, draining the brush by that much. The mixture
     * itself is untouched — what's left is the same colour, just less of it.
     */
    fun deposit(amount: Float): BrushLoad {
        if (amount <= 0f) return this
        val left = volume - amount
        return if (left <= DRY_THRESHOLD) copy(recipe = Recipe.EMPTY)
        else copy(recipe = recipe.scaledTo(left))
    }

    /**
     * Swaps [fraction] of the load for [color] — the pigment the brush just
     * dragged through. The total is unchanged, so this contaminates the colour
     * without refilling the brush: a dirty brush doesn't gain paint.
     *
     * Because it's a swap, dragging through paint of the colour the brush
     * already carries is a no-op (it removes and re-adds the same thing), so a
     * brush passing over its own fresh trail doesn't reinforce or refill itself
     * — only crossing a *different* colour actually shifts it.
     */
    fun contaminate(@ColorInt color: Int, fraction: Float): BrushLoad {
        val v = volume
        if (fraction <= 0f || v <= DRY_THRESHOLD) return this
        val f = fraction.coerceIn(0f, 1f)
        val kept = recipe.scaledTo(v * (1f - f)).plus(color, v * f)
        return copy(recipe = kept.withoutTraces())
    }

    /**
     * Takes on [amount] of [color] — wet paint lifted off the canvas. Unlike
     * [contaminate], this ADDS volume: dragging even a bone-dry brush through
     * fresh paint loads it with that paint (which is what makes smearing
     * possible — the brush carries the wet paint away and lays it back down
     * ahead), up to the brush's capacity.
     */
    fun take(@ColorInt color: Int, amount: Float): BrushLoad {
        val room = capacity - volume
        if (amount <= 0f || room <= 0f) return this
        val a = amount.coerceAtMost(room)
        return copy(recipe = recipe.plus(color, a).withoutTraces())
    }

    companion object {
        /**
         * A full brush, in abstract paint units. Only ratios against this
         * matter (see [fill]); the physical scale lives in [COVERAGE_MM2].
         */
        const val DEFAULT_CAPACITY = 1f

        /**
         * How much surface a full brush covers before running dry, in real
         * millimetres squared — so a brush lasts the same physical distance on
         * any calibrated screen, rather than draining faster on a denser one.
         * Tuned by eye on-device.
         */
        const val COVERAGE_MM2 = 900f

        /** Below this the brush is spent; avoids dividing a vanishing recipe. */
        const val DRY_THRESHOLD = 1e-4f

        val EMPTY = BrushLoad(Recipe.EMPTY)

        /** A brush loaded full of a single pigment. */
        fun of(@ColorInt color: Int, capacity: Float = DEFAULT_CAPACITY): BrushLoad =
            BrushLoad(Recipe.of(color, capacity), capacity)
    }
}

/**
 * The palette: pots of pigment around the rim, and a well in the middle where
 * they mix. Dab from a pot into the well to build a colour, then load the brush
 * from it.
 *
 * Pure state — no Android types, no rendering. The panel drives it; the canvas
 * reads [BrushLoad] off it.
 */
class Tray(pots: List<Pot> = STANDARD_POTS) {

    private val _pots = pots.toMutableList()

    /** Pigments available to dab from. */
    val pots: List<Pot> get() = _pots

    /** What's in the mixing well. */
    var mixture: Recipe = Recipe.EMPTY
        private set

    /** The colour the well currently reads as, or null when it's clean. */
    @get:ColorInt
    val mixedColor: Int? get() = if (mixture.isEmpty) null else mixture.color

    /** Adds pigment from a pot to the well. Pots are bottomless. */
    fun dab(pot: Pot, amount: Float = 1f) {
        mixture = mixture.plus(pot.color, amount)
    }

    /** Adds an arbitrary colour — a pickup off the canvas, or one off the wheel. */
    fun dab(@ColorInt color: Int, amount: Float = 1f) {
        mixture = mixture.plus(color, amount)
    }

    /** Wipes the well clean. */
    fun clearMixture() {
        mixture = Recipe.EMPTY
    }

    /**
     * Charges the brush from the well. The well is not consumed — a palette
     * holds more than one brushful, and you'd otherwise have to re-mix the same
     * colour every stroke.
     */
    fun loadBrush(capacity: Float = BrushLoad.DEFAULT_CAPACITY): BrushLoad =
        if (mixture.isEmpty) BrushLoad(Recipe.EMPTY, capacity)
        else BrushLoad(mixture.scaledTo(capacity), capacity)

    /** Adds a colour mixed on the wheel to the rim, so it can be dabbed from. */
    fun addPot(pot: Pot): Pot {
        _pots.add(pot)
        return pot
    }

    fun removePot(pot: Pot): Boolean = _pots.remove(pot)

    companion object {
        /**
         * A conventional artist's palette. Names are real pigments; the sRGB
         * values approximate their masstone.
         */
        val STANDARD_POTS = listOf(
            Pot("Titanium White", 0xFFFBFCFF.toInt()),
            Pot("Cadmium Yellow", 0xFFFFD300.toInt()),
            Pot("Yellow Ochre", 0xFFCC7722.toInt()),
            Pot("Cadmium Red", 0xFFE30022.toInt()),
            Pot("Quinacridone Magenta", 0xFF8E3A59.toInt()),
            Pot("Dioxazine Purple", 0xFF4B0082.toInt()),
            Pot("Ultramarine Blue", 0xFF1B1BB3.toInt()),
            Pot("Phthalo Blue", 0xFF000F89.toInt()),
            Pot("Phthalo Green", 0xFF123B26.toInt()),
            Pot("Sap Green", 0xFF507D2A.toInt()),
            Pot("Burnt Sienna", 0xFF8A3324.toInt()),
            Pot("Ivory Black", 0xFF1B1B1B.toInt()),
        )
    }
}
