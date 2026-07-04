import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

/// The base layer you paint on. Procedural for now; the generator is behind an
/// interface so image-based surfaces can be added later.
enum SurfaceKind { paper, canvas, metal, stone, wood, watercolor, chalkboard, concrete }

extension SurfaceInfo on SurfaceKind {
  String get label => switch (this) {
        SurfaceKind.paper => 'Paper',
        SurfaceKind.canvas => 'Canvas',
        SurfaceKind.metal => 'Metal',
        SurfaceKind.stone => 'Stone',
        SurfaceKind.wood => 'Wood',
        SurfaceKind.watercolor => 'Watercolor',
        SurfaceKind.chalkboard => 'Chalkboard',
        SurfaceKind.concrete => 'Concrete',
      };

  /// Logical px spanned by one tooth texel when the surface breaks up a stroke.
  /// Tuned so the tooth's feature size matches the drawn texture: canvas locks
  /// to the visual weave (a thread crest in the mark lands on a thread crest in
  /// the surface), paper stays a fine, coarsely-sampled grit.
  double get toothScale => switch (this) {
        SurfaceKind.canvas => 0.375, // 8-texel thread -> 6 buffer px == weave
        SurfaceKind.metal => 1.2, // fine brushed grain
        SurfaceKind.stone => 2.0, // coarse, blotchy
        SurfaceKind.wood => 1.5, // grain-scaled
        SurfaceKind.watercolor => 1.2, // rough cold-press dimples
        SurfaceKind.chalkboard => 1.5, // fine, even
        SurfaceKind.concrete => 2.0, // coarse grit
        _ => 1.8, // paper
      };

  IconData get icon => switch (this) {
        SurfaceKind.paper => Icons.description_outlined,
        SurfaceKind.canvas => Icons.grid_on,
        SurfaceKind.metal => Icons.blur_linear,
        SurfaceKind.stone => Icons.terrain,
        SurfaceKind.wood => Icons.forest_outlined,
        SurfaceKind.watercolor => Icons.water_drop_outlined,
        SurfaceKind.chalkboard => Icons.rectangle_outlined,
        SurfaceKind.concrete => Icons.dashboard_outlined,
      };
}

/// Surfaces wired into the picker so far. Grows as recipes are added.
const List<SurfaceKind> kAvailableSurfaces = [
  SurfaceKind.paper,
  SurfaceKind.canvas,
  SurfaceKind.watercolor,
  SurfaceKind.wood,
  SurfaceKind.stone,
  SurfaceKind.concrete,
  SurfaceKind.metal,
  SurfaceKind.chalkboard,
];

/// Builds the visual (color) layer for a surface at [w]x[h] buffer pixels.
/// Textures are designed at buffer-pixel scale and tiled.
Future<ui.Image> buildSurfaceVisual(SurfaceKind kind, int w, int h) async {
  final tile = await _visualTile(kind);
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  // Tile the seamless texture across the whole surface (identity scale: the
  // tile is authored at final resolution).
  canvas.drawRect(
    Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()),
    Paint()
      ..shader = ui.ImageShader(
          tile, TileMode.repeated, TileMode.repeated, _identity),
  );
  return recorder.endRecording().toImage(w, h);
}

final Float64List _identity = Float64List.fromList([
  1, 0, 0, 0, //
  0, 1, 0, 0, //
  0, 0, 1, 0, //
  0, 0, 0, 1, //
]);

Future<ui.Image> _visualTile(SurfaceKind kind) => switch (kind) {
      SurfaceKind.canvas => _canvasTile(),
      SurfaceKind.metal => _metalTile(),
      SurfaceKind.stone => _stoneTile(),
      SurfaceKind.wood => _woodTile(),
      SurfaceKind.watercolor => _watercolorTile(),
      SurfaceKind.chalkboard => _chalkboardTile(),
      SurfaceKind.concrete => _concreteTile(),
      _ => _paperTile(), // paper + fallback
    };

