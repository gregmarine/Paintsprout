package com.symmetricalpalmtree.paintsproutonyx.library

import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Release build: the development tools do not exist here.
 *
 * This is a twin of the file in `src/debug`, not a flag read at runtime. A build that ships
 * cannot contain a control that reveals the recovery key, however well hidden — and the
 * only way to be sure of that is for the code to be absent from the source set rather than
 * merely unreachable in it.
 */
object DebugMenu {
    /** Hides the overflow control; a release library has nothing to put behind it. */
    fun install(activity: AppCompatActivity, overflow: View) {
        overflow.visibility = View.GONE
    }
}
