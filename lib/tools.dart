import 'package:flutter/material.dart';

/// The drawing tools available in Phase 0.
enum Tool { pencil, pen, brush, watercolor, marker, spray, eraser }

/// How a stroke is rendered.
enum RenderStyle {
  /// Crisp constant-color ribbon (pen, eraser).
  solid,

  /// Soft blurred, translucent, builds up on overlap (spray can).
  soft,

  /// Variable-width ribbon multiplied by a grain texture (pencil, marker).
  grain,

  /// Multiple bristle streaks following the path with dry-brush gaps (brush).
  bristle,

  /// Translucent pigment wash: soft bleeding edges, darker pooled rim, grainy
  /// granulation in the surface tooth, building up where strokes overlap.
  wash,
}

extension ToolInfo on Tool {
  String get label => switch (this) {
        Tool.pencil => 'Pencil',
        Tool.pen => 'Pen',
        Tool.brush => 'Brush',
        Tool.watercolor => 'Watercolor',
        Tool.marker => 'Marker',
        Tool.spray => 'Spray',
        Tool.eraser => 'Eraser',
      };

  IconData get icon => switch (this) {
        Tool.pencil => Icons.edit,
        Tool.pen => Icons.create,
        Tool.brush => Icons.brush,
        Tool.watercolor => Icons.water_drop,
        Tool.marker => Icons.border_color,
        Tool.spray => Icons.blur_on,
        Tool.eraser => Icons.cleaning_services,
      };

  /// Whether the tool's stroke width reacts to stylus pressure and tilt.
  /// Pen and eraser strictly honor the base size; the rest grow.
  bool get isDynamic => this != Tool.pen && this != Tool.eraser;

  /// Sensible starting base size (logical px) per tool.
  double get defaultSize => switch (this) {
        Tool.pencil => 1,
        Tool.pen => 3,
        Tool.brush => 18,
        Tool.watercolor => 26,
        Tool.marker => 4,
        Tool.spray => 28,
        Tool.eraser => 24,
      };
}

/// Per-tool feel parameters used by the stroke renderer.
class ToolProfile {
  const ToolProfile({
    required this.minPressureFactor,
    required this.maxPressureFactor,
    required this.pressureAffectsWidth,
    required this.tiltGain,
    required this.pressureAffectsDensity,
    required this.minDensity,
    required this.maxDensity,
    required this.opacity,
    required this.blurFactor,
    required this.toothFloor,
    required this.toothBias,
    required this.renderStyle,
  });

  /// Width multiplier at zero and full pressure (relative to base size).
  final double minPressureFactor;
  final double maxPressureFactor;

  /// Whether pressure scales stroke width (brush) or not (pencil: pressure
  /// drives density instead).
  final bool pressureAffectsWidth;

  /// How much tilt broadens the mark (0 = none).
  final double tiltGain;

  /// Whether pressure scales per-point opacity/darkness (pencil).
  final bool pressureAffectsDensity;
  final double minDensity;
  final double maxDensity;

  /// Stroke opacity applied uniformly across the whole stroke (tools that
  /// don't vary density per point).
  final double opacity;

  /// Soft-edge blur as a fraction of stroke width (0 = crisp).
  final double blurFactor;

  /// How the tool reacts to the surface tooth. The surface supplies a raw tooth
  /// field; these remap it into the alpha mask that breaks up the mark.
  /// [toothFloor] is the ink kept at the deepest tooth valley — low = high
  /// sensitivity (grooves punch through to bare surface, gritty), high =
  /// near-solid with only a hint of tooth, 1.0 = ignores the surface entirely.
  /// [toothBias] > 1 skews toward the valleys for more speckle.
  final double toothFloor;
  final double toothBias;

  /// Whether this tool's mark is broken up by the surface tooth at all.
  bool get reactsToTooth => toothFloor < 1.0;

  /// How the stroke is rendered.
  final RenderStyle renderStyle;

  // Pencil: pressure -> darkness, tilt -> width, gritty graphite grain.
  // High tilt gain so a fine upright tip opens up to a broad side-of-the-lead
  // mark across the remapped tilt range.
  static const _pencil = ToolProfile(
    minPressureFactor: 1.0,
    maxPressureFactor: 1.0,
    pressureAffectsWidth: false,
    tiltGain: 16.0,
    pressureAffectsDensity: true,
    minDensity: 0.1,
    maxDensity: 0.95,
    opacity: 1.0,
    blurFactor: 0.0,
    toothFloor: 0.0, // gritty: grooves show bare surface
    toothBias: 1.4,
    renderStyle: RenderStyle.grain,
  );

