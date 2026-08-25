package com.symmetricalpalmtree.paintsproutonyx.bootstrap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.core.IndexGuard
import com.symmetricalpalmtree.paintsproutonyx.core.TopGuard
import com.symmetricalpalmtree.paintsproutonyx.crypto.PassphraseStore
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivityRecoveryKeyBinding
import com.symmetricalpalmtree.paintsproutonyx.library.LibraryActivity

/**
 * Shown once, on the first launch that mints a key: this is the recovery key, and it is the
 * only copy of it that will ever exist.
 *
 * The library is encrypted from its first byte, and the key is not derived from anything —
 * not an account, not the device, not a password anyone could be asked to remember again.
 * That is what makes the sketchbooks genuinely the artist's own, and it is also why losing
 * this string loses every drawing behind it, backups included, because a backup is
 * ciphertext too. There is no support path, no reset and no second chance, so this screen
 * gets a whole launch to itself and refuses to move on until the box is ticked.
 *
 * The tick is a click-guard, not a legal formality. It costs a second and it is the only
 * moment in the app's life where the difference between reading that sentence and skipping
 * it is still recoverable.
 *
 * The key is displayed and, on request, copied. It is never logged and never put in an
 * Intent — the whole point of the encryption is undone by leaving the key somewhere the
 * artist did not choose to put it.
 */
class RecoveryKeyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        val binding = ActivityRecoveryKeyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        val key = PassphraseStore.getGlobalPassphrase(this)
        if (key == null) {
            // Cannot happen after a successful boot — the index only opens once a key exists.
            // If it somehow does, the wrong answer is stranding the artist on a screen with a
            // blank where the key should be and a button that will not let them past.
            startActivity(Intent(this, LibraryActivity::class.java))
            finish()
            return
        }
        binding.recoveryKey.text = key

        binding.copyButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.recovery_clip_label), key)
            )
            Toast.makeText(this, R.string.recovery_copied, Toast.LENGTH_SHORT).show()
        }

        binding.continueButton.setOnClickListener {
            if (!binding.savedIt.isChecked) {
                // A toast is right here and only here: nothing happened, and the reason is one
                // short sentence about a control the artist can see. Anything longer would be a
                // dialog.
                Toast.makeText(this, R.string.recovery_tick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PassphraseStore.setRecoveryKeyAcknowledged(this)
            startActivity(Intent(this, LibraryActivity::class.java))
            finish()
        }
    }
}
