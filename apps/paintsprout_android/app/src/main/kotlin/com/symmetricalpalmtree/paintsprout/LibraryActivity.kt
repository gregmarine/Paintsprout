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
import com.symmetricalpalmtree.paintsprout.data.LastOpen
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.IndexObject
import com.symmetricalpalmtree.paintsprout.data.index.IndexType
import com.symmetricalpalmtree.paintsprout.data.index.LibrarySort
import com.symmetricalpalmtree.paintsprout.data.soil.Sketchbooks
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

            breadcrumb.text = when {
                searching -> getString(R.string.library_results, books.size)
                folderId == null -> getString(R.string.library_title)
                else -> index.breadcrumbOf(folderId!!) + " / " + (index.byId(folderId!!)?.name ?: "")
            }
            upButton.visibility = if (folderId != null && !searching) View.VISIBLE else View.GONE

            grid.removeAllViews()
            folders.forEach { grid.addView(folderCard(it)) }
            books.forEach { grid.addView(bookCard(it)) }

            empty.text = when {
                searching -> getString(R.string.library_no_results)
                folderId == null -> getString(R.string.library_empty)
                else -> getString(R.string.library_folder_empty)
            }
            empty.visibility = if (folders.isEmpty() && books.isEmpty()) View.VISIBLE else View.GONE
        }
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

    private fun bookCard(book: IndexObject): View {
        val cover = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(140))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEFEDE7.toInt())
            book.blob?.let { bytes ->
                // Bounded decode: these bytes came off disk, and a hostile or
                // merely enormous cover must not be an OOM on the library screen.
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    .getOrNull()?.let(::setImageBitmap)
            }
            contentDescription = book.name
        }
        val pages = book.pageCount ?: 0
        return card(cover, book.name, resources.getQuantityString(R.plurals.library_pages, pages, pages)) {
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

    private fun open(book: IndexObject) {
        LastOpen.save(this, LastOpen.Pointer(LastOpen.Kind.SKETCHBOOK, book.id, null))
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun bookActions(book: IndexObject) = MaterialAlertDialogBuilder(this)
        .setTitle(book.name)
        .setItems(
            arrayOf(
                getString(R.string.library_rename),
                getString(R.string.library_move),
                getString(R.string.library_duplicate),
                getString(R.string.library_delete),
            ),
        ) { _, which ->
            when (which) {
                0 -> promptRename(book)
                1 -> promptMove(book)
                2 -> duplicate(book)
                3 -> confirmDeleteBook(book)
            }
        }
        .show()

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
        val sizes = listOf<CanvasSize>(CanvasSize.FullScreen) + CanvasSize.PRESETS
        var chosen = 0
        val choices = RadioGroup(this).apply {
            sizes.forEachIndexed { i, size ->
                addView(
                    RadioButton(this@LibraryActivity).apply {
                        text = size.label
                        id = i + 1
                        isChecked = i == 0
                    },
                )
            }
            setOnCheckedChangeListener { _, id -> chosen = id - 1 }
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
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_new_sketchbook)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_create) { _, _ ->
                val name = field.text.toString().trim().ifEmpty { getString(R.string.library_default_name) }
                lifecycleScope.launch {
                    runCatching {
                        Sketchbooks.create(this@LibraryActivity, name, sizes[chosen], parentId = folderId)
                    }
                        .onSuccess { open(it) }
                        .onFailure { toast(it.message ?: getString(R.string.library_failed)) }
                }
            }
            .show()
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
            addView(
                MaterialButton(this@LibraryActivity).apply {
                    text = getString(R.string.library_new)
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@LibraryActivity)
                            .setItems(
                                arrayOf(
                                    getString(R.string.library_new_sketchbook),
                                    getString(R.string.library_new_folder),
                                ),
                            ) { _, which -> if (which == 0) promptNewSketchbook() else promptNewFolder() }
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

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAF9F6.toInt())
            addView(header)
            addView(empty)
            addView(
                ScrollView(this@LibraryActivity).apply { addView(grid) },
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
    }
}
