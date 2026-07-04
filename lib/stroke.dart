import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import 'tools.dart';

/// One captured sample along a stroke. Width and density are resolved at
/// capture time from the tool profile + stylus pressure/tilt, so rendering
/// never needs to look at pressure again.
class StrokePoint {
  const StrokePoint(this.position, this.width, [this.density = 1.0]);
  final Offset position;
  final double width;

  /// Per-point opacity/darkness in [0, 1]. Pencil maps pressure to this so
  /// lighter pressure = fainter marks; other tools leave it at 1.
  final double density;
}

/// Tileable grain textures (alpha = paper tooth), one per [GrainStyle].
/// Built once at startup via [initGrains].
final Map<GrainStyle, ui.Image> _grains = {};
ui.Image? grainFor(GrainStyle style) => _grains[style];

/// How many device pixels one grain texel spans (coarser than 1:1 reads more
/// like paper tooth than fine video noise).
const double kGrainScale = 1.8;

/// Builds a tileable grain image. [floor] is the minimum alpha (0 = holes punch
/// through to bare paper for gritty graphite; higher = gentler, even tooth) and
/// [bias] > 1 skews toward the floor for more speckle.
Future<ui.Image> _makeGrain(int size, double floor, double bias, int seed) {
  final rnd = math.Random(seed);
  final px = Uint8List(size * size * 4);
  for (var i = 0; i < size * size; i++) {
    final n = math.pow(rnd.nextDouble(), bias).toDouble();
    final a = floor + (1 - floor) * n;
    px[i * 4 + 3] = (a * 255).round();
  }
  final completer = Completer<ui.Image>();
  ui.decodeImageFromPixels(
      px, size, size, ui.PixelFormat.rgba8888, completer.complete);
  return completer.future;
}

Future<void> initGrains({int size = 128}) async {
  // Pencil: low floor + strong bias -> gritty, broken graphite tooth.
  _grains[GrainStyle.pencil] = await _makeGrain(size, 0.0, 1.4, 7);
  // Marker: high floor, even -> soft, near-solid ink with a hint of texture.
  _grains[GrainStyle.marker] = await _makeGrain(size, 0.62, 1.0, 11);
}

/// A single continuous stroke (pointer-down to pointer-up).
class Stroke {
  Stroke(this.tool, this.color, {this.seed = 0}) : points = <StrokePoint>[];

  final Tool tool;

  /// The color the stroke was drawn with. Eraser strokes use the paper color.
  final Color color;

  /// Stable per-stroke seed for any randomized texture (brush bristles), so the
  /// live preview and the baked result look identical.
  final int seed;

  final List<StrokePoint> points;

  void add(StrokePoint p) => points.add(p);

  bool get isEmpty => points.isEmpty;
}

/// A smooth path through the sample points using quadratic béziers whose
/// endpoints are the segment midpoints and whose control points are the raw
/// samples. This both curves through the points and damps sample jitter.
Path smoothPath(List<StrokePoint> pts) {
  final path = Path()..moveTo(pts.first.position.dx, pts.first.position.dy);
  if (pts.length == 2) {
    path.lineTo(pts[1].position.dx, pts[1].position.dy);
    return path;
  }
  for (var i = 1; i < pts.length - 1; i++) {
    final c = pts[i].position;
    final next = pts[i + 1].position;
    path.quadraticBezierTo(
        c.dx, c.dy, (c.dx + next.dx) / 2, (c.dy + next.dy) / 2);
  }
  final last = pts.last.position;
  path.lineTo(last.dx, last.dy);
  return path;
}

/// Per-point unit normals (perpendicular to the local tangent) for a stroke.
List<Offset> _strokeNormals(List<StrokePoint> pts) {
  final normals = <Offset>[];
  for (var i = 0; i < pts.length; i++) {
    final Offset tangent = i == 0
        ? pts[1].position - pts[0].position
        : i == pts.length - 1
            ? pts[i].position - pts[i - 1].position
            : pts[i + 1].position - pts[i - 1].position;
    final len = tangent.distance;
    final dir = len < 1e-3 ? const Offset(1, 0) : tangent / len;
    normals.add(Offset(-dir.dy, dir.dx));
  }
  return normals;
}

/// A held stylus never reaches 0 (perpendicular) or pi/2 (flat) tilt in
/// practice — the reachable range sits in a compressed middle band. Remap that
/// band to [0, 1] so an upright pen reads as a fine tip and laying it onto its
/// side reads as the full width, with everything in between proportional.
const double kTiltLoRad = 0.42; // ~24 deg: "upright" hold -> thin
const double kTiltHiRad = 1.05; // ~60 deg: on its side -> full width

/// Ease-in exponent (>1) so the first few degrees off upright widen gently
/// instead of jumping, while full tilt still reaches the same max width.
const double kTiltEase = 2.1;

