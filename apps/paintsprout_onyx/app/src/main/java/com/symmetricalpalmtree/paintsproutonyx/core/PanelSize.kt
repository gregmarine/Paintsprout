package com.symmetricalpalmtree.paintsproutonyx.core

import android.app.Activity
import android.graphics.Point
import android.os.Build

/**
 * The size of the panel, in pixels — not the size of the window a screen happens to have.
 *
 * A page in this app *is* the panel: full screen, portrait, one size for the life of the sketchbook.
 * That size is recorded on the page row the moment the sketchbook is made and it is never rewritten,
 * so it has to be the right number the first time. The obvious source, `resources.displayMetrics`, is
 * the current window — which on any screen with chrome, a keyboard up, or a system bar the app has
 * not yet gone edge-to-edge past, is smaller than the panel by however much of it that screen was
 * not using. Ask that question from the new-sketchbook screen and every page in the sketchbook is
 * born a little short of the paper it will be drawn on.
 *
 * So this asks the window manager for the *maximum* bounds instead: what the app could have if it
 * took the whole display. Below Android 11 that call does not exist and the real display metrics are
 * the same answer by another route.
 *
 * Portrait-locked, so [width] is always the short side. Returning them sorted rather than as reported
 * means a screen that is briefly landscape during a configuration change cannot mint a sideways page.
 */
data class PanelSize(val width: Int, val height: Int) {

    companion object {
        fun of(activity: Activity): PanelSize {
            val (a, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = activity.windowManager.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val point = Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }
                point.x to point.y
            }
            return PanelSize(minOf(a, b), maxOf(a, b))
        }
    }
}
