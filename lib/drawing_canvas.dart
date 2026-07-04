import 'dart:async';
import 'dart:math' as math;
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
    this.paperColor = Colors.white,
    this.debugPencilSample = false,
  });

  final DrawingController controller;
  final Tool tool;
  final Color color;
  final double baseSize;
  final SurfaceKind surface;
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
    // Switching surfaces re-renders the base but keeps the paint.
    if (widget.surface != old.surface && _paint != null) {
      unawaited(_regenerateSurface());
    }
  }

  Future<void> _regenerateSurface() async {
    final surfaceImg = await buildSurfaceVisual(widget.surface, _bufW, _bufH);
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
    final samples = _buildToolSamples();
    final img = await _composite(_paint!, samples);
    if (!mounted) {
      img.dispose();
      return;
    }
    final old = _paint;
    setState(() {
      _paint = img;
      _committed.addAll(samples);
    });
    old?.dispose();
  }

  List<Stroke> _buildToolSamples() {
    // Tooth test: one broad band per deposit tool so each tool's reaction to the
    // surface is visible, plus a marker patch crossed by an eraser to show the
    // eraser leaving residue down in the tooth valleys.
    final w = _logicalSize.width, h = _logicalSize.height;
    final strokes = <Stroke>[];
    const tools = [Tool.pencil, Tool.pen, Tool.brush, Tool.marker, Tool.spray];
    Stroke band(Tool tool, double y, double width, {int seed = 1}) {
      final s = Stroke(tool, Colors.black, seed: seed);
      for (var x = 240.0; x <= w - 160; x += 5) {
        s.add(StrokePoint(Offset(x, y), width, 0.9));
      }
      return s;
    }

    for (var t = 0; t < tools.length; t++) {
      strokes.add(band(tools[t], h * (0.14 + t * 0.13), 40, seed: t + 1));
    }
    // Eraser test: a dense marker patch, then an eraser crossing it vertically.
    final eraseY = h * (0.14 + tools.length * 0.13);
    strokes.add(band(Tool.marker, eraseY, 64, seed: 20));
    final erase = Stroke(Tool.eraser, Colors.black, seed: 21);
    for (var y = eraseY - 48; y <= eraseY + 48; y += 5) {
      erase.add(StrokePoint(Offset(w / 2, y), 40));
    }
    strokes.add(erase);
    return strokes;
  }

  int get _bufW => (_logicalSize.width * kSuperSample).round();
  int get _bufH => (_logicalSize.height * kSuperSample).round();

  /// The base surface texture (procedural) for the selected surface.
  Future<ui.Image> _blankSurface() =>
      buildSurfaceVisual(widget.surface, _bufW, _bufH);

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

  Future<ui.Image> _composite(ui.Image base, List<Stroke> strokes) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    // Draw the existing buffer 1:1, then scale into logical space so strokes
    // (captured in logical coords) bake at supersampled resolution. The painter
    // scales the buffer back down to the logical surface, so preview and bake
    // land at the exact same on-screen position.
    canvas.drawImage(base, Offset.zero, Paint());
    canvas.scale(kSuperSample);
    for (final s in strokes) {
      paintStroke(canvas, s, surface: widget.surface);
    }
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
    canvas.drawImageRect(
        paintLayer,
        Rect.fromLTWH(
            0, 0, paintLayer.width.toDouble(), paintLayer.height.toDouble()),
        dst,
        hq);
    for (final s in unbaked) {
      paintStroke(canvas, s, surface: surfaceKind);
    }
    if (active != null) paintStroke(canvas, active!, surface: surfaceKind);
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