  // Marker: same feel as the pencil, but a soft/even grain -> chunky marker.
  static const _marker = ToolProfile(
    minPressureFactor: 1.0,
    maxPressureFactor: 1.0,
    pressureAffectsWidth: false,
    tiltGain: 5.5,
    pressureAffectsDensity: true,
    minDensity: 0.1,
    maxDensity: 0.95,
    opacity: 1.0,
    blurFactor: 0.0,
    toothFloor: 0.62, // even: soft, near-solid ink
    toothBias: 1.0,
    renderStyle: RenderStyle.grain,
  );

  static const _pen = ToolProfile(
    minPressureFactor: 1.0,
    maxPressureFactor: 1.0,
    pressureAffectsWidth: false,
    tiltGain: 0.0,
    pressureAffectsDensity: false,
    minDensity: 1.0,
    maxDensity: 1.0,
    opacity: 1.0,
    blurFactor: 0.0,
    toothFloor: 0.85, // gel pen: mostly fills, faint tooth on rough surfaces
    toothBias: 1.0,
    renderStyle: RenderStyle.solid,
  );

  // Paint brush: bristle streaks that follow the path, spreading with pressure
  // and dragging like a loaded brush.
  static const _brush = ToolProfile(
    minPressureFactor: 0.35,
    maxPressureFactor: 2.2,
    pressureAffectsWidth: true,
    tiltGain: 0.4,
    pressureAffectsDensity: false,
    minDensity: 1.0,
    maxDensity: 1.0,
    opacity: 0.9,
    blurFactor: 0.08, // slight smear so bristles read as paint, not hard combs
    toothFloor: 0.7, // medium: dry-brush skips over the tooth
    toothBias: 1.0,
    renderStyle: RenderStyle.bristle,
  );

  // Watercolor: a translucent pigment wash. Low opacity so strokes are
  // see-through and build up where they overlap; a soft blur for bleeding edges;
  // a moderate tooth response so pigment granulates in the surface. Pressure
  // loads the brush (wider). The wash renderer adds the pooled edge rim.
  static const _watercolor = ToolProfile(
    minPressureFactor: 0.5,
    maxPressureFactor: 2.0,
    pressureAffectsWidth: true,
    tiltGain: 0.3,
    pressureAffectsDensity: false,
    minDensity: 1.0,
    maxDensity: 1.0,
    opacity: 0.5,
    blurFactor: 0.12, // soft bleed, but not so strong it erases the pooled rim
    toothFloor: 0.6, // granulation: pigment settles into the tooth
    toothBias: 1.0,
    renderStyle: RenderStyle.wash,
  );

  // Spray can: soft, translucent, builds up on overlap (the old brush look).
  static const _spray = ToolProfile(
    minPressureFactor: 0.25,
    maxPressureFactor: 2.8,
    pressureAffectsWidth: true,
    tiltGain: 0.5,
    pressureAffectsDensity: false,
    minDensity: 1.0,
    maxDensity: 1.0,
    opacity: 0.92,
    blurFactor: 0.25,
    toothFloor: 0.78, // droplets settle a touch more on the crests
    toothBias: 1.0,
    renderStyle: RenderStyle.soft,
  );

  static const _eraser = ToolProfile(
    minPressureFactor: 1.0,
    maxPressureFactor: 1.0,
    pressureAffectsWidth: false,
    tiltGain: 0.0,
    pressureAffectsDensity: false,
    minDensity: 1.0,
    maxDensity: 1.0,
    opacity: 1.0,
    blurFactor: 0.0,
    toothFloor: 0.85, // erasing leaves faint residue down in the tooth valleys
    toothBias: 1.0,
    renderStyle: RenderStyle.solid,
  );

  static ToolProfile of(Tool tool) => switch (tool) {
        Tool.pencil => _pencil,
        Tool.pen => _pen,
        Tool.brush => _brush,
        Tool.watercolor => _watercolor,
        Tool.marker => _marker,
        Tool.spray => _spray,
        Tool.eraser => _eraser,
      };
}
