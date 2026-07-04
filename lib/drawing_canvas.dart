import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:gal/gal.dart';

import 'stroke.dart';
import 'tools.dart';

/// Backing buffer resolution multiplier over the logical canvas. 2x sits above
/// the Movink's native pixel density, so strokes (especially crisp pen lines)
/// stay razor-sharp instead of being upscaled from a logical-res buffer.
const double kSuperSample = 2.0;

/// Handle the toolbar uses to drive the canvas (clear / save).
class DrawingController {
  _DrawingCanvasState? _state;

  bool get isReady => _state?._image != null;

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
    this.paperColor = Colors.white,
    this.debugPencilSample = false,
  });

  final DrawingController controller;
  final Tool tool;
  final Color color;
  final double baseSize;
  final Color paperColor;

  /// When true, bakes synthetic pencil sample strokes on init so the pencil
  /// look can be verified on-device without a stylus. Temporary/dev only.
  final bool debugPencilSample;

  @override
  State<DrawingCanvas> createState() => _DrawingCanvasState();
}

class _DrawingCanvasState extends State<DrawingCanvas> {
  final math.Random _rng = math.Random();

  /// Committed pixels, sized to the logical canvas (1:1 with pointer coords).
  ui.Image? _image;

  /// Strokes finished but not yet baked into [_image].
  final List<Stroke> _unbaked = [];

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
  void dispose() {
    if (widget.controller._state == this) widget.controller._state = null;
    _image?.dispose();
    super.dispose();
  }

  Future<void> _ensureBuffer(Size logical) async {
    if (_image != null) return;
    _logicalSize = logical;
    final img = await _blankImage();
    if (!mounted) {
      img.dispose();
      return;
    }
    setState(() => _image = img);
    if (widget.debugPencilSample) await _bakeDebugSample();
  }

  Future<void> _bakeDebugSample() async {
    if (_image == null) return;
    final img = await _composite(_image!, _buildPencilSamples());
    if (!mounted) {
      img.dispose();
      return;
    }
    final old = _image;
    setState(() => _image = img);
    old?.dispose();
  }

  List<Stroke> _buildPencilSamples() {
    // Alignment + crispness test: a pen border hugging the canvas edges and an
    // X to the corners. If the supersample bake scale is right, these span the
    // full canvas; wrong scale would shrink them into a corner. Thin pen lines
    // also reveal edge crispness.
    final w = _logicalSize.width, h = _logicalSize.height;
    const m = 14.0;
    StrokePoint pt(double x, double y) => StrokePoint(Offset(x, y), 3.0);
    Stroke pen(int seed) => Stroke(Tool.pen, Colors.black, seed: seed);

    final border = pen(1)
      ..add(pt(m, m))
      ..add(pt(w - m, m))
      ..add(pt(w - m, h - m))
      ..add(pt(m, h - m))
      ..add(pt(m, m));
    final d1 = pen(2)
      ..add(pt(m, m))
      ..add(pt(w - m, h - m));
    final d2 = pen(3)
      ..add(pt(w - m, m))
      ..add(pt(m, h - m));
    return [border, d1, d2];
  }

  int get _bufW => (_logicalSize.width * kSuperSample).round();
  int get _bufH => (_logicalSize.height * kSuperSample).round();

  Future<ui.Image> _blankImage() {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawRect(
      Rect.fromLTWH(0, 0, _bufW.toDouble(), _bufH.toDouble()),
      Paint()..color = widget.paperColor,
    );
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
    if (_baking || _image == null || _unbaked.isEmpty) return;
    _baking = true;
    try {
      final batch = List<Stroke>.from(_unbaked);
      final newImage = await _composite(_image!, batch);
      if (!mounted) {
        newImage.dispose();
        return;
      }
      final old = _image;
      setState(() {
        _image = newImage;
        _unbaked.removeRange(0, batch.length);
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
      paintStroke(canvas, s);
    }
    return recorder.endRecording().toImage(_bufW, _bufH);
  }

  Future<void> _clear() async {
    final blank = await _blankImage();
    if (!mounted) {
      blank.dispose();
      return;
    }
    final old = _image;
    setState(() {
      _unbaked.clear();
      _active = null;
      _image = blank;
    });
    old?.dispose();
  }

  Future<String> _savePng() async {
    // Flush anything still pending so the export includes every stroke.
    while (_unbaked.isNotEmpty || _baking) {
      await _bake();
      if (_baking) await Future<void>.delayed(const Duration(milliseconds: 8));
    }
    final image = _image;
    if (image == null) throw StateError('Nothing to save yet');

    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
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
        if (_image == null) {
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
              image: _image!,
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
    required this.image,
    required this.unbaked,
    required this.active,
  });

  final ui.Image image;
  final List<Stroke> unbaked;
  final Stroke? active;

  @override
  void paint(Canvas canvas, Size size) {
    // Scale the supersampled buffer down to fill the logical surface.
    final src =
        Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble());
    final dst = Rect.fromLTWH(0, 0, size.width, size.height);
    canvas.drawImageRect(
        image, src, dst, Paint()..filterQuality = FilterQuality.high);

    // Pending + active strokes ride on top in the same logical coordinates.
    for (final s in unbaked) {
      paintStroke(canvas, s);
    }
    if (active != null) paintStroke(canvas, active!);
  }

  @override
  bool shouldRepaint(_CanvasPainter old) =>
      old.image != image ||
      old.active != active ||
      old.unbaked.length != unbaked.length ||
      (active != null && active!.points.length != (old.active?.points.length ?? -1));
}
