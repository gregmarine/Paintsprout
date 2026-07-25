package com.symmetricalpalmtree.paintsprout

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.symmetricalpalmtree.paintsprout.data.soil.Sketchbooks
import com.symmetricalpalmtree.paintsprout.paint.CanvasSize
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The library: every sketchbook, as a card you can open.
 *
 * The cards are drawn entirely from **index rows** — name, page count, cover — and
 * never by opening a document. That is not an optimisation but the rule the index
 * exists for: deciding whether a book is locked, and what to show on its card,
 * must not require the key you are deciding whether to ask for.
 *
 * The grid is rebuilt wholesale on every change rather than diffed. A library of
 * this size does not need a `RecyclerView`, and folders and search (Phase 15) will
 * change the shape of this enough that a list adapter now would be scaffolding
 * built to be torn down.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var grid: GridLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val books = runCatching { IndexGate.awaitReady().sketchbooks(null) }.getOrDefault(emptyList())
            grid.removeAllViews()
            books.forEach { grid.addView(cardFor(it)) }
            empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // --- Cards --------------------------------------------------------------

    private fun cardFor(book: IndexObject): View {
        val cover = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(140))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEFEDE7.toInt())
            book.blob?.let { bytes ->
                // Bounded decode: these bytes came off disk, and a hostile or
                // merely enormous cover must not be an OOM on the library screen.
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
                    .getOrNull()?.let(::setImageBitmap)
            }
            // A private-passphrase book has no cover by design — the index opens
            // with the global key, and a picture of the contents must not cross
            // that boundary. Phase 24 gives it a lock glyph.
            contentDescription = book.name
        }

        val title = TextView(this).apply {
            text = book.name
            textSize = 15f
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val subtitle = TextView(this).apply {
            val pages = book.pageCount ?: 0
            text = resources.getQuantityString(R.plurals.library_pages, pages, pages)
            textSize = 12f
            setTextColor(0xFF6B7075.toInt())
        }

        return MaterialCardView(this).apply {
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
                    addView(cover)
                    addView(
                        LinearLayout(this@LibraryActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(8), dp(12), dp(12))
                            addView(title)
                            addView(subtitle)
                        },
                    )
                },
            )
            setOnClickListener { open(book) }
            setOnLongClickListener { showActions(book); true }
        }
    }

    // --- Actions ------------------------------------------------------------

    private fun open(book: IndexObject) {
        LastOpen.save(this, LastOpen.Pointer(LastOpen.Kind.SKETCHBOOK, book.id, null))
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun showActions(book: IndexObject) {
        MaterialAlertDialogBuilder(this)
            .setTitle(book.name)
            .setItems(
                arrayOf(
                    getString(R.string.library_rename),
                    getString(R.string.library_duplicate),
                    getString(R.string.library_delete),
                ),
            ) { _, which ->
                when (which) {
                    0 -> promptRename(book)
                    1 -> duplicate(book)
                    2 -> confirmDelete(book)
                }
            }
            .show()
    }

    private fun promptNew() {
        val field = EditText(this).apply {
            hint = getString(R.string.library_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(getString(R.string.library_default_name))
            setSelection(text.length)
        }
        // Real print sizes, plus the whole panel. A sketchbook is bought at one
        // size: every page in it shares this, which is what keeps the cards and
        // the page strip a single shape.
        val sizes = listOf<CanvasSize>(CanvasSize.FullScreen) + CanvasSize.PRESETS
        var chosen = 0

        // One custom view rather than setView + setSingleChoiceItems, which puts
        // the list above the field and so asks for the size before the name.
        val choices = android.widget.RadioGroup(this).apply {
            sizes.forEachIndexed { i, size ->
                addView(
                    android.widget.RadioButton(this@LibraryActivity).apply {
                        text = size.label
                        id = i + 1
                        isChecked = i == 0
                    },
                )
            }
            setOnCheckedChangeListener { _, id -> chosen = id - 1 }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@LibraryActivity).apply { text = getString(R.string.library_name_hint) })
            addView(field)
            addView(
                TextView(this@LibraryActivity).apply {
                    text = getString(R.string.library_size)
                    setPadding(0, dp(16), 0, 0)
                },
            )
            addView(ScrollView(this@LibraryActivity).apply { addView(choices) })
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_new)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_create) { _, _ ->
                val name = field.text.toString().trim().ifEmpty { getString(R.string.library_default_name) }
                lifecycleScope.launch {
                    runCatching { Sketchbooks.create(this@LibraryActivity, name, sizes[chosen]) }
                        .onSuccess { open(it) }
                        .onFailure { toast(it.message ?: getString(R.string.library_failed)) }
                }
            }
            .show()
    }

    private fun promptRename(book: IndexObject) {
        val field = EditText(this).apply {
            setText(book.name)
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
                    Sketchbooks.rename(book.id, name)
                    refresh()
                }
            }
            .show()
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

    private fun confirmDelete(book: IndexObject) {
        MaterialAlertDialogBuilder(this)
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
    }

    // --- Chrome -------------------------------------------------------------

    private fun buildUi(): View {
        val title = TextView(this).apply {
            text = getString(R.string.library_title)
            textSize = 26f
            setTextColor(Color.BLACK)
        }
        val newButton = MaterialButton(this).apply {
            text = getString(R.string.library_new)
            setOnClickListener { promptNew() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(newButton)
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
            setPadding(dp(16), dp(8), dp(16), dp(24))
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

    private fun padded(view: View): View = LinearLayout(this).apply {
        setPadding(dp(24), dp(12), dp(24), 0)
        addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
