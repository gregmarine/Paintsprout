package com.symmetricalpalmtree.paintsproutonyx.library

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import com.symmetricalpalmtree.paintsproutonyx.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.paintsproutonyx.data.sidecarsOf
import com.symmetricalpalmtree.paintsproutonyx.data.soilFile
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivityLibraryBinding
import com.symmetricalpalmtree.paintsproutonyx.sketchbook.SketchbookActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *
 * **Pinned and Recent are this screen wearing a different list**, not screens of their own — see
 * [BrowseMode]. The grid, the paging, the long-press sheet, the covers and the sort are all identical
 * in the three; what changes is where the cards come from and what the top bar says. Two more
 * Activities would be two more copies of every one of those, and three places to fix the next thing
 * found wrong with a card.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var prefs: LibraryPrefs
    private lateinit var recents: RecentsPrefs
    private val repo by lazy { IndexRepository() }
    private val covers by lazy { CoverLoader(repo) }
    private val listing by lazy { ShelfListing(this, repo, prefs, recents) }

    private var folderId: String? = null
    private var pageIndex = 0
    private var pageCount = 1
    private var items = emptyList<CardItem>()
    private var grid: LibraryGrid? = null

    /**
     * Which of the three shelves is showing.
     *
     * Held here as well as in the prefs because the prefs are where it is *left*, not where it is
     * read from a hundred times while the screen is up. [folderId] stays exactly where it was
     * underneath a mode, so closing the mode puts the artist back in the folder they were standing
     * in rather than at the root.
     */
    private var mode: BrowseMode = BrowseMode.NORMAL

    /**
     * Which sketchbooks wear the pin badge, read once per listing.
     *
     * One query for a whole shelf rather than one per card — see [IndexRepository.pinnedIds]. It is
     * read for every mode including Pinned itself, where the answer is "all of them" and the badge
     * is redundant: a badge that vanished on the one shelf where it is guaranteed true would be a
     * card that changes appearance depending on how you got to it, and a card should look like
     * itself wherever it is standing.
     */
    private var pinned: Set<String> = emptySet()

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
            // Straight onto the page. Making a sketchbook is not an act of filing — it is the first
            // half of sitting down to draw — so landing back on the shelf and asking the artist to
            // find the card they just made would be the shelf getting in its own way. The refresh
            // still happens, so the card is there behind them when they come back.
            lifecycleScope.launch { refresh() }
            result.data?.getStringExtra(NewSketchbookActivity.EXTRA_SKETCHBOOK_ID)
                ?.takeIf { it.isNotEmpty() }
                ?.let { openSketchbook(it) }
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
        recents = RecentsPrefs(this)
        folderId = prefs.folderId
        mode = prefs.mode
        backCallback.isEnabled = canGoBack()
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
                // Once, on the way in. The pinned list is a row in the index like any other and it
                // is made on the read path rather than by a migration — a library that has never
                // pinned anything has no such row, and the Pinned mode asking for its members is
                // exactly the moment it should exist.
                runCatching { repo.ensurePinnedListExists() }
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
        // Where the shelf was left: the folder, and which of the three shelves was on top of it.
        if (::prefs.isInitialized) {
            prefs.folderId = folderId
            prefs.mode = mode
        }
    }

    private fun wireBars() {
        binding.btnUp.setOnClickListener { navigateUp() }
        binding.btnSort.setOnClickListener { showSortSheet() }
        // Tapping the button for the mode already showing puts it away again. The buttons carry no
        // "on" state — nothing on this panel that changes appearance to mean "currently selected"
        // can be trusted to have been redrawn — so the tap that opened Pinned has to be the tap that
        // closes it, or the artist is left hunting for the way out of a shelf they can see but the
        // chrome will not admit to.
        binding.btnPinned.setOnClickListener {
            setMode(if (mode == BrowseMode.PINNED) BrowseMode.NORMAL else BrowseMode.PINNED)
        }
        binding.btnRecents.setOnClickListener {
            setMode(if (mode == BrowseMode.RECENTS) BrowseMode.NORMAL else BrowseMode.RECENTS)
        }
        binding.btnCloseMode.setOnClickListener { setMode(BrowseMode.NORMAL) }
        binding.btnNewFolder.setOnClickListener { showNewFolderDialog() }
        binding.btnNewSketchbook.setOnClickListener {
            newSketchbookLauncher.launch(NewSketchbookActivity.intent(this, folderId))
        }
        binding.btnFirst.setOnClickListener { goToPage(0) }
        binding.btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        binding.btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        binding.btnLast.setOnClickListener { goToPage(pageCount - 1) }

        // Every control in both bars is an icon and nothing else, and the panel has no hover and no
        // ripple to explain one. A long press reads the content description out, which is the only
        // way an icon button here can say what it is — so every one of them gets it, and any button
        // added later has to join this list or it is a mark with no name.
        listOf(
            binding.btnUp, binding.btnCloseMode, binding.overflowButton,
            binding.btnPinned, binding.btnRecents,
            binding.btnSort, binding.btnNewFolder, binding.btnNewSketchbook,
            binding.btnFirst, binding.btnPrev, binding.btnNext, binding.btnLast,
        ).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    private suspend fun refresh() {
        val generation = ++listingGeneration
        renderChrome()
        // A cover changes whenever a sketchbook is closed, which is to say between one visit to this
        // screen and the next. Anything held from the last listing is a picture of the page the
        // artist was on before the session they have just finished.
        covers.forget()
        val listed = listing.cards(mode, folderId)
        // The listing was read against one folder and one mode, and this is the last one anybody
        // asked for. Anything else on screen would be a shelf assembled out of two different places
        // — a fence that matters more now than in G2, because a mode change is a second thing that
        // can start a listing while one is still in flight.
        if (generation != listingGeneration) return
        items = listed
        pinned = runCatching { repo.pinnedIds() }.getOrDefault(emptySet())

        binding.emptyState.setText(emptyStateText())
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        pageCount = GridGeometry.pageCount(items.size, grid?.cardsPerPage ?: 1)
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        bindPage(generation)
    }

    /**
     * Hand the visible slice to the grid, with its covers and its badges.
     *
     * **The covers are read before the cards are drawn, not painted in afterwards.** The grid is
     * torn down and rebuilt on every bind, so binding once bare and again with the pictures would be
     * two full-screen repaints for one page turn — on this panel that is a visible flash of an empty
     * shelf followed by the real one. Six covers out of the index is a wait short enough to spend,
     * and the alternative is a shelf that always shows the artist its own scaffolding first.
     *
     * The fence is checked again after that wait, for the same reason [refresh] checks it: the cover
     * read is another suspension point, and a page bound after a newer listing has replaced [items]
     * is a page of the wrong shelf.
     */
    private suspend fun bindPage(generation: Int) {
        val perPage = grid?.cardsPerPage ?: 1
        val start = pageIndex * perPage
        val slice = if (start >= items.size) emptyList() else items.subList(start, minOf(start + perPage, items.size))
        val ids = slice.filterIsInstance<CardItem.Sketchbook>().map { it.summary.id }
        val pictures = covers.load(ids)
        if (generation != listingGeneration) return
        grid?.bind(items, pageIndex, pictures, pinned)
        renderPager()
    }

    /** What an empty shelf says. Each mode is empty for its own reason and names its own way out. */
    @StringRes
    private fun emptyStateText(): Int = when (mode) {
        BrowseMode.NORMAL -> R.string.library_empty
        BrowseMode.PINNED -> R.string.library_empty_pinned
        BrowseMode.RECENTS -> R.string.library_empty_recents
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
        // A page turn is now a coroutine, because the new slice's covers have to come out of the
        // encrypted index before there is anything to draw. A page already visited is nearly free —
        // its blobs are still in [covers] — but the trip through IO is taken either way rather than
        // branched on, because a page turn that is sometimes synchronous and sometimes not is a page
        // turn that reorders itself against a refresh under load.
        lifecycleScope.launch { bindPage(listingGeneration) }
    }

    // ── Where am I ───────────────────────────────────────────────────────────

    /**
     * Make the two bars say which shelf this is.
     *
     * A mode is not a place in the folder tree, so the crumb trail has nothing to draw and the Up
     * arrow has nowhere to point: the trail is replaced by the mode's name and Up by a close, which
     * is the only way out of a mode and says so.
     *
     * **New folder and New sketchbook go INVISIBLE, not GONE**, for the same reason the pager does:
     * they hold the right-hand end of the bottom bar, and letting them collapse would slide the
     * pager sideways every time a mode opened or closed. A pager that moves under the thumb turning
     * pages is a worse fault than two buttons standing in a bar with nothing to do — and they are
     * not merely idle, they are untappable, which is the part GONE and INVISIBLE agree on. Making
     * something *inside* a mode would have to invent an answer to "in which folder", and there is
     * no honest one: Pinned and Recent are views of the library, not places in it.
     */
    private fun renderChrome() {
        val inMode = mode != BrowseMode.NORMAL
        binding.breadcrumbScroll.visibility = if (inMode) View.GONE else View.VISIBLE
        binding.modeTitle.visibility = if (inMode) View.VISIBLE else View.GONE
        binding.btnCloseMode.visibility = if (inMode) View.VISIBLE else View.GONE
        val newButtons = if (inMode) View.INVISIBLE else View.VISIBLE
        binding.btnNewFolder.visibility = newButtons
        binding.btnNewSketchbook.visibility = newButtons
        if (inMode) {
            binding.modeTitle.setText(
                if (mode == BrowseMode.PINNED) R.string.mode_title_pinned else R.string.mode_title_recents
            )
            binding.btnUp.visibility = View.GONE
        } else {
            // Only in NORMAL: the trail is built by walking the index and it would be a listing read
            // for a bar that is not on the screen, and worse, a coroutine that could land after the
            // mode closed and draw the trail of a folder nobody is standing in.
            renderBreadcrumb()
        }
    }

    /**
     * Change shelves.
     *
     * Back to the front of the new one, every time. Page four of Recent has nothing to do with page
     * four of the shelf, and arriving on it would land the artist among cards they did not ask to
     * see — the same reasoning the sort sheet uses.
     */
    private fun setMode(next: BrowseMode) {
        if (mode == next) return
        mode = next
        prefs.mode = next
        pageIndex = 0
        backCallback.isEnabled = canGoBack()
        lifecycleScope.launch { refresh() }
    }

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
        // GONE rather than INVISIBLE: at the root the trail should start at the edge of the panel,
        // not a button's width in from it, with nothing standing there to explain the gap.
        binding.btnUp.visibility = if (folderId == null) View.GONE else View.VISIBLE
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
     * Back closes a mode before it walks out of a folder, and walks out of a folder before it walks
     * out of the app.
     *
     * That order is the one the artist got here in. A mode is put *on top of* the folder they were
     * standing in — [folderId] is left exactly where it was underneath it — so backing out of the
     * mode has to put them down where they were, not one folder further up. Leaving the app from
     * inside Pinned because back skipped over the mode it was showing is the same fault as leaving
     * the app from three folders down.
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
     * The callback is only enabled while there is somewhere to go: a mode to close or a folder to
     * climb out of. Disabled on the root shelf, the press falls through to the system's own answer
     * and leaves the app, which is what it should do.
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (mode != BrowseMode.NORMAL) setMode(BrowseMode.NORMAL) else navigateUp()
        }
    }

    /** Somewhere to go back to: a mode standing over the shelf, or a folder above this one. */
    private fun canGoBack(): Boolean = mode != BrowseMode.NORMAL || folderId != null

    private fun navigateTo(id: String?) {
        folderId = id
        pageIndex = 0
        prefs.folderId = id
        backCallback.isEnabled = canGoBack()
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
            // Only ever reachable from the shelf itself — neither mode lists folders.
            is CardItem.Folder -> navigateTo(item.summary.id)
            is CardItem.Sketchbook -> openSketchbook(item.summary.id)
        }
    }

    /**
     * Open a sketchbook, and remember that it was opened.
     *
     * **The record goes to a preference file and never to the index.** Opening is not work: the
     * index's `updatedAt` is what "Last worked on" sorts by, and a shelf that filed everything the
     * artist looked at as everything they worked on is a shelf they stop being able to find their
     * way around — see [IndexRepository]. Recent is the answer to a different question and keeps its
     * own short list of ids.
     *
     * Written before the page is launched rather than after, because "after" is a callback on a
     * screen that may not be resumed again for hours, or at all: this device kills background
     * processes as a matter of routine. It is a twenty-entry JSON encode and an `apply()`, which is
     * a write handed to another thread, so the tap does not wait on the disk for it.
     */
    private fun openSketchbook(id: String) {
        recents.record(id)
        startActivity(SketchbookActivity.intent(this, id))
    }

    /**
     * The long-press sheet. A folder's is unchanged; a sketchbook's gains Pin at the top.
     *
     * **Whether it is pinned is read before the sheet is built, not while it is up.** A sheet that
     * opened saying "Pin" on a card that is already pinned would be a sheet that lies until it is
     * tapped, and the tap would then unpin the sketchbook the artist was trying to pin. The read is
     * one row and the sheet appears a frame later, which is a frame well spent on a row whose whole
     * job is to say which way the toggle is currently pointing.
     */
    private fun onCardLongPress(item: CardItem) {
        val s = item.summary
        if (item is CardItem.Folder) {
            sheetFor(s, pinnedNow = null).show()
            return
        }
        lifecycleScope.launch {
            val isPinned = runCatching { repo.isPinned(s.id) }.getOrDefault(false)
            sheetFor(s, pinnedNow = isPinned).show()
        }
    }

    /** [pinnedNow] null is a folder, which cannot be pinned and gets no such row. */
    private fun sheetFor(s: ObjectSummary, pinnedNow: Boolean?): ActionSheetDialog {
        val sheet = ActionSheetDialog(this).title(s.name)
        if (pinnedNow != null) {
            val label = if (pinnedNow) R.string.action_unpin else R.string.action_pin
            val icon = if (pinnedNow) R.drawable.ic_pin_off else R.drawable.ic_pin
            sheet.addAction(getString(label), icon) {
                lifecycleScope.launch {
                    if (pinnedNow) repo.unpin(s.id) else repo.pin(s.id)
                    // The badge on the card behind the sheet has just become wrong, and in the
                    // Pinned mode the card itself has just left or joined the shelf.
                    refresh()
                }
            }
        }
        return sheet
            .addAction(getString(R.string.action_rename), R.drawable.ic_edit) { showRenameDialog(s) }
            .addAction(getString(R.string.action_move), R.drawable.ic_move_page) { showMovePicker(s) }
            .addAction(getString(R.string.action_delete), R.drawable.ic_trash) {
                if (s.type == ObjectType.FOLDER) confirmDeleteFolder(s) else confirmDeleteSketchbook(s)
            }
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

    /**
     * The sort sheet, which is the same sheet on all three shelves.
     *
     * **In the Recent mode it still opens, still ticks, still saves — and the cards do not move.**
     * That looks like a bug and is not one: recency is the whole content of that shelf, and a
     * "Recent" list sorted by name is a list that has stopped answering the question it was opened
     * to answer. The choice is kept all the same and takes effect the moment the mode is closed, so
     * the tap is not thrown away. It is not disabled or hidden, because nothing in this app's chrome
     * ever is — a button whose look changes with the state behind it cannot be trusted to have been
     * redrawn on this panel, and a faded control reads as a broken app.
     */
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
            sheet.addAction(label, if (field == f && order == o) R.drawable.ic_check else null) {
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
