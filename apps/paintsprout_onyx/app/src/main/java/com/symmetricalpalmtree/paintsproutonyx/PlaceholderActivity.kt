package com.symmetricalpalmtree.paintsproutonyx

import android.os.Bundle
import android.util.DisplayMetrics
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivityPlaceholderBinding

/**
 * The G0 launcher screen, and it exists for exactly one phase.
 *
 * G1 replaces it with the Bootstrap → RecoveryKey → Unlock path, so nothing here
 * is worth building on. What it does earn is the scaffold's own proof: if this
 * screen comes up on the NA5C in the right theme, then the Onyx build baggage
 * merged, the resources resolve, viewBinding is wired and the debug variant
 * installs under its own id.
 *
 * It also reports the panel's real resolution and density, which G0 owes the
 * plan. That number decides layout tiers and, later, what a millimetre would
 * even mean here — and it is the kind of thing that is far more annoying to go
 * back for than to print once while the screen is already this simple.
 */
class PlaceholderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaceholderBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaceholderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // BOOX has a real status bar overlaying the window top — the opposite of
        // Supernote, where the guard is zero. Without this the first line of any
        // screen sits under it, and anything tappable up there pulls the shade
        // down instead of firing.
        //
        // The inset is ADDED to whatever padding the layout already asked for,
        // not written over it. That distinction is the whole reason this is
        // written out longhand on a screen that plainly does not need it: the
        // obvious one-liner assigns the inset straight to the view, which
        // silently throws away the layout's own margin. On this centred screen
        // nobody would ever notice. On the library or the sketchbook toolbar it
        // means chrome ends up flush against the very edge this code was added
        // to stay away from — the bug hiding behind its own fix. Later screens
        // copy from here, so here is where it has to be right.
        val basePaddingTop = binding.root.paddingTop
        val basePaddingBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = basePaddingTop + bars.top,
                bottom = basePaddingBottom + bars.bottom,
            )
            insets
        }

        val metrics: DisplayMetrics = resources.displayMetrics
        binding.panel.text = getString(
            R.string.placeholder_panel,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            metrics.density.toString(),
        )
    }
}
