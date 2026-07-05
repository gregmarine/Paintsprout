import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:gal/gal.dart';

import 'stroke.dart';
import 'surface.dart';
import 'tools.dart';

/// Backing buffer resolution multiplier over the logical canvas. 2x sits above
/// the Movink's native pixel density, so strokes (especially crisp pen lines)
/// stay razor-sharp instead of being upscaled from a logical-res buffer.
const double kSuperSample = 2.0;

/// Handle the toolbar uses to drive the canvas (clear / save).
class DrawingController {
  _DrawingCanvasState? _state;

  bool get isReady => _state?._paint != null;

  Future<void> clear() async => _state?._clear();

  /// Bakes any pending strokes and saves the canvas as a PNG in the device
  /// Gallery (Paintsprout album). Returns a short human-readable location.
  Future<String> savePng() async {
    final state = _state;
    if (state == null) throw StateError('Canvas not attached');
    return state._savePng();
  }
}

/// Full-screen raster drawing surface. Strokes are baked into a [ui.Image]
/// backing buffer kept 1:1 with the logical canvas (the same coordinate space
/// as pointer positions), so the live preview and the baked result use an
/// identical transform. The current stroke is previewed on top until
/// pointer-up. Only stylus input draws; touch/palm is ignored.
class DrawingCanvas extends StatefulWidget {
  const DrawingCanvas({
    super.key,
    required this.controller,
    required this.tool,
    required this.color,
    required this.baseSize,
    this.surface = SurfaceKind.paper,
    this.plainColor = Colors.white,
    this.paperColor = Colors.white,
    this.debugPencilSample = false,
  });

  final DrawingController controller;
  final Tool tool;
  final Color color;
  final double baseSize;
  final SurfaceKind surface;

  /// Background color for the Plain surface (ignored by textured surfaces).
  final Color plainColor;
  final Color paperColor;

  /// When true, bakes synthetic pencil sample strokes on init so the pencil
  /// look can be verified on-device without a stylus. Temporary/dev only.
  final bool debugPencilSample;

  @override
  State<DrawingCanvas> createState() => _DrawingCanvasState();
}

class _DrawingCanvasState extends State<DrawingCanvas> {
  final math.Random _rng = math.Random();

  /// The base layer you paint on (paper/canvas/etc.), buffer resolution.
  ui.Image? _surface;

  /// Committed paint, transparent where unpainted so the surface shows through.
  ui.Image? _paint;

  /// Strokes finished but not yet baked into [_paint].
  final List<Stroke> _unbaked = [];

  /// Every stroke already baked into [_paint], in paint order. Kept as vectors
  /// so the whole drawing can be re-rendered against a new surface's tooth when
  /// the surface changes (existing marks take on the new substrate).
  final List<Stroke> _committed = [];

  /// Stroke currently under the pointer.
  Stroke? _active;

  /// Pointer id of the stylus that owns [_active]; guards against palm/finger
  /// events corrupting an in-progress stroke.
  int? _activePointer;

  bool _baking = false;

  Size _logicalSize = Size.zero;

  @override
  void initState() {
    super.initState();
    widget.controller._state = this;
  }

  @override
  void didUpdateWidget(DrawingCanvas old) {
    super.didUpdateWidget(old);
    // Switching surfaces — or recoloring the Plain background — re-renders the
    // base but keeps the paint.
    final surfaceChanged = widget.surface != old.surface;
    final plainRecolored = widget.surface == SurfaceKind.plain &&
        widget.plainColor != old.plainColor;
    if ((surfaceChanged || plainRecolored) && _paint != null) {
      unawaited(_regenerateSurface());
    }
  }

  Future<void> _regenerateSurface() async {
    final surfaceImg = await buildSurfaceVisual(widget.surface, _bufW, _bufH,
        plainColor: widget.plainColor);
    // Re-render every committed stroke against the new surface so existing marks
    // pick up the new tooth (a pencil sketch becomes pencil-on-canvas, etc.).
    final blank = await _blankPaint();
    final paintImg = await _composite(blank, _committed);
    blank.dispose();
    if (!mounted) {
      surfaceImg.dispose();
      paintImg.dispose();
      return;
    }
    final oldS = _surface;
    final oldP = _paint;
    setState(() {
      _surface = surfaceImg;
      _paint = paintImg;
    });
    oldS?.dispose();
    oldP?.dispose();
  }

