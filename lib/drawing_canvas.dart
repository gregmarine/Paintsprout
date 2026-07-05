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

/// Spectral Kubelka-Munk pigment-mixing shader (loaded once at startup). Left
/// null if it fails to load, in which case the watercolor wash falls back to a
/// plain over-composite (no true pigment mixing).
ui.FragmentProgram? _pigmentProgram;

/// Loads the pigment-mixing fragment shader. Call once before the first frame
/// (see main()). Failures are swallowed so the app still runs without mixing.
Future<void> initPigmentShader() async {
  try {
    _pigmentProgram ??=
        await ui.FragmentProgram.fromAsset('shaders/pigment_mix.frag');
  } catch (e, st) {
    debugPrint('Pigment shader failed to load: $e\n$st');
    _pigmentProgram = null;
  }
}

/// Handle the toolbar uses to drive the canvas (clear / save / history).
class DrawingController {
  _DrawingCanvasState? _state;

  /// Set by the screen while it applies an undo/redo snapshot, so the canvas's
  /// prop-driven surface regeneration doesn't fire a second, redundant rebuild
  /// on top of the atomic [restore] the snapshot already performs.
  bool _suppressRegen = false;

  bool get isReady => _state?._paint != null;

  Future<void> clear() async => _state?._clear();

  /// A snapshot of the committed strokes, for the undo/redo history. Strokes are
  /// immutable once committed, so the list shares their instances (cheap).
  List<Stroke> strokes() => _state?._strokesForHistory() ?? const [];

  /// Restores a document snapshot atomically: rebuilds the surface for
  /// [surface]/[plainColor] and re-composites [strokes] in one pass.
  Future<void> restore(
          SurfaceKind surface, Color plainColor, List<Stroke> strokes) async =>
      _state?._restoreDocument(surface, plainColor, strokes);

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
    this.onCommitted,
    this.onUndoGesture,
    this.onRedoGesture,
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

  /// Called after a stroke is baked into the paint layer, so the screen can
  /// record an undo step.
  final VoidCallback? onCommitted;

  /// Touch gestures for history: a two-finger tap fires [onUndoGesture], a
  /// three-finger tap fires [onRedoGesture] (drawing is stylus-only, so touch
  /// is free for these).
  final VoidCallback? onUndoGesture;
  final VoidCallback? onRedoGesture;

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

  /// While a watercolor stroke is in progress, the live spectral composite of
  /// the committed paint + pending strokes, produced by the SAME recipe as the
  /// bake so the preview matches the committed result exactly (no pop on lift).
  /// Null when no watercolor stroke is pending.
  ui.Image? _wetPreview;
  bool _previewing = false; // a preview composite is in flight
  bool _previewDirty = false; // the stroke changed while a preview was running

