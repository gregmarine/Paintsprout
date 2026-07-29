package com.symmetricalpalmtree.paintsprout

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.symmetricalpalmtree.paintsprout.crypto.KeyRotation
import com.symmetricalpalmtree.paintsprout.data.LastOpen
import com.symmetricalpalmtree.paintsprout.data.LaunchRoute
import com.symmetricalpalmtree.paintsprout.data.LaunchTarget
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.IndexStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The gate the app opens through.
 *
 * Nothing may read the index before it is open, and opening it can be slow
 * (a key derivation), interactive (an unlock prompt) or a first run (a key to
 * mint and show). This screen is where all of that is visible, so that no other
 * screen has to know about any of it.
 *
 * A failure here shows an error with a Retry button and never a crash — a
 * launcher activity that crashes on start is a loop the user cannot escape, and
 * the thing it would be looping on is their entire library.
 */
class BootstrapActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var passphrase: PassphraseField
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton

    private var countdown: kotlinx.coroutines.Job? = null

    /** Added once; [render] can run more than once for the same state. */
    private var keyView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately NOT edge-to-edge, unlike the canvas screens. This one has a
        // text field, and with the decor fitting insets normally the soft keyboard
        // resizes the layout instead of covering the Unlock button — which is
        // exactly what it did on the first device run.
        setContentView(buildUi())

        lifecycleScope.launch {
            render(IndexGate.ensureReady(this@BootstrapActivity))
        }
    }

    // --- States -------------------------------------------------------------

    private fun render(status: IndexStatus) {
        countdown?.cancel()
        when (status) {
            is IndexStatus.Starting -> showBusy()
            is IndexStatus.Ready -> onReady()
            is IndexStatus.NeedsUnlock -> showUnlock(status.lockedUntil)
            is IndexStatus.Failed -> showFailure(status.cause)
        }
    }

    private fun showBusy() {
        title.text = getString(R.string.bootstrap_opening)
        message.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        passphrase.visibility = View.GONE
        primary.visibility = View.GONE
        secondary.visibility = View.GONE
    }

    private fun onReady() {
        val key = IndexGate.pendingRecoveryKey(this)
        if (key != null) {
            showRecoveryKey(key)
            return
        }
        // A rotation that was interrupted finishes here, before any screen that
        // opens a document exists — half the library on one key and half on
        // another is a state to pass through, never one to run in.
        if (KeyRotation.isPending(this)) {
            showBusy()
            lifecycleScope.launch {
                runCatching { KeyRotation.resume(this@BootstrapActivity) }
                route()
            }
            return
        }
        route()
    }

    /**
     * Shown until the user says they have written it down, and shown again on
     * every launch until then — this string is the only way back into the library
     * on another device or after a reinstall, and it is minted without ever asking
     * for anything.
     */
    private fun showRecoveryKey(key: String) {
        title.text = getString(R.string.bootstrap_recovery_title)
        message.text = getString(R.string.bootstrap_recovery_body)
        message.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        passphrase.visibility = View.GONE

        if (keyView == null) {
            keyView = TextView(this).apply {
                text = key
                typeface = Typeface.MONOSPACE
                textSize = 20f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(pad(20), pad(20), pad(20), pad(20))
                setBackgroundColor(0xFFF2F0EA.toInt())
                setTextIsSelectable(true)
            }
            root.addView(keyView, root.indexOfChild(primary), lp())
        }

        primary.visibility = View.VISIBLE
        primary.text = getString(R.string.bootstrap_recovery_written)
        primary.setOnClickListener {
            IndexGate.acknowledgeRecoveryKey(this)
            route()
        }
        secondary.visibility = View.VISIBLE
        secondary.text = getString(R.string.bootstrap_recovery_copy)
        secondary.setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("", key))
            secondary.text = getString(R.string.bootstrap_recovery_copied)
        }
    }

    private fun showUnlock(lockedUntil: Long) {
        title.text = getString(R.string.bootstrap_unlock_title)
        message.text = getString(R.string.bootstrap_unlock_body)
        message.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        passphrase.visibility = View.VISIBLE
        secondary.visibility = View.GONE
        primary.visibility = View.VISIBLE
        primary.text = getString(R.string.bootstrap_unlock_action)
        primary.setOnClickListener { attemptUnlock() }

        if (lockedUntil > System.currentTimeMillis()) startCountdown(lockedUntil) else clearLockout()
    }

    private fun attemptUnlock() {
        val entered = passphrase.value
        if (entered.isEmpty()) return
        showBusy()
        lifecycleScope.launch {
            val result = IndexGate.unlock(this@BootstrapActivity, entered)
            render(result)
            if (result is IndexStatus.NeedsUnlock) {
                passphrase.clear()
                // A lockout has its own countdown message; a plain wrong answer
                // says so, rather than repeating the neutral instructions.
                if (result.lockedUntil <= System.currentTimeMillis()) {
                    message.text = getString(R.string.bootstrap_unlock_wrong)
                }
            }
        }
    }

    /** Counts the lockout down in place rather than leaving a dead button. */
    private fun startCountdown(until: Long) {
        primary.isEnabled = false
        passphrase.isEnabled = false
        countdown = lifecycleScope.launch {
            while (true) {
                val remaining = until - System.currentTimeMillis()
                if (remaining <= 0) break
                message.text = getString(R.string.bootstrap_locked, formatRemaining(remaining))
                delay(500)
            }
            clearLockout()
            message.text = getString(R.string.bootstrap_unlock_body)
        }
    }

    private fun clearLockout() {
        primary.isEnabled = true
        passphrase.isEnabled = true
    }

    private fun formatRemaining(ms: Long): String {
        val totalSeconds = (ms / 1000.0).roundToInt().coerceAtLeast(1)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private fun showFailure(cause: Throwable) {
        title.text = getString(R.string.bootstrap_failed_title)
        // The message, not a stack trace: this is a screen a user reads. Nothing
        // here can name a key or a passphrase — none of those exceptions carry one.
        message.text = cause.message ?: cause::class.java.simpleName
        message.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        passphrase.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.visibility = View.VISIBLE
        primary.text = getString(R.string.bootstrap_retry)
        primary.setOnClickListener {
            showBusy()
            lifecycleScope.launch { render(IndexGate.retry(this@BootstrapActivity)) }
        }
    }

    // --- Routing ------------------------------------------------------------

    /**
     * Back to wherever the user was, or to the library if that is nowhere.
     *
     * The pointer is only a hint: it names ids, and the editor checks that both
     * the index row and the file still exist before opening anything. A library
     * is the right landing place for someone with nothing open — and for someone
     * whose last document has since been deleted.
     */
    private fun route() {
        val destination = when (LaunchRoute.of(LastOpen.load(this))) {
            LaunchTarget.EDITOR -> MainActivity::class.java
            LaunchTarget.LIBRARY -> LibraryActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
    }

    // --- Chrome -------------------------------------------------------------

    private fun buildUi(): View {
        title = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        message = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF5A5F63.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        spinner = ProgressBar(this).apply { isIndeterminate = true }
        passphrase = PassphraseField(this).apply {
            hint = getString(R.string.bootstrap_unlock_hint)
            visibility = View.GONE
            centre()
            onSubmit { if (primary.isEnabled) attemptUnlock() }
        }
        primary = MaterialButton(this).apply { visibility = View.GONE }
        secondary = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle)
            .apply { visibility = View.GONE }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad(48), pad(48), pad(48), pad(48))
            addView(title, lp())
            addView(message, lp(topMargin = pad(12)))
            addView(spinner, lp(topMargin = pad(24)).also { it.gravity = Gravity.CENTER })
            // Wide enough that a revealed recovery key fits on one line — the
            // point of revealing it is comparing it against what it was copied
            // from, and a key that wraps mid-group is harder to check than dots.
            addView(
                passphrase,
                LinearLayout.LayoutParams(pad(RECOVERY_KEY_FIELD_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = pad(24)
                    },
            )
            addView(primary, lp(topMargin = pad(24)))
            addView(secondary, lp(topMargin = pad(4)))
        }
        return root
    }

    private fun lp(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        this.topMargin = topMargin
    }

    private fun pad(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private companion object {
        /** `PSPT-` plus eight groups of four, monospaced, with room for the eye. */
        const val RECOVERY_KEY_FIELD_DP = 460
    }
}
