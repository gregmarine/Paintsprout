package com.symmetricalpalmtree.paintsproutonyx.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.core.Dialogs
import com.symmetricalpalmtree.paintsproutonyx.core.IndexGuard
import com.symmetricalpalmtree.paintsproutonyx.core.TopGuard
import com.symmetricalpalmtree.paintsproutonyx.data.index.IndexRepository
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectSummary
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectType
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivityFolderPickerBinding
import kotlinx.coroutines.launch

/**
 * Where shall this go: a shelf you can walk through, showing folders only.
 *
 * Moving is a browse rather than a list of destinations because the destinations are a tree and the
 * artist made it. A flat list of every folder in the library, indented, is legible for about six
 * folders and unusable after that — and it would have to invent a way to say "the root", which the
 * breadcrumb already does by being a breadcrumb.
 *
 * Sketchbooks are not shown at all. Nothing can be dropped into a sketchbook, and cards for things
 * that cannot be chosen are a page of dead ends between the folders that can be.
 *
 * Two moves are refused, and both are refused here rather than in the index, because both are only
 * mistakes from the point of view of the person making them:
 *
 *  - **Into a folder that already holds this name.** Nothing breaks — the ids differ — but two
 *    sketchbooks called the same thing in the same folder is a library that cannot be read.
 *  - **A folder into itself or its own contents.** That one is not recoverable by looking: the folder
 *    and everything in it simply stop being reachable from the root, still there, still taking up
 *    space, gone.
 */
class FolderPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderPickerBinding
    private val repo by lazy { IndexRepository() }

    private var movingId = ""
    private var movingType = ObjectType.SKETCHBOOK
    private var movingName = ""
    private var folderId: String? = null

    private var folders = emptyList<ObjectSummary>()
    private var pageIndex = 0
    private var pageCount = 1
    private var grid: LibraryGrid? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        movingId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { finish(); return }
        movingType = intent.getStringExtra(EXTRA_ITEM_TYPE) ?: ObjectType.SKETCHBOOK
        movingName = intent.getStringExtra(EXTRA_ITEM_NAME).orEmpty()
        // Opens where the thing already lives, so "move it one folder up" is one tap on the
        // breadcrumb rather than a walk down from the root.
        folderId = intent.getStringExtra(EXTRA_CURRENT_PARENT)

        backCallback.isEnabled = folderId != null
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.movingLabel.text = getString(R.string.picker_title, movingName)
        binding.btnBack.setOnClickListener { navigateUp() }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnMoveHere.setOnClickListener { moveHere() }
        binding.btnFirst.setOnClickListener { goToPage(0) }
        binding.btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        binding.btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        binding.btnLast.setOnClickListener { goToPage(pageCount - 1) }
        listOf(binding.btnBack, binding.btnFirst, binding.btnPrev, binding.btnNext, binding.btnLast)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (grid != null) return@addOnGlobalLayoutListener
            val width = binding.gridContainer.width
            val height = binding.gridContainer.height
            if (width <= 0 || height <= 0) return@addOnGlobalLayoutListener
            // The same grid as the shelf, deliberately. Choosing a folder should look like the place
            // it is choosing from, or the artist has to learn a second library.
            grid = LibraryGrid(binding.gridContainer, ::onCardTap, onLongPress = {}).also {
                it.measure(
                    resources.displayMetrics.density,
                    resources.getDimensionPixelSize(R.dimen.card_gap),
                )
            }
            lifecycleScope.launch { refresh() }
        }
    }

    private suspend fun refresh() {
        renderBreadcrumb()
        // A folder being moved cannot be shown as a destination — the one place it certainly may not
        // go is inside itself, and offering it would be offering the one move that loses it.
        val excludeId = if (movingType == ObjectType.FOLDER) movingId else ""
        folders = Sorting.sort(repo.folders(folderId), SortField.NAME, SortOrder.ASC)
            .filter { it.id != excludeId }

        binding.emptyState.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        pageCount = GridGeometry.pageCount(folders.size, grid?.cardsPerPage ?: 1)
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        grid?.bind(folders.map { CardItem.Folder(it) }, pageIndex)
        renderPager()
    }

    private fun renderPager() {
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = getString(R.string.pager_label, pageIndex + 1, pageCount)
    }

    private fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, pageCount - 1)
        if (clamped == pageIndex) return
        pageIndex = clamped
        grid?.bind(folders.map { CardItem.Folder(it) }, pageIndex)
        renderPager()
    }

    private fun onCardTap(item: CardItem) {
        if (item is CardItem.Folder) navigateTo(item.summary.id)
    }

    private fun renderBreadcrumb() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()
        lifecycleScope.launch {
            val trail = repo.ancestry(folderId)
            container.removeAllViews()
            container.addView(crumb(getString(R.string.library_root)) { navigateTo(null) })
            for (ref in trail) {
                container.addView(separator())
                container.addView(crumb(ref.name) { navigateTo(ref.id) })
            }
            binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
        }
        binding.btnBack.visibility = if (folderId == null) View.INVISIBLE else View.VISIBLE
    }

    private fun crumb(label: String, onClick: () -> Unit): AppCompatTextView = AppCompatTextView(this).apply {
        text = label
        textSize = 16f
        setTextColor(ContextCompat.getColor(this@FolderPickerActivity, R.color.inkBlack))
        maxLines = 1
        val pad = (6 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun separator(): AppCompatTextView = AppCompatTextView(this).apply {
        text = " / "
        textSize = 16f
        setTextColor(ContextCompat.getColor(this@FolderPickerActivity, R.color.inkBlack))
    }

    /** See [LibraryActivity]'s callback for why this is not an `onBackPressed` override. */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = navigateUp()
    }

    private fun navigateTo(id: String?) {
        folderId = id
        pageIndex = 0
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

    private fun moveHere() {
        lifecycleScope.launch {
            if (repo.nameTaken(folderId, movingType, movingName, movingId)) {
                val message = if (movingType == ObjectType.SKETCHBOOK) {
                    getString(R.string.picker_collision_sketchbook, movingName)
                } else {
                    getString(R.string.picker_collision_folder, movingName)
                }
                Dialogs.problem(this@FolderPickerActivity, R.string.move_problem_title, message)
                return@launch
            }
            if (movingType == ObjectType.FOLDER && repo.isSelfOrDescendant(folderId, movingId)) {
                Dialogs.problem(this@FolderPickerActivity, R.string.move_problem_title, R.string.picker_into_self)
                return@launch
            }
            repo.move(movingId, folderId)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }


    companion object {
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_ITEM_TYPE = "itemType"
        private const val EXTRA_ITEM_NAME = "itemName"
        private const val EXTRA_CURRENT_PARENT = "currentParent"

        fun intent(
            context: Context,
            itemId: String,
            itemType: String,
            itemName: String,
            currentParent: String?,
        ): Intent = Intent(context, FolderPickerActivity::class.java)
            .putExtra(EXTRA_ITEM_ID, itemId)
            .putExtra(EXTRA_ITEM_TYPE, itemType)
            .putExtra(EXTRA_ITEM_NAME, itemName)
            .putExtra(EXTRA_CURRENT_PARENT, currentParent)
    }
}
