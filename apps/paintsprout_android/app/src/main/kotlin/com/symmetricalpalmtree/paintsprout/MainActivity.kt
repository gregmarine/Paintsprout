package com.symmetricalpalmtree.paintsprout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.symmetricalpalmtree.paintsprout.databinding.ActivityMainBinding
import com.symmetricalpalmtree.paintsprout.paint.AVAILABLE_SURFACES
import com.symmetricalpalmtree.paintsprout.paint.Calibration
import com.symmetricalpalmtree.paintsprout.paint.CanvasParams
import com.symmetricalpalmtree.paintsprout.paint.ChalkboardParams
import com.symmetricalpalmtree.paintsprout.paint.ConcreteParams
import com.symmetricalpalmtree.paintsprout.paint.MetalParams
import com.symmetricalpalmtree.paintsprout.paint.StoneParams
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.buildSurfaceVisual
import com.symmetricalpalmtree.paintsprout.paint.Tool
import com.symmetricalpalmtree.paintsprout.paint.WatercolorParams
import com.symmetricalpalmtree.paintsprout.paint.WoodParams
import kotlin.math.roundToInt

/**
 * Hosts [PaintCanvasView] behind a floating tool rail — the native counterpart
 * of the Flutter reference's `CanvasScreen` + `_ToolRail`. The rail's buttons are
 * built in code: a loop over the tools, then context-sensitive color / size /
 * surface / selection / history / save actions. Landscape-locked and immersive.
 *
 * Undo/redo map straight to the canvas's own op history (paint ops). Surface and
 * plain-colour changes are not yet on that timeline (a known gap vs. Flutter,
 * which snapshots the whole document).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var tool = Tool.PENCIL
    private var color = Color.BLACK
    private var surfaceIndex = AVAILABLE_SURFACES.indexOf(SurfaceKind.PAPER).coerceAtLeast(0)
    private var plainColor = Color.WHITE
    private var canvasParams = CanvasParams()
    private var watercolorParams = WatercolorParams()
    private var woodParams = WoodParams()
    private var stoneParams = StoneParams()
    private var concreteParams = ConcreteParams()
    private var metalParams = MetalParams()
    private var chalkboardParams = ChalkboardParams()
    private var hasSelection = false
    private var hasPendingLine = false
    private var hasPendingArc = false
    private var hasPendingPolyline = false
    private var hasPendingPolyarc = false

    // Magic-wand settings (Flutter defaults).
    private var wandTolerance = 0.15f
    private var wandEdgeSensitivity = 0.5f
    private var wandGap = 3

    // Each tool remembers its own base size, in millimetres. Converted to pixels
    // at the current PPI when pushed to the canvas, so a size is a real physical
    // width on any calibrated screen.
    private val sizes = Tool.values().associateWith { it.defaultSizeMm }.toMutableMap()

    private val calibrationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val ppi = result.data?.getFloatExtra(CalibrationActivity.EXTRA_PPI, 0f) ?: 0f
                if (ppi > 0f) {
                    // Sizes are stored in mm; re-push at the new PPI so brush widths
                    // stay their real physical size.
                    applySizeToCanvas()
                    Snackbar.make(
                        binding.root, "Screen calibrated: ${ppi.roundToInt()} PPI",
                        Snackbar.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    // Rail views kept for state updates.
    private val toolButtons = mutableMapOf<Tool, ImageButton>()
    private lateinit var colorBtn: ImageButton
    private lateinit var sizeBtn: TextView
    private lateinit var toleranceBtn: TextView
    private lateinit var surfaceBtn: ImageButton
    private lateinit var fillBtn: ImageButton
    private lateinit var eraseBtn: ImageButton
    private lateinit var deselectBtn: ImageButton
    private lateinit var lineDoneBtn: ImageButton
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        buildRail()
        binding.btnShowRail.setOnClickListener { setRailVisible(true) }

        binding.canvas.tool = tool
        binding.canvas.strokeColor = color
        applySizeToCanvas()
        binding.canvas.setInitialSurface(
            currentSurface(), plainColor, canvasParams, watercolorParams, woodParams, stoneParams,
            concreteParams, metalParams, chalkboardParams,
        )
        applyWandSettings()
        binding.canvas.onHistoryChanged = {
            // Undo/redo may have reverted the surface — mirror it back into the rail.
            syncSurfaceFromCanvas()
            updateRail()
        }
        binding.canvas.onSelectionChanged = {
            hasSelection = it
            updateRail()
        }
        binding.canvas.onLineChanged = {
            hasPendingLine = it
            updateRail()
        }
        binding.canvas.onArcChanged = {
            hasPendingArc = it
            updateRail()
        }
        binding.canvas.onPolylineChanged = {
            hasPendingPolyline = it
            updateRail()
        }
        binding.canvas.onPolyarcChanged = {
            hasPendingPolyarc = it
            updateRail()
        }
        updateRail()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // --- Rail construction --------------------------------------------------

    private fun buildRail() {
        val rail = binding.rail
        for (t in Tool.values()) {
            val b = iconButton(toolIcon(t), t.label) { onToolChanged(t) }
            toolButtons[t] = b
            rail.addView(b)
        }
        rail.addView(divider())

        colorBtn = iconButton(0, "Color") { pickColor("Stroke color", color) { onColorChanged(it) } }
        rail.addView(colorBtn)

        sizeBtn = textButton("Size") { pickSize() }
        rail.addView(sizeBtn)
        toleranceBtn = textButton("Wand tolerance") { pickWand() }
        rail.addView(toleranceBtn)

        surfaceBtn = iconButton(surfaceIcon(currentSurface()), "Surface") { pickSurface() }
        rail.addView(surfaceBtn)

        fillBtn = iconButton(R.drawable.ic_fill, "Fill selection") { binding.canvas.fillSelection(color) }
        eraseBtn = iconButton(R.drawable.ic_erase_sel, "Erase inside selection") { binding.canvas.deleteSelection() }
        deselectBtn = iconButton(R.drawable.ic_deselect, "Deselect") { binding.canvas.clearSelection() }
        rail.addView(fillBtn)
        rail.addView(eraseBtn)
        rail.addView(deselectBtn)

        lineDoneBtn = iconButton(R.drawable.ic_done, "Finish shape") { binding.canvas.commitPendingShape() }
        rail.addView(lineDoneBtn)

        rail.addView(divider())
        undoBtn = iconButton(R.drawable.ic_undo, "Undo") { binding.canvas.undo() }
        redoBtn = iconButton(R.drawable.ic_redo, "Redo") { binding.canvas.redo() }
        rail.addView(undoBtn)
        rail.addView(redoBtn)

        rail.addView(divider())
        rail.addView(iconButton(R.drawable.ic_save, "Save PNG") { save() })
        rail.addView(iconButton(R.drawable.ic_calibrate, "Calibrate screen") { openCalibration() })
        rail.addView(iconButton(R.drawable.ic_clear, "Clear") { confirmClear() })
        rail.addView(iconButton(R.drawable.ic_hide, "Hide toolbar") { setRailVisible(false) })
    }

    private fun updateRail() {
        for ((t, b) in toolButtons) b.background = if (t == tool) selectedBg() else rippleBg()

        colorBtn.visibility = if (tool == Tool.ERASER) View.GONE else View.VISIBLE
        colorBtn.setImageDrawable(swatchDrawable(color))

        sizeBtn.visibility = if (tool == Tool.WAND) View.GONE else View.VISIBLE
        sizeBtn.text = formatMm(sizes[tool] ?: tool.defaultSizeMm)
        toleranceBtn.visibility = if (tool == Tool.WAND) View.VISIBLE else View.GONE
        toleranceBtn.text = "${(wandTolerance * 100).roundToInt()}%"

        surfaceBtn.setImageResource(surfaceIcon(currentSurface()))

        val selVis = if (hasSelection) View.VISIBLE else View.GONE
        fillBtn.visibility = selVis
        eraseBtn.visibility = selVis
        deselectBtn.visibility = selVis
        fillBtn.imageTintList = android.content.res.ColorStateList.valueOf(color)

        lineDoneBtn.visibility =
            if (hasPendingLine || hasPendingArc || hasPendingPolyline || hasPendingPolyarc)
                View.VISIBLE else View.GONE

        setEnabled(undoBtn, binding.canvas.canUndo)
        setEnabled(redoBtn, binding.canvas.canRedo)
    }

    private fun onToolChanged(t: Tool) {
        tool = t
        binding.canvas.tool = t
        applySizeToCanvas()
        updateRail()
    }

    /** Pushes the current tool's stored mm size to the canvas as pixels at this PPI. */
    private fun applySizeToCanvas() {
        val mm = sizes[tool] ?: tool.defaultSizeMm
        binding.canvas.baseSize = Calibration.mmToPx(mm, Calibration.effectivePpi(this))
    }

    /** Compact mm label for the rail button: "0.5", "4", "12.5". */
    private fun formatMm(mm: Float): String =
        if (mm >= 10f || mm == mm.roundToInt().toFloat()) {
            mm.roundToInt().toString()
        } else {
            String.format("%.1f", mm)
        }

    private fun onColorChanged(c: Int) {
        color = c
        binding.canvas.strokeColor = c
        updateRail()
    }

    private fun setRailVisible(visible: Boolean) {
        binding.railCard.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnShowRail.visibility = if (visible) View.GONE else View.VISIBLE
    }

    // --- Pickers ------------------------------------------------------------

    private fun pickSize() {
        var working = (sizes[tool] ?: tool.defaultSizeMm).coerceIn(SIZE_MIN_MM, SIZE_MAX_MM)
        val ppi = Calibration.effectivePpi(this)
        val label = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
        }
        val pxHint = hint("")
        fun refresh() {
            label.text = String.format("%.1f mm", working)
            pxHint.text = "≈ ${Calibration.mmToPx(working, ppi).roundToInt()} px on screen" +
                if (Calibration.isCalibrated(this)) "" else "  ·  screen not calibrated"
        }
        refresh()
        val slider = Slider(this).apply {
            valueFrom = SIZE_MIN_MM
            valueTo = SIZE_MAX_MM
            value = working
            addOnChangeListener { _, v, _ ->
                working = v
                refresh()
            }
        }
        val content = vbox(label, slider, pxHint)
        MaterialAlertDialogBuilder(this)
            .setTitle("${tool.label} size (mm)")
            .setView(content)
            .setPositiveButton("Done") { _, _ ->
                sizes[tool] = working
                applySizeToCanvas()
                updateRail()
            }
            .show()
    }

    private fun pickWand() {
        var tol = wandTolerance
        var edge = wandEdgeSensitivity
        var gap = wandGap.toFloat()
        val tolLabel = TextView(this)
        val edgeLabel = TextView(this)
        val gapLabel = TextView(this)
        fun refresh() {
            tolLabel.text = "Tolerance  ${(tol * 100).roundToInt()}%"
            edgeLabel.text = "Edge sensitivity  ${(edge * 100).roundToInt()}%"
            gapLabel.text = "Close gaps  ${gap.roundToInt()} px"
        }
        refresh()
        val tolSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 1f; value = tol
            addOnChangeListener { _, v, _ -> tol = v; refresh() }
        }
        val edgeSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 1f; value = edge
            addOnChangeListener { _, v, _ -> edge = v; refresh() }
        }
        val gapSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 8f; stepSize = 1f; value = gap
            addOnChangeListener { _, v, _ -> gap = v; refresh() }
        }
        val content = vbox(
            tolLabel, tolSlider, hint("Higher = matches a wider range of colors."),
            edgeLabel, edgeSlider, hint("Higher = fainter lines (soft pencil) stop the fill."),
            gapLabel, gapSlider, hint("Bridges holes in a grainy/broken boundary."),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Magic wand")
            .setView(content)
            .setPositiveButton("Done") { _, _ ->
                wandTolerance = tol
                wandEdgeSensitivity = edge
                wandGap = gap.roundToInt()
                applyWandSettings()
                updateRail()
            }
            .show()
    }

    private fun pickSurface() {
        val labels = AVAILABLE_SURFACES.map { it.label }.toTypedArray()
        val current = surfaceIndex
        MaterialAlertDialogBuilder(this)
            .setTitle("Surface")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                onSurfaceChosen(which)
            }
            .show()
    }

    private fun onSurfaceChosen(index: Int) {
        when (val kind = AVAILABLE_SURFACES[index]) {
            SurfaceKind.PLAIN ->
                // Pick the background first, then commit the surface + colour as one op.
                pickColor("Background color", plainColor) { c ->
                    plainColor = c
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.PLAIN, c)
                    updateRail()
                }
            SurfaceKind.CANVAS ->
                // Dial in the weave first, then commit surface + params as one op.
                customizeCanvas(canvasParams) { params ->
                    canvasParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.CANVAS, plainColor, params)
                    updateRail()
                }
            SurfaceKind.WATERCOLOR ->
                // Dial in the paper first, then commit surface + params as one op.
                customizeWatercolor(watercolorParams) { params ->
                    watercolorParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.WATERCOLOR, plainColor, watercolor = params)
                    updateRail()
                }
            SurfaceKind.WOOD ->
                // Dial in the board first, then commit surface + params as one op.
                customizeWood(woodParams) { params ->
                    woodParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.WOOD, plainColor, wood = params)
                    updateRail()
                }
            SurfaceKind.STONE ->
                // Dial in the slab first, then commit surface + params as one op.
                customizeStone(stoneParams) { params ->
                    stoneParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.STONE, plainColor, stone = params)
                    updateRail()
                }
            SurfaceKind.CONCRETE ->
                // Dial in the slab first, then commit surface + params as one op.
                customizeConcrete(concreteParams) { params ->
                    concreteParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.CONCRETE, plainColor, concrete = params)
                    updateRail()
                }
            SurfaceKind.METAL ->
                // Dial in the sheet first, then commit surface + params as one op.
                customizeMetal(metalParams) { params ->
                    metalParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.METAL, plainColor, metal = params)
                    updateRail()
                }
            SurfaceKind.CHALKBOARD ->
                // Dial in the board first, then commit surface + params as one op.
                customizeChalkboard(chalkboardParams) { params ->
                    chalkboardParams = params
                    surfaceIndex = index
                    binding.canvas.commitSurfaceChange(SurfaceKind.CHALKBOARD, plainColor, chalkboard = params)
                    updateRail()
                }
            else -> {
                surfaceIndex = index
                binding.canvas.commitSurfaceChange(kind, plainColor)
                updateRail()
            }
        }
    }

    /** Mirrors the canvas's current surface/background into the rail state. */
    private fun syncSurfaceFromCanvas() {
        surfaceIndex = AVAILABLE_SURFACES.indexOf(binding.canvas.surface).coerceAtLeast(0)
        plainColor = binding.canvas.plainColor
        canvasParams = binding.canvas.canvasParams
        watercolorParams = binding.canvas.watercolorParams
        woodParams = binding.canvas.woodParams
        stoneParams = binding.canvas.stoneParams
        concreteParams = binding.canvas.concreteParams
        metalParams = binding.canvas.metalParams
        chalkboardParams = binding.canvas.chalkboardParams
    }

    /** HSV colour wheel + brightness slider, with swatch quick-picks. */
    private fun pickColor(title: String, initial: Int, onUse: (Int) -> Unit) {
        var working = initial or (0xFF shl 24)
        val preview = View(this)
        val wheel = ColorWheelView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(4)
            }
        }
        val valueSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 1f
        }
        fun show(c: Int) {
            working = c
            preview.background = previewSwatch(c)
        }
        wheel.setColor(working)
        valueSlider.value = FloatArray(3).also { Color.colorToHSV(working, it) }[2]

        wheel.onColorChanged = { c -> show(c) }
        valueSlider.addOnChangeListener { _, v, _ ->
            wheel.setValue(v)
            show(wheel.color)
        }

        val grid = GridLayout(this).apply {
            columnCount = 9
            setPadding(0, dp(8), 0, dp(4))
        }
        for (c in SWATCHES) {
            grid.addView(swatchCell(c) {
                wheel.setColor(c)
                valueSlider.value = FloatArray(3).also { Color.colorToHSV(c, it) }[2]
                show(c)
            })
        }
        preview.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
        preview.background = previewSwatch(working)

        val content = vbox(wheel, labelled("V", valueSlider), preview, grid)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(working) }
            .show()
    }

    /**
     * Canvas customisation — "not all canvas is the same". A live weave preview
     * over tint / weave / grain controls; visual only (the tooth is unchanged).
     * Mirrors [pickColor]: dial it in, then the caller commits it as one op.
     */
    private fun customizeCanvas(initial: CanvasParams, onUse: (CanvasParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var weave = initial.weave
        var grain = initial.grain

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(SurfaceKind.CANVAS, w, h, Color.WHITE, CanvasParams(tint, weave, grain))
        }
        val tintRow = colorRow("Tint", "Canvas tint", tint) { c -> tint = c; preview.refresh() }
        val weaveSlider = Slider(this).apply {
            valueFrom = 0.05f; valueTo = 0.45f; value = weave.coerceIn(0.05f, 0.45f)
            addOnChangeListener { _, v, _ -> weave = v; preview.refresh() }
        }
        val grainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.10f; value = grain.coerceIn(0f, 0.10f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }

        val content = vbox(preview, tintRow, sliderRow("Weave", weaveSlider), sliderRow("Grain", grainSlider))
        MaterialAlertDialogBuilder(this)
            .setTitle("Canvas")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeCanvas(CanvasParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(CanvasParams(tint, weave, grain)) }
            .show()
    }

    /**
     * Watercolor customisation. The paper itself is fixed per artwork (its seed);
     * these controls shape its character. Live preview is a true-scale swatch — a
     * small window onto the sheet, not the whole buffer, so slider drags stay snappy.
     */
    private fun customizeWatercolor(initial: WatercolorParams, onUse: (WatercolorParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var texture = initial.texture
        var mottle = initial.mottle
        var grain = initial.grain
        val seed = binding.canvas.surfaceSeed // preview the actual sheet

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.WATERCOLOR, w, h,
                seed = seed,
                watercolorParams = WatercolorParams(tint, texture, mottle, grain),
            )
        }
        val tintRow = colorRow("Tint", "Paper tint", tint) { c -> tint = c; preview.refresh() }
        val textureSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.25f; value = texture.coerceIn(0f, 0.25f)
            addOnChangeListener { _, v, _ -> texture = v; preview.refresh() }
        }
        val mottleSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.15f; value = mottle.coerceIn(0f, 0.15f)
            addOnChangeListener { _, v, _ -> mottle = v; preview.refresh() }
        }
        val grainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.08f; value = grain.coerceIn(0f, 0.08f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Texture", textureSlider),
            sliderRow("Mottle", mottleSlider),
            sliderRow("Grain", grainSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Watercolor paper")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeWatercolor(WatercolorParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(WatercolorParams(tint, texture, mottle, grain)) }
            .show()
    }

    /**
     * Wood customisation. The board is fixed per artwork (its seed); these controls
     * shape its look. Live preview is a true-scale window onto the actual board.
     */
    private fun customizeWood(initial: WoodParams, onUse: (WoodParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var grain = initial.grain
        var scale = initial.scale
        var weathering = initial.weathering
        val seed = binding.canvas.surfaceSeed // preview the actual board

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.WOOD, w, h,
                seed = seed,
                woodParams = WoodParams(tint, grain, scale, weathering),
            )
        }
        val tintRow = colorRow("Tint", "Wood tint", tint) { c -> tint = c; preview.refresh() }
        val grainSlider = Slider(this).apply {
            valueFrom = 0.10f; valueTo = 0.60f; value = grain.coerceIn(0.10f, 0.60f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }
        // Smaller scale = finer / more zoomed out; larger = coarser grain.
        val scaleSlider = Slider(this).apply {
            valueFrom = 0.30f; valueTo = 1.00f; value = scale.coerceIn(0.30f, 1.00f)
            addOnChangeListener { _, v, _ -> scale = v; preview.refresh() }
        }
        val weatherSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.20f; value = weathering.coerceIn(0f, 0.20f)
            addOnChangeListener { _, v, _ -> weathering = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Grain", grainSlider),
            sliderRow("Scale", scaleSlider),
            sliderRow("Weather", weatherSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Wood")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeWood(WoodParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(WoodParams(tint, grain, scale, weathering)) }
            .show()
    }

    /**
     * Stone (slate) customisation. The slab is fixed per artwork (its seed); these
     * controls shape its look. Live preview is a true-scale window onto the actual slab.
     */
    private fun customizeStone(initial: StoneParams, onUse: (StoneParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var mottle = initial.mottle
        var cracks = initial.cracks
        var crackContrast = initial.crackContrast
        var grain = initial.grain
        val seed = binding.canvas.surfaceSeed // preview the actual slab

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.STONE, w, h,
                seed = seed,
                stoneParams = StoneParams(tint, mottle, cracks, crackContrast, grain),
            )
        }
        val tintRow = colorRow("Tint", "Slate tint", tint) { c -> tint = c; preview.refresh() }
        val mottleSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.50f; value = mottle.coerceIn(0f, 0.50f)
            addOnChangeListener { _, v, _ -> mottle = v; preview.refresh() }
        }
        val cracksSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.0f; value = cracks.coerceIn(0f, 2.0f)
            addOnChangeListener { _, v, _ -> cracks = v; preview.refresh() }
        }
        val contrastSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.0f; value = crackContrast.coerceIn(0f, 2.0f)
            addOnChangeListener { _, v, _ -> crackContrast = v; preview.refresh() }
        }
        val grainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.20f; value = grain.coerceIn(0f, 0.20f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Mottle", mottleSlider),
            sliderRow("Cracks", cracksSlider),
            sliderRow("Crack contrast", contrastSlider),
            sliderRow("Grain", grainSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Stone")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeStone(StoneParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ ->
                onUse(StoneParams(tint, mottle, cracks, crackContrast, grain))
            }
            .show()
    }

    /**
     * Concrete customisation. The slab is fixed per artwork (its seed); these
     * controls shape its look. Live preview is a true-scale window onto the actual slab.
     */
    private fun customizeConcrete(initial: ConcreteParams, onUse: (ConcreteParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var staining = initial.staining
        var pores = initial.pores
        var grit = initial.grit
        val seed = binding.canvas.surfaceSeed // preview the actual slab

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.CONCRETE, w, h,
                seed = seed,
                concreteParams = ConcreteParams(tint, staining, pores, grit),
            )
        }
        val tintRow = colorRow("Tint", "Cement tint", tint) { c -> tint = c; preview.refresh() }
        val stainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.45f; value = staining.coerceIn(0f, 0.45f)
            addOnChangeListener { _, v, _ -> staining = v; preview.refresh() }
        }
        val poresSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.5f; value = pores.coerceIn(0f, 2.5f)
            addOnChangeListener { _, v, _ -> pores = v; preview.refresh() }
        }
        val gritSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.30f; value = grit.coerceIn(0f, 0.30f)
            addOnChangeListener { _, v, _ -> grit = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Staining", stainSlider),
            sliderRow("Pores", poresSlider),
            sliderRow("Grit", gritSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Concrete")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeConcrete(ConcreteParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(ConcreteParams(tint, staining, pores, grit)) }
            .show()
    }

    /**
     * Metal customisation. The sheet is fixed per artwork (its seed); these
     * controls shape its look. Live preview is a true-scale window onto the actual sheet.
     */
    private fun customizeMetal(initial: MetalParams, onUse: (MetalParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var grain = initial.grain
        var sheen = initial.sheen
        var scratches = initial.scratches
        val seed = binding.canvas.surfaceSeed // preview the actual sheet

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.METAL, w, h,
                seed = seed,
                metalParams = MetalParams(tint, grain, sheen, scratches),
            )
        }
        val tintRow = colorRow("Tint", "Metal tint", tint) { c -> tint = c; preview.refresh() }
        val grainSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.35f; value = grain.coerceIn(0f, 0.35f)
            addOnChangeListener { _, v, _ -> grain = v; preview.refresh() }
        }
        val sheenSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 0.20f; value = sheen.coerceIn(0f, 0.20f)
            addOnChangeListener { _, v, _ -> sheen = v; preview.refresh() }
        }
        val scratchSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.5f; value = scratches.coerceIn(0f, 2.5f)
            addOnChangeListener { _, v, _ -> scratches = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Grain", grainSlider),
            sliderRow("Sheen", sheenSlider),
            sliderRow("Scratches", scratchSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Metal")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeMetal(MetalParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(MetalParams(tint, grain, sheen, scratches)) }
            .show()
    }

    /**
     * Chalkboard customisation. The board is fixed per artwork (its seed); these
     * controls shape its look. Live preview is a true-scale window onto the actual board.
     */
    private fun customizeChalkboard(initial: ChalkboardParams, onUse: (ChalkboardParams) -> Unit) {
        var tint = initial.tint or (0xFF shl 24)
        var ghosting = initial.ghosting
        var dust = initial.dust
        val seed = binding.canvas.surfaceSeed // preview the actual board

        val preview = SurfacePreview { w, h ->
            buildSurfaceVisual(
                SurfaceKind.CHALKBOARD, w, h,
                seed = seed,
                chalkboardParams = ChalkboardParams(tint, ghosting, dust),
            )
        }
        val tintRow = colorRow("Tint", "Board tint", tint) { c -> tint = c; preview.refresh() }
        val ghostSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.5f; value = ghosting.coerceIn(0f, 2.5f)
            addOnChangeListener { _, v, _ -> ghosting = v; preview.refresh() }
        }
        val dustSlider = Slider(this).apply {
            valueFrom = 0f; valueTo = 2.5f; value = dust.coerceIn(0f, 2.5f)
            addOnChangeListener { _, v, _ -> dust = v; preview.refresh() }
        }

        val content = vbox(
            preview, tintRow,
            sliderRow("Ghosting", ghostSlider),
            sliderRow("Dust", dustSlider),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Chalkboard")
            .setView(content)
            .setNeutralButton("Reset") { _, _ -> customizeChalkboard(ChalkboardParams(), onUse) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use") { _, _ -> onUse(ChalkboardParams(tint, ghosting, dust)) }
            .show()
    }

    /**
     * A live surface swatch. Renders [render] at the view's OWN pixel size — true
     * 1:1, no scaling — on resize and on every [refresh]. Fixed 300dp square,
     * centred, with a hairline border so a near-white surface still reads.
     */
    private inner class SurfacePreview(
        private val render: (Int, Int) -> Bitmap,
    ) : View(this@MainActivity) {
        private var bmp: Bitmap? = null
        private val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = 0x33000000
        }

        init {
            layoutParams = LinearLayout.LayoutParams(dp(300), dp(300)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        }

        fun refresh() {
            if (width <= 0 || height <= 0) return
            val old = bmp
            bmp = render(width, height)
            old?.recycle()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            refresh()
        }

        override fun onDraw(canvas: Canvas) {
            bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            canvas.drawRect(0.5f, 0.5f, width - 0.5f, height - 0.5f, border)
        }
    }

    /** A tappable label + colour swatch that opens [pickColor]; reports picks to [onPicked]. */
    private fun colorRow(label: String, title: String, initial: Int, onPicked: (Int) -> Unit): View {
        var current = initial
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(28))
            background = previewSwatch(current)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
            isClickable = true
            addView(TextView(context).apply { text = label; width = dp(64) })
            addView(swatch)
            setOnClickListener {
                pickColor(title, current) { c ->
                    current = c
                    swatch.background = previewSwatch(c)
                    onPicked(c)
                }
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear canvas?")
            .setMessage("This erases everything. There is no undo.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> binding.canvas.clear() }
            .show()
    }

    private fun save() {
        binding.canvas.savePng { result ->
            val msg = result.fold(
                onSuccess = { "Saved to $it" },
                onFailure = { "Save failed: ${it.message}" },
            )
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun openCalibration() {
        calibrationLauncher.launch(Intent(this, CalibrationActivity::class.java))
    }

    private fun applyWandSettings() {
        binding.canvas.wandTolerance = wandTolerance
        binding.canvas.wandEdgeSensitivity = wandEdgeSensitivity
        binding.canvas.wandGap = wandGap
    }

    private fun currentSurface() = AVAILABLE_SURFACES[surfaceIndex]

    // --- View helpers -------------------------------------------------------

    private fun iconButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            if (iconRes != 0) setImageResource(iconRes)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = rippleBg()
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF37474F.toInt())
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    private fun textButton(desc: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            gravity = Gravity.CENTER
            setTextColor(0xFF37474F.toInt())
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rippleBg()
            isClickable = true
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(1)).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        }
        setBackgroundColor(0x22000000)
    }

    private fun swatchCell(color: Int, onClick: () -> Unit): View = View(this).apply {
        layoutParams = GridLayout.LayoutParams().apply {
            width = dp(34); height = dp(34)
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
        background = swatchDrawable(color)
        setOnClickListener { onClick() }
    }

    /** A rounded-rectangle colour chip for the picker's preview bar. */
    private fun previewSwatch(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6).toFloat()
        setColor(color)
        setStroke(dp(1), 0x33000000)
    }

    private fun swatchDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), 0x33000000)
        setSize(dp(24), dp(24))
    }

    private fun selectedBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(0x333DA35A)
    }

    /** A fresh borderless-ripple background (each view needs its own instance). */
    private fun rippleBg(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return ResourcesCompat.getDrawable(resources, outValue.resourceId, theme)
    }

    private fun labelled(name: String, slider: Slider): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = name; width = dp(20) })
        addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** A slider preceded by a fixed-width text label (wider than [labelled]). */
    private fun sliderRow(name: String, slider: Slider): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = name; width = dp(64) })
        addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0x8A000000.toInt())
        setPadding(0, 0, 0, dp(8))
    }

    private fun vbox(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(0))
        for (v in views) addView(v)
    }

    private fun setEnabled(b: ImageButton, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.3f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun toolIcon(t: Tool): Int = when (t) {
        Tool.PENCIL -> R.drawable.ic_tool_pencil
        Tool.PEN -> R.drawable.ic_tool_pen
        Tool.LINE -> R.drawable.ic_tool_line
        Tool.ARC -> R.drawable.ic_tool_arc
        Tool.POLYLINE -> R.drawable.ic_tool_polyline
        Tool.POLYARC -> R.drawable.ic_tool_polyarc
        Tool.BRUSH -> R.drawable.ic_tool_brush
        Tool.WATERCOLOR -> R.drawable.ic_tool_watercolor
        Tool.MARKER -> R.drawable.ic_tool_marker
        Tool.SPRAY -> R.drawable.ic_tool_spray
        Tool.ERASER -> R.drawable.ic_tool_eraser
        Tool.WAND -> R.drawable.ic_tool_wand
    }

    private fun surfaceIcon(s: SurfaceKind): Int = when (s) {
        SurfaceKind.PAPER -> R.drawable.ic_surface_paper
        SurfaceKind.CANVAS -> R.drawable.ic_surface_canvas
        SurfaceKind.METAL -> R.drawable.ic_surface_metal
        SurfaceKind.STONE -> R.drawable.ic_surface_stone
        SurfaceKind.WOOD -> R.drawable.ic_surface_wood
        SurfaceKind.WATERCOLOR -> R.drawable.ic_surface_watercolor
        SurfaceKind.CHALKBOARD -> R.drawable.ic_surface_chalkboard
        SurfaceKind.CONCRETE -> R.drawable.ic_surface_concrete
        SurfaceKind.PLAIN -> R.drawable.ic_surface_plain
    }

    private companion object {
        // Brush/tool size range in millimetres (physical mark width).
        const val SIZE_MIN_MM = 0.1f
        const val SIZE_MAX_MM = 40f

        // Material palette, matching the Flutter reference's swatch list.
        val SWATCHES = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt(),
            0xFF795548.toInt(), 0xFFF44336.toInt(), 0xFFFF5722.toInt(),
            0xFFFF9800.toInt(), 0xFFFFC107.toInt(), 0xFFFFEB3B.toInt(),
            0xFF8BC34A.toInt(), 0xFF4CAF50.toInt(), 0xFF009688.toInt(),
            0xFF00BCD4.toInt(), 0xFF03A9F4.toInt(), 0xFF2196F3.toInt(),
            0xFF3F51B5.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(),
        )
    }
}
