package com.symmetricalpalmtree.paintsprout.paint

/**
 * The drawing tools. Ported from the Flutter reference `tools.dart`.
 *
 * [wand] is not a drawing tool — it's the magic-wand selection tool. It never
 * creates a stroke; a tap flood-fills a selection instead.
 *
 * Note: the per-tool `IconData` from the Flutter enum is intentionally omitted
 * here — icons map to Android drawable resources and belong to the UI shell, not
 * the pure paint model.
 */
enum class Tool {
    PENCIL, PEN, LINE, ARC, POLYLINE, POLYARC, BRUSH, WATERCOLOR, MARKER, SPRAY, ERASER, WAND;

    val label: String
        get() = when (this) {
            PENCIL -> "Pencil"
            PEN -> "Pen"
            LINE -> "Line"
            ARC -> "Arc"
            POLYLINE -> "Polyline"
            POLYARC -> "Polyarc"
            BRUSH -> "Brush"
            WATERCOLOR -> "Watercolor"
            MARKER -> "Marker"
            SPRAY -> "Spray"
            ERASER -> "Eraser"
            WAND -> "Magic Wand"
        }

    /** Whether this tool paints strokes at all. The wand only selects. */
    val isDrawing: Boolean get() = this != WAND

    /**
     * Whether the tool's stroke width reacts to stylus pressure and tilt.
     * Pen, line, arc, polyline, polyarc and eraser strictly honor the base size; the rest grow.
     */
    val isDynamic: Boolean
        get() = this != PEN && this != ERASER && this != LINE && this != ARC &&
            this != POLYLINE && this != POLYARC

    /**
     * Wet media: they carry a finite load of paint from the tray, run out as
     * they go, and pick up pigment they drag through. Dry media don't — a
     * pencil dragged through wet paint must not load up with it.
     */
    val usesLoad: Boolean
        get() = this == BRUSH || this == WATERCOLOR

    /** Sensible starting base size (logical px) per tool. Fallback only; the UI
     *  drives size in millimetres (see [defaultSizeMm]). */
    val defaultSize: Float
        get() = when (this) {
            PENCIL -> 1f
            PEN -> 3f
            LINE -> 3f
            ARC -> 3f
            POLYLINE -> 3f
            POLYARC -> 3f
            BRUSH -> 18f
            WATERCOLOR -> 26f
            MARKER -> 4f
            SPRAY -> 28f
            ERASER -> 24f
            WAND -> 1f
        }

    /**
     * Sensible starting size in millimetres — the physical width of the mark on a
     * calibrated screen. The UI stores sizes in mm and converts to pixels at the
     * current PPI, so a given size prints the same real size on any device.
     */
    val defaultSizeMm: Float
        get() = when (this) {
            PENCIL -> 0.3f
            PEN -> 0.5f
            LINE -> 0.5f
            ARC -> 0.5f
            POLYLINE -> 0.5f
            POLYARC -> 0.5f
            BRUSH -> 4f
            WATERCOLOR -> 6f
            MARKER -> 2f
            SPRAY -> 8f
            ERASER -> 6f
            WAND -> 1f
        }
}

/** How a stroke is rendered. */
enum class RenderStyle {
    /** Crisp constant-color ribbon (pen, eraser). */
    SOLID,

    /** Soft blurred, translucent, builds up on overlap (spray can). */
    SOFT,

    /** Variable-width ribbon multiplied by a grain texture (pencil, marker). */
    GRAIN,

    /** Multiple bristle streaks following the path with dry-brush gaps (brush). */
    BRISTLE,

    /**
     * Translucent pigment wash: soft bleeding edges, darker pooled rim, grainy
     * granulation in the surface tooth, building up where strokes overlap.
     */
    WASH,
}

/**
 * Per-tool feel parameters used by the stroke renderer. Ported field-for-field
 * (and value-for-value) from `ToolProfile` in the Flutter `tools.dart`.
 */
