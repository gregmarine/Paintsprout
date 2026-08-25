package com.symmetricalpalmtree.paintsproutonyx.bootstrap

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.core.TopGuard
import com.symmetricalpalmtree.paintsproutonyx.crypto.PassphraseStore
import com.symmetricalpalmtree.paintsproutonyx.data.index.PaintsproutIndex
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivityBootstrapBinding
import com.symmetricalpalmtree.paintsproutonyx.library.LibraryActivity
import kotlinx.coroutines.launch

/**
 * The launcher, and **the only thing in this app that opens the index.**
 *
 * Opening an encrypted library is not one operation. It can be a first-run key mint, a
 * derivation that takes real time, a key the Keystore no longer holds, or a file that turns
 * out not to be ours at all. Every one of those has to be resolved before any other screen
 * can read a single row — so all of it happens here, on a screen that exists for nothing
 * else, and no other screen has to know that unlocking is a thing that can happen.
 *
 * Where it forwards:
 *  - opened, and the recovery key has been acknowledged → the library
 *  - opened, but the key was never acknowledged (first run, or an install that never got
 *    past that screen) → [RecoveryKeyActivity]
 *  - a real file that no cached key opens → [UnlockActivity]
 *  - a plaintext file where ours should be → an error. It is never opened and never
 *    touched.
 *
 * `noHistory` in the manifest plus `finish()` here: this screen is never on the back stack,
 * so backing out of the library leaves the app instead of landing on a boot screen with
 * nothing to do. Every other screen bounces back here through `IndexGuard` if it ever finds
 * the index closed.
 *
 * A failure shows Retry / Close rather than throwing. This is the launcher — an uncaught
 * exception here is an app that dies on the icon tap, and an app that dies on the icon tap
 * is indistinguishable, from the outside, from an app that has lost the drawings.
 */
class BootstrapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBootstrapBinding
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBootstrapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        // "Preparing…" appears only if the open is genuinely slow — a first-run derivation,
        // say. A warm open is effectively instant, and on e-ink a word that flashes up and
        // leaves costs a panel refresh to say nothing; worse, it reads as a stumble rather
        // than as speed.
        val reveal = Runnable { binding.preparing.visibility = View.VISIBLE }
        handler.postDelayed(reveal, REVEAL_DELAY_MS)
        boot(reveal)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun boot(reveal: Runnable) {
        lifecycleScope.launch {
            try {
                route()
            } catch (e: Exception) {
                Log.e(TAG, "boot failed", e)
                binding.preparing.visibility = View.GONE
                AlertDialog.Builder(this@BootstrapActivity)
                    .setTitle(R.string.boot_error_title)
                    .setMessage(
                        getString(R.string.boot_error_body, e.message ?: e.javaClass.simpleName)
                    )
                    .setCancelable(false)
                    .setPositiveButton(R.string.retry) { _, _ -> boot(reveal) }
                    .setNegativeButton(R.string.close_app) { _, _ -> finishAffinity() }
                    .show()
            } finally {
                handler.removeCallbacks(reveal)
            }
        }
    }

    private suspend fun route() {
        when (PaintsproutIndex.ensureReady(this)) {
            PaintsproutIndex.PrepareOutcome.READY,
            PaintsproutIndex.PrepareOutcome.FIRST_LAUNCH ->
                forward(
                    if (PassphraseStore.isRecoveryKeyAcknowledged(this)) LibraryActivity::class.java
                    else RecoveryKeyActivity::class.java
                )

            PaintsproutIndex.PrepareOutcome.NEEDS_UNLOCK -> forward(UnlockActivity::class.java)

            // Not a crash and not a repair: a file we did not write, sitting where ours goes.
            // The one thing that must not happen is treating it as a broken library and
            // replacing it, so this path only ever reports.
            PaintsproutIndex.PrepareOutcome.FOREIGN_FILE ->
                throw IllegalStateException(getString(R.string.boot_error_foreign))
        }
    }

    /**
     * A clean in-app intent, never the launcher intent this activity was started with — its
     * flags re-trigger launcher task routing and can drop the window back on the home
     * screen instead of onto the next screen.
     */
    private fun forward(next: Class<*>) {
        startActivity(Intent(this, next))
        finish()
    }

    companion object {
        private const val TAG = "BootstrapActivity"
        private const val REVEAL_DELAY_MS = 450L
    }
}