/// Resolves the width of a mark for the given tool, base size and stylus data.
///
/// [pressureNorm] is expected in [0, 1]; [tiltRadians] in [0, pi/2] where 0 is
/// perpendicular to the surface and pi/2 is flat against it.
double resolveWidth({
  required Tool tool,
  required double baseSize,
  required double pressureNorm,
  required double tiltRadians,
}) {
  final profile = ToolProfile.of(tool);
  if (!tool.isDynamic) return baseSize;

  final p = pressureNorm.clamp(0.0, 1.0);
  final pressureFactor = profile.pressureAffectsWidth
      ? ui.lerpDouble(profile.minPressureFactor, profile.maxPressureFactor, p)!
      : 1.0;

  final rawTilt =
      ((tiltRadians - kTiltLoRad) / (kTiltHiRad - kTiltLoRad)).clamp(0.0, 1.0);
  final tiltNorm = math.pow(rawTilt, kTiltEase).toDouble();
  final tiltFactor = 1.0 + profile.tiltGain * tiltNorm;

  return math.max(0.5, baseSize * pressureFactor * tiltFactor);
}

/// Resolves per-point density (darkness) from pressure. Pencil maps light
/// pressure to faint marks; other tools stay fully dense.
double resolveDensity({required Tool tool, required double pressureNorm}) {
  final profile = ToolProfile.of(tool);
  if (!profile.pressureAffectsDensity) return 1.0;
  // Real strokes rarely exceed ~0.8 raw pressure, so scale up to use the full
  // density range without the artist having to bear down to the stops.
  final p = (pressureNorm * 1.3).clamp(0.0, 1.0);
  return ui.lerpDouble(profile.minDensity, profile.maxDensity, p)!;
}

