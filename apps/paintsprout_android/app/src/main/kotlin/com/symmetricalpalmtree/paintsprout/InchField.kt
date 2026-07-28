package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A measurement typed rather than dragged.
 *
 * A slider is fine for a quantity you judge by eye — how wet, how rough — but a
 * canvas is a number you already know before you open the dialog, and hitting
 * 9.88 by dragging a thumb across eleven inches of travel is a fight. These are
 * the fields that replaced those sliders.
 *
 * Nothing here clamps. The field holds what was typed, however wrong, and the
 * clamp happens where the value is read — otherwise a "1" on its way to becoming
 * "10" gets rewritten under the cursor the instant it is entered.
 */

/** A one-line decimal field holding a measurement in inches. */
fun Context.inchField(initial: Float): EditText = EditText(this).apply {
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    setSingleLine()
    setText(trimZeros(initial))
    setSelection(text.length)
}

/**
 * What the field currently holds, or [fallback] when it holds nothing usable.
 *
 * An empty field is a moment in the middle of typing, not a request for a canvas
 * of no size, so it reads as the value that was already there.
 */
fun EditText.inches(fallback: Float): Float =
    text.toString().trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback

/** Runs [action] after every edit, for keeping a preview honest as you type. */
fun EditText.onEdit(action: () -> Unit) {
    addTextChangedListener(
        object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = action()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        },
    )
}

/**
 * A labelled field with its unit after it: `Width [ 7.41 ] in`.
 *
 * [unit] was an assumption before it was a parameter — the row was written for
 * sizes and said "in" whatever it was measuring, so an opacity read "74 in" and
 * a name read "Underpainting in". Empty means the thing has no unit, which is
 * the honest answer for a name.
 */
fun Context.fieldRow(name: String, field: EditText, unit: String = "in"): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = name; width = dpOf(64) })
        addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (unit.isNotEmpty()) addView(TextView(context).apply { text = " $unit" })
    }

private fun Context.dpOf(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

/** "7", "7.4", "9.88" — a size field should not open reading "9.88000". */
private fun trimZeros(v: Float): String =
    String.format("%.2f", v).trimEnd('0').trimEnd('.')
