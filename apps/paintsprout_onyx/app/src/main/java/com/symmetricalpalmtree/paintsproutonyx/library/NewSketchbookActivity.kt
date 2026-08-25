package com.symmetricalpalmtree.paintsproutonyx.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.core.Dialogs
import com.symmetricalpalmtree.paintsproutonyx.core.IndexGuard
import com.symmetricalpalmtree.paintsproutonyx.core.PanelSize
import com.symmetricalpalmtree.paintsproutonyx.core.TopGuard
import com.symmetricalpalmtree.paintsproutonyx.data.index.IndexRepository
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectType
import com.symmetricalpalmtree.paintsproutonyx.data.soil.createSketchbook
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivityNewSketchbookBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Naming a new sketchbook.
 *
 * A whole screen for one text field, and not a dialog, for a reason that belongs to this device: a
 * dialog with the keyboard up on a BOOX panel is a small window sandwiched between a dimmed
 * background and half a screen of keys, redrawn every keystroke. A full screen has room for the field
 * to sit where it is being typed and for the rule about names to be visible while the artist breaks
 * it, rather than appearing afterwards as a complaint.
 *
 * It is also where the paper choice will go when there is more than one paper. Arc 1's paper is plain
 * white and there is nothing to choose, so nothing is shown — but a screen with room for a second
 * question is why this is not a dialog either.
 *
 * The name is the artist's to pick and the default is only a starting point: a timestamp, because it
 * is the one default that is never already taken and never means anything misleading. It arrives
 * selected, so the first keystroke replaces it.
 */
class NewSketchbookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewSketchbookBinding
    private val repo by lazy { IndexRepository() }
    private var parentFolderId: String? = null
    private var creating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityNewSketchbookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // followIme: the keyboard's height is added to the padding instead of the window being panned,
        // and a panned window on e-ink redraws everything to move it.
        TopGuard.applyInsetPadding(binding.root, followIme = true)

        parentFolderId = intent.getStringExtra(EXTRA_PARENT_FOLDER_ID)
        binding.nameField.setText(defaultName())
        // Focused, selected, and the keyboard already up. This screen exists to type a name into, and
        // without this it opens with the field merely *looking* ready: the first tap on it puts a
        // caret at the end of a fifteen-character timestamp rather than replacing it, and the artist
        // has to hold backspace to get rid of a default the app chose for them. Posted rather than
        // called straight away because the field cannot take focus before it has been laid out, and
        // asking early fails silently — which is exactly how this got shipped looking correct.
        binding.nameField.post {
            binding.nameField.requestFocus()
            binding.nameField.selectAll()
            ContextCompat.getSystemService(this, InputMethodManager::class.java)
                ?.showSoftInput(binding.nameField, InputMethodManager.SHOW_IMPLICIT)
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnCreate.setOnClickListener { attemptCreate() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
    }

    private fun defaultName(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

    /**
     * Every reason this can fail is a dialog, and every one of them names the actual problem.
     *
     * The button is disarmed for the whole attempt rather than at the end of it. Creating a sketchbook
     * writes a file and then a row, and a second tap arriving between those two would pass the
     * duplicate check against a shelf that does not know about the first one yet — two sketchbooks
     * with the same name and two files, from one moment of impatience on a panel that gives no
     * feedback for a tap.
     */
    private fun attemptCreate() {
        if (creating) return
        val name = binding.nameField.text.toString().trim()
        val problem = NameRules.validate(name)
        if (problem != null) {
            Dialogs.problem(this, R.string.name_problem_title, problem)
            return
        }
        creating = true
        binding.btnCreate.isEnabled = false
        binding.btnCreate.text = getString(R.string.new_sketchbook_creating)
        lifecycleScope.launch {
            try {
                if (repo.nameTaken(parentFolderId, ObjectType.SKETCHBOOK, name)) {
                    Dialogs.problem(
                        this@NewSketchbookActivity,
                        R.string.name_problem_title,
                        getString(R.string.new_sketchbook_duplicate, name),
                    )
                    return@launch
                }
                val id = createSketchbook(
                    context = this@NewSketchbookActivity,
                    name = name,
                    parentFolderId = parentFolderId,
                    panel = PanelSize.of(this@NewSketchbookActivity),
                    repo = repo,
                )
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_SKETCHBOOK_ID, id).putExtra(EXTRA_SKETCHBOOK_NAME, name),
                )
                finish()
            } catch (e: Exception) {
                // The half-made file has already cleaned itself up. What matters to the artist is that
                // nothing was added to their library, which is why the message says so rather than
                // reporting whatever went wrong underneath.
                Dialogs.problem(
                    this@NewSketchbookActivity,
                    R.string.new_sketchbook_failed_title,
                    R.string.new_sketchbook_failed,
                )
            } finally {
                creating = false
                binding.btnCreate.isEnabled = true
                binding.btnCreate.text = getString(R.string.new_sketchbook_create)
            }
        }
    }

    companion object {
        private const val EXTRA_PARENT_FOLDER_ID = "parentFolderId"
        const val EXTRA_SKETCHBOOK_ID = "sketchbookId"
        const val EXTRA_SKETCHBOOK_NAME = "sketchbookName"

        fun intent(context: Context, parentFolderId: String?): Intent =
            Intent(context, NewSketchbookActivity::class.java)
                .putExtra(EXTRA_PARENT_FOLDER_ID, parentFolderId)
    }
}