  @override
  void dispose() {
    if (widget.controller._state == this) widget.controller._state = null;
    _surface?.dispose();
    _paint?.dispose();
    super.dispose();
  }

  Future<void> _ensureBuffer(Size logical) async {
    if (_paint != null) return;
    _logicalSize = logical;
    final surface = await _blankSurface();
    final paint = await _blankPaint();
    if (!mounted) {
      surface.dispose();
      paint.dispose();
      return;
    }
    setState(() {
      _surface = surface;
      _paint = paint;
    });
    if (widget.debugPencilSample) await _bakeDebugSample();
  }

  Future<void> _bakeDebugSample() async {
    if (_paint == null) return;
    // Bake colored bands, then a watercolor sweep across them so the wet
    // interaction (dilute / push / mix) is visible without a stylus.
    var img = await _composite(_paint!, _buildToolSamples());
    final w = _logicalSize.width, h = _logicalSize.height;
    final sweep = Stroke(Tool.watercolor, Colors.yellow, seed: 50);
    for (var y = h * 0.12; y <= h * 0.9; y += 6) {
      sweep.add(StrokePoint(Offset(w * 0.5, y), 60));
    }
    final next = await _compositeWatercolor(img, sweep);
    img.dispose();
    img = next;
    if (!mounted) {
      img.dispose();
      return;
    }
    final old = _paint;
    setState(() => _paint = img);
    old?.dispose();
  }

  List<Stroke> _buildToolSamples() {
    // Interaction test: three solid colored marker bands. A watercolor sweep is
    // left ACTIVE over them (see _debugActiveSweep) so the live wet interaction
    // renders through the painter.
    final w = _logicalSize.width, h = _logicalSize.height;
    final strokes = <Stroke>[];
    const bands = [Colors.red, Colors.blue, Colors.black];
    for (var b = 0; b < bands.length; b++) {
      final y = h * (0.22 + b * 0.22);
      final s = Stroke(Tool.marker, bands[b], seed: b + 1);
      for (var x = 220.0; x <= w - 180; x += 6) {
        s.add(StrokePoint(Offset(x, y), 74));
      }
      strokes.add(s);
    }
    return strokes;
  }

  int get _bufW => (_logicalSize.width * kSuperSample).round();
  int get _bufH => (_logicalSize.height * kSuperSample).round();

  /// The base surface texture (procedural) for the selected surface.
  Future<ui.Image> _blankSurface() =>
      buildSurfaceVisual(widget.surface, _bufW, _bufH,
          plainColor: widget.plainColor);

  /// A fully transparent paint layer (an empty recording rasterizes to clear).
  Future<ui.Image> _blankPaint() {
    final recorder = ui.PictureRecorder();
    Canvas(recorder);
    return recorder.endRecording().toImage(_bufW, _bufH);
  }

  // --- Pointer handling ---------------------------------------------------

  double _pressureNorm(PointerEvent e) {
    final range = e.pressureMax - e.pressureMin;
    if (range <= 0) return 1.0; // device without pressure reporting
    return ((e.pressure - e.pressureMin) / range).clamp(0.0, 1.0);
  }

  StrokePoint _sample(PointerEvent e) {
    final pressure = _pressureNorm(e);
    final width = resolveWidth(
      tool: widget.tool,
      baseSize: widget.baseSize,
      pressureNorm: pressure,
      tiltRadians: e.tilt,
    );
    final density =
        resolveDensity(tool: widget.tool, pressureNorm: pressure);
    return StrokePoint(e.localPosition, width, density);
  }

  /// Only a pen draws; fingers and palm are ignored.
  static bool _isStylus(ui.PointerDeviceKind kind) =>
      kind == ui.PointerDeviceKind.stylus ||
      kind == ui.PointerDeviceKind.invertedStylus;

  void _onDown(PointerDownEvent e) {
    if (!_isStylus(e.kind)) return;
    if (_active != null) return; // already tracking a stroke
    final color =
        widget.tool == Tool.eraser ? widget.paperColor : widget.color;
    setState(() {
      _activePointer = e.pointer;
      _active = Stroke(widget.tool, color, seed: _rng.nextInt(1 << 30))
        ..add(_sample(e));
    });
  }

  void _onMove(PointerMoveEvent e) {
    if (_active == null || e.pointer != _activePointer) return;
    setState(() => _active!.add(_sample(e)));
  }

