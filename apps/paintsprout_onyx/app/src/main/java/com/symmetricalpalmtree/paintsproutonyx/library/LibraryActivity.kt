package com.symmetricalpalmtree.paintsproutonyx.library

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.core.ActionSheetDialog
import com.symmetricalpalmtree.paintsproutonyx.core.Dialogs
import com.symmetricalpalmtree.paintsproutonyx.core.IndexGuard
import com.symmetricalpalmtree.paintsproutonyx.core.TopGuard
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeyMaterial
import com.symmetricalpalmtree.paintsproutonyx.data.index.IndexRepository
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectSummary
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectType
import com.symmetricalpalmtree.paintsproutonyx.data.prefs.LibraryPrefs
import com.symmetricalpalmtree.paintsproutonyx.data.sidecarsOf
import com.symmetricalpalmtree.paintsproutonyx.data.soilFile
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivityLibraryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The shelf: folders and sketchbooks, a page at a time.
 *
 * Everything the artist does to their library that is not drawing happens here — make, name, rename,
 * move, throw away, and walk in and out of folders. Three ideas run through it.
 *
 * **The shelf does not scroll.** It paginates. The reason is [LibraryGrid]'s to explain, but the
 * consequence is this screen's to live with: it cannot lay out a single card until it knows how big
 * its own grid area turned out to be, so the first listing waits on a layout pass rather than
 * happening in `onCreate`.
 *
 * **Folders are a fact about the index and nothing else.** There is no directory anywhere that
 * matches what is on this screen — the sketchbook files are UUIDs in one flat `Garden/`. Moving a
 * sketchbook into a folder moves no bytes; it is one column of one row, which is exactly why it can
 * never lose a drawing.
 *
 * **Deleting is the one thing here that is real.** Every other write is a soft delete or a rename in
 * an encrypted index, recoverable in principle. A delete takes the `.soil` off the disk, and there is
 * no undo and no bin — which is why it is the only action on this screen that asks first, and why the
 * folder question names what is inside before it goes.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var prefs: LibraryPrefs
    private val repo by lazy { IndexRepository() }

    private var folderId: String? = null
    private var pageIndex = 0
    private var pageCount = 1
    private var items = emptyList<CardItem>()
    private var grid: LibraryGrid? = null

    /**
     * Which listing is the current one.
     *
     * A refresh reads the index across several suspension points, and several can be in flight at
     * once — a folder tapped while `onResume`'s refresh is still running, a delete finishing behind a
     * navigation. Room answers on a pool, so they do not necessarily come back in the order they were
     * asked. Without this the slower, older read can land last and bind a shelf that is half one
     * folder and half another. It corrects itself on the next refresh, which is exactly what makes it
     * the kind of thing nobody ever catches.
     */
    private var listingGeneration = 0

    private val newSketchbookLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // G3 opens the new sketchbook straight from here. Until there is a screen to open it on,
            // the honest thing is to come back to the shelf with the new card on it.
            lifecycleScope.launch { refresh() }
        }
    }

    private val movePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) lifecycleScope.launch { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        prefs = LibraryPrefs(this)
        folderId = prefs.folderId
        backCallback.isEnabled = folderId != null
        onBackPressedDispatcher.addCallback(this, backCallback)
        wireBars()

        // Debug build only. The release twin of DebugMenu hides the control instead of filling it, so
        // a shipped build has no path to the recovery key at all.
        DebugMenu.install(this, binding.overflowButton)

        // The grid area's size is not known until it has been laid out, and nothing can be listed
        // before it is — six cards or four is the difference between a listing and the wrong listing.
        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (grid != null) return@addOnGlobalLayoutListener
            val width = binding.gridContainer.width
            val height = binding.gridContainer.height
            if (width <= 0 || height <= 0) return@addOnGlobalLayoutListener
            grid = LibraryGrid(binding.gridContainer, ::onCardTap, ::onCardLongPress).also {
                it.measure(
                    resources.displayMetrics.density,
                    resources.getDimensionPixelSize(R.dimen.card_gap),
                )
            }
            lifecycleScope.launch {
                // The folder this screen was left in may have been deleted since — by the folder
                // sweep on the previous visit, or by a restore. Landing in it would draw a breadcrumb
                // trail to somewhere that is not on the shelf any more.
                if (folderId != null && repo.alive(folderId!!) == null) navigateTo(null) else refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the new-sketchbook screen or the move picker, the shelf is out of date.
        if (grid != null) lifecycleScope.launch { refresh() }
    }

    override fun onPause() {
        super.onPause()
        if (::prefs.isInitialized) prefs.folderId = folderId
    }

    private fun wireBars() {
        binding.btnUp.setOnClickListener { navigateUp() }
        binding.btnSort.setOnClickListener { showSortSheet() }
        binding.btnNewFolder.setOnClickListener { showNewFolderDialog() }
        binding.btnNewSketchbook.setOnClickListener {
            newSketchbookLauncher.launch(NewSketchbookActivity.intent(this, folderId))
        }
        binding.btnFirst.setOnClickListener { goToPage(0) }
        binding.btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        binding.btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        binding.btnLast.setOnClickListener { goToPage(pageCount - 1) }

        // The panel has no hover and no ripple, so a control that is only a glyph has no other way of
        // saying what it does. A long press reads it out.
        listOf(
            binding.btnUp, binding.btnSort, binding.overflowButton,
            binding.btnFirst, binding.btnPrev, binding.btnNext, binding.btnLast,
        ).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    private suspend fun refresh() {
        val generation = ++listingGeneration
        val here = folderId
        renderBreadcrumb()
        val folders = Sorting.sort(repo.folders(here), prefs.sortField, prefs.sortOrder)
        val sketchbooks = Sorting.sort(repo.sketchbooks(here), prefs.sortField, prefs.sortOrder)
        // Both halves were read against `here`, and this is the last listing anybody asked for.
        // Anything else on screen would be a shelf assembled out of two different folders.
        if (generation != listingGeneration) return
        items = folders.map { CardItem.Folder(it) } + sketchbooks.map { CardItem.Sketchbook(it, metaLine(it)) }

        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        pageCount = GridGeometry.pageCount(items.size, grid?.cardsPerPage ?: 1)
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        grid?.bind(items, pageIndex)
        renderPager()
    }

    /** "6 pages · 25 Aug 2026" — what the card says under the name. */
    private fun metaLine(summary: ObjectSummary): String {
        val pages = summary.pageCount ?: 1
        val pagesText =
            if (pages == 1) getString(R.string.card_meta_pages_one) else getString(R.string.card_meta_pages, pages)
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(summary.updatedAt))
        return getString(R.string.card_meta, pagesText, date)
    }

    private fun renderPager() {
        // INVISIBLE rather than GONE: a single page still reserves the pager's width, so the New
        // buttons beside it do not shuffle sideways the moment a seventh sketchbook is made.
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = getString(R.string.pager_label, pageIndex + 1, pageCount)
    }

    private fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, pageCount - 1)
        if (clamped == pageIndex) return
        pageIndex = clamped
        grid?.bind(items, pageIndex)
        renderPager()
    }

    // ── Where am I ───────────────────────────────────────────────────────────

    private fun renderBreadcrumb() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()
        lifecycleScope.launch {
            val trail = repo.ancestry(folderId)
            // Built again on arrival rather than added to: this coroutine can land after a second
            // navigation started, and a trail appended to a stale one reads as a folder inside itself.
            container.removeAllViews()
            container.addView(crumb(getString(R.string.library_root)) { navigateTo(null) })
            for (ref in trail) {
                container.addView(separator())
                container.addView(crumb(ref.name) { navigateTo(ref.id) })
            }
            // A trail longer than the bar scrolls, and it is the tail that matters — where you are,
            // not where you started.
            binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
        }
        binding.btnUp.visibility = if (folderId == null) View.INVISIBLE else View.VISIBLE
    }

    private fun crumb(label: String, onClick: () -> Unit): AppCompatTextView = AppCompatTextView(this).apply {
        text = label
        textSize = 16f
        setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.inkBlack))
        maxLines = 1
        val pad = (6 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun separator(): AppCompatTextView = AppCompatTextView(this).apply {
        text = " / "
        textSize = 16f
        setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.inkBlack))
    }

    /**
     * Back walks out of a folder before it walks out of the app.
     *
     * Registered with the dispatcher rather than written as `onBackPressed`, and that is not a style
     * choice. This app targets SDK 35 and the NA5C runs Android 15, where predictive back is on by
     * default for apps targeting 35 — and the framework then never calls `Activity.onBackPressed` at
     * all. An override there compiles, reads correctly, and is dead code: back would leave the app
     * from three folders down and nobody would find out from the source.
     *
     * **This one cannot be checked from a desk.** An injected `KEYCODE_BACK` does not reach apps on
     * this device — not this one, and not the system settings either — so the walk that would have
     * caught it silently passes. It needs a thumb.
     *
     * The callback is only enabled while there is somewhere to go up to. Disabled at the root, the
     * press falls through to the system's own answer and leaves the app, which is what it should do.
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = navigateUp()
    }

    private fun navigateTo(id: String?) {
        folderId = id
        pageIndex = 0
        prefs.folderId = id
        backCallback.isEnabled = id != null
        lifecycleScope.launch { refresh() }
    }

    private fun navigateUp() {
        lifecycleScope.launch {
            val here = folderId ?: return@launch
            val trail = repo.ancestry(here)
            navigateTo(if (trail.size >= 2) trail[trail.size - 2].id else null)
        }
    }


    // ── Cards ────────────────────────────────────────────────────────────────

    private fun onCardTap(item: CardItem) {
        when (item) {
            is CardItem.Folder -> navigateTo(item.summary.id)
            // G3 gives a sketchbook somewhere to open. Until it does, tapping one is not a failure
            // worth a dialog — there is simply nothing on the other side of it yet.
            is CardItem.Sketchbook -> Unit
        }
    }

    private fun onCardLongPress(item: CardItem) {
        val s = item.summary
        ActionSheetDialog(this)
            .title(s.name)
            .addAction(getString(R.string.action_rename)) { showRenameDialog(s) }
            .addAction(getString(R.string.action_move)) { showMovePicker(s) }
            .addAction(getString(R.string.action_delete)) {
                if (item is CardItem.Folder) confirmDeleteFolder(s) else confirmDeleteSketchbook(s)
            }
            .show()
    }

    private fun showMovePicker(s: ObjectSummary) {
        movePickerLauncher.launch(FolderPickerActivity.intent(this, s.id, s.type, s.name, s.parentId))
    }

    // ── Naming ───────────────────────────────────────────────────────────────

    /**
     * A name dialog: a bordered field, a title, and a verb.
     *
     * [onAccept] runs on the main thread with a trimmed, rule-checked name and is responsible for the
     * duplicate check, which needs the index and therefore a coroutine. It dismisses the dialog
     * itself, because whether the name was acceptable is not known until that read comes back — and a
     * dialog that closes before the answer arrives has already told the artist their name was fine.
     */
    private fun nameDialog(
        titleRes: Int,
        hintRes: Int,
        confirmRes: Int,
        initial: String?,
        onAccept: (dialog: AlertDialog, name: String) -> Unit,
    ) {
        val density = resources.displayMetrics.density
        val field = AppCompatEditText(this).apply {
            hint = getString(hintRes)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.inkBlack))
            setHintTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.inkLight))
            background = ContextCompat.getDrawable(this@LibraryActivity, R.drawable.shape_bordered)
            val pad = resources.getDimensionPixelSize(R.dimen.dialog_field_padding)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            if (initial != null) {
                setText(initial)
                selectAll()
            }
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * density).toInt()
            setPadding(side, (16 * density).toInt(), side, 0)
            addView(field)
        }
        val dialog = Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(wrapper)
                .setPositiveButton(confirmRes, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        // The positive button is wired after the dialog is showing, and not through the builder,
        // because a builder listener dismisses the dialog before it runs — which would take the
        // typed name off the screen every time it was rejected.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = field.text.toString().trim()
                val problem = NameRules.validate(name)
                if (problem != null) {
                    Dialogs.problem(this, R.string.name_problem_title, problem)
                    return@setOnClickListener
                }
                onAccept(dialog, name)
            }
        }
        dialog.show()
    }

    private fun showNewFolderDialog() {
        nameDialog(
            R.string.new_folder_title,
            R.string.new_folder_hint,
            R.string.new_folder_create,
            initial = null,
        ) { dialog, name ->
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = false
            lifecycleScope.launch {
                try {
                    if (repo.nameTaken(folderId, ObjectType.FOLDER, name)) {
                        Dialogs.problem(
                            this@LibraryActivity,
                            R.string.name_problem_title,
                            getString(R.string.new_folder_duplicate, name),
                        )
                        return@launch
                    }
                    repo.createFolder(name, folderId)
                    dialog.dismiss()
                    refresh()
                } finally {
                    button.isEnabled = true
                }
            }
        }
    }

    private fun showRenameDialog(s: ObjectSummary) {
        nameDialog(R.string.rename_title, R.string.rename_hint, R.string.action_rename, initial = s.name) { dialog, name ->
            // Renaming something to what it is already called is not a clash and not an edit — it is
            // a change of mind, and it should close the dialog without moving `updatedAt`.
            if (name == s.name) {
                dialog.dismiss()
                return@nameDialog
            }
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = false
            lifecycleScope.launch {
                try {
                    if (repo.nameTaken(s.parentId, s.type, name, s.id)) {
                        val message = if (s.type == ObjectType.SKETCHBOOK) {
                            getString(R.string.rename_duplicate_sketchbook, name)
                        } else {
                            getString(R.string.rename_duplicate_folder, name)
                        }
                        Dialogs.problem(this@LibraryActivity, R.string.name_problem_title, message)
                        return@launch
                    }
                    repo.rename(s.id, name)
                    dialog.dismiss()
                    refresh()
                } finally {
                    button.isEnabled = true
                }
            }
        }
    }

    // ── Sort ─────────────────────────────────────────────────────────────────

    private fun showSortSheet() {
        val field = prefs.sortField
        val order = prefs.sortOrder
        fun row(labelRes: Int, f: SortField, o: SortOrder) = Triple(getString(labelRes), f, o)
        val rows = listOf(
            row(R.string.sort_name_asc, SortField.NAME, SortOrder.ASC),
            row(R.string.sort_name_desc, SortField.NAME, SortOrder.DESC),
            row(R.string.sort_modified_desc, SortField.MODIFIED, SortOrder.DESC),
            row(R.string.sort_modified_asc, SortField.MODIFIED, SortOrder.ASC),
        )
        val sheet = ActionSheetDialog(this).title(getString(R.string.sort_title))
        for ((label, f, o) in rows) {
            sheet.addAction(label, ticked = field == f && order == o) {
                prefs.sortField = f
                prefs.sortOrder = o
                // Back to the front of the shelf: the sketchbook that was on page three under the old
                // order is somewhere else entirely under the new one, and staying on page three would
                // land the artist among things they did not ask to see.
                pageIndex = 0
                lifecycleScope.launch { refresh() }
            }
        }
        sheet.show()
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    private fun confirmDeleteSketchbook(s: ObjectSummary) {
        Dialogs.confirm(
            this,
            getString(R.string.delete_sketchbook_title, s.name),
            getString(R.string.delete_sketchbook_body),
            R.string.delete_confirm,
        ) {
            lifecycleScope.launch {
                repo.deleteSketchbook(s.id)
                withContext(Dispatchers.IO) { discard(this@LibraryActivity, s.id) }
                refresh()
            }
        }
    }

    /**
     * Deleting a folder takes what is inside it — and says how much before it does.
     *
     * The count is read before the question is asked rather than after it is answered, because
     * "Delete Studies?" and "3 sketchbooks and 1 folder inside it go with it" are the same sentence
     * to the person reading them. A confirmation that does not say what it is about to take is a
     * confirmation of nothing.
     */
    private fun confirmDeleteFolder(s: ObjectSummary) {
        lifecycleScope.launch {
            val contents = repo.countWithin(s.id)
            val body = if (contents.isEmpty) {
                getString(R.string.delete_folder_body_empty)
            } else {
                getString(R.string.delete_folder_body, describeContents(this@LibraryActivity, contents))
            }
            Dialogs.confirm(
                this@LibraryActivity,
                getString(R.string.delete_folder_title, s.name),
                body,
                R.string.delete_confirm,
            ) {
                lifecycleScope.launch {
                    val sketchbookIds = repo.deleteFolderRecursive(s.id)
                    withContext(Dispatchers.IO) {
                        sketchbookIds.forEach { discard(this@LibraryActivity, it) }
                    }
                    // Standing inside the folder that just went: the shelf has to leave with it.
                    if (folderId == s.id) navigateTo(s.parentId) else refresh()
                }
            }
        }
    }

    /**
     * Take a sketchbook's file off the disk, sidecars and cached key included.
     *
     * The index has already stamped the row; this is the half it deliberately does not do, because a
     * repository that deleted files behind its caller's back would make every soft delete a hard one.
     * The cached raw key goes too, from RAM as well as the Keystore: a key left behind under a dead
     * id is one that will one day be tried against whatever next claims that name and reported as
     * corruption — a bug this family has actually shipped once.
     */
    private fun discard(context: Context, sketchbookId: String) {
        val file = soilFile(context, sketchbookId)
        file.delete()
        sidecarsOf(file).forEach { it.delete() }
        KeyMaterial.invalidate(context, sketchbookId)
    }

    private fun describeContents(context: Context, contents: IndexRepository.FolderContents): String {
        val parts = mutableListOf<String>()
        if (contents.sketchbooks == 1) parts += context.getString(R.string.count_sketchbook_one)
        else if (contents.sketchbooks > 1) parts += context.getString(R.string.count_sketchbooks, contents.sketchbooks)
        if (contents.folders == 1) parts += context.getString(R.string.count_folder_one)
        else if (contents.folders > 1) parts += context.getString(R.string.count_folders, contents.folders)
        return if (parts.size == 2) {
            context.getString(R.string.count_joined, parts[0], parts[1])
        } else {
            parts.first()
        }
    }
}