/// Paints a single stroke onto [canvas]. Shared by the live preview and the
/// bake-into-buffer step so what you see while drawing is exactly what commits.
void paintStroke(Canvas canvas, Stroke stroke) {
  if (stroke.isEmpty) return;

  final profile = ToolProfile.of(stroke.tool);
  final rgb = stroke.color.withAlpha(255);

  // Representative width (for blur) and stroke bounds (to keep any layer tight).
  var maxWidth = 0.0;
  var sumWidth = 0.0;
  final first = stroke.points.first.position;
  var minX = first.dx, maxX = first.dx, minY = first.dy, maxY = first.dy;
  for (final p in stroke.points) {
    maxWidth = math.max(maxWidth, p.width);
    sumWidth += p.width;
    minX = math.min(minX, p.position.dx);
    maxX = math.max(maxX, p.position.dx);
    minY = math.min(minY, p.position.dy);
    maxY = math.max(maxY, p.position.dy);
  }
  final avgWidth = sumWidth / stroke.points.length;
  final blurSigma =
      profile.blurFactor > 0 ? profile.blurFactor * avgWidth : 0.0;

  // Pencil/Marker: draw the whole stroke as ONE variable-width ribbon
  // (drawVertices) with per-vertex alpha = density. Painting each pixel exactly
  // once means light pressure stays light — overlapping per-segment draws
  // instead build up to opaque and wash the pressure signal out. Then multiply
  // the ribbon by a grain texture (dstIn) so it reads as lead/ink on paper
  // tooth, not a smooth pen line.
  if (profile.renderStyle == RenderStyle.grain) {
    final pad = maxWidth / 2 + 1;
    final bounds =
        Rect.fromLTRB(minX - pad, minY - pad, maxX + pad, maxY + pad);
    canvas.saveLayer(bounds, Paint());
    final pts = stroke.points;
    if (pts.length == 1) {
      final p = pts.first;
      canvas.drawCircle(
        p.position,
        p.width / 2,
        Paint()
          ..color = rgb.withValues(alpha: p.density)
          ..isAntiAlias = true,
      );
    } else {
      final normals = _strokeNormals(pts);
      final positions = <Offset>[];
      final colors = <Color>[];
      for (var i = 0; i < pts.length; i++) {
        final p = pts[i];
        final hw = math.max(0.25, p.width / 2);
        final col = rgb.withValues(alpha: p.density);
        positions.add(p.position + normals[i] * hw); // left edge
        positions.add(p.position - normals[i] * hw); // right edge
        colors..add(col)..add(col);
      }
      final vertices =
          ui.Vertices(VertexMode.triangleStrip, positions, colors: colors);
      // modulate with opaque white keeps each vertex's own color+alpha.
      canvas.drawVertices(vertices, BlendMode.modulate,
          Paint()..color = const Color(0xFFFFFFFF));
    }
    final grain = grainFor(profile.grainStyle);
    if (grain != null) {
      final matrix = Float64List.fromList([
        kGrainScale, 0, 0, 0, //
        0, kGrainScale, 0, 0, //
        0, 0, 1, 0, //
        0, 0, 0, 1, //
      ]);
      canvas.drawRect(
        bounds,
        Paint()
          ..shader = ui.ImageShader(
              grain, TileMode.repeated, TileMode.repeated, matrix)
          ..blendMode = BlendMode.dstIn,
      );
    }
    canvas.restore();
    return;
  }

  // Paint brush: render several opaque "bristle" streaks that follow the stroke
  // path, offset across its width, with jittered widths and occasional dry-brush
  // gaps. Drawn opaque so they don't build up, then composited once at the tool
  // opacity (with a slight blur) so the whole thing reads as a paint smear.
  if (profile.renderStyle == RenderStyle.bristle) {
    final smear = profile.blurFactor * avgWidth;
    final pad = maxWidth / 2 + smear * 3 + 2;
    final bounds =
        Rect.fromLTRB(minX - pad, minY - pad, maxX + pad, maxY + pad);
    final layerPaint = Paint()
      ..color = Colors.black.withValues(alpha: profile.opacity);
    if (smear > 0.3) {
      layerPaint.imageFilter = ui.ImageFilter.blur(
          sigmaX: smear, sigmaY: smear, tileMode: TileMode.decal);
    }
    canvas.saveLayer(bounds, layerPaint);
    final pts = stroke.points;
    if (pts.length == 1) {
      final p = pts.first;
      canvas.drawCircle(p.position, p.width / 2,
          Paint()..color = rgb..isAntiAlias = true);
    } else {
      final normals = _strokeNormals(pts);
      final rnd = math.Random(stroke.seed);
      // Size coverage from the widest point so the stroke stays filled where it
      // spreads; narrower points just overlap the bristles into a solid band.
      final bristleCount = (maxWidth / 2.5).round().clamp(8, 22);
      final centerSpacing = maxWidth / bristleCount;
      for (var b = 0; b < bristleCount; b++) {
        if (rnd.nextDouble() < 0.1) continue; // dry-brush gap
        // even lateral position across [-1, 1] (fraction of half-width), jittered
        final base = (b + 0.5) / bristleCount * 2 - 1;
        final frac =
            (base + (rnd.nextDouble() - 0.5) * (2.0 / bristleCount) * 0.8)
                .clamp(-1.0, 1.0);
        final bw = math.max(0.6, centerSpacing * (0.9 + rnd.nextDouble() * 0.9));
        final path = Path();
        for (var j = 0; j < pts.length; j++) {
          final p = pts[j];
          final off = p.position + normals[j] * (frac * p.width / 2);
          if (j == 0) {
            path.moveTo(off.dx, off.dy);
          } else {
            path.lineTo(off.dx, off.dy);
          }
        }
        canvas.drawPath(
          path,
          Paint()
            ..style = PaintingStyle.stroke
            ..strokeWidth = bw
            ..strokeCap = StrokeCap.round
            ..strokeJoin = StrokeJoin.round
            ..color = rgb
            ..isAntiAlias = true,
        );
      }
    }
    canvas.restore();
    return;
  }

  // A single isolated layer gives uniform opacity (no darker seams where
  // round-cap segments overlap) AND a one-pass soft edge — far cheaper than
  // blurring every segment individually, which is what made the brush lag.
  final needsLayer = profile.opacity < 1.0 || blurSigma > 0;
  if (needsLayer) {
    final pad = maxWidth / 2 + blurSigma * 3 + 1;
    final bounds =
        Rect.fromLTRB(minX - pad, minY - pad, maxX + pad, maxY + pad);
    final layerPaint = Paint();
    if (profile.opacity < 1.0) {
      layerPaint.color = Colors.black.withValues(alpha: profile.opacity);
    }
    if (blurSigma > 0) {
      // ImageFilter (not MaskFilter) blurs the composited layer — MaskFilter on
      // a saveLayer paint is ignored by Impeller, which left the edges crisp.
      layerPaint.imageFilter = ui.ImageFilter.blur(
        sigmaX: blurSigma,
        sigmaY: blurSigma,
        tileMode: TileMode.decal,
      );
    }
    canvas.saveLayer(bounds, layerPaint);
  }

  // The eraser removes paint (revealing the surface layer) rather than
  // depositing color.
  final blend =
      stroke.tool == Tool.eraser ? BlendMode.clear : BlendMode.srcOver;

  if (stroke.points.length == 1) {
    final p = stroke.points.first;
    canvas.drawCircle(
      p.position,
      p.width / 2,
      Paint()
        ..color = rgb
        ..blendMode = blend
        ..style = PaintingStyle.fill
        ..isAntiAlias = true,
    );
  } else if (!stroke.tool.isDynamic) {
    // Constant width (pen, eraser): one smooth bézier path for clean, curved
    // edges instead of angular straight segments between raw samples.
    canvas.drawPath(
      smoothPath(stroke.points),
      Paint()
        ..color = rgb
        ..blendMode = blend
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke.points.first.width
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round
        ..isAntiAlias = true,
    );
  } else {
    // Variable width (spray): per-segment so the width can track pressure.
    final segPaint = Paint()
      ..color = rgb
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..isAntiAlias = true;
    for (var i = 1; i < stroke.points.length; i++) {
      final a = stroke.points[i - 1];
      final b = stroke.points[i];
      segPaint.strokeWidth = (a.width + b.width) / 2;
      canvas.drawLine(a.position, b.position, segPaint);
    }
  }

  if (needsLayer) canvas.restore();
}