  void _onUp(PointerEvent e) {
    if (_active == null || e.pointer != _activePointer) return;
    setState(() {
      _unbaked.add(_active!);
      _active = null;
      _activePointer = null;
    });
    unawaited(_bake());
  }

  // --- Baking -------------------------------------------------------------

  Future<void> _bake() async {
    if (_baking || _paint == null || _unbaked.isEmpty) return;
    _baking = true;
    try {
      final batch = List<Stroke>.from(_unbaked);
      final newPaint = await _composite(_paint!, batch);
      if (!mounted) {
        newPaint.dispose();
        return;
      }
      final old = _paint;
      setState(() {
        _paint = newPaint;
        _unbaked.removeRange(0, batch.length);
        _committed.addAll(batch);
      });
      old?.dispose();
    } finally {
      _baking = false;
    }
    if (_unbaked.isNotEmpty) unawaited(_bake());
  }

  /// Folds [strokes] onto [base] in order, returning a new image (never disposes
  /// [base]). Ordinary tools just deposit (batched); the watercolor brush reads
  /// the paint underneath and re-wets it, so it must run against an already-
  /// rasterized image and is processed one stroke at a time.
  Future<ui.Image> _composite(ui.Image base, List<Stroke> strokes) async {
    ui.Image current = base;
    var owns = false; // whether we own `current` and must dispose it
    var i = 0;
    while (i < strokes.length) {
      final ui.Image next;
      if (strokes[i].tool == Tool.watercolor) {
        next = await _compositeWatercolor(current, strokes[i]);
        i++;
      } else {
        final run = <Stroke>[];
        while (i < strokes.length && strokes[i].tool != Tool.watercolor) {
          run.add(strokes[i]);
          i++;
        }
        next = await _additiveComposite(current, run);
      }
      if (owns) current.dispose();
      current = next;
      owns = true;
    }
    // No strokes: hand back a fresh copy the caller can own/dispose.
    if (!owns) return _additiveComposite(base, const []);
    return current;
  }

