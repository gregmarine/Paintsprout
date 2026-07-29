package com.symmetricalpalmtree.paintsprout

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.symmetricalpalmtree.paintsprout.paint.Calibration
import com.symmetricalpalmtree.paintsprout.data.LastOpen
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.IndexObject
import com.symmetricalpalmtree.paintsprout.data.index.IndexType
import com.symmetricalpalmtree.paintsprout.data.index.LibrarySort
import android.net.Uri
import com.symmetricalpalmtree.paintsprout.crypto.AttemptLimiter
import com.symmetricalpalmtree.paintsprout.crypto.CryptoStores
import com.symmetricalpalmtree.paintsprout.crypto.KeyRotation
import com.symmetricalpalmtree.paintsprout.data.soil.BoundedDecode
import com.symmetricalpalmtree.paintsprout.data.soil.DocumentKeying
import com.symmetricalpalmtree.paintsprout.data.soil.ExportName
import com.symmetricalpalmtree.paintsprout.data.soil.ImportPlan
import com.symmetricalpalmtree.paintsprout.data.soil.SoilImport
import com.symmetricalpalmtree.paintsprout.data.soil.Sketchbooks
import com.symmetricalpalmtree.paintsprout.data.soil.SoilExport
import com.symmetricalpalmtree.paintsprout.paint.CanvasSize
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The library: folders, sketchbooks, and a way to find them.
 *
 * Every card is drawn from an **index row** — name, page count, cover — and never
 * by opening a document. That is the rule the index exists for: deciding whether
 * a book is locked, and what to put on its card, must not require the key you are
 * deciding whether to ask for.
 *
 * Structure lives here and nowhere else. The `Garden/` directory is flat, files
 * are named by UUID, and a folder is a row with a `parentId` — which is precisely
 * what lets a document be exported and imported without carrying any assumption
 * about where it lived.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var grid: GridLayout
    private lateinit var shortcuts: LinearLayout
    private lateinit var empty: TextView
    private lateinit var breadcrumb: TextView
    private lateinit var search: EditText
    private lateinit var upButton: MaterialButton

    /** null is the root. Folder navigation is a stack of these. */
    private var folderId: String? = null
    private var sort = LibrarySort.NAME
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sort = LibrarySort.parse(prefs().getString(KEY_SORT, null))
        setContentView(buildUi())

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back walks up the tree before it leaves the library — the same
                // thing the up button does, because that is what it looks like it
                // should do.
                if (query.isNotEmpty()) {
                    search.setText("")
                } else if (folderId != null) {
                    goUp()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    // --- Loading ------------------------------------------------------------

    private fun refresh() {
        lifecycleScope.launch {
            val index = runCatching { IndexGate.awaitReady() }.getOrNull() ?: return@launch

            val searching = query.isNotBlank()
            val folders = if (searching) emptyList() else sort.applyTo(index.folders(folderId))
            val books = sort.applyTo(
                if (searching) index.searchSketchbooks(query) else index.sketchbooks(folderId),
            )
            val pinnedIds = index.pinnedSketchbooks().map { it.id }.toSet()

            breadcrumb.text = when {
                searching -> getString(R.string.library_results, books.size)
                folderId == null -> getString(R.string.library_title)
                else -> index.breadcrumbOf(folderId!!) + " / " + (index.byId(folderId!!)?.name ?: "")
            }
            upButton.visibility = if (folderId != null && !searching) View.VISIBLE else View.GONE

            // Pinned and Recent are library-wide shortcuts, so they belong at the
            // root of it — not repeated inside every folder, and not competing with
            // a search the user is in the middle of.
            shortcuts.removeAllViews()
            if (folderId == null && !searching) {
                val pinned = index.pinnedSketchbooks()
                val recent = index.recentSketchbooks(limit = 8).filter { it.id !in pinnedIds }
                if (pinned.isNotEmpty()) shortcuts.addView(section(getString(R.string.library_pinned), pinned, pinnedIds))
                if (recent.isNotEmpty()) shortcuts.addView(section(getString(R.string.library_recent), recent, pinnedIds))
            }

            grid.removeAllViews()
            folders.forEach { grid.addView(folderCard(it)) }
            books.forEach { grid.addView(bookCard(it, it.id in pinnedIds)) }

            empty.text = when {
                searching -> getString(R.string.library_no_results)
                folderId == null -> getString(R.string.library_empty)
                else -> getString(R.string.library_folder_empty)
            }
            empty.visibility =
                if (folders.isEmpty() && books.isEmpty() && shortcuts.childCount == 0) View.VISIBLE else View.GONE
        }
    }

    /** A labelled row of cards — Pinned, or Recent. */
    private fun section(title: String, books: List<IndexObject>, pinnedIds: Set<String>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@LibraryActivity).apply {
                    text = title
                    textSize = 13f
                    setTextColor(0xFF6B7075.toInt())
                    setPadding(dp(20), dp(12), dp(20), 0)
                },
            )
            addView(
                android.widget.HorizontalScrollView(this@LibraryActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(
                        LinearLayout(this@LibraryActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(dp(12), 0, dp(12), 0)
                            books.forEach { book ->
                                addView(
                                    bookCard(book, book.id in pinnedIds).apply {
                                        // The card builds itself for the grid, and
                                        // GridLayout's margins do not survive being
                                        // re-generated for a LinearLayout — so a row
                                        // card states its own or the cards touch.
                                        layoutParams = LinearLayout.LayoutParams(
                                            dp(200), ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

    private fun goUp() {
        lifecycleScope.launch {
            val index = IndexGate.awaitReady()
            folderId = folderId?.let { index.byId(it)?.parentId }
            refresh()
        }
    }

    // --- Cards --------------------------------------------------------------

    private fun folderCard(folder: IndexObject): View {
        // A drawable rather than a glyph: the folder characters in Unicode are not
        // in the system font on this device and render as an empty box.
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(140))
            setImageResource(R.drawable.ic_folder)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(44), dp(28), dp(44), dp(28))
            setBackgroundColor(0xFFE9E6DE.toInt())
        }
        return card(icon, folder.name, getString(R.string.library_folder)) {
            folderId = folder.id
            refresh()
        }.apply { setOnLongClickListener { folderActions(folder); true } }
    }

    private fun bookCard(book: IndexObject, pinned: Boolean = false): View {
        val cover = ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(200), dp(140))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEFEDE7.toInt())
            book.blob?.let { bytes ->
                // Sampled to the size a card actually shows, with a ceiling on
                // what will be decoded at all — see [BoundedDecode]. A cover is
                // written by this app today and arrives in a file tomorrow.
                BoundedDecode.sampled(bytes, COVER_EDGE)?.let(::setImageBitmap)
            }
            contentDescription = book.name
        }
        // A private-passphrase book has no cover here and never will — the index
        // is encrypted with the *global* key, and a thumbnail of something the
        // user locked separately would cross exactly the boundary they drew. So
        // the card says "locked" instead of showing blank paper.
        if (book.isPrivateScope) {
            cover.setImageDrawable(null)
            cover.setBackgroundColor(0xFFDCD8D0.toInt())
        }
        // A drawable, not a glyph — this device's font has no pushpin, and Phase 15
        // already learned what that looks like.
        val top = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(140))
            addView(cover)
            if (book.isPrivateScope) {
                addView(
                    ImageView(this@LibraryActivity).apply {
                        setImageResource(R.drawable.ic_lock)
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        imageTintList = android.content.res.ColorStateList.valueOf(0xFF6B7075.toInt())
                        layoutParams = android.widget.FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                            gravity = Gravity.CENTER
                        }
                    },
                )
            }
            if (pinned) {
                addView(
                    ImageView(this@LibraryActivity).apply {
                        setImageResource(R.drawable.ic_pin)
                        setBackgroundColor(0x66000000)
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        layoutParams = android.widget.FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                            gravity = Gravity.TOP or Gravity.END
                            setMargins(0, dp(6), dp(6), 0)
                        }
                    },
                )
            }
        }
        val pages = book.pageCount ?: 0
        return card(top, book.name, resources.getQuantityString(R.plurals.library_pages, pages, pages)) {
            open(book)
        }.apply { setOnLongClickListener { bookActions(book); true } }
    }

    private fun card(top: View, title: String, subtitle: String, onClick: () -> Unit) =
        MaterialCardView(this).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = dp(200)
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            isClickable = true
            addView(
                LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(top)
                    addView(
                        LinearLayout(this@LibraryActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(8), dp(12), dp(12))
                            addView(
                                TextView(this@LibraryActivity).apply {
                                    text = title
                                    textSize = 15f
                                    setTextColor(Color.BLACK)
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                },
                            )
                            addView(
                                TextView(this@LibraryActivity).apply {
                                    text = subtitle
                                    textSize = 12f
                                    setTextColor(0xFF6B7075.toInt())
                                },
                            )
                        },
                    )
                },
            )
            setOnClickListener { onClick() }
        }

    // --- Actions ------------------------------------------------------------

    /**
     * Opens a book, asking for its passphrase first when it has its own.
     *
     * The passphrase never travels: it is turned into a derived key held **in
     * RAM only** for this process, which is what `SKETCHBOOK` scope means, and the
     * editor finds it there. Nothing about a private book is written anywhere the
     * global key could reach it — not a cover, not a cached key, not a pointer.
     */
    private fun open(book: IndexObject) {
        if (!book.isPrivateScope) {
            launchEditor(book)
            return
        }
        val limiter = AttemptLimiter(CryptoStores.secrets(this))
        if (limiter.isLocked(book.id)) {
            val minutes = (limiter.remainingMs(book.id) / 60_000) + 1
            toast(getString(R.string.import_locked, minutes))
            return
        }
        val field = PassphraseField(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(book.name)
            .setMessage(R.string.keying_unlock_body)
            .setView(padded(field))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_unlock) { _, _ ->
                lifecycleScope.launch {
                    val ok = runCatching {
                        Sketchbooks.unlock(this@LibraryActivity, book.id, field.value)
                    }.getOrDefault(false)
                    if (ok) {
                        limiter.recordSuccess(book.id)
                        launchEditor(book)
                    } else {
                        limiter.recordFailure(book.id)
                        toast(getString(R.string.import_wrong_key))
                    }
                }
            }
            .show()
    }

    private fun launchEditor(book: IndexObject) {
        LastOpen.save(this, LastOpen.Pointer(LastOpen.Kind.SKETCHBOOK, book.id, null))
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openScratchpad() {
        LastOpen.save(this, LastOpen.Pointer(LastOpen.Kind.SCRATCHPAD, null, null))
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun bookActions(book: IndexObject) {
        lifecycleScope.launch {
            val index = IndexGate.awaitReady()
            val pinned = index.isPinned(book.id)
            MaterialAlertDialogBuilder(this@LibraryActivity)
                .setTitle(book.name)
                .setItems(
                    arrayOf(
                        getString(if (pinned) R.string.library_unpin else R.string.library_pin),
                        getString(R.string.library_rename),
                        getString(R.string.library_move),
                        getString(R.string.library_duplicate),
                        getString(R.string.library_export),
                        getString(R.string.library_keying),
                        getString(
                            if (book.isExcludedFromBackup) R.string.backup_include else R.string.backup_exclude,
                        ),
                        getString(R.string.library_delete),
                    ),
                ) { _, which ->
                    when (which) {
                        0 -> lifecycleScope.launch {
                            if (pinned) index.unpin(book.id) else index.pin(book.id)
                            refresh()
                        }
                        1 -> promptRename(book)
                        2 -> promptMove(book)
                        3 -> duplicate(book)
                        4 -> export(book)
                        5 -> promptKeying(book)
                        6 -> toggleBackupExclusion(book)
                        7 -> confirmDeleteBook(book)
                    }
                }
                .show()
        }
    }

    /**
     * "Don't copy this one anywhere."
     *
     * A policy choice about a sketchbook rather than an edit to it, so it does not
     * touch `updatedAt` — see `IndexEdit`. Nothing about the card changes, hence
     * the toast: the only feedback there is, is the one we give.
     */
    private fun toggleBackupExclusion(book: IndexObject) {
        lifecycleScope.launch {
            val excluded = !book.isExcludedFromBackup
            IndexGate.awaitReady().setExcludedFromBackup(book.id, excluded)
            toast(
                getString(
                    if (excluded) R.string.backup_excluded_toast else R.string.backup_included_toast,
                    book.name,
                ),
            )
            refresh()
        }
    }

    /**
     * Hands the sketchbook to whatever the user picks from the share sheet.
     *
     * A byte copy under the book's own name — see [SoilExport]. An encrypted book
     * leaves as ciphertext without a word about it, which is the correct amount
     * of ceremony: the user asked for their file, and their file is encrypted.
     */
    private fun export(book: IndexObject) {
        lifecycleScope.launch {
            val name = ExportName.of(book.name, book.id)
            val uri = runCatching { SoilExport.stage(this@LibraryActivity, book.id, book.name) }.getOrNull()
            if (uri == null) {
                toast(getString(R.string.library_failed))
                return@launch
            }
            startActivity(SoilExport.shareIntent(uri, name))
        }
    }

    // --- Import ---------------------------------------------------------------

    /**
     * The system picker, rather than a browser of our own.
     *
     * A `.soil` can arrive from anywhere — Downloads, Drive, a message — and the
     * picker is the one thing that can reach all of them. Every MIME type is
     * offered, because the extension is ours and no MIME database has heard of
     * it: the file is identified by what is inside it, not by what it is called.
     */
    private fun pickImport() {
        runCatching {
            importFile.launch(arrayOf("*/*"))
        }.onFailure { toast(getString(R.string.import_failed)) }
    }

    private val importFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) beginImport(uri) }

    /**
     * Calibration saves itself before it returns, so nothing here has to store
     * anything — the result is read only to say out loud that it worked.
     *
     * Which is the whole point of confirming it: an uncalibrated screen says
     * nothing about being uncalibrated. It quietly uses whatever PPI the OEM
     * reports, and on one of these tablets that is a third too high, so every
     * physical size in the app is wrong and looks deliberate.
     */
    private val calibrationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val ppi = result.data?.getFloatExtra(CalibrationActivity.EXTRA_PPI, 0f) ?: 0f
        if (ppi > 0f) toast(getString(R.string.library_calibrated, ppi.roundToInt()))
    }

    private fun beginImport(uri: Uri) {
        lifecycleScope.launch {
            when (val step = runCatching { SoilImport.inspect(this@LibraryActivity, uri) }.getOrNull()) {
                is SoilImport.Step.Ready -> resolve(step)
                is SoilImport.Step.NeedsKey -> promptImportKey(step)
                is SoilImport.Step.Refused -> toast(refusal(step))
                null -> toast(getString(R.string.import_failed))
            }
        }
    }

    private fun refusal(step: SoilImport.Step.Refused): String = when (step.reason) {
        ImportPlan.Verdict.BAD_ID -> getString(R.string.import_bad_id)
        else -> getString(R.string.import_not_a_sketchbook)
    }

    /**
     * A locked file, and the one prompt this flow has.
     *
     * Cancelling discards the staged copy rather than leaving it in the cache for
     * later: the user said no, and a copy of somebody\'s artwork should not
     * outlive that.
     */
    private fun promptImportKey(step: SoilImport.Step.NeedsKey) {
        if (step.lockedUntil > System.currentTimeMillis()) {
            val minutes = ((step.lockedUntil - System.currentTimeMillis()) / 60_000) + 1
            toast(getString(R.string.import_locked, minutes))
            SoilImport.discard(step.staged)
            return
        }
        val field = PassphraseField(this).apply { hint = getString(R.string.import_unlock) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_unlock_title)
            .setMessage(R.string.import_unlock_body)
            .setView(padded(field))
            .setNegativeButton(android.R.string.cancel) { _, _ -> SoilImport.discard(step.staged) }
            .setOnCancelListener { SoilImport.discard(step.staged) }
            .setPositiveButton(R.string.import_unlock) { _, _ ->
                lifecycleScope.launch {
                    when (val next = SoilImport.unlock(this@LibraryActivity, step.staged, field.value)) {
                        is SoilImport.Step.Ready -> resolve(next)
                        is SoilImport.Step.NeedsKey -> {
                            toast(getString(R.string.import_wrong_key))
                            promptImportKey(next)
                        }
                        is SoilImport.Step.Refused -> toast(refusal(next))
                    }
                }
            }
            .show()
    }

    /** Asks about a collision, or installs straight away when there isn\'t one. */
    private fun resolve(ready: SoilImport.Step.Ready) {
        when (ready.collision) {
            ImportPlan.Collision.NONE -> finishImport(ready, ImportPlan.Resolution.KEEP_BOTH)

            // Never behind the editor\'s back: the open document has state this
            // copy does not include, and overwriting the file under it corrupts
            // both.
            ImportPlan.Collision.EXISTS_AND_OPEN -> {
                toast(getString(R.string.import_open_elsewhere))
                SoilImport.discard(ready.staged)
            }

            ImportPlan.Collision.EXISTS -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_exists_title)
                .setMessage(getString(R.string.import_exists_body, ready.meta.name))
                .setNeutralButton(android.R.string.cancel) { _, _ ->
                    finishImport(ready, ImportPlan.Resolution.CANCEL)
                }
                .setOnCancelListener { finishImport(ready, ImportPlan.Resolution.CANCEL) }
                .setNegativeButton(R.string.import_keep_both) { _, _ ->
                    finishImport(ready, ImportPlan.Resolution.KEEP_BOTH)
                }
                .setPositiveButton(R.string.import_replace) { _, _ ->
                    finishImport(ready, ImportPlan.Resolution.REPLACE)
                }
                .show()
        }
    }

    private fun finishImport(ready: SoilImport.Step.Ready, resolution: ImportPlan.Resolution) {
        lifecycleScope.launch {
            val id = SoilImport.install(this@LibraryActivity, ready, resolution)
            if (resolution == ImportPlan.Resolution.CANCEL) return@launch
            if (id == null) {
                toast(getString(R.string.import_failed))
                return@launch
            }
            refresh()
            val name = IndexGate.awaitReady().byId(id)?.name ?: ready.meta.name
            toast(getString(R.string.import_done, name))
        }
    }

    // --- Encryption -----------------------------------------------------------

    /**
     * How this sketchbook is locked.
     *
     * Three real states rather than a switch, because "no encryption" is a choice
     * with a use — a file another program can read — and hiding it behind a
     * toggle labelled *off* would understate it. Each conversion is a
     * `sqlcipher_export` round trip through the one shared helper.
     */
    private fun promptKeying(book: IndexObject) {
        lifecycleScope.launch {
            val current = DocumentKeying.current(book.id)
            val options = listOf(
                DocumentKeying.Keying.DEVICE to getString(R.string.keying_device),
                DocumentKeying.Keying.PRIVATE to getString(R.string.keying_private),
                DocumentKeying.Keying.NONE to getString(R.string.keying_none),
            )
            MaterialAlertDialogBuilder(this@LibraryActivity)
                .setTitle(R.string.library_keying)
                .setSingleChoiceItems(
                    options.map { it.second }.toTypedArray(),
                    options.indexOfFirst { it.first == current },
                ) { dialog, which ->
                    dialog.dismiss()
                    val chosen = options[which].first
                    if (chosen != current) beginKeying(book, current, chosen)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Collects whatever passphrases the change needs, then makes it.
     *
     * The *current* one is asked for only when the book has its own — the device
     * key is on hand and a plaintext book has none. Removing encryption is
     * confirmed rather than just done: it is the one direction that makes the
     * artwork readable by anything that can open a database.
     */
    private fun beginKeying(
        book: IndexObject,
        from: DocumentKeying.Keying,
        to: DocumentKeying.Keying,
    ) {
        val needsCurrent = from == DocumentKeying.Keying.PRIVATE
        val needsNew = to == DocumentKeying.Keying.PRIVATE

        fun go(current: String?, fresh: String?) {
            if (to == DocumentKeying.Keying.NONE) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.keying_none)
                    .setMessage(R.string.keying_none_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.keying_none_confirm) { _, _ -> applyKeying(book, to, current, fresh) }
                    .show()
            } else {
                applyKeying(book, to, current, fresh)
            }
        }

        if (needsCurrent) {
            askPassphrase(R.string.keying_current_title) { current ->
                if (needsNew) askPassphrase(R.string.keying_new_title) { fresh -> go(current, fresh) }
                else go(current, null)
            }
        } else if (needsNew) {
            askPassphrase(R.string.keying_new_title) { fresh -> go(null, fresh) }
        } else {
            go(null, null)
        }
    }

    private fun askPassphrase(titleRes: Int, onEntered: (String) -> Unit) {
        val field = PassphraseField(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(padded(field))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = field.value
                if (entered.isNotBlank()) onEntered(entered)
            }
            .show()
    }

    private fun applyKeying(
        book: IndexObject,
        to: DocumentKeying.Keying,
        current: String?,
        fresh: String?,
    ) {
        lifecycleScope.launch {
            val ok = runCatching {
                DocumentKeying.convert(this@LibraryActivity, book.id, to, current, fresh)
            }.getOrDefault(false)
            refresh()
            toast(getString(if (ok) R.string.keying_done else R.string.keying_failed))
        }
    }

    /**
     * Changing the key the whole library uses.
     *
     * Every global-scope document and the index, one file at a time, resumable if
     * it is interrupted — see `KeyRotation`. The new passphrase replaces the
     * recovery key, so it is worth saying so before it happens.
     */
    private fun promptRotate() {
        val field = PassphraseField(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rotate_title)
            .setMessage(R.string.rotate_body)
            .setView(padded(field))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rotate_action) { _, _ ->
                val entered = field.value
                if (entered.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    toast(getString(R.string.rotate_running))
                    val outcome = runCatching { KeyRotation.start(this@LibraryActivity, entered) }.getOrNull()
                    refresh()
                    toast(
                        when {
                            outcome == null -> getString(R.string.keying_failed)
                            outcome.quarantined.isEmpty() ->
                                getString(R.string.rotate_done, outcome.converted)
                            else -> getString(R.string.rotate_done_partial, outcome.converted, outcome.quarantined.size)
                        },
                    )
                }
            }
            .show()
    }

    private fun folderActions(folder: IndexObject) = MaterialAlertDialogBuilder(this)
        .setTitle(folder.name)
        .setItems(
            arrayOf(
                getString(R.string.library_rename),
                getString(R.string.library_move),
                getString(R.string.library_delete),
            ),
        ) { _, which ->
            when (which) {
                0 -> promptRename(folder)
                1 -> promptMove(folder)
                2 -> confirmDeleteFolder(folder)
            }
        }
        .show()

    private fun promptRename(row: IndexObject) {
        val field = EditText(this).apply {
            setText(row.name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_rename)
            .setView(padded(field))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_rename) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    IndexGate.awaitReady().rename(row.id, name)
                    refresh()
                }
            }
            .show()
    }

    /**
     * Moves a row into another folder.
     *
     * The destination list is every folder plus the root. A folder cannot be moved
     * into itself or into anything inside it — `IndexRepository.move` refuses that
     * outright rather than trusting this list to have excluded it, because a cycle
     * in the tree is something every ancestry walk afterwards has to survive.
     */
    private fun promptMove(row: IndexObject) {
        lifecycleScope.launch {
            val index = IndexGate.awaitReady()
            val destinations = listOf<IndexObject?>(null) + index.allFolders()
                .filter { it.id != row.id }
                .let { LibrarySort.NAME.applyTo(it) }
            val labels = destinations.map { it?.name ?: getString(R.string.library_root) }.toTypedArray()

            MaterialAlertDialogBuilder(this@LibraryActivity)
                .setTitle(R.string.library_move)
                .setItems(labels) { _, which ->
                    lifecycleScope.launch {
                        val moved = index.move(row.id, destinations[which]?.id)
                        if (!moved) toast(getString(R.string.library_move_refused))
                        refresh()
                    }
                }
                .show()
        }
    }

    private fun duplicate(book: IndexObject) {
        lifecycleScope.launch {
            runCatching {
                Sketchbooks.duplicate(this@LibraryActivity, book.id, getString(R.string.library_copy_of, book.name))
            }
                .onSuccess { refresh() }
                .onFailure { toast(it.message ?: getString(R.string.library_failed)) }
        }
    }

    private fun confirmDeleteBook(book: IndexObject) = MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.library_delete_title, book.name))
        .setMessage(R.string.library_delete_body)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.library_delete) { _, _ ->
            lifecycleScope.launch {
                runCatching { Sketchbooks.delete(this@LibraryActivity, book.id) }
                    .onFailure { toast(it.message ?: getString(R.string.library_failed)) }
                refresh()
            }
        }
        .show()

    /**
     * Deleting a folder is refused while anything is inside it.
     *
     * A recursive delete would put someone two taps from losing every sketchbook
     * in a folder they believed was empty — and the card cannot show them what is
     * in there. Emptying it first is one more step and no ambiguity.
     */
    private fun confirmDeleteFolder(folder: IndexObject) {
        lifecycleScope.launch {
            val index = IndexGate.awaitReady()
            if (!index.isEmptyFolder(folder.id)) {
                MaterialAlertDialogBuilder(this@LibraryActivity)
                    .setTitle(getString(R.string.library_delete_title, folder.name))
                    .setMessage(R.string.library_folder_not_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@LibraryActivity)
                .setTitle(getString(R.string.library_delete_title, folder.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.library_delete) { _, _ ->
                    lifecycleScope.launch {
                        index.delete(folder.id)
                        refresh()
                    }
                }
                .show()
        }
    }

    private fun promptNewFolder() {
        val field = EditText(this).apply {
            hint = getString(R.string.library_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_new_folder)
            .setView(padded(field))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_create) { _, _ ->
                val name = field.text.toString().trim().ifEmpty { getString(R.string.library_folder) }
                lifecycleScope.launch {
                    IndexGate.awaitReady().createFolder(name, folderId)
                    refresh()
                }
            }
            .show()
    }

    private fun promptNewSketchbook() {
        val field = EditText(this).apply {
            hint = getString(R.string.library_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(getString(R.string.library_default_name))
            setSelection(text.length)
        }
        // A sketchbook is bought at one size: every page in it shares this, which
        // is what keeps the cards and the page strip a single shape.
        //
        // The same list the editor offers, and for the same reason: a book created
        // larger than the panel could never be drawn at the size it claims.
        val (maxW, maxH) = maxSheetInches()
        val sizes = CanvasSize.choices(maxW, maxH)
        // Full screen is the default, and it sits at the end of the list — so the
        // row that starts checked is the last one, not the first.
        val defaultIndex = sizes.indexOf(CanvasSize.FullScreen).coerceAtLeast(0)
        var chosen = defaultIndex

        // Custom sits first, but its fields appear only once it is picked — a size
        // form permanently open would make the question look harder than choosing
        // off the list, which is what nearly everyone wants.
        val startW = sizes.filterIsInstance<CanvasSize.Print>().lastOrNull()?.wIn ?: maxW
        val startH = sizes.filterIsInstance<CanvasSize.Print>().lastOrNull()?.hIn ?: maxH
        val customLabel = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        val wField = inchField(startW)
        val hField = inchField(startH)
        fun typedCustom() =
            CanvasSize.custom(wField.inches(startW), hField.inches(startH), maxW, maxH)
        // The label is the size that will actually be made — clamped and truncated.
        // A preview that rounds up, or that shows a number the screen will refuse,
        // is a preview of a sheet you cannot have.
        fun refreshCustom() {
            customLabel.text = typedCustom().label
        }
        refreshCustom()
        wField.onEdit(::refreshCustom)
        hField.onEdit(::refreshCustom)
        val customPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(customLabel)
            addView(fieldRow(getString(R.string.library_size_width), wField))
            addView(fieldRow(getString(R.string.library_size_height), hField))
            addView(label(getString(R.string.library_size_capped, CanvasSize.custom(maxW, maxH).label)))
        }

        val customIndex = sizes.size
        val choices = RadioGroup(this).apply {
            addView(
                RadioButton(this@LibraryActivity).apply {
                    text = getString(R.string.library_size_custom)
                    id = customIndex + 1
                },
            )
            sizes.forEachIndexed { i, size ->
                addView(
                    RadioButton(this@LibraryActivity).apply {
                        text = size.label
                        id = i + 1
                        isChecked = i == defaultIndex
                    },
                )
            }
            setOnCheckedChangeListener { _, id ->
                chosen = id - 1
                customPanel.visibility = if (chosen == customIndex) View.VISIBLE else View.GONE
            }
        }
        // One custom view rather than setView + setSingleChoiceItems, which puts
        // the list above the field and so asks for the size before the name.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(label(getString(R.string.library_name_hint)))
            addView(field)
            addView(label(getString(R.string.library_size)).apply { setPadding(0, dp(16), 0, 0) })
            addView(ScrollView(this@LibraryActivity).apply { addView(choices) })
            addView(customPanel)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_new_sketchbook)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_create) { _, _ ->
                val name = field.text.toString().trim().ifEmpty { getString(R.string.library_default_name) }
                val size = if (chosen == customIndex) typedCustom() else sizes[chosen]
                lifecycleScope.launch {
                    runCatching {
                        Sketchbooks.create(this@LibraryActivity, name, size, parentId = folderId)
                    }
                        .onSuccess { open(it) }
                        .onFailure { toast(it.message ?: getString(R.string.library_failed)) }
                }
            }
            .show()
    }

    /**
     * The panel in inches at the calibrated PPI: long side, then short side.
     *
     * Measured from the window rather than the view because this dialog is built
     * over a list, not over a canvas. Long and short rather than width and height
     * because a sheet is bounded by the *editor's* orientation, and every activity
     * here is landscape-locked — the sizes offered must not depend on which way
     * round the window happened to be when the dialog opened.
     */
    private fun maxSheetInches(): Pair<Float, Float> {
        val bounds = windowManager.currentWindowMetrics.bounds
        val ppi = Calibration.effectivePpi(this)
        val longPx = maxOf(bounds.width(), bounds.height()).toFloat()
        val shortPx = minOf(bounds.width(), bounds.height()).toFloat()
        return Calibration.pxToIn(longPx, ppi) to Calibration.pxToIn(shortPx, ppi)
    }

    private fun promptSort() = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.library_sort)
        .setSingleChoiceItems(
            arrayOf(
                getString(R.string.library_sort_name),
                getString(R.string.library_sort_created),
                getString(R.string.library_sort_updated),
            ),
            sort.ordinal,
        ) { dialog, which ->
            sort = LibrarySort.entries[which]
            // A setting, not a name — safe for a plaintext preference file.
            prefs().edit().putString(KEY_SORT, sort.name).apply()
            dialog.dismiss()
            refresh()
        }
        .show()

    // --- Chrome -------------------------------------------------------------

    private fun buildUi(): View {
        breadcrumb = TextView(this).apply {
            text = getString(R.string.library_title)
            textSize = 24f
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        upButton = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.library_up)
            visibility = View.GONE
            setOnClickListener { goUp() }
        }
        search = EditText(this).apply {
            hint = getString(R.string.library_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            addTextChangedListener(
                object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString().orEmpty()
                        refresh()
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                },
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            addView(upButton)
            addView(breadcrumb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(search, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                MaterialButton(this@LibraryActivity, null, com.google.android.material.R.attr.borderlessButtonStyle)
                    .apply {
                        text = getString(R.string.library_sort)
                        setOnClickListener { promptSort() }
                    },
            )
            // Always available, and never a card in the grid: the scratchpad is
            // not a sketchbook you might one day rename, move or delete.
            addView(
                MaterialButton(this@LibraryActivity, null, com.google.android.material.R.attr.borderlessButtonStyle)
                    .apply {
                        text = getString(R.string.library_scratchpad)
                        setOnClickListener { openScratchpad() }
                    },
            )
            // Backup and Calibrate are the library's own business rather than a
            // drawing's, which is why they are here and not on the rail: one is
            // about the whole shelf, the other about the screen the shelf is on,
            // and neither is a thing you reach for mid-stroke.
            addView(
                MaterialButton(this@LibraryActivity, null, com.google.android.material.R.attr.borderlessButtonStyle)
                    .apply {
                        text = getString(R.string.library_backup)
                        setOnClickListener {
                            startActivity(Intent(this@LibraryActivity, BackupSettingsActivity::class.java))
                        }
                    },
            )
            addView(
                MaterialButton(this@LibraryActivity, null, com.google.android.material.R.attr.borderlessButtonStyle)
                    .apply {
                        text = getString(R.string.library_calibrate)
                        setOnClickListener { calibrationLauncher.launch(Intent(this@LibraryActivity, CalibrationActivity::class.java)) }
                    },
            )
            addView(
                MaterialButton(this@LibraryActivity).apply {
                    text = getString(R.string.library_new)
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@LibraryActivity)
                            .setItems(
                                arrayOf(
                                    getString(R.string.library_new_sketchbook),
                                    getString(R.string.library_new_folder),
                                    getString(R.string.library_import),
                                    getString(R.string.rotate_title),
                                ),
                            ) { _, which ->
                                when (which) {
                                    0 -> promptNewSketchbook()
                                    1 -> promptNewFolder()
                                    2 -> pickImport()
                                    else -> promptRotate()
                                }
                            }
                            .show()
                    }
                },
            )
        }

        empty = TextView(this).apply {
            text = getString(R.string.library_empty)
            textSize = 15f
            setTextColor(0xFF6B7075.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(64), dp(24), dp(24))
            visibility = View.GONE
        }

        grid = GridLayout(this).apply {
            columnCount = (resources.displayMetrics.widthPixels / dp(216)).coerceAtLeast(1)
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }

        shortcuts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAF9F6.toInt())
            addView(header)
            addView(empty)
            addView(
                ScrollView(this@LibraryActivity).apply {
                    addView(
                        LinearLayout(this@LibraryActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(shortcuts)
                            addView(grid)
                        },
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF6B7075.toInt())
        textSize = 13f
    }

    private fun padded(view: View): View = LinearLayout(this).apply {
        setPadding(dp(24), dp(12), dp(24), 0)
        addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun prefs() = getSharedPreferences("paintsprout_session", Context.MODE_PRIVATE)

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val KEY_SORT = "library_sort"

        /** A card is 200dp wide; a cover decoded much past that is wasted memory. */
        const val COVER_EDGE = 640
    }
}