data class ToolProfile(
    /** Width multiplier at zero and full pressure (relative to base size). */
    val minPressureFactor: Float,
    val maxPressureFactor: Float,

    /**
     * Whether pressure scales stroke width (brush) or not (pencil: pressure
     * drives density instead).
     */
    val pressureAffectsWidth: Boolean,

    /** How much tilt broadens the mark (0 = none). */
    val tiltGain: Float,

    /** Whether pressure scales per-point opacity/darkness (pencil). */
    val pressureAffectsDensity: Boolean,
    val minDensity: Float,
    val maxDensity: Float,

    /**
     * Stroke opacity applied uniformly across the whole stroke (tools that don't
     * vary density per point).
     */
    val opacity: Float,

    /** Soft-edge blur as a fraction of stroke width (0 = crisp). */
    val blurFactor: Float,

    /**
     * How the tool reacts to the surface tooth. [toothFloor] is the ink kept at
     * the deepest tooth valley — low = high sensitivity (gritty), high =
     * near-solid, 1.0 = ignores the surface entirely. [toothBias] > 1 skews
     * toward the valleys for more speckle.
     */
    val toothFloor: Float,
    val toothBias: Float,

    /** How the stroke is rendered. */
    val renderStyle: RenderStyle,

    /**
     * How long this tool's laid paint stays WET on the canvas, in ms — the
     * window in which a brush dragged through it smears it (picks it up fast
     * and carries it). 0 = dry media: pencil graphite never liquid-smears.
     * Fresher = more smearable; see the wet-trace ledger in PaintCanvasView.
     */
    val wetMs: Long = 0L,
) {
    /** Whether this tool's mark is broken up by the surface tooth at all. */
    val reactsToTooth: Boolean get() = toothFloor < 1.0f

    companion object {
        // Pencil: pressure -> darkness, tilt -> width, gritty graphite grain.
        private val PENCIL = ToolProfile(
            minPressureFactor = 1.0f,
            maxPressureFactor = 1.0f,
            pressureAffectsWidth = false,
            tiltGain = 16.0f,
            pressureAffectsDensity = true,
            minDensity = 0.1f,
            maxDensity = 0.95f,
            opacity = 1.0f,
            blurFactor = 0.0f,
            toothFloor = 0.0f, // gritty: grooves show bare surface
            toothBias = 1.4f,
            renderStyle = RenderStyle.GRAIN,
        )

        // Marker: same feel as the pencil, but a soft/even grain -> chunky marker.
        private val MARKER = ToolProfile(
            minPressureFactor = 1.0f,
            maxPressureFactor = 1.0f,
            pressureAffectsWidth = false,
            tiltGain = 5.5f,
            pressureAffectsDensity = true,
            minDensity = 0.1f,
            maxDensity = 0.95f,
            opacity = 1.0f,
            blurFactor = 0.0f,
            toothFloor = 0.62f, // even: soft, near-solid ink
            toothBias = 1.0f,
            renderStyle = RenderStyle.GRAIN,
            wetMs = 4000, // fresh dye ink smears briefly
        )

        private val PEN = ToolProfile(
            minPressureFactor = 1.0f,
            maxPressureFactor = 1.0f,
            pressureAffectsWidth = false,
            tiltGain = 0.0f,
            pressureAffectsDensity = false,
            minDensity = 1.0f,
            maxDensity = 1.0f,
            opacity = 1.0f,
            blurFactor = 0.0f,
            toothFloor = 0.85f, // gel pen: mostly fills, faint tooth on rough surfaces
            toothBias = 1.0f,
            renderStyle = RenderStyle.SOLID,
            wetMs = 4000, // wet ink line, briefly smearable
        )

        // Line: a straight, clean, constant-width solid mark — the pen's feel, drawn
        // as an editable two-point segment rather than a freehand path.
        private val LINE = ToolProfile(
            minPressureFactor = 1.0f,
            maxPressureFactor = 1.0f,
            pressureAffectsWidth = false,
            tiltGain = 0.0f,
            pressureAffectsDensity = false,
            minDensity = 1.0f,
            maxDensity = 1.0f,
            opacity = 1.0f,
            blurFactor = 0.0f,
            toothFloor = 0.85f, // like the pen: mostly fills, faint tooth on rough surfaces
            toothBias = 1.0f,
            renderStyle = RenderStyle.SOLID,
            wetMs = 4000, // same ink as the pen
        )

        // Paint brush: bristle streaks that follow the path, spreading with pressure.
        private val BRUSH = ToolProfile(
            minPressureFactor = 0.35f,
            maxPressureFactor = 2.2f,
            pressureAffectsWidth = true,
            tiltGain = 0.7f, // laying the brush over drags its side: notably wider

            pressureAffectsDensity = false,
            minDensity = 1.0f,
            maxDensity = 1.0f,
            opacity = 0.9f,
            blurFactor = 0.08f, // slight smear so bristles read as paint, not hard combs
            toothFloor = 0.7f, // medium: dry-brush skips over the tooth
            toothBias = 1.0f,
            renderStyle = RenderStyle.BRISTLE,
            wetMs = 15000, // laid paint stays workable
        )

        // Watercolor: a translucent pigment wash.
        private val WATERCOLOR = ToolProfile(
            minPressureFactor = 0.5f,
            maxPressureFactor = 2.0f,
            pressureAffectsWidth = true,
            tiltGain = 0.3f,
            pressureAffectsDensity = false,
            minDensity = 1.0f,
            maxDensity = 1.0f,
            opacity = 0.5f,
            blurFactor = 0.12f, // soft bleed, but not so strong it erases the pooled rim
            toothFloor = 0.6f, // granulation: pigment settles into the tooth
            toothBias = 1.0f,
            renderStyle = RenderStyle.WASH,
            wetMs = 20000, // a wash stays wet longest
        )

        // Spray can: soft, translucent, builds up on overlap.
        private val SPRAY = ToolProfile(
            minPressureFactor = 0.25f,
            maxPressureFactor = 2.8f,
            pressureAffectsWidth = true,
            tiltGain = 0.5f,
            pressureAffectsDensity = false,
            minDensity = 1.0f,
            maxDensity = 1.0f,
            opacity = 0.92f,
            blurFactor = 0.25f,
            toothFloor = 0.78f, // droplets settle a touch more on the crests
            toothBias = 1.0f,
            renderStyle = RenderStyle.SOFT,
            wetMs = 6000, // a wet droplet field
        )

        private val ERASER = ToolProfile(
            minPressureFactor = 1.0f,
            maxPressureFactor = 1.0f,
            pressureAffectsWidth = false,
            tiltGain = 0.0f,
            pressureAffectsDensity = false,
            minDensity = 1.0f,
            maxDensity = 1.0f,
            opacity = 1.0f,
            blurFactor = 0.0f,
            toothFloor = 0.85f, // erasing leaves faint residue down in the tooth valleys
            toothBias = 1.0f,
            renderStyle = RenderStyle.SOLID,
        )

        fun of(tool: Tool): ToolProfile = when (tool) {
            Tool.PENCIL -> PENCIL
            Tool.PEN -> PEN
            Tool.LINE -> LINE
            Tool.ARC -> LINE // arc shares the line's clean solid feel
            Tool.POLYLINE -> LINE // polyline: straight segments with the line's feel
            Tool.POLYARC -> LINE // polyarc: chained arcs with the line's feel
            Tool.BRUSH -> BRUSH
            Tool.WATERCOLOR -> WATERCOLOR
            Tool.MARKER -> MARKER
            Tool.SPRAY -> SPRAY
            Tool.ERASER -> ERASER
            Tool.WAND -> PEN // unused: the wand never draws
        }
    }
}