  /// The plain additive bake: draw the buffer 1:1, then scale into logical space
  /// so strokes (captured in logical coords) bake at supersampled resolution.
  Future<ui.Image> _additiveComposite(ui.Image base, List<Stroke> strokes) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawImage(base, Offset.zero, Paint());
    canvas.scale(kSuperSample);
    for (final s in strokes) {
      paintStroke(canvas, s, surface: widget.surface);
    }
    return recorder.endRecording().toImage(_bufW, _bufH);
  }

  /// Watercolor's wet interaction (bake): within the brush region, the existing
  /// paint is diluted (faded toward the surface) and softened (blurred), then
  /// the pigment is laid on top so its color bleeds into what was there.
  /// Operates in buffer coordinates. Shares [applyWetInteraction] with the live
  /// preview so the on-screen effect and the committed result match.
  Future<ui.Image> _compositeWatercolor(ui.Image base, Stroke stroke,
      {WetConfig config = WetConfig.production}) {
    const s = kSuperSample;
    final scale = Float64List.fromList(
        [s, 0, 0, 0, 0, s, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
    final region = strokeRibbon(stroke).transform(scale);
    final avgW = _avgWidth(stroke) * s;

    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawImage(base, Offset.zero, Paint()); // sharp existing paint
    applyWetInteraction(
      canvas: canvas,
      region: region,
      avgWidth: avgW,
      config: config,
      drawPaint: (c) => c.drawImage(base, Offset.zero, Paint()),
    );
    // Lay the wash on top; its translucent pigment bleeds into the wet area.
    // Multiply blends it subtractively so overlaps mix like transparent paint.
    final washBounds = region.getBounds().inflate(avgW);
    if (config.multiply) {
      canvas.saveLayer(washBounds, Paint()..blendMode = BlendMode.multiply);
    }
    canvas.save();
    canvas.scale(s);
    paintStroke(canvas, stroke, surface: widget.surface);
    canvas.restore();
    if (config.multiply) canvas.restore();
    return recorder.endRecording().toImage(_bufW, _bufH);
  }

  /// Clears painted strokes but keeps the chosen surface.
  Future<void> _clear() async {
    final blank = await _blankPaint();
    if (!mounted) {
      blank.dispose();
      return;
    }
    final old = _paint;
    setState(() {
      _unbaked.clear();
      _committed.clear();
      _active = null;
      _paint = blank;
    });
    old?.dispose();
  }

  /// Flattens surface + paint into a single image for export.
  Future<ui.Image> _flatten() {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    if (_surface != null) canvas.drawImage(_surface!, Offset.zero, Paint());
    if (_paint != null) canvas.drawImage(_paint!, Offset.zero, Paint());
    return recorder.endRecording().toImage(_bufW, _bufH);
  }

  Future<String> _savePng() async {
    // Flush anything still pending so the export includes every stroke.
    while (_unbaked.isNotEmpty || _baking) {
      await _bake();
      if (_baking) await Future<void>.delayed(const Duration(milliseconds: 8));
    }
    if (_paint == null) throw StateError('Nothing to save yet');

    final image = await _flatten();
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    if (bytes == null) throw StateError('PNG encode failed');

    final ts = DateTime.now()
        .toIso8601String()
        .replaceAll(':', '-')
        .split('.')
        .first;

    Future<void> put() => Gal.putImageBytes(
          bytes.buffer.asUint8List(),
          album: 'Paintsprout',
          name: 'paintsprout_$ts',
        );

    try {
      await put();
    } on GalException catch (e) {
      // First-run permission on older Android versions.
      if (e.type == GalExceptionType.accessDenied) {
        await Gal.requestAccess(toAlbum: true);
        await put();
      } else {
        rethrow;
      }
    }
    return 'Gallery ▸ Paintsprout album';
  }

  // --- Build --------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final size = Size(constraints.maxWidth, constraints.maxHeight);
        if (_paint == null || _surface == null) {
          WidgetsBinding.instance
              .addPostFrameCallback((_) => _ensureBuffer(size));
          return ColoredBox(
            color: widget.paperColor,
            child: const Center(child: CircularProgressIndicator()),
          );
        }
        return Listener(
          onPointerDown: _onDown,
          onPointerMove: _onMove,
          onPointerUp: _onUp,
          onPointerCancel: _onUp,
          behavior: HitTestBehavior.opaque,
          child: CustomPaint(
            size: size,
            painter: _CanvasPainter(
              surface: _surface!,
              surfaceKind: widget.surface,
              paintLayer: _paint!,
              unbaked: _unbaked,
              active: _active,
            ),
          ),
        );
      },
    );
  }
}

// --- Watercolor wet interaction (shared by bake + live preview) -------------

/// Tunables for watercolor's wet interaction. The multipliers are relative to
/// the brush's average width so the feel scales with brush size.
class WetConfig {
  const WetConfig({
    required this.dilute,
    required this.soften,
    required this.spread,
    required this.clearFeather,
    this.multiply = false,
  });

  /// Fraction of the softened underlying paint kept (lower = lighter centre).
  final double dilute;

  /// Blur of the displaced pigment, * avgWidth.
  final double soften;

  /// Outward push: how far past the stroke edge pigment blooms, * avgWidth.
  final double spread;

  /// Edge softness of the dilute-clear (kept tight so only the stroke fades).
  final double clearFeather;

  /// Deposit the wash with subtractive (multiply) blending so overlapping
  /// pigments mix like transparent paint (yellow over blue -> green) instead of
  /// muddy alpha-averaged tones.
  final bool multiply;

  static const production = WetConfig(
      dilute: 0.5, soften: 0.14, spread: 0.30, clearFeather: 0.10, multiply: true);
}

double _avgWidth(Stroke stroke) {
  var sum = 0.0;
  for (final p in stroke.points) {
    sum += p.width;
  }
  return sum / stroke.points.length;
}

/// Draws [region] as a soft-edged (blurred) opaque white shape — a feathered
/// mask so the wet effect fades at the brush boundary.
void _softRegion(Canvas canvas, Path region, Rect bounds, double feather) {
  canvas.saveLayer(
      bounds,
      Paint()
        ..imageFilter = ui.ImageFilter.blur(
            sigmaX: feather, sigmaY: feather, tileMode: TileMode.decal));
  canvas.drawPath(
      region, Paint()..color = const Color(0xFFFFFFFF)..isAntiAlias = true);
  canvas.restore();
}

