package com.symmetricalpalmtree.paintsproutonyx.core

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Keep a screen out from under BOOX's status bar — and do it by *adding* to what the
 * layout already asked for.
 *
 * This device has a real status bar drawn over the top of the window. That is the opposite
 * of the Supernote, where the guard is zero and a layout can start at y = 0 and look
 * right, so it is exactly the sort of thing that gets forgotten by anyone carrying habits
 * over from the other panel. Two things go wrong without a guard: the first line of a
 * screen sits underneath the bar and reads as a rendering fault, and anything tappable up
 * there pulls the shade down instead of firing — a button that is visibly present and
 * cannot be pressed.
 *
 * The addition is the part worth writing down. The obvious version of this helper assigns
 * the inset straight onto the view, and on a centred screen with no padding of its own
 * nobody would ever see the difference. On a screen that *does* set its own margin — a
 * top bar, a toolbar — assignment silently discards it, and the chrome ends up flush
 * against the very edge the guard was added to stay clear of. The bug hides behind its own
 * fix, which is the kind that survives a long time. So the base padding is read once, at
 * the moment the listener is installed, and every inset delivery is base + inset.
 *
 * [followIme] adds the keyboard inset to the bottom as well. Only a screen with a text
 * field wants it; on every other screen it is one more thing that can move.
 */
object TopGuard {

    fun applyInsetPadding(root: View, followIme: Boolean = false) {
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        val baseLeft = root.paddingLeft
        val baseRight = root.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = if (followIme) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            view.updatePadding(
                left = baseLeft + bars.left,
                right = baseRight + bars.right,
                top = baseTop + bars.top,
                bottom = baseBottom + maxOf(bars.bottom, ime),
            )
            insets
        }
        // A view already laid out when the listener arrives never gets a delivery on its own,
        // and the screen would sit under the bar until something else forced a pass.
        ViewCompat.requestApplyInsets(root)
    }
}