/// Raw surface tooth: how strongly each texel catches dry media, in [0, 1].
/// 1 = a raised fiber/thread crest that grabs graphite; 0 = a recessed groove
/// that stays bare. A tool's own response (floor/bias) turns this into the
/// alpha texture that breaks up its ribbon — see `_bakeTooth` in stroke.dart.
///
/// Paper is a fine, even tooth (uniform noise, same seed as the old pencil
/// grain so pencil-on-paper is unchanged). Canvas follows the weave so a pencil
/// catches the raised threads and skips the grooves.
Float64List buildToothField(SurfaceKind kind, int size) {
  final field = Float64List(size * size);
  switch (kind) {
    case SurfaceKind.canvas:
      _canvasTooth(field, size);
    case SurfaceKind.metal:
      _metalTooth(field, size);
    case SurfaceKind.stone:
      _stoneTooth(field, size);
    case SurfaceKind.wood:
      _woodTooth(field, size);
    case SurfaceKind.watercolor:
      _watercolorTooth(field, size);
    case SurfaceKind.chalkboard:
      _chalkboardTooth(field, size);
    case SurfaceKind.concrete:
      _concreteTooth(field, size);
    default:
      _paperTooth(field, size);
  }
  return field;
}

void _paperTooth(Float64List f, int size) {
  final rnd = math.Random(7); // matches the original pencil-grain seed
  for (var i = 0; i < f.length; i++) {
    f[i] = rnd.nextDouble();
  }
}

void _canvasTooth(Float64List f, int size) {
  const period = 8.0; // texels per thread: the weave, locked to the visual scale
  // Graphite grit is authored coarse (4-texel cells) so it survives canvas's
  // fine tooth scale instead of being filtered away into a smooth (marker-like)
  // fill. The grid tiles seamlessly with the 128px tooth tile.
  const gritCell = 4;
  final gridN = size ~/ gritCell;
  final gritRnd = math.Random(5);
  final grit =
      List<double>.generate(gridN * gridN, (_) => gritRnd.nextDouble());
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final warp = 0.5 - 0.5 * math.cos(2 * math.pi * x / period);
      final weft = 0.5 - 0.5 * math.cos(2 * math.pi * y / period);
      final over = ((x ~/ period) + (y ~/ period)) % 2 == 0;
      final crown = over ? warp : weft;
      final g = grit[(y ~/ gritCell) * gridN + (x ~/ gritCell)];
      // Threads catch lead (crown -> 1); grooves fall away toward bare (0.12) so
      // a pencil skips them and the weave shows through. Coarse grit breaks up
      // the crests so graphite reads as grit, not a solid marker band.
      final t = (0.12 + 0.88 * crown) * (0.45 + 0.55 * g);
      f[y * size + x] = t.clamp(0.0, 1.0);
    }
  }
}

/// Metal is nearly smooth: high tooth everywhere (marks deposit evenly) with a
/// faint brushed streak, so a pencil slides rather than biting.
void _metalTooth(Float64List f, int size) {
  final streak = _noiseGrid(48, 6, 121);
  final rnd = math.Random(122);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final s = _noise2(streak, 48, 6, x / size * 48, y / size * 6);
      final t = 0.80 + 0.15 * s + 0.05 * rnd.nextDouble();
      f[y * size + x] = t.clamp(0.0, 1.0);
    }
  }
}

/// Stone: medium, irregular tooth — broad blotches over a mineral grain.
void _stoneTooth(Float64List f, int size) {
  final big = _noiseGrid(6, 6, 131);
  final mid = _noiseGrid(20, 20, 132);
  final rnd = math.Random(133);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final b = _noise2(big, 6, 6, x / size * 6, y / size * 6);
      final m = _noise2(mid, 20, 20, x / size * 20, y / size * 20);
      final base = 0.55 * b + 0.3 * m + 0.15 * rnd.nextDouble();
      f[y * size + x] = (0.25 + 0.75 * base).clamp(0.0, 1.0);
    }
  }
}

