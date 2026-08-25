package com.symmetricalpalmtree.paintsproutonyx.core

import android.app.Activity
import android.content.Intent
import com.symmetricalpalmtree.paintsproutonyx.bootstrap.BootstrapActivity
import com.symmetricalpalmtree.paintsproutonyx.data.index.PaintsproutIndex
import java.util.Collections
import java.util.WeakHashMap

/**
 * Refuse to run a screen against a closed index, and send it somewhere that can open one.
 *
 * [BootstrapActivity] is the only thing in this app that opens the index, and it finishes
 * itself the moment it has forwarded, so it never sits on the back stack. That leaves one
 * route into a screen that has not passed through it: Android rebuilding a task's
 * activities by itself after the process was killed in the background. On a memory-tight
 * e-ink tablet that is not an edge case — it is what happens when the library is tapped in
 * Recents a while after it was last used. Nothing has opened the index in the new process,
 * so the screen's first read would throw, and what the artist would see is the app dying
 * on the way back to their own work.
 *
 * Use it as the first line of `onCreate` on every screen that touches the index, and
 * nowhere else:
 * ```
 * if (!IndexGuard.ready(this)) return
 * ```
 * `onCreate` runs before any index read, and nothing ever closes the index once open, so a
 * screen that gets past this line cannot later find it shut. Finishing inside `onCreate`
 * skips `onStart` and `onResume` but still runs `onDestroy`, so any screen that tears down
 * `lateinit` state there has to open with `if (IndexGuard.bounced(this)) { super.onDestroy(); return }`
 * or it will crash releasing something it never built.
 */
object IndexGuard {

    /** Screens [ready] turned away. Weak keys, so this can never keep a finished Activity alive. */
    private val bounced: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /**
     * True when the index is open. Otherwise restarts the task at [BootstrapActivity]
     * (`NEW_TASK or CLEAR_TASK` — the rebuilt stack is wrong from the root down, so it goes
     * rather than gets something pushed on top of it), finishes [activity] and returns
     * false. The caller must return immediately. Idempotent via [Activity.isFinishing].
     */
    fun ready(activity: Activity): Boolean {
        if (PaintsproutIndex.isReady()) return true
        bounced += activity
        if (!activity.isFinishing) {
            activity.startActivity(
                Intent(activity, BootstrapActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            activity.finish()
        }
        return false
    }

    /** True when [ready] turned [activity] away, so its `onDestroy` has nothing to release. */
    fun bounced(activity: Activity): Boolean = activity in bounced
}
