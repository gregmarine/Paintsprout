package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.paint.Recipe
import java.util.Locale

/**
 * A pigment recipe, as text.
 *
 * `AARRGGBB:amount` pairs, comma separated — `FF1B1BB3:0.5,FFFFD300:0.25`. A
 * recipe is at most eight pigments, it is never queried, and it is something a
 * person debugging a palette will want to read straight out of the row, so text
 * beats a blob here.
 *
 * The amounts are what make a mix a mix: only their *ratios* affect the colour,
 * so the same recipe scaled up or down reads identically, and the total doubles
 * as how much paint is left on the brush.
 *
 * Parsing is total. A malformed pair is skipped rather than throwing — a palette
 * that comes back missing one pigment is a small annoyance; one that stops the
 * document opening is not.
 */
object RecipeCodec {

    fun encode(recipe: Recipe): String =
        recipe.dabs.joinToString(",") { dab ->
            String.format(Locale.ROOT, "%08X", dab.color) + ":" + dab.amount
        }

    fun decode(text: String?): Recipe {
        if (text.isNullOrBlank()) return Recipe.EMPTY
        var recipe = Recipe.EMPTY
        for (pair in text.split(',')) {
            val parts = pair.split(':')
            if (parts.size != 2) continue
            val color = parts[0].trim().toLongOrNull(16)?.toInt() ?: continue
            val amount = parts[1].trim().toFloatOrNull() ?: continue
            if (amount <= 0f || !amount.isFinite()) continue
            recipe = recipe.plus(color, amount)
        }
        return recipe
    }
}