/// Wood: grain lines are recessed and catch a little less, so a pencil rides
/// the flats and skips the grooves, letting the grain read through.
void _woodTooth(Float64List f, int size) {
  final wave = _noiseGrid(4, 8, 141);
  final rnd = math.Random(142);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final disp = _noise2(wave, 4, 8, x / size * 4, y / size * 8);
      final g = 0.5 - 0.5 * math.cos(2 * math.pi * (7 * x / size + 0.35 * disp));
      final line = math.pow(g, 2.2).toDouble();
      final t = (0.45 + 0.4 * (1 - line)) * (0.75 + 0.25 * rnd.nextDouble());
      f[y * size + x] = t.clamp(0.0, 1.0);
    }
  }
}

/// Watercolor paper: the roughest tooth — deep irregular dimples (near bare in
/// the pits) plus a coarse grit that survives the fine scale, so dry media
/// breaks up hard, like graphite on cold-press stock.
void _watercolorTooth(Float64List f, int size) {
  final dimple = _noiseGrid(22, 22, 151);
  const gritCell = 4;
  final gridN = size ~/ gritCell;
  final gritRnd = math.Random(153);
  final grit =
      List<double>.generate(gridN * gridN, (_) => gritRnd.nextDouble());
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final d = _noise2(dimple, 22, 22, x / size * 22, y / size * 22);
      final g = grit[(y ~/ gritCell) * gridN + (x ~/ gritCell)];
      final t = (0.1 + 0.9 * d) * (0.45 + 0.55 * g);
      f[y * size + x] = t.clamp(0.0, 1.0);
    }
  }
}

/// Chalkboard: a fine, fairly even tooth — smoother than paper (higher floor)
/// so chalk/pencil lays down cleanly with just a hint of grain.
void _chalkboardTooth(Float64List f, int size) {
  final rnd = math.Random(161);
  for (var i = 0; i < f.length; i++) {
    f[i] = 0.45 + 0.55 * rnd.nextDouble();
  }
}

/// Concrete: rough and sandy — fine aggregate grit modulated by broad stains.
void _concreteTooth(Float64List f, int size) {
  final stain = _noiseGrid(6, 6, 171);
  final rnd = math.Random(172);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final s = _noise2(stain, 6, 6, x / size * 6, y / size * 6);
      final t = 0.2 + 0.8 * (0.4 * s + 0.6 * rnd.nextDouble());
      f[y * size + x] = t.clamp(0.0, 1.0);
    }
  }
}

Future<ui.Image> _imageFromPixels(int size, Uint8List px) {
  final completer = Completer<ui.Image>();
  ui.decodeImageFromPixels(
      px, size, size, ui.PixelFormat.rgba8888, completer.complete);
  return completer.future;
}

void _setPixel(Uint8List px, int i, double r, double g, double b) {
  px[i] = (r * 255).round().clamp(0, 255);
  px[i + 1] = (g * 255).round().clamp(0, 255);
  px[i + 2] = (b * 255).round().clamp(0, 255);
  px[i + 3] = 255;
}

/// A [gx]x[gy] grid of random cells for tileable value noise.
List<double> _noiseGrid(int gx, int gy, int seed) {
  final rnd = math.Random(seed);
  return List<double>.generate(gx * gy, (_) => rnd.nextDouble());
}

