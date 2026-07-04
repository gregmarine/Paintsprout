import 'package:flutter/material.dart';

/// The drawing tools available in Phase 0.
enum Tool { pencil, pen, brush, marker, spray, eraser }

/// Which grain texture (if any) modulates a tool's ribbon.
enum GrainStyle { none, pencil, marker }

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
}

extension ToolInfo on Tool {
  String get label => switch (this) {
        Tool.pencil => 'Pencil',
        Tool.pen => 'Pen',
        Tool.brush => 'Brush',
        Tool.marker => 'Marker',
        Tool.spray => 'Spray',
        Tool.eraser => 'Eraser',
      };

  IconData get icon => switch (this) {
        Tool.pencil => Icons.edit,
        Tool.pen => Icons.create,
        Tool.brush => Icons.brush,
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
    required this.grainStyle,
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

  /// Which grain texture modulates the stroke ribbon (none = smooth tool).
  final GrainStyle grainStyle;

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
    grainStyle: GrainStyle.pencil,
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
    grainStyle: GrainStyle.marker,
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
    grainStyle: GrainStyle.none,
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
    grainStyle: GrainStyle.none,
    renderStyle: RenderStyle.bristle,
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
    grainStyle: GrainStyle.none,
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
    grainStyle: GrainStyle.none,
    renderStyle: RenderStyle.solid,
  );

  static ToolProfile of(Tool tool) => switch (tool) {
        Tool.pencil => _pencil,
        Tool.pen => _pen,
        Tool.brush => _brush,
        Tool.marker => _marker,
        Tool.spray => _spray,
        Tool.eraser => _eraser,
      };
}