/// Within [region], dilutes and softens the paint that [drawPaint] renders:
/// clears the region, then lays back a blurred, faded copy masked to it. The
/// caller must have already drawn the sharp paint, and lays the wash on after.
/// Shared by the bake (buffer coords) and the live preview (logical coords) so
/// the on-screen effect matches the committed result.
void applyWetInteraction({
  required Canvas canvas,
  required Path region,
  required double avgWidth,
  required void Function(Canvas) drawPaint,
  WetConfig config = WetConfig.production,
}) {
  final soften = math.max(2.0, avgWidth * config.soften);
  final clearFeather = math.max(2.0, avgWidth * config.clearFeather);
  final spread = math.max(4.0, avgWidth * config.spread);
  final bounds =
      region.getBounds().inflate(soften * 3 + spread * 3 + 4);
  // 1. Dilute: clear the paint inside the stroke (tight edge) so the centre
  //    fades toward the surface where the water floods it.
  canvas.saveLayer(bounds, Paint()..blendMode = BlendMode.dstOut);
  _softRegion(canvas, region, bounds, clearFeather);
  canvas.restore();
  // 2. Push: a blurred, faded copy of the paint, masked to a WIDER region so the
  //    displaced pigment blooms past the stroke edges instead of staying put.
  canvas.saveLayer(bounds,
      Paint()..color = const Color(0xFF000000).withValues(alpha: config.dilute));
  canvas.saveLayer(
      bounds,
      Paint()
        ..imageFilter = ui.ImageFilter.blur(
            sigmaX: soften, sigmaY: soften, tileMode: TileMode.decal));
  drawPaint(canvas);
  canvas.restore();
  canvas.saveLayer(bounds, Paint()..blendMode = BlendMode.dstIn);
  _softRegion(canvas, region, bounds, spread);
  canvas.restore();
  canvas.restore();
}

class _CanvasPainter extends CustomPainter {
  _CanvasPainter({
    required this.surface,
    required this.surfaceKind,
    required this.paintLayer,
    required this.unbaked,
    required this.active,
  });

  final ui.Image surface;
  final SurfaceKind surfaceKind;
  final ui.Image paintLayer;
  final List<Stroke> unbaked;
  final Stroke? active;

  @override
  void paint(Canvas canvas, Size size) {
    final dst = Rect.fromLTWH(0, 0, size.width, size.height);
    final hq = Paint()..filterQuality = FilterQuality.high;

    // Base surface, scaled down from the supersampled buffer.
    canvas.drawImageRect(
        surface,
        Rect.fromLTWH(0, 0, surface.width.toDouble(), surface.height.toDouble()),
        dst,
        hq);

    // Paint layer + pending edits, composited in an isolated layer so an
    // eraser stroke (BlendMode.clear) punches through to reveal the surface
    // rather than the widget behind the canvas.
    canvas.saveLayer(dst, Paint());
    final paintSrc = Rect.fromLTWH(
        0, 0, paintLayer.width.toDouble(), paintLayer.height.toDouble());
    canvas.drawImageRect(paintLayer, paintSrc, dst, hq);
    // A watercolor stroke re-wets the paint beneath it each frame (same recipe
    // the bake uses, in logical coords) so what you see under the brush matches
    // what commits on lift. Applied to both the active stroke AND still-unbaked
    // strokes, so the wash-out doesn't blink away in the gap before the bake
    // lands.
    void render(Stroke s) {
      if (s.tool == Tool.watercolor) {
        applyWetInteraction(
          canvas: canvas,
          region: strokeRibbon(s),
          avgWidth: _avgWidth(s),
          drawPaint: (c) => c.drawImageRect(paintLayer, paintSrc, dst, hq),
        );
        if (WetConfig.production.multiply) {
          final b = strokeRibbon(s).getBounds().inflate(_avgWidth(s));
          canvas.saveLayer(b, Paint()..blendMode = BlendMode.multiply);
          paintStroke(canvas, s, surface: surfaceKind);
          canvas.restore();
          return;
        }
      }
      paintStroke(canvas, s, surface: surfaceKind);
    }

    for (final s in unbaked) {
      render(s);
    }
    if (active != null) render(active!);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_CanvasPainter old) =>
      old.surface != surface ||
      old.surfaceKind != surfaceKind ||
      old.paintLayer != paintLayer ||
      old.active != active ||
      old.unbaked.length != unbaked.length ||
      (active != null && active!.points.length != (old.active?.points.length ?? -1));
}