/// Samples [g] (a [gx]x[gy] grid) at ([u], [v]) in cell units with smoothstep
/// bilinear interpolation. Wrapping the cell indices keeps the result seamless,
/// so the containing tile tiles without visible seams. Anisotropic grids (gx !=
/// gy) stretch the noise — e.g. fine-x/coarse-y reads as brushed streaks.
double _noise2(List<double> g, int gx, int gy, double u, double v) {
  final x0 = u.floor(), y0 = v.floor();
  final fx = u - x0, fy = v - y0;
  final x0i = x0 % gx, y0i = y0 % gy;
  final x1i = (x0 + 1) % gx, y1i = (y0 + 1) % gy;
  double s(double t) => t * t * (3 - 2 * t);
  final a = g[y0i * gx + x0i], b = g[y0i * gx + x1i];
  final c = g[y1i * gx + x0i], d = g[y1i * gx + x1i];
  final top = a + (b - a) * s(fx);
  final bot = c + (d - c) * s(fx);
  return top + (bot - top) * s(fy);
}

/// Warm off-white paper with faint fiber flecks.
Future<ui.Image> _paperTile() {
  const size = 256;
  const baseR = 0xF6 / 255, baseG = 0xF1 / 255, baseE = 0xE7 / 255;
  final rnd = math.Random(11);
  final px = Uint8List(size * size * 4);
  for (var i = 0; i < size * size; i++) {
    final n = rnd.nextDouble();
    final v = 0.92 + 0.08 * n; // subtle brightness variation = fiber
    _setPixel(px, i * 4, baseR * v, baseG * v, baseE * v);
  }
  return _imageFromPixels(size, px);
}

/// Woven linen: perpendicular threads in a plain over/under weave.
Future<ui.Image> _canvasTile() {
  const size = 24; // one weave repeat, buffer pixels
  const thread = size / 2; // 12px: warp+weft pair
  const half = thread / 2; // 6px individual thread
  const baseR = 0xE9 / 255, baseG = 0xE0 / 255, baseB = 0xCB / 255;
  final rnd = math.Random(3);
  final px = Uint8List(size * size * 4);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      // raised-cosine crown across each thread (bright center, dark groove)
      final warpCrown = 0.5 - 0.5 * math.cos(2 * math.pi * x / half);
      final weftCrown = 0.5 - 0.5 * math.cos(2 * math.pi * y / half);
      // plain-weave over/under checker per thread cell
      final over = ((x ~/ half) + (y ~/ half)) % 2 == 0;
      final crown = over ? warpCrown : weftCrown;
      final shade = 0.72 + 0.28 * crown + (rnd.nextDouble() - 0.5) * 0.04;
      final i = (y * size + x) * 4;
      _setPixel(px, i, baseR * shade, baseG * shade, baseB * shade);
    }
  }
  return _imageFromPixels(size, px);
}

/// Brushed aluminium: cool grey with fine horizontal sheen streaks.
Future<ui.Image> _metalTile() {
  const size = 128;
  const baseR = 0.72, baseG = 0.74, baseB = 0.78;
  final streak = _noiseGrid(64, 6, 21); // fine in x, elongated in y
  final rnd = math.Random(22);
  final px = Uint8List(size * size * 4);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final s = _noise2(streak, 64, 6, x / size * 64, y / size * 6);
      final sparkle = (rnd.nextDouble() - 0.5) * 0.05;
      final shade = 0.88 + 0.12 * s + sparkle;
      final i = (y * size + x) * 4;
      _setPixel(px, i, baseR * shade, baseG * shade, baseB * shade);
    }
  }
  return _imageFromPixels(size, px);
}

/// Slate: mottled cool grey, broad blotches over a fine mineral grain.
Future<ui.Image> _stoneTile() {
  const size = 256;
  const baseR = 0.55, baseG = 0.54, baseB = 0.52;
  final big = _noiseGrid(5, 5, 31);
  final mid = _noiseGrid(16, 16, 32);
  final rnd = math.Random(33);
  final px = Uint8List(size * size * 4);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final b = _noise2(big, 5, 5, x / size * 5, y / size * 5);
      final m = _noise2(mid, 16, 16, x / size * 16, y / size * 16);
      final grain = (rnd.nextDouble() - 0.5) * 0.06;
      final shade = 0.72 + 0.34 * b + 0.12 * (m - 0.5) + grain;
      final i = (y * size + x) * 4;
      _setPixel(px, i, baseR * shade, baseG * shade, baseB * shade);
    }
  }
  return _imageFromPixels(size, px);
}

