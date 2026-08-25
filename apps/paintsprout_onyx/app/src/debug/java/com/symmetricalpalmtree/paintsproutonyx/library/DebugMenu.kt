package com.symmetricalpalmtree.paintsproutonyx.library

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeyMaterial
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeySession
import com.symmetricalpalmtree.paintsproutonyx.crypto.PassphraseStore

/**
 * The two development tools that make the lock testable on a real device. **Debug build
 * only** — there is a no-op twin of this file in `src/release`, so the release build does
 * not merely hide these actions, it does not contain them.
 *
 * - **Show recovery key** reveals and copies the global passphrase. On a device with no
 *   file manager worth the name, the alternative is reinstalling to see the key again,
 *   which destroys the library you were trying to unlock.
 * - **Forget cached key** clears the cached passphrase and every derived raw key, then
 *   kills the process. Clearing alone is not enough: the index is already open in this
 *   process, so a relaunch would find it ready and sail straight past the screen under
 *   test. Killing is what makes the next launch a real cold boot, which is the only way to
 *   reach Unlock without wiping app data — and wiping app data would take the sketchbooks
 *   with it, leaving nothing for the unlocked library to show.
 *
 * Neither action logs the key. Revealing it on screen at the artist's request is the whole
 * point of the first one; writing it into logcat, where it would outlive the moment and sit
 * in a buffer anyone can read, is not the same thing at all.
 */
object DebugMenu {

    /** Arms the library's overflow control. Debug only; the release twin hides it instead. */
    fun install(activity: AppCompatActivity, overflow: View) {
        overflow.visibility = View.VISIBLE
        overflow.setOnClickListener { showSheet(activity) }
    }

    private fun showSheet(activity: AppCompatActivity) {
        val items = arrayOf(
            activity.getString(R.string.debug_show_recovery_key),
            activity.getString(R.string.debug_forget_cached_key),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.debug_tools)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showKey(activity)
                    1 -> confirmForget(activity)
                }
            }
            .show()
    }

    private fun showKey(activity: AppCompatActivity) {
        val key = PassphraseStore.getGlobalPassphrase(activity)
            ?: activity.getString(R.string.debug_no_key_cached)
        AlertDialog.Builder(activity)
            .setTitle(R.string.debug_show_recovery_key)
            .setMessage(key)
            .setPositiveButton(R.string.recovery_copy) { _, _ ->
                val cm = activity.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(
                    ClipData.newPlainText(activity.getString(R.string.recovery_clip_label), key)
                )
                Toast.makeText(activity, R.string.recovery_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun confirmForget(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.debug_forget_title)
            .setMessage(R.string.debug_forget_body)
            .setPositiveButton(R.string.debug_forget_confirm) { _, _ ->
                PassphraseStore.clearGlobalPassphrase(activity)
                KeyMaterial.clearAll(activity)
                KeySession.clear()
                Toast.makeText(activity, R.string.debug_forgotten, Toast.LENGTH_SHORT).show()
                activity.finishAffinity()
                // A short delay so the toast is actually on the panel before the process
                // goes. On e-ink a message that never got a frame is a message that never
                // happened, and this one is the only confirmation the tap did anything.
                Handler(Looper.getMainLooper()).postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 400L)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
