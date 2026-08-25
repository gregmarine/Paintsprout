package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * A colour typed rather than found.
 *
 * The wheel is for a colour you have not decided on yet — you hunt it by eye. A
 * hex code off a brand sheet, or a channel you want one step darker, is the
 * opposite: the number is the thing you already have, and finding it by dragging a
 * thumb around a disc is a fight you would lose. So the same colour can be reached
 * either way.
 *
 * Nothing here clamps what a field *holds*, for the reason `InchField.kt` gives:
 * the clamp belongs where the value is read, or a "2" on its way to becoming "25"
 * is rewritten under the cursor the instant it lands. A channel of 999 is a moment
 * in the middle of typing.
 */

/** The byte range of one channel. */
const val CHANNEL_MAX = 255

/** A one-line whole-number field holding one 0–255 channel. */
fun Context.channelField(initial: Int): EditText = EditText(this).apply {
    inputType = InputType.TYPE_CLASS_NUMBER
    setSingleLine()
    setText(initial.coerceIn(0, CHANNEL_MAX).toString())
}

/**
 * A one-line field holding a hex colour.
 *
 * Shown as six bare digits under a `#` label rather than carrying its own hash,
 * because the label is already saying that. Pasting one in with the hash still
 * works — see [parseHexColor], which is deliberately forgiving about the ways a
 * colour gets copied around.
 */
fun Context.hexField(initial: Int): EditText = EditText(this).apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    setSingleLine()
    setText(hexOf(initial))
}

/** A 0–255 channel slider, stepping in whole units. */
fun Context.channelSlider(initial: Int): Slider = Slider(this).apply {
    valueFrom = 0f
    valueTo = CHANNEL_MAX.toFloat()
    stepSize = 1f
    value = initial.coerceIn(0, CHANNEL_MAX).toFloat()
}

/**
 * `R [====o====] [ 137 ]` — one channel, dragged or typed, whichever suits.
 *
 * Both on one row because they are not two controls: they are one number with two
 * ways in, and stacking them would read as a slider that happens to have a box
 * under it.
 *
 * [control] is a [View] rather than a [Slider] for the hex row, which has nothing
 * to drag — six digits are six digits — and passes a blank of the same width so
 * that its field lands in the same column as the three above it.
 */
fun Context.channelRow(name: String, control: View, field: EditText): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = name; width = dpUnit(20) })
        addView(control, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(field, LinearLayout.LayoutParams(dpUnit(80), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

/**
 * Replaces what a field holds and leaves the caret after it.
 *
 * Only ever called on a field the change did *not* come from — see the rule at the
 * top of this file — so there is no keystroke here to get in the way of. Where the
 * caret ends up still matters, though: a value dropped in from the colour wheel is
 * one you may well want to edit next, and finding the cursor stranded at position
 * zero in front of it is not where you left it.
 */
fun EditText.setTextKeepingCaretAtEnd(value: String) {
    if (text.toString() == value) return
    setText(value)
    setSelection(text.length)
}

/** What the field holds, held to a byte — or [fallback] when it holds nothing usable. */
fun EditText.channel(fallback: Int): Int =
    text.toString().trim().toIntOrNull()?.coerceIn(0, CHANNEL_MAX) ?: fallback

/** Opaque ARGB from three channels, each held to a byte. */
fun rgbOf(r: Int, g: Int, b: Int): Int =
    OPAQUE or (r.coerceIn(0, CHANNEL_MAX) shl 16) or
        (g.coerceIn(0, CHANNEL_MAX) shl 8) or b.coerceIn(0, CHANNEL_MAX)

/** The six digits of [argb], upper case, alpha dropped. `#` is the label's job. */
fun hexOf(argb: Int): String = String.format("%06X", argb and 0xFFFFFF)

/**
 * A hex colour out of whatever was typed or pasted, or null if it is not one yet.
 *
 * Forgiving on purpose, because a colour gets copied from a lot of places: with or
 * without the `#`, either case, and three digits (`#F80`, where each digit means
 * itself twice) as well as six. Eight is accepted with its alpha discarded — this
 * picker only makes opaque colours, and refusing a pasted `#FF3366CC` on a
 * technicality would be no help to anyone.
 *
 * Null rather than a guess when it cannot be read. Half a hex code is a moment in
 * the middle of typing, and the colour should sit still through it rather than
 * lurching about on every keystroke.
 */
fun parseHexColor(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.any { it.digitToIntOrNull(16) == null }) return null
    val rgb = when (digits.length) {
        3 -> digits.flatMap { listOf(it, it) }.joinToString("")
        6 -> digits
        8 -> digits.substring(2)
        else -> return null
    }
    return OPAQUE or rgb.toInt(16)
}

/** Full alpha. Every colour this picker makes is opaque. */
private const val OPAQUE = 0xFF shl 24

private fun Context.dpUnit(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
