package com.symmetricalpalmtree.paintsproutonyx.core

import android.app.Activity
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.paintsproutonyx.R

/**
 * Dialogs, and the rule about when there is one.
 *
 * **A toast only ever confirms something that already happened.** Anything explaining why a tap did
 * *not* work is a dialog. On an EPD panel a toast fades in and out inside a single slow refresh; it
 * is genuinely easy to miss, and a Create button that visibly does nothing reads as an app that is
 * broken rather than a name that is already taken. That is the whole argument, and [problem] is
 * where it is spent.
 *
 * [style] exists because this panel has no shadow to give. Elevation is what normally lifts a dialog
 * off the page, and on e-ink it is a grey smear that costs a refresh, so what separates a dialog from
 * what is behind it here is a black border and nothing else. The theme already asks for both; the
 * window only takes them once it is showing, which is why this runs on the show listener rather than
 * at build time.
 */
object Dialogs {

    fun style(dialog: AlertDialog): AlertDialog {
        dialog.setOnShowListener {
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
        }
        return dialog
    }

    /**
     * One title, one sentence, OK.
     *
     * Silent when the activity is on its way out. This is usually called from a coroutine that
     * started before a back press, and a dialog attached to a finishing window is a crash rather than
     * a message anybody reads.
     */
    fun problem(
        activity: Activity,
        title: CharSequence,
        message: CharSequence,
        onDismiss: (() -> Unit)? = null,
    ) {
        // [onDismiss] still runs when the window has already gone. A screen that cannot open what it
        // was launched for closes itself from here, and skipping that because the dialog could not be
        // shown would leave it standing there blank with no message on it either.
        if (activity.isFinishing || activity.isDestroyed) {
            onDismiss?.invoke()
            return
        }
        style(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener { onDismiss?.invoke() }
                .create()
        ).show()
    }

    fun problem(activity: Activity, @StringRes titleRes: Int, message: CharSequence) =
        problem(activity, activity.getString(titleRes), message)

    fun problem(
        activity: Activity,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        onDismiss: (() -> Unit)? = null,
    ) = problem(activity, activity.getString(titleRes), activity.getString(messageRes), onDismiss)

    /**
     * A destructive question: title, body, a named verb on the affirmative button, Cancel.
     *
     * The verb is named ("Delete") rather than "OK" because the artist is being asked to agree to
     * something that cannot be taken back, and the last word they read before tapping should be what
     * is about to happen.
     */
    fun confirm(
        activity: Activity,
        title: CharSequence,
        message: CharSequence,
        @StringRes confirmRes: Int,
        onConfirm: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        style(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(confirmRes) { _, _ -> onConfirm() }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }
}
