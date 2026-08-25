package com.symmetricalpalmtree.paintsprout

import android.app.Activity
import android.app.ActivityManager

/**
 * The screen the app holds while a painting is in front of the artist.
 *
 * Hiding the system bars is not the same as being left alone: the gestures that
 * summon them stay live under the glass, and a hand at the edge of the paper is
 * exactly where they are. Lock task mode — Android's own app pinning — is the
 * one thing that stops them, so the editor takes it.
 *
 * Taking it costs a system dialog every single time, and there is no way to
 * suppress that: the confirmation is skipped only for an app a device owner has
 * allowlisted, and a device with accounts on it cannot be given a device owner.
 * So the lock is taken once and *kept* — across the library, across a page turn,
 * across everything — and given back only where the app genuinely has to reach
 * something outside itself, because that is the one thing pinning forbids.
 */

/** Whether the app is holding the screen right now. */
fun Activity.holdsTheScreen(): Boolean {
    val activities = getSystemService(ActivityManager::class.java) ?: return false
    return activities.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
}

/**
 * Lets the screen go so that somewhere outside the app can be opened.
 *
 * A document picker, a share sheet, a sign-in page: all of them live in other
 * apps, and lock task mode will not launch another app's activity — the call
 * does not fail loudly, the screen simply does not change. So every door that
 * leads out of Paintsprout has to be unlocked before it is opened. The editor
 * takes the screen back the next time it is in front, which costs one dialog,
 * which is why this is called at those few doors and nowhere else.
 */
fun Activity.releaseTheScreen() {
    if (!holdsTheScreen()) return
    runCatching { stopLockTask() }
}