  // --- Touch gesture tracking --------------------------------------------
  // Two-finger double-tap = undo, three-finger double-tap = redo.
  final Map<int, Offset> _touchStart = {}; // active touch pointers -> down pos
  int _touchMaxCount = 0; // most fingers down at once this session
  DateTime? _touchSessionStart;
  bool _touchMoved = false; // any finger dragged past the tap slop
  static const double _touchTapSlop = 18.0;
  // Pending first tap of a potential double-tap.
  int _pendingTapCount = 0;
  DateTime? _pendingTapTime;
  static const Duration _doubleTapWindow = Duration(milliseconds: 450);

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
    if (surfaceChanged || plainRecolored) {
      // An undo/redo already rebuilt the surface via restore(); consume the
      // suppress flag so we don't rebuild it a second time here.
      if (widget.controller._suppressRegen) {
        widget.controller._suppressRegen = false;
        return;
      }
      if (_paint != null) unawaited(_regenerateSurface());
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

  /// Snapshot of the committed strokes for the undo/redo history.
  List<Stroke> _strokesForHistory() => List.of(_committed);

  /// Restores a document snapshot: rebuilds the surface for [surface]/
  /// [plainColor] and re-composites [strokes], in a single pass. Used by
  /// undo/redo so surface and strokes come back together without a double
  /// rebuild (see the _suppressRegen guard in didUpdateWidget).
  Future<void> _restoreDocument(
      SurfaceKind surface, Color plainColor, List<Stroke> strokes) async {
    if (_paint == null) return;
    // If the restored surface differs from the current props, the screen's
    // matching setState will trigger didUpdateWidget; flag it so that pass
    // skips its own rebuild (we do the one rebuild here).
    final willRegen = surface != widget.surface ||
        (surface == SurfaceKind.plain && plainColor != widget.plainColor);
    if (willRegen) widget.controller._suppressRegen = true;
    final surfaceImg =
        await buildSurfaceVisual(surface, _bufW, _bufH, plainColor: plainColor);
    _committed
      ..clear()
      ..addAll(strokes);
    _unbaked.clear();
    _active = null;
    _activePointer = null;
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
    final oldPrev = _wetPreview;
    setState(() {
      _surface = surfaceImg;
      _paint = paintImg;
      _wetPreview = null;
    });
    oldS?.dispose();
    oldP?.dispose();
    oldPrev?.dispose();
  }

  @override
  void dispose() {
    if (widget.controller._state == this) widget.controller._state = null;
    _surface?.dispose();
    _paint?.dispose();
    _wetPreview?.dispose();
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
    // interaction (dilute / push / spectral mix) is visible without a stylus.
    final w = _logicalSize.width, h = _logicalSize.height;
    var img = await _composite(_paint!, _buildToolSamples());
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
    if (!_isStylus(e.kind)) {
      _onTouchDown(e);
      return;
    }
    if (_active != null) return; // already tracking a stroke
    final color =
        widget.tool == Tool.eraser ? widget.paperColor : widget.color;
    setState(() {
      _activePointer = e.pointer;
      _active = Stroke(widget.tool, color, seed: _rng.nextInt(1 << 30))
        ..add(_sample(e));
    });
    if (widget.tool == Tool.watercolor) unawaited(_updateWetPreview());
  }

  void _onMove(PointerMoveEvent e) {
    if (!_isStylus(e.kind)) {
      _onTouchMove(e);
      return;
    }
    if (_active == null || e.pointer != _activePointer) return;
    setState(() => _active!.add(_sample(e)));
    if (_active!.tool == Tool.watercolor) unawaited(_updateWetPreview());
  }

  void _onUp(PointerEvent e) {
    if (!_isStylus(e.kind)) {
      _onTouchUp(e);
      return;
    }
    if (_active == null || e.pointer != _activePointer) return;
    setState(() {
      _unbaked.add(_active!);
      _active = null;
      _activePointer = null;
    });
    unawaited(_bake());
  }

  // --- Touch gestures: 2-finger tap = undo, 3-finger tap = redo -------------

  void _onTouchDown(PointerDownEvent e) {
    if (_touchStart.isEmpty) {
      _touchSessionStart = DateTime.now();
      _touchMaxCount = 0;
      _touchMoved = false;
    }
    _touchStart[e.pointer] = e.localPosition;
    if (_touchStart.length > _touchMaxCount) _touchMaxCount = _touchStart.length;
  }

  void _onTouchMove(PointerMoveEvent e) {
    final start = _touchStart[e.pointer];
    if (start == null) return;
    if ((e.localPosition - start).distance > _touchTapSlop) _touchMoved = true;
  }

  void _onTouchUp(PointerEvent e) {
    if (_touchStart.remove(e.pointer) == null) return;
    if (_touchStart.isNotEmpty) return; // still fingers down; wait for all up
    final now = DateTime.now();
    final duration = _touchSessionStart == null
        ? Duration.zero
        : now.difference(_touchSessionStart!);
    final tapped = !_touchMoved && duration < const Duration(milliseconds: 400);
    final count = _touchMaxCount;
    _touchSessionStart = null;
    _touchMaxCount = 0;
    _touchMoved = false;
    if (!tapped || (count != 2 && count != 3)) {
      _pendingTapCount = 0;
      return;
    }
    // Second matching tap within the window completes a double-tap.
    if (_pendingTapCount == count &&
        _pendingTapTime != null &&
        now.difference(_pendingTapTime!) < _doubleTapWindow) {
      _pendingTapCount = 0;
      _pendingTapTime = null;
      if (count == 2) {
        widget.onUndoGesture?.call();
      } else {
        widget.onRedoGesture?.call();
      }
    } else {
      // First tap — remember it and wait for the second.
      _pendingTapCount = count;
      _pendingTapTime = now;
    }
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
      // Once this bake commits everything pending (nothing added mid-bake and no
      // stroke under the pointer), the live preview is redundant — the committed
      // paint now shows the identical spectral result. Drop it in the same frame
      // so there's no flash between preview and committed.
      final clearPreview =
          _active == null && _unbaked.length == batch.length;
      final oldPreview = clearPreview ? _wetPreview : null;
      final old = _paint;
      setState(() {
        _paint = newPaint;
        _unbaked.removeRange(0, batch.length);
        _committed.addAll(batch);
        if (clearPreview) _wetPreview = null;
      });
      old?.dispose();
      oldPreview?.dispose();
    } finally {
      _baking = false;
    }
    if (_unbaked.isNotEmpty) {
      unawaited(_bake());
    } else {
      // A batch fully committed — record an undo step for the drawn stroke(s).
      widget.onCommitted?.call();
    }
  }

