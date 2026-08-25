package com.symmetricalpalmtree.paintsproutonyx.core

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.paintsproutonyx.R

/**
 * The long-press sheet, and the sort sheet: a title and a short stack of things you can do.
 *
 * Built in code rather than from a layout because the rows are not a fixed set — a sketchbook offers
 * different actions from a folder, and the sort sheet's tick moves. A layout would either have to
 * hold every row every caller might want and hide most of them, or be one row inflated in a loop,
 * which is this file with an extra file.
 *
 * Three things about it are the panel's doing rather than taste:
 *
 *  - **Rows are words, not icons.** This app owns exactly one icon, the folder glyph on a folder
 *    card. An icon vocabulary invented for four menu items would be four more marks to keep legible
 *    at every size on a display with no colour and no anti-aliasing worth the name — and "Delete" is
 *    already the clearest possible label for deleting. The tick on a chosen sort row is a character
 *    for the same reason, in a fixed-width column so the labels stay in line whether or not one is
 *    ticked.
 *  - **Dividers are 1 dp of solid black.** A hairline grey between rows is invisible here; without a
 *    real line the sheet reads as a paragraph rather than a list of separate taps.
 *  - **The sheet dismisses before the action runs.** Several of these open a dialog of their own, and
 *    a confirmation appearing over a sheet that is still up means two windows fighting for the same
 *    refresh — which on this panel resolves as a smear that needs a third refresh to clear.
 */
class ActionSheetDialog(private val context: Context) {

    private data class Action(val ticked: Boolean, val label: String, val onClick: () -> Unit)

    private var title: String? = null
    private val actions = mutableListOf<Action>()

    fun title(text: String): ActionSheetDialog = apply { title = text }

    fun addAction(label: String, ticked: Boolean = false, onClick: () -> Unit): ActionSheetDialog =
        apply { actions += Action(ticked, label, onClick) }

    fun show() {
        val density = context.resources.displayMetrics.density
        val ink = ContextCompat.getColor(context, R.color.inkBlack)
        val padH = (16 * density).toInt()
        val padV = (14 * density).toInt()
        val tickWidth = (28 * density).toInt()
        val dividerH = (1 * density).toInt()

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null

        title?.let { text ->
            root.addView(
                AppCompatTextView(context).apply {
                    this.text = text
                    textSize = 16f
                    setTextColor(ink)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(padH, padV, padH, padV)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            root.addView(divider(ink, dividerH))
        }

        actions.forEachIndexed { index, action ->
            if (index > 0) root.addView(divider(ink, dividerH))
            root.addView(
                row(action, ink, padH, padV, tickWidth) {
                    dialog?.dismiss()
                    action.onClick()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        dialog = Dialogs.style(AlertDialog.Builder(context).setView(root).create())
        dialog.show()
    }

    private fun divider(color: Int, heightPx: Int): View = View(context).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
    }

    private fun row(
        action: Action,
        ink: Int,
        padH: Int,
        padV: Int,
        tickWidth: Int,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isFocusable = true
        background = ColorDrawable(Color.TRANSPARENT)
        setOnClickListener { onClick() }

        addView(
            AppCompatTextView(context).apply {
                text = if (action.ticked) TICK else ""
                textSize = 16f
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(tickWidth, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(
            AppCompatTextView(context).apply {
                text = action.label
                textSize = 16f
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private companion object {
        const val TICK = "✓"
    }
}
