package com.symmetricalpalmtree.paintsprout

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.symmetricalpalmtree.paintsprout.paint.Calibration
import com.symmetricalpalmtree.paintsprout.paint.Calibration.Reference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen screen-size calibration. The user matches the on-screen outline
 * ([CalibrationView]) to a physical reference object, and the resulting true PPI
 * is stored via [Calibration]. Returns the chosen PPI in [EXTRA_PPI] on OK.
 *
 * Chrome (reference chooser, ruler length/unit, live readout, actions) is built
 * in code in the style of [MainActivity]'s rail and dialogs.
 */
class CalibrationActivity : AppCompatActivity() {

    private enum class RulerUnit { MM, CM, IN }

    private lateinit var view: CalibrationView
    private lateinit var readout: TextView
    private lateinit var rulerRow: View
    private lateinit var rulerLengthLabel: TextView

    private var rulerMm = Calibration.DEFAULT_REFERENCE.longMm
    private var unit = RulerUnit.MM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        view = CalibrationView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setInitialPpi(Calibration.effectivePpi(this@CalibrationActivity))
            onPpiChanged = { updateReadout() }
        }

        val root = FrameLayout(this).apply {
            addView(view)
            addView(buildControls())
        }
        setContentView(root)
        updateReadout()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, view).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // --- Control card -------------------------------------------------------

    private fun buildControls(): View {
        val title = TextView(this).apply {
            text = "Calibrate screen size"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        readout = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(2), 0, dp(10))
        }

        // Build the ruler row first: the reference chooser's initial check() fires
        // its listener, which touches the (lateinit) rulerRow.
        val rulerRowView = buildRulerRow()
        val chooser = buildReferenceChooser()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(title)
            addView(readout)
            addView(chooser)
            addView(rulerRowView)
            addView(buildActionRow())
        }

        return MaterialCardView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM
                marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(12)
            }
            radius = dp(24).toFloat()
            cardElevation = dp(6).toFloat()
            setContentPadding(0, 0, 0, 0)
            addView(content)
        }
    }

    private fun buildReferenceChooser(): View {
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val byId = mutableMapOf<Int, Reference>()
        for (ref in Calibration.REFERENCES) {
            val btn = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                id = View.generateViewId()
                text = ref.label
                isAllCaps = false
            }
            byId[btn.id] = ref
            group.addView(btn)
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val ref = byId[checkedId] ?: return@addOnButtonCheckedListener
            view.reference = ref
            if (ref.isRuler) view.rulerLengthMm = rulerMm
            rulerRow.visibility = if (ref.isRuler) View.VISIBLE else View.GONE
            updateReadout()
        }
        // Default selection: ID-1 card.
        val defaultId = byId.entries.first { it.value.id == Calibration.DEFAULT_REFERENCE.id }.key
        group.check(defaultId)
        return group
    }

    private fun buildRulerRow(): View {
        rulerLengthLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            width = dp(96)
        }
        val minus = stepButton("−") { stepRuler(-1) }
        val plus = stepButton("+") { stepRuler(+1) }

        val unitGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
        }
        val unitIds = mutableMapOf<Int, RulerUnit>()
        for (u in RulerUnit.values()) {
            val b = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                id = View.generateViewId()
                text = u.name.lowercase()
                isAllCaps = false
            }
            unitIds[b.id] = u
            unitGroup.addView(b)
        }
        unitGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            unit = unitIds[checkedId] ?: return@addOnButtonCheckedListener
            refreshRulerLabel()
        }
        unitGroup.check(unitIds.entries.first { it.value == RulerUnit.MM }.key)

        rulerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(6))
            visibility = View.GONE
            addView(TextView(this@CalibrationActivity).apply {
                text = "Length"; setPadding(0, 0, dp(8), 0)
            })
            addView(minus)
            addView(rulerLengthLabel)
            addView(plus)
            addView(View(this@CalibrationActivity), LinearLayout.LayoutParams(dp(16), 1))
            addView(unitGroup)
        }
        refreshRulerLabel()
        return rulerRow
    }

    private fun buildActionRow(): View {
        val cancel = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = "Cancel"; isAllCaps = false
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        val reset = MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle,
        ).apply {
            text = "Reset"; isAllCaps = false
            setOnClickListener {
                view.setInitialPpi(Calibration.reportedPpi(this@CalibrationActivity))
                updateReadout()
            }
        }
        val save = MaterialButton(this).apply {
            text = "Save"; isAllCaps = false
            setOnClickListener {
                Calibration.save(this@CalibrationActivity, view.ppi)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PPI, view.ppi))
                finish()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(reset)
            addView(View(this@CalibrationActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(cancel)
            addView(View(this@CalibrationActivity), LinearLayout.LayoutParams(dp(8), 1))
            addView(save)
        }
    }

    // --- Ruler length -------------------------------------------------------

    private fun stepRuler(dir: Int) {
        val stepMm = when (unit) {
            RulerUnit.MM -> 10f
            RulerUnit.CM -> 10f
            RulerUnit.IN -> Calibration.MM_PER_IN
        }
        rulerMm = (rulerMm + dir * stepMm).coerceIn(20f, 400f)
        view.rulerLengthMm = rulerMm
        refreshRulerLabel()
        updateReadout()
    }

    private fun refreshRulerLabel() {
        rulerLengthLabel.text = when (unit) {
            RulerUnit.MM -> "${rulerMm.roundToInt()} mm"
            RulerUnit.CM -> String.format("%.1f cm", rulerMm / 10f)
            RulerUnit.IN -> String.format("%.1f in", rulerMm / Calibration.MM_PER_IN)
        }
    }

    // --- Readout ------------------------------------------------------------

    private fun updateReadout() {
        val ppi = view.ppi
        val dm = resources.displayMetrics
        val hi = max(dm.widthPixels, dm.heightPixels)
        val lo = min(dm.widthPixels, dm.heightPixels)
        val wIn = hi / ppi
        val hIn = lo / ppi
        readout.text = String.format(
            "≈ %d PPI  ·  screen %.1f × %.1f in  (%d × %d mm)",
            ppi.roundToInt(), wIn, hIn,
            Calibration.pxToMm(hi.toFloat(), ppi).roundToInt(),
            Calibration.pxToMm(lo.toFloat(), ppi).roundToInt(),
        )
    }

    private fun stepButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            minWidth = dp(44); minimumWidth = dp(44)
            insetTop = 0; insetBottom = 0
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_PPI = "ppi"
    }
}
