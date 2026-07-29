package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * A passphrase box you can look at.
 *
 * Every secret in this app is typed into one of these, and one of them is a
 * 44-character recovery key read off another screen. Dots are the right default —
 * somebody is often watching a tablet — but a field that can *only* be dots turns
 * one mistyped character into an answer indistinguishable from having lost the
 * key: the app says "that didn't open the library" either way, because it cannot
 * tell the difference, and neither can you.
 *
 * So the dots stay, with an eye to lift them. That is the whole feature, and it is
 * worth its own type only because there are five of these fields and a reveal on
 * four of them would be worse than none.
 *
 * Monospaced, because the thing being checked is a grouped string: proportional
 * type makes `PSPT-4K7P` and `PSPT-4K7P` look identical when one of them isn't.
 */
class PassphraseField(context: Context) : TextInputLayout(context) {

    /**
     * `lateinit` rather than an initialised `val`, because [setEnabled] below is
     * reached from `TextInputLayout`'s own constructor — before any subclass
     * property has been assigned. As a `val` it is still null at that moment and
     * the override dereferences it, which crashes the launcher activity on start.
     */
    private lateinit var input: TextInputEditText

    init {
        input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Set explicitly rather than left to the password variation's default:
            // the toggle swaps the transformation method, and a field that changed
            // shape when revealed would defeat the purpose of looking at it.
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        endIconMode = END_ICON_PASSWORD_TOGGLE
        addView(input)
    }

    var value: String
        get() = input.text?.toString().orEmpty()
        set(v) = input.setText(v)

    fun clear() = input.setText("")

    /** Centres the text, for the one screen that is nothing but this field. */
    fun centre() {
        input.gravity = android.view.Gravity.CENTER
    }

    /**
     * Submitting from the keyboard, which is the natural gesture at the end of a
     * long key — and means the flow never depends on a button being reachable
     * above the soft keyboard.
     */
    fun onSubmit(action: () -> Unit) {
        input.imeOptions = EditorInfo.IME_ACTION_GO
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                action()
                true
            } else {
                false
            }
        }
    }

    /**
     * The layout does not pass this down to its child, and a lockout that greys
     * the box while still accepting typing is a lockout in appearance only.
     *
     * The guard is not defensive coding: the superclass constructor calls this,
     * and at that point [input] genuinely does not exist yet.
     */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (::input.isInitialized) input.isEnabled = enabled
    }
}