  /// Recomputes the live watercolor preview: the committed paint with all
  /// pending strokes folded in via [_composite] (the same recipe the bake uses,
  /// so the on-screen wash matches what commits). Throttled to one composite in
  /// flight; if the stroke moves while one runs, it re-runs once on completion.
  Future<void> _updateWetPreview() async {
    if (_paint == null) return;
    if (_previewing) {
      _previewDirty = true;
      return;
    }
    _previewing = true;
    try {
      final strokes = <Stroke>[..._unbaked, ?_active];
      final img = await _composite(_paint!, strokes);
      if (!mounted) {
        img.dispose();
        return;
      }
      final old = _wetPreview;
      setState(() => _wetPreview = img);
      old?.dispose();
    } finally {
      _previewing = false;
    }
    if (_previewDirty) {
      _previewDirty = false;
      unawaited(_updateWetPreview());
    }
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
      {WetConfig config = WetConfig.production}) async {
    const s = kSuperSample;
    final scale = Float64List.fromList(
        [s, 0, 0, 0, 0, s, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
    final region = strokeRibbon(stroke).transform(scale);
    final avgW = _avgWidth(stroke) * s;

    // 1. Wet backdrop: the existing paint with the stroke area diluted and
    //    pushed outward, but WITHOUT the new pigment yet.
    final backdropRec = ui.PictureRecorder();
    final bc = Canvas(backdropRec);
    bc.drawImage(base, Offset.zero, Paint());
    applyWetInteraction(
      canvas: bc,
      region: region,
      avgWidth: avgW,
      config: config,
      drawPaint: (c) => c.drawImage(base, Offset.zero, Paint()),
    );
    final backdrop = await backdropRec.endRecording().toImage(_bufW, _bufH);

    // 2. Deposit the wash. Spectral mixing runs the pigment shader; multiply and
    //    additive deposit the wash straight onto the wet backdrop.
    if (config.mix == PigmentMix.spectral) {
      final washRec = ui.PictureRecorder();
      final wc = Canvas(washRec);
      wc.scale(s);
      paintStroke(wc, stroke, surface: widget.surface);
      final wash = await washRec.endRecording().toImage(_bufW, _bufH);
      final result =
          await _mixPigment(backdrop, wash, config.washGain, config.darkHold);
      backdrop.dispose();
      wash.dispose();
      return result;
    }

    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawImage(backdrop, Offset.zero, Paint());
    final washBounds = region.getBounds().inflate(avgW);
    if (config.mix == PigmentMix.multiply) {
      canvas.saveLayer(washBounds, Paint()..blendMode = BlendMode.multiply);
    }
    canvas.save();
    canvas.scale(s);
    paintStroke(canvas, stroke, surface: widget.surface);
    canvas.restore();
    if (config.mix == PigmentMix.multiply) canvas.restore();
    final picture = recorder.endRecording();
    final out = await picture.toImage(_bufW, _bufH);
    picture.dispose();
    backdrop.dispose();
    return out;
  }

  /// Blends [wash] onto [backdrop] through the spectral pigment-mixing shader.
  /// Falls back to a plain over-composite if the shader didn't load.
  Future<ui.Image> _mixPigment(ui.Image backdrop, ui.Image wash,
      double washGain, double darkHold) async {
    final program = _pigmentProgram;
    final rect = Rect.fromLTWH(0, 0, _bufW.toDouble(), _bufH.toDouble());
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    ui.FragmentShader? shader;
    if (program == null) {
      canvas.drawImage(backdrop, Offset.zero, Paint());
      canvas.drawImage(wash, Offset.zero, Paint());
    } else {
      shader = program.fragmentShader()
        ..setFloat(0, _bufW.toDouble())
        ..setFloat(1, _bufH.toDouble())
        ..setFloat(2, washGain)
        ..setFloat(3, darkHold)
        ..setImageSampler(0, backdrop)
        ..setImageSampler(1, wash);
      canvas.drawRect(rect, Paint()..shader = shader);
    }
    final picture = recorder.endRecording();
    final img = await picture.toImage(_bufW, _bufH);
    picture.dispose();
    shader?.dispose();
    return img;
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
              wetPreview: _wetPreview,
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

/// How overlapping watercolor pigment is combined with the paint underneath.
enum PigmentMix {
  /// Plain source-over: translucent pigment sits on top (muddy on overlap).
  additive,

  /// Subtractive multiply blend (darkens only; the old approximation).
  multiply,

  /// Spectral Kubelka-Munk pigment mixing (blue under yellow reads green).
  spectral,
}

/// Tunables for watercolor's wet interaction. The multipliers are relative to
/// the brush's average width so the feel scales with brush size.
class WetConfig {
  const WetConfig({
    required this.dilute,
    required this.soften,
    required this.spread,
    required this.clearFeather,
    this.mix = PigmentMix.spectral,
    this.washGain = 1.0,
    this.darkHold = 0.0,
  });

  /// Fraction of the softened underlying paint kept (lower = lighter centre).
  final double dilute;

  /// Blur of the displaced pigment, * avgWidth.
  final double soften;

  /// Outward push: how far past the stroke edge pigment blooms, * avgWidth.
  final double spread;

  /// Edge softness of the dilute-clear (kept tight so only the stroke fades).
  final double clearFeather;

  /// How the wash pigment combines with the paint underneath.
  final PigmentMix mix;

  /// Multiplier on the wash pigment's weight in the spectral mix. 1.0 = neutral;
  /// higher makes the wash tint underlying paint more strongly.
  final double washGain;

  /// How strongly near-neutral dark paint (black/grey) resists being tinted by
  /// a wash. 0 = no resistance (dark lifts freely); higher keeps darks dark.
  final double darkHold;

  static const production = WetConfig(
      dilute: 0.5,
      soften: 0.14,
      spread: 0.30,
      clearFeather: 0.10,
      mix: PigmentMix.spectral);
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
    required this.wetPreview,
    required this.unbaked,
    required this.active,
  });

  final ui.Image surface;
  final SurfaceKind surfaceKind;
  final ui.Image paintLayer;

  /// Live spectral composite of committed paint + pending strokes while a
  /// watercolor stroke is in progress. When present it fully replaces the paint
  /// layer (it already contains the pending strokes, mixed exactly as they will
  /// commit), so no on-the-fly wash approximation is needed.
  final ui.Image? wetPreview;
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
    final base = wetPreview ?? paintLayer;
    final baseSrc =
        Rect.fromLTWH(0, 0, base.width.toDouble(), base.height.toDouble());
    canvas.drawImageRect(base, baseSrc, dst, hq);

    if (wetPreview != null) {
      // The preview already holds every pending watercolor stroke, spectrally
      // mixed. Only a non-watercolor active stroke (e.g. a pen drawn while a
      // wash is still baking) needs a live overlay.
      if (active != null && active!.tool != Tool.watercolor) {
        paintStroke(canvas, active!, surface: surfaceKind);
      }
      canvas.restore();
      return;
    }

    // No spectral preview yet: render pending strokes on the fly. A watercolor
    // stroke re-wets the paint beneath it (same wet recipe as the bake, in
    // logical coords) with a cheap multiply approximation of the mix; this only
    // shows for the frame or two before the first spectral preview lands.
    final paintSrc = Rect.fromLTWH(
        0, 0, paintLayer.width.toDouble(), paintLayer.height.toDouble());
    void render(Stroke s) {
      if (s.tool == Tool.watercolor) {
        applyWetInteraction(
          canvas: canvas,
          region: strokeRibbon(s),
          avgWidth: _avgWidth(s),
          drawPaint: (c) => c.drawImageRect(paintLayer, paintSrc, dst, hq),
        );
        final b = strokeRibbon(s).getBounds().inflate(_avgWidth(s));
        canvas.saveLayer(b, Paint()..blendMode = BlendMode.multiply);
        paintStroke(canvas, s, surface: surfaceKind);
        canvas.restore();
        return;
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
      old.wetPreview != wetPreview ||
      old.active != active ||
      old.unbaked.length != unbaked.length ||
      (active != null && active!.points.length != (old.active?.points.length ?? -1));
}
