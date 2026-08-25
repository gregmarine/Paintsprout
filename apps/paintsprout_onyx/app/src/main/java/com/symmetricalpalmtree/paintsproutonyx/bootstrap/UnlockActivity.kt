package com.symmetricalpalmtree.paintsproutonyx.bootstrap

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.core.TopGuard
import com.symmetricalpalmtree.paintsproutonyx.crypto.AttemptLimiter
import com.symmetricalpalmtree.paintsproutonyx.crypto.GlobalKey
import com.symmetricalpalmtree.paintsproutonyx.crypto.PassphraseStore
import com.symmetricalpalmtree.paintsproutonyx.data.index.PaintsproutIndex
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivityUnlockBinding
import com.symmetricalpalmtree.paintsproutonyx.library.LibraryActivity
import kotlinx.coroutines.launch

/**
 * Type the recovery key to open a library this install has no cached key for — after a
 * reinstall, a restore, or the debug menu deliberately forgetting one.
 *
 * Two things about this screen are deliberate and unusual.
 *
 * It is **forgiving about transcription**. The key is Crockford base32, whose alphabet has
 * no I, L, O or U precisely so that a hand-copied key can be read back without ambiguity —
 * so a typed "O" can only ever have been meant as a zero, and I or l as a one. Folding those
 * cannot corrupt a correct key, because a correct key contains none of them. Someone copying
 * thirty-two characters off a piece of paper on the wrong side of a lost library should not
 * lose it to a letter that looks like a digit.
 *
 * It is **rate-limited but never destructive**. A wrong key is verified by a read-only open
 * and the file is not touched — no counter written into it, no re-encryption, nothing. What
 * a wrong attempt costs is time: the lockout schedule in [AttemptLimiter], during which the
 * entry field is taken away entirely rather than left there refusing input, because a field
 * that ignores typing looks broken while a countdown explains itself.
 *
 * There is no "forgot my key" path, and there cannot be one. The key is the encryption.
 */
class UnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockBinding
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never sit in front of an index that is already open — that would be a locked door
        // standing in an empty doorway.
        if (PaintsproutIndex.isReady()) {
            startActivity(Intent(this, LibraryActivity::class.java))
            finish()
            return
        }
        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root, followIme = true)

        binding.unlockButton.setOnClickListener { attempt() }
        binding.keyField.setOnEditorActionListener { _, _, _ -> attempt(); true }
        refreshLockout()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Hide the entry row and count down while locked out; restore it when the wait is over. */
    private fun refreshLockout() {
        val remaining = AttemptLimiter.check(this)
        if (remaining > 0) {
            binding.entryRow.visibility = View.GONE
            binding.lockoutText.visibility = View.VISIBLE
            binding.lockoutText.text = getString(R.string.unlock_locked_out, formatWait(remaining))
            handler.postDelayed({ refreshLockout() }, 1000L)
        } else {
            binding.lockoutText.visibility = View.GONE
            binding.entryRow.visibility = View.VISIBLE
        }
    }

    private fun attempt() {
        if (busy) return
        val typed = binding.keyField.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) return
        if (AttemptLimiter.check(this) > 0) {
            refreshLockout()
            return
        }
        hideKeyboard()
        busy = true
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = getString(R.string.unlock_checking)
        lifecycleScope.launch {
            // As typed first, then folded. Trying the literal string first means a key that is
            // already correct never depends on the fold being right.
            var opened = PaintsproutIndex.unlockAndOpen(this@UnlockActivity, typed)
            val folded = GlobalKey.normalize(typed)
            if (!opened && folded != typed) {
                opened = PaintsproutIndex.unlockAndOpen(this@UnlockActivity, folded)
            }
            busy = false
            if (opened) {
                AttemptLimiter.recordSuccess(this@UnlockActivity)
                // They have just proved they hold the key; showing them the reveal screen now
                // would be the app telling them to save something they clearly already saved.
                PassphraseStore.setRecoveryKeyAcknowledged(this@UnlockActivity)
                startActivity(Intent(this@UnlockActivity, LibraryActivity::class.java))
                finish()
            } else {
                AttemptLimiter.recordFailure(this@UnlockActivity)
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.text = getString(R.string.unlock_wrong)
                binding.keyField.text?.clear()
                refreshLockout()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.keyField.windowToken, 0)
    }

    private fun formatWait(ms: Long): String {
        val seconds = (ms + 999) / 1000
        return if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"
    }
}