/// Wood plank: warm tan with wavy vertical grain lines.
Future<ui.Image> _woodTile() {
  const size = 256;
  const baseR = 0.80, baseG = 0.66, baseB = 0.46;
  final wave = _noiseGrid(4, 8, 41);
  final rnd = math.Random(42);
  final px = Uint8List(size * size * 4);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final disp = _noise2(wave, 4, 8, x / size * 4, y / size * 8);
      // 7 seamless grain cycles across the tile, warped by low-freq noise.
      final g = 0.5 - 0.5 * math.cos(2 * math.pi * (7 * x / size + 0.35 * disp));
      final line = math.pow(g, 2.2).toDouble(); // sharpen into thin dark lines
      final grain = (rnd.nextDouble() - 0.5) * 0.05;
      final shade = 0.9 - 0.28 * line + 0.06 * (disp - 0.5) + grain;
      final i = (y * size + x) * 4;
      _setPixel(px, i, baseR * shade, baseG * shade, baseB * shade);
    }
  }
  return _imageFromPixels(size, px);
}

/// Cold-press watercolor paper: bright white with a soft dimpled tooth.
Future<ui.Image> _watercolorTile() {
  const size = 256;
  const baseR = 0.98, baseG = 0.97, baseB = 0.93;
  final dimple = _noiseGrid(27, 27, 51);
  final fine = _noiseGrid(61, 61, 52);
  final rnd = math.Random(53);
  final px = Uint8List(size * size * 4);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final d = _noise2(dimple, 27, 27, x / size * 27, y / size * 27);
      final f = _noise2(fine, 61, 61, x / size * 61, y / size * 61);
      final grain = (rnd.nextDouble() - 0.5) * 0.03;
      // Deeper dimples than before so the cold-press tooth is visible on the
      // bare page, while the paper still reads bright.
      final shade = 0.87 + 0.12 * d + 0.06 * (f - 0.5) + grain;
      final i = (y * size + x) * 4;
      _setPixel(px, i, baseR * shade, baseG * shade, baseB * shade);
    }
  }
  return _imageFromPixels(size, px);
}

/// Chalkboard: dark slate green with a faint chalk-dust haze.
Future<ui.Image> _chalkboardTile() {
  const size = 128;
  const baseR = 0.17, baseG = 0.23, baseB = 0.20;
  final dust = _noiseGrid(10, 10, 61);
  final rnd = math.Random(62);
  final px = Uint8List(size * size * 4);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final d = _noise2(dust, 10, 10, x / size * 10, y / size * 10);
      final grain = (rnd.nextDouble() - 0.5) * 0.04;
      final shade = 0.92 + 0.16 * d + grain;
      final i = (y * size + x) * 4;
      _setPixel(px, i, baseR * shade, baseG * shade, baseB * shade);
    }
  }
  return _imageFromPixels(size, px);
}

/// Concrete: flat mid-grey with broad stains and fine aggregate speckle.
Future<ui.Image> _concreteTile() {
  const size = 256;
  const baseR = 0.67, baseG = 0.66, baseB = 0.63;
  final stain = _noiseGrid(6, 6, 71);
  final rnd = math.Random(72);
  final px = Uint8List(size * size * 4);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final s = _noise2(stain, 6, 6, x / size * 6, y / size * 6);
      final speck = rnd.nextDouble();
      var shade = 0.84 + 0.14 * s + (speck - 0.5) * 0.10;
      if (speck > 0.985) shade -= 0.25; // occasional dark aggregate fleck
      final i = (y * size + x) * 4;
      _setPixel(px, i, baseR * shade, baseG * shade, baseB * shade);
    }
  }
  return _imageFromPixels(size, px);
}
