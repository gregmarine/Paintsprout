package com.symmetricalpalmtree.paintsproutonyx.core

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
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
 *  - **A row is an icon and a word, and the icon column is always there.** The chrome speaks Tabler
 *    outline throughout, so a sheet of bare words would read as a different app opening on top of
 *    this one. The column is reserved whether or not a row fills it — a sort sheet ticks one row and
 *    leaves the rest empty, and labels that shuffled sideways depending on which one was chosen
 *    would make the tick the least noticeable thing about it.
 *  - **Dividers are 1 dp of solid black.** A hairline grey between rows is invisible here; without a
 *    real line the sheet reads as a paragraph rather than a list of separate taps.
 *  - **The sheet dismisses before the action runs.** Several of these open a dialog of their own, and
 *    a confirmation appearing over a sheet that is still up means two windows fighting for the same
 *    refresh — which on this panel resolves as a smear that needs a third refresh to clear.
 */
class ActionSheetDialog(private val context: Context) {

    private data class Action(@DrawableRes val iconRes: Int?, val label: String, val onClick: () -> Unit)

    private var title: String? = null
    private val actions = mutableListOf<Action>()

    fun title(text: String): ActionSheetDialog = apply { title = text }

    /** [iconRes] null leaves the icon column empty — an unticked row of a sort sheet. */
    fun addAction(label: String, @DrawableRes iconRes: Int? = null, onClick: () -> Unit): ActionSheetDialog =
        apply { actions += Action(iconRes, label, onClick) }

    fun show() {
        val density = context.resources.displayMetrics.density
        val ink = ContextCompat.getColor(context, R.color.inkBlack)
        val padH = (16 * density).toInt()
        val padV = (14 * density).toInt()
        val iconSize = (24 * density).toInt()
        val iconGap = (12 * density).toInt()
        val dividerH = (1 * density).toInt()

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null

        title?.let { text ->
            // The title carries a close control of its own. A sheet is dismissed by tapping outside
            // it, which is a gesture with nothing on screen to suggest it — and on a panel that
            // redraws slowly, a tap into the dark that does not visibly do anything reads as a tap
            // that missed.
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, padV, padH, padV)
            }
            titleRow.addView(
                AppCompatTextView(context).apply {
                    this.text = text
                    textSize = 16f
                    setTextColor(ink)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            titleRow.addView(
                AppCompatImageView(context).apply {
                    setImageResource(R.drawable.ic_x)
                    isClickable = true
                    isFocusable = true
                    background = ColorDrawable(Color.TRANSPARENT)
                    contentDescription = context.getString(R.string.close)
                    setOnClickListener { dialog?.dismiss() }
                },
                LinearLayout.LayoutParams(iconSize, iconSize),
            )
            root.addView(
                titleRow,
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
                row(action, ink, padH, padV, iconSize, iconGap) {
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
        iconSize: Int,
        iconGap: Int,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isFocusable = true
        background = ColorDrawable(Color.TRANSPARENT)
        setOnClickListener { onClick() }

        // A Space where there is no icon rather than nothing at all: the column holds its width, so
        // the labels of a sort sheet stay in one line down the sheet with the tick standing out
        // beside the chosen one.
        val iconLayout = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = iconGap }
        if (action.iconRes != null) {
            addView(
                AppCompatImageView(context).apply {
                    setImageResource(action.iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                iconLayout,
            )
        } else {
            addView(Space(context), iconLayout)
        }
        addView(
            AppCompatTextView(context).apply {
                text = action.label
                textSize = 16f
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }
}
