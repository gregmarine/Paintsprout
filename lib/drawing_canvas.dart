import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
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

/// Marching-ants selection overlay shader (loaded once at startup). Null if it
/// fails to load, in which case the selection falls back to a dim/tint overlay.
ui.FragmentProgram? _selectionProgram;

/// Loads the selection overlay shader. Call once before the first frame.
Future<void> initSelectionShader() async {
  try {
    _selectionProgram ??=
        await ui.FragmentProgram.fromAsset('shaders/selection_overlay.frag');
  } catch (e, st) {
    debugPrint('Selection shader failed to load: $e\n$st');
    _selectionProgram = null;
  }
}

/// One committed edit to the paint layer, kept in order so the whole paint can
/// be rebuilt on demand — re-toothed when the surface changes, and replayed for
/// undo/redo. Either a drawn [Stroke], or a magic-wand region fill/erase.
sealed class PaintOp {}

/// A drawn stroke (pencil, pen, brush, watercolor, …). Re-rendered against the
/// current surface's tooth when replayed. If [clip] is set (the stroke was drawn
/// while a magic-wand selection was active), the stroke's effect is masked to
/// that region — a frisket — and the clip replays with the op so the constraint
/// survives surface changes and undo/redo. Strokes drawn under the same
/// selection share one clip instance so they still batch together.
class StrokeOp extends PaintOp {
  StrokeOp(this.stroke, {this.clip});
  final Stroke stroke;
  final ui.Image? clip;
}

/// Fills a magic-wand region with [color], broken up by the surface tooth.
/// [mask] (white = selected) is an independent clone, so it survives the live
/// selection being cleared or replaced.
class FillOp extends PaintOp {
  FillOp(this.mask, this.color);
  final ui.Image mask;
  final Color color;
}

/// Erases paint (revealing the surface) within a magic-wand region.
class EraseOp extends PaintOp {
  EraseOp(this.mask);
  final ui.Image mask;
}

/// Which transform a floating-selection drag is performing.
enum _Xform { translate, scale, rotate }

/// Lifts the paint inside a magic-wand region and re-lays it under [transform]
/// (a move/scale/rotate), clearing the original spot. [transform] is a 4x4
/// column-major matrix in BUFFER coordinates. On replay it recomputes the lifted
/// paint from whatever is under [sourceMask] at that point, so it re-tooths and
/// composes correctly through surface changes and undo/redo.
class MoveOp extends PaintOp {
  MoveOp(this.sourceMask, this.transform);
  final ui.Image sourceMask;
  final Float64List transform;
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

  /// Drops the current magic-wand selection.
  void clearSelection() => _state?._clearSelection();

  /// Fills the current magic-wand selection with [color] (toothed like paint).
  Future<void> fillSelection(Color color) async =>
      _state?._fillSelection(color);

  /// Erases paint within the current magic-wand selection (reveals the surface).
  Future<void> deleteSelection() async => _state?._deleteSelection();

  /// A snapshot of the committed paint ops, for the undo/redo history. Ops are
  /// immutable once committed, so the list shares their instances (cheap).
  List<PaintOp> ops() => _state?._opsForHistory() ?? const [];

  /// Restores a document snapshot atomically: rebuilds the surface for
  /// [surface]/[plainColor] and re-composites [ops] in one pass.
  Future<void> restore(
          SurfaceKind surface, Color plainColor, List<PaintOp> ops) async =>
      _state?._restoreDocument(surface, plainColor, ops);

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
    this.wandTolerance = 0.15,
    this.wandEdgeSensitivity = 0.5,
    this.wandGap = 3,
    this.onCommitted,
    this.onUndoGesture,
    this.onRedoGesture,
    this.onSelectionChanged,
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

  /// Magic-wand color tolerance, 0..1 (0 = exact match, 1 = everything).
  final double wandTolerance;

  /// How faint a drawn edge still stops the wand fill, 0..1 (higher = catches
  /// lighter lines like a soft pencil).
  final double wandEdgeSensitivity;

  /// Gap-closing radius (buffer px): bridges holes in a grainy boundary so the
  /// fill can't leak through.
  final int wandGap;

  /// Touch gestures for history: a two-finger tap fires [onUndoGesture], a
  /// three-finger tap fires [onRedoGesture] (drawing is stylus-only, so touch
  /// is free for these).
  final VoidCallback? onUndoGesture;
  final VoidCallback? onRedoGesture;

  /// Fired when the magic-wand selection appears (true) or is cleared (false).
  final ValueChanged<bool>? onSelectionChanged;

  /// When true, bakes synthetic pencil sample strokes on init so the pencil
  /// look can be verified on-device without a stylus. Temporary/dev only.
  final bool debugPencilSample;

  @override
  State<DrawingCanvas> createState() => _DrawingCanvasState();
}

class _DrawingCanvasState extends State<DrawingCanvas>
    with SingleTickerProviderStateMixin {
  final math.Random _rng = math.Random();

  /// The base layer you paint on (paper/canvas/etc.), buffer resolution.
  ui.Image? _surface;

  /// Committed paint, transparent where unpainted so the surface shows through.
  ui.Image? _paint;

  /// Strokes finished but not yet baked into [_paint].
  final List<Stroke> _unbaked = [];

  /// Every edit already baked into [_paint], in paint order. Kept as ops (drawn
  /// strokes, region fills, region erases) so the whole drawing can be re-
  /// rendered against a new surface's tooth when the surface changes (existing
  /// marks take on the new substrate) and replayed for undo/redo.
  final List<PaintOp> _committed = [];

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

  /// Magic-wand selection: white where selected, transparent elsewhere, at
  /// buffer resolution. Null when nothing is selected.
  ui.Image? _selectionMask;

  /// The frisket that constrains the pending/preview strokes (and the eraser and
  /// wet preview) currently on top of [_paint]. Captured from [_selectionMask]
  /// when a stroke starts, and kept until that content bakes — so it survives the
  /// selection being dismissed mid-stroke (otherwise the preview would redraw the
  /// still-unbaked stroke unclipped the instant the mask went away). Null when
  /// the pending content wasn't drawn under a selection.
  ui.Image? _strokeClip;

  /// Drives the marching-ants animation while a selection is active.
  late final AnimationController _antsController;

  /// Reused selection-overlay shader instance (marching ants).
  ui.FragmentShader? _antsShader;

  // Wand tap tracking (a stylus tap in the wand tool triggers a selection).
  Offset? _wandDownPos;
  int? _wandPointer;
  bool _wandMoved = false;

  /// Bounding box of the current selection, in logical coordinates (the mask
  /// runs at logical resolution). Rect.zero when nothing is selected.
  Rect _selRect = Rect.zero;

  // --- Floating move/transform of a selection -----------------------------
  /// The lifted paint (buffer res, masked to the selection), shown transformed
  /// while a move is in progress. Null when not moving.
  ui.Image? _floating;

  /// The committed paint with the lifted region removed (buffer res), shown
  /// under the floating paint during a move.
  ui.Image? _paintHole;

  /// The lifted region mask (a clone), used to bake the move op.
  ui.Image? _floatSourceMask;

  /// Live transform of the float: translation (logical), uniform scale, and
  /// rotation (radians), all about the selection's center.
  Offset _floatTranslate = Offset.zero;
  double _floatScale = 1.0;
  double _floatRotation = 0.0;

  bool _lifting = false; // a lift is being computed
  int? _movePointer; // stylus owning the in-progress transform drag
  Offset _moveLastPos = Offset.zero;
  _Xform? _xformMode; // which transform the current drag is performing
  // Values captured at the start of a scale/rotate drag.
  double _gestureStartScale = 1.0;
  double _gestureStartDist = 1.0;
  double _gestureStartRotation = 0.0;
  double _gestureStartAngle = 0.0;

  bool get _isFloating => _floating != null;

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
    _antsController =
        AnimationController(vsync: this, duration: const Duration(seconds: 1));
    _antsShader = _selectionProgram?.fragmentShader();
  }

  @override
  void didUpdateWidget(DrawingCanvas old) {
    super.didUpdateWidget(old);
    // Leaving the wand tool bakes any in-progress move.
    if (widget.tool != old.tool && _isFloating) unawaited(_commitFloating());
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

  /// Snapshot of the committed ops for the undo/redo history.
  List<PaintOp> _opsForHistory() => List.of(_committed);

  /// Restores a document snapshot: rebuilds the surface for [surface]/
  /// [plainColor] and re-composites [ops], in a single pass. Used by
  /// undo/redo so surface and paint come back together without a double
  /// rebuild (see the _suppressRegen guard in didUpdateWidget).
  Future<void> _restoreDocument(
      SurfaceKind surface, Color plainColor, List<PaintOp> ops) async {
    if (_paint == null) return;
    // If the restored surface differs from the current props, the screen's
    // matching setState will trigger didUpdateWidget; flag it so that pass
    // skips its own rebuild (we do the one rebuild here).
    final willRegen = surface != widget.surface ||
        (surface == SurfaceKind.plain && plainColor != widget.plainColor);
    if (willRegen) widget.controller._suppressRegen = true;
    _discardFloating();
    final surfaceImg =
        await buildSurfaceVisual(surface, _bufW, _bufH, plainColor: plainColor);
    _committed
      ..clear()
      ..addAll(ops);
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
    _antsController.dispose();
    _antsShader?.dispose();
    _surface?.dispose();
    _paint?.dispose();
    _wetPreview?.dispose();
    _selectionMask?.dispose();
    _strokeClip?.dispose();
    _floating?.dispose();
    _paintHole?.dispose();
    _floatSourceMask?.dispose();
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
    var img = await _composite(
        _paint!, [for (final s in _buildToolSamples()) StrokeOp(s)]);
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
    if (widget.tool == Tool.wand) {
      final p = e.localPosition;
      // A corner handle scales, the top handle rotates, dragging inside moves.
      // Lift the paint so it floats. A tap (no drag) is harmless — an unmoved
      // float commits to nothing.
      final mode = _hitTransform(p);
      if (mode != null) {
        _movePointer = e.pointer;
        _moveLastPos = p;
        _xformMode = mode;
        if (!_isFloating && !_lifting) unawaited(_liftSelection());
        return;
      }
      // Otherwise a stylus tap selects; a drag is ignored. No stroke is created.
      _wandPointer = e.pointer;
      _wandDownPos = p;
      _wandMoved = false;
      return;
    }
    if (_active != null) return; // already tracking a stroke
    final color =
        widget.tool == Tool.eraser ? widget.paperColor : widget.color;
    // Capture the frisket this stroke is drawn under. It outlives the live
    // selection so the preview stays clipped even if the selection is dismissed
    // before this stroke bakes. Committed ops keep their own clip, so nothing
    // still references the previous value; drop it.
    final prevClip = _strokeClip;
    setState(() {
      _strokeClip = _selectionMask?.clone();
      _activePointer = e.pointer;
      _active = Stroke(widget.tool, color, seed: _rng.nextInt(1 << 30))
        ..add(_sample(e));
    });
    prevClip?.dispose();
    if (widget.tool == Tool.watercolor) unawaited(_updateWetPreview());
  }

  void _onMove(PointerMoveEvent e) {
    if (!_isStylus(e.kind)) {
      _onTouchMove(e);
      return;
    }
    if (widget.tool == Tool.wand) {
      if (e.pointer == _movePointer) {
        final p = e.localPosition;
        // Until the lift finishes, just track position (no transform yet).
        if (!_isFloating) {
          _moveLastPos = p;
          return;
        }
        switch (_xformMode) {
          case _Xform.translate:
            final delta = p - _moveLastPos;
            _moveLastPos = p;
            setState(() => _floatTranslate += delta);
          case _Xform.scale:
            final d = (p - _visiblePivot()).distance;
            setState(() => _floatScale = (_gestureStartScale *
                    d /
                    _gestureStartDist)
                .clamp(0.05, 20.0));
          case _Xform.rotate:
            final ang = (p - _visiblePivot()).direction;
            setState(() => _floatRotation =
                _gestureStartRotation + (ang - _gestureStartAngle));
          case null:
            break;
        }
        return;
      }
      if (e.pointer == _wandPointer &&
          _wandDownPos != null &&
          (e.localPosition - _wandDownPos!).distance > 8) {
        _wandMoved = true;
      }
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
    if (widget.tool == Tool.wand) {
      if (e.pointer == _movePointer) {
        // Drag ended; the selection stays floating so it can be transformed
        // again. It bakes on deselect, tool switch, or a new selection.
        _movePointer = null;
        _xformMode = null;
        return;
      }
      if (e.pointer == _wandPointer) {
        final pos = _wandDownPos;
        final moved = _wandMoved;
        _wandPointer = null;
        _wandDownPos = null;
        _wandMoved = false;
        if (!moved && pos != null) unawaited(_runWandSelection(pos));
      }
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

  // --- Magic wand selection -----------------------------------------------

  /// Flood-fills a contiguous painted region starting at [logical] (logical
  /// coords), building a selection mask. Samples the PAINT layer only — never
  /// the surface. The surface material only affects how paint looks, not how the
  /// wand selects. (With layers, this will target the active layer or all paint
  /// layers, but never the surface.)
  Future<void> _runWandSelection(Offset logical) async {
    if (_paint == null) return;
    // Bake any in-progress move before selecting a new region.
    await _commitFloating();
    if (_paint == null) return;
    final seedX = (logical.dx * kSuperSample).round().clamp(0, _bufW - 1);
    final seedY = (logical.dy * kSuperSample).round().clamp(0, _bufH - 1);

    final data = await _paint!.toByteData(format: ui.ImageByteFormat.rawRgba);
    if (data == null) return;

    final request = _WandRequest(
      pixels: data.buffer.asUint8List(),
      width: _bufW,
      height: _bufH,
      seedX: seedX,
      seedY: seedY,
      tolerance: widget.wandTolerance,
      edgeSensitivity: widget.wandEdgeSensitivity,
      gap: widget.wandGap,
    );
    final result = await compute(_wandFloodFill, request);
    final maskImg = await _decodeRgba(result.mask, result.width, result.height);
    if (!mounted) {
      maskImg.dispose();
      return;
    }
    final old = _selectionMask;
    setState(() {
      _selectionMask = maskImg;
      _selRect = result.bounds;
      _floatTranslate = Offset.zero;
      _floatScale = 1.0;
      _floatRotation = 0.0;
    });
    old?.dispose();
    if (!_antsController.isAnimating) _antsController.repeat();
    widget.onSelectionChanged?.call(true);
  }

  /// Fills the current selection with [color], toothed like paint, as one
  /// committed op (undoable). The selection stays active afterward.
  Future<void> _fillSelection(Color color) async {
    if (_paint == null || _selectionMask == null) return;
    await _commitFloating();
    await _flushPending();
    final mask = _selectionMask;
    if (mask == null || _paint == null) return;
    // Clone so the op's mask survives the live selection being cleared/replaced.
    final op = FillOp(mask.clone(), color);
    await _applyCommittedOp(op);
  }

  /// Erases paint within the current selection (revealing the surface) as one
  /// committed op (undoable). The selection stays active afterward.
  Future<void> _deleteSelection() async {
    if (_paint == null || _selectionMask == null) return;
    await _commitFloating();
    await _flushPending();
    final mask = _selectionMask;
    if (mask == null || _paint == null) return;
    final op = EraseOp(mask.clone());
    await _applyCommittedOp(op);
  }

  /// Bakes [op] onto the paint layer and records it as a committed step.
  Future<void> _applyCommittedOp(PaintOp op) async {
    final newPaint = await _composite(_paint!, [op]);
    if (!mounted) {
      newPaint.dispose();
      return;
    }
    final old = _paint;
    setState(() {
      _paint = newPaint;
      _committed.add(op);
    });
    old?.dispose();
    widget.onCommitted?.call();
  }

  /// Flushes any strokes still pending a bake so a following op composites on
  /// top of a fully up-to-date paint layer.
  Future<void> _flushPending() async {
    while (_unbaked.isNotEmpty || _baking) {
      await _bake();
      if (_baking) await Future<void>.delayed(const Duration(milliseconds: 8));
    }
  }

  /// Clears the current magic-wand selection. Pending strokes keep their own
  /// frisket ([_strokeClip]), so this doesn't need to flush them — the preview
  /// stays clipped and they bake correctly even after the ants disappear. A
  /// floating move (if any) is baked first.
  Future<void> _clearSelection() async {
    if (_selectionMask == null && !_isFloating) return;
    await _commitFloating();
    final old = _selectionMask;
    if (old != null) {
      setState(() => _selectionMask = null);
      old.dispose();
    }
    _selRect = Rect.zero;
    _antsController.stop();
    widget.onSelectionChanged?.call(false);
  }

  // --- Floating move/transform --------------------------------------------

  /// The visible center the float scales/rotates about, in logical coords.
  Offset _visiblePivot() => _selRect.center + _floatTranslate;

  /// The current float transform as a 2D affine matrix (column-major 4x4).
  /// [superSample] scales the pivot/translation into buffer coordinates.
  Float64List _floatMatrix({double superSample = 1.0}) => _affine(
        _floatScale,
        _floatRotation,
        _selRect.center * superSample,
        _floatTranslate * superSample,
      );

  /// Builds a 2D affine (scale [s] and rotation [theta] about [pivot], then
  /// translate by [t]) as a column-major 4x4 matrix for Canvas.transform.
  Float64List _affine(double s, double theta, Offset pivot, Offset t) {
    final cos = math.cos(theta), sin = math.sin(theta);
    final a = s * cos, b = s * sin, c = -s * sin, d = s * cos;
    final e = pivot.dx + t.dx - (a * pivot.dx + c * pivot.dy);
    final f = pivot.dy + t.dy - (b * pivot.dx + d * pivot.dy);
    return Float64List.fromList([
      a, b, 0, 0, //
      c, d, 0, 0, //
      0, 0, 1, 0, //
      e, f, 0, 1, //
    ]);
  }

  Offset _mapPoint(Float64List m, Offset p) =>
      Offset(m[0] * p.dx + m[4] * p.dy + m[12],
          m[1] * p.dx + m[5] * p.dy + m[13]);

  /// The four transformed corners of the selection frame (logical coords).
  List<Offset> _floatCorners() {
    final m = _floatMatrix();
    return [
      _mapPoint(m, _selRect.topLeft),
      _mapPoint(m, _selRect.topRight),
      _mapPoint(m, _selRect.bottomRight),
      _mapPoint(m, _selRect.bottomLeft),
    ];
  }

  /// The rotate handle position: out past the (transformed) top edge midpoint.
  Offset _rotateHandle() {
    final m = _floatMatrix();
    final topMid = _mapPoint(m, Offset(_selRect.center.dx, _selRect.top));
    final pivot = _mapPoint(m, _selRect.center);
    final dir = topMid - pivot;
    final len = dir.distance;
    final norm = len == 0 ? const Offset(0, -1) : dir / len;
    return topMid + norm * 30.0;
  }

  /// Hit-tests a wand-tool pointer-down against the transform affordances,
  /// returning the transform it starts (and recording the gesture anchors), or
  /// null if the point isn't on the selection.
  _Xform? _hitTransform(Offset p) {
    if (_selectionMask == null && !_isFloating) return null;
    if (_selRect.isEmpty) return null;
    const hit = 26.0;
    final pivot = _visiblePivot();
    if ((p - _rotateHandle()).distance <= hit) {
      _gestureStartRotation = _floatRotation;
      _gestureStartAngle = (p - pivot).direction;
      return _Xform.rotate;
    }
    for (final c in _floatCorners()) {
      if ((p - c).distance <= hit) {
        _gestureStartScale = _floatScale;
        _gestureStartDist = math.max(1.0, (p - pivot).distance);
        return _Xform.scale;
      }
    }
    if (_pointInQuad(p, _floatCorners())) return _Xform.translate;
    return null;
  }

  static bool _pointInQuad(Offset p, List<Offset> q) {
    var sign = 0;
    for (var i = 0; i < 4; i++) {
      final a = q[i], b = q[(i + 1) % 4];
      final cross =
          (b.dx - a.dx) * (p.dy - a.dy) - (b.dy - a.dy) * (p.dx - a.dx);
      final s = cross > 0 ? 1 : (cross < 0 ? -1 : 0);
      if (s != 0) {
        if (sign == 0) {
          sign = s;
        } else if (s != sign) {
          return false;
        }
      }
    }
    return true;
  }

  /// Lifts the selected paint into a floating layer (and clears its original
  /// spot) so it can be transformed. Idempotent while a lift is in flight.
  Future<void> _liftSelection() async {
    if (_paint == null || _selectionMask == null || _isFloating || _lifting) {
      return;
    }
    _lifting = true;
    try {
      final mask = _selectionMask!;
      final floating = await _maskedCopy(_paint!, mask, keepInside: true);
      final hole = await _maskedCopy(_paint!, mask, keepInside: false);
      if (!mounted) {
        floating.dispose();
        hole.dispose();
        return;
      }
      setState(() {
        _floating = floating;
        _paintHole = hole;
        _floatSourceMask = mask.clone();
        _floatTranslate = Offset.zero;
        _floatScale = 1.0;
        _floatRotation = 0.0;
      });
    } finally {
      _lifting = false;
    }
  }

  /// Bakes the current floating move into the paint as a [MoveOp] and re-lands
  /// the selection at its new spot. An unmoved float commits to nothing.
  Future<void> _commitFloating() async {
    final sourceMask = _floatSourceMask;
    if (_floating == null || sourceMask == null || _paint == null) {
      _discardFloating();
      return;
    }
    // Nothing actually transformed → drop the float, no undo step.
    if (_floatTranslate == Offset.zero &&
        _floatScale == 1.0 &&
        _floatRotation == 0.0) {
      _discardFloating();
      return;
    }
    final logical = _floatMatrix();
    final op = MoveOp(
        sourceMask.clone(), _floatMatrix(superSample: kSuperSample));
    final newRect = _boundsOf(_floatCorners());
    final newPaint = await _compositeMove(_paint!, op);
    final movedMask = _selectionMask == null
        ? null
        : await _transformMask(_selectionMask!, logical);
    if (!mounted) {
      newPaint.dispose();
      movedMask?.dispose();
      return;
    }
    final oldPaint = _paint;
    final oldMask = _selectionMask;
    final f = _floating, h = _paintHole, m = _floatSourceMask;
    setState(() {
      _paint = newPaint;
      _committed.add(op);
      if (movedMask != null) _selectionMask = movedMask;
      _selRect = newRect;
      _floating = null;
      _paintHole = null;
      _floatSourceMask = null;
      _floatTranslate = Offset.zero;
      _floatScale = 1.0;
      _floatRotation = 0.0;
    });
    oldPaint?.dispose();
    if (movedMask != null) oldMask?.dispose();
    f?.dispose();
    h?.dispose();
    m?.dispose();
    widget.onCommitted?.call();
  }

  static Rect _boundsOf(List<Offset> pts) {
    var l = pts.first.dx, t = pts.first.dy, r = l, b = t;
    for (final p in pts) {
      if (p.dx < l) l = p.dx;
      if (p.dx > r) r = p.dx;
      if (p.dy < t) t = p.dy;
      if (p.dy > b) b = p.dy;
    }
    return Rect.fromLTRB(l, t, r, b);
  }

  /// Drops the floating layer without committing (used for an unmoved float).
  void _discardFloating() {
    if (_floating == null && _paintHole == null && _floatSourceMask == null) {
      return;
    }
    final f = _floating, h = _paintHole, m = _floatSourceMask;
    setState(() {
      _floating = null;
      _paintHole = null;
      _floatSourceMask = null;
      _floatTranslate = Offset.zero;
    });
    f?.dispose();
    h?.dispose();
    m?.dispose();
  }

  /// Copies [src], keeping only the paint inside [mask] (keepInside) or only the
  /// paint outside it. Both at buffer resolution.
  Future<ui.Image> _maskedCopy(ui.Image src, ui.Image mask,
      {required bool keepInside}) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    final dst = Rect.fromLTWH(0, 0, _bufW.toDouble(), _bufH.toDouble());
    final msrc =
        Rect.fromLTWH(0, 0, mask.width.toDouble(), mask.height.toDouble());
    canvas.drawImage(src, Offset.zero, Paint());
    canvas.drawImageRect(mask, msrc, dst,
        Paint()..blendMode = keepInside ? BlendMode.dstIn : BlendMode.dstOut);
    return recorder.endRecording().toImage(_bufW, _bufH);
  }

  /// Returns a copy of [mask] under [logicalMatrix]. The mask runs at logical
  /// resolution, so the logical-coordinate transform applies to it 1:1.
  Future<ui.Image> _transformMask(ui.Image mask, Float64List logicalMatrix) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.transform(logicalMatrix);
    canvas.drawImage(mask, Offset.zero, Paint());
    return recorder.endRecording().toImage(mask.width, mask.height);
  }

  /// Bakes a [MoveOp]: lifts the paint under its source mask, clears that spot,
  /// and re-lays the lifted paint under the op's transform.
  Future<ui.Image> _compositeMove(ui.Image base, MoveOp op) async {
    final floating = await _maskedCopy(base, op.sourceMask, keepInside: true);
    final hole = await _maskedCopy(base, op.sourceMask, keepInside: false);
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawImage(hole, Offset.zero, Paint());
    canvas.save();
    canvas.transform(op.transform);
    canvas.drawImage(floating, Offset.zero, Paint());
    canvas.restore();
    final out = await recorder.endRecording().toImage(_bufW, _bufH);
    floating.dispose();
    hole.dispose();
    return out;
  }

  // --- Baking -------------------------------------------------------------

  Future<void> _bake() async {
    if (_baking || _paint == null || _unbaked.isEmpty) return;
    _baking = true;
    try {
      final batch = List<Stroke>.from(_unbaked);
      // These strokes are constrained to the frisket they were drawn under
      // ([_strokeClip], which persists even if the selection was since dropped).
      // Clone once, shared across the batch, so the ops still batch together and
      // the clip replays with them (surface change, undo/redo).
      final clip = _strokeClip?.clone();
      final batchOps = [for (final s in batch) StrokeOp(s, clip: clip)];
      final newPaint = await _composite(_paint!, batchOps);
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
        _committed.addAll(batchOps);
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
      final img = await _composite(
          _paint!, [for (final s in strokes) StrokeOp(s)]);
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

  /// Folds [ops] onto [base] in order, returning a new image (never disposes
  /// [base]). Ordinary stroke tools just deposit (batched); the watercolor brush
  /// reads the paint underneath and re-wets it, so it runs against an already-
  /// rasterized image one stroke at a time. Region fills/erases apply their mask
  /// directly and break a stroke run.
  Future<ui.Image> _composite(ui.Image base, List<PaintOp> ops) async {
    ui.Image current = base;
    var owns = false; // whether we own `current` and must dispose it
    var i = 0;
    while (i < ops.length) {
      final op = ops[i];
      final ui.Image next;
      if (op is StrokeOp && op.stroke.tool == Tool.watercolor) {
        next = await _compositeStrokes(current, [op.stroke], op.clip,
            watercolor: true);
        i++;
      } else if (op is StrokeOp) {
        // Batch a run of consecutive ordinary strokes that share a clip.
        final clip = op.clip;
        final run = <Stroke>[];
        while (i < ops.length &&
            ops[i] is StrokeOp &&
            (ops[i] as StrokeOp).stroke.tool != Tool.watercolor &&
            identical((ops[i] as StrokeOp).clip, clip)) {
          run.add((ops[i] as StrokeOp).stroke);
          i++;
        }
        next = await _compositeStrokes(current, run, clip, watercolor: false);
      } else if (op is FillOp) {
        next = await _compositeFill(current, op);
        i++;
      } else if (op is EraseOp) {
        next = await _compositeErase(current, op);
        i++;
      } else {
        next = await _compositeMove(current, op as MoveOp);
        i++;
      }
      if (owns) current.dispose();
      current = next;
      owns = true;
    }
    // No ops: hand back a fresh copy the caller can own/dispose.
    if (!owns) return _additiveComposite(base, const []);
    return current;
  }

  /// Deposits [strokes] onto [base], optionally masked to [clip] (a frisket, so
  /// only the part inside the selection survives — outside stays as it was).
  Future<ui.Image> _compositeStrokes(
      ui.Image base, List<Stroke> strokes, ui.Image? clip,
      {required bool watercolor}) async {
    final full = watercolor
        ? await _compositeWatercolor(base, strokes.first)
        : await _additiveComposite(base, strokes);
    if (clip == null) return full;
    final out = await _blendMasked(base, full, clip);
    full.dispose();
    return out;
  }

  /// Per-pixel select between [base] (outside [mask]) and [full] (inside it):
  /// `out = base*(1-m) + full*m`. Constrains an edit to the selection uniformly
  /// for any tool (additive, eraser, watercolor), since it just chooses the
  /// pre- or post-edit pixel by mask coverage.
  Future<ui.Image> _blendMasked(ui.Image base, ui.Image full, ui.Image mask) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    final dst = Rect.fromLTWH(0, 0, _bufW.toDouble(), _bufH.toDouble());
    final msrc =
        Rect.fromLTWH(0, 0, mask.width.toDouble(), mask.height.toDouble());
    // base with a hole punched inside the selection.
    canvas.drawImage(base, Offset.zero, Paint());
    canvas.saveLayer(dst, Paint()..blendMode = BlendMode.dstOut);
    canvas.drawImageRect(mask, msrc, dst, Paint());
    canvas.restore();
    // full, kept only inside the selection, dropped into the hole.
    canvas.saveLayer(dst, Paint());
    canvas.drawImage(full, Offset.zero, Paint());
    canvas.drawImageRect(mask, msrc, dst, Paint()..blendMode = BlendMode.dstIn);
    canvas.restore();
    return recorder.endRecording().toImage(_bufW, _bufH);
  }

  /// Deposits a toothed color fill (masked to the op's region) onto [base].
  Future<ui.Image> _compositeFill(ui.Image base, FillOp op) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawImage(base, Offset.zero, Paint());
    paintToothedFill(
      canvas,
      op.mask,
      Rect.fromLTWH(0, 0, _bufW.toDouble(), _bufH.toDouble()),
      op.color,
      surface: widget.surface,
      toothTexelScale: widget.surface.toothScale * kSuperSample,
    );
    return recorder.endRecording().toImage(_bufW, _bufH);
  }

  /// Erases paint (masked to the op's region) from [base].
  Future<ui.Image> _compositeErase(ui.Image base, EraseOp op) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawImage(base, Offset.zero, Paint());
    paintMaskedErase(canvas, op.mask,
        Rect.fromLTWH(0, 0, _bufW.toDouble(), _bufH.toDouble()));
    return recorder.endRecording().toImage(_bufW, _bufH);
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
    _discardFloating();
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
    // Bake a floating move and flush pending strokes so the export is current.
    await _commitFloating();
    await _flushPending();
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
          child: Stack(
            fit: StackFit.expand,
            children: [
              // Base paint. No RepaintBoundary here: during a stroke this
              // repaints every frame, and a boundary would force a full-screen
              // offscreen re-rasterize each time (that stuttered the first
              // strokes). It's a separate CustomPaint from the overlay, so the
              // ants animation still can't reach back into it.
              CustomPaint(
                size: size,
                painter: _CanvasPainter(
                  surface: _surface!,
                  surfaceKind: widget.surface,
                  paintLayer: _paint!,
                  wetPreview: _wetPreview,
                  clip: _strokeClip,
                  unbaked: _unbaked,
                  active: _active,
                  floating: _floating,
                  paintHole: _paintHole,
                  floatMatrix: _isFloating ? _floatMatrix() : null,
                ),
              ),
              // Animated selection overlay: marching ants for a static
              // selection, or a transform frame + handles while moving. The
              // RepaintBoundary contains its per-frame ticks so they repaint
              // only this cheap layer, never the paint stack above.
              if (_selectionMask != null || _isFloating)
                RepaintBoundary(
                  child: CustomPaint(
                    size: size,
                    painter: _SelectionPainter(
                      selectionMask: _selectionMask,
                      antsShader: _antsShader,
                      antsPhase: _antsController,
                      corners: _isFloating ? _floatCorners() : null,
                      rotateHandle: _isFloating ? _rotateHandle() : null,
                    ),
                  ),
                ),
            ],
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

// --- Magic wand flood fill --------------------------------------------------

/// Input for the flood fill, sent to a background isolate via [compute].
class _WandRequest {
  const _WandRequest({
    required this.pixels,
    required this.width,
    required this.height,
    required this.seedX,
    required this.seedY,
    required this.tolerance,
    required this.edgeSensitivity,
    required this.gap,
  });

  final Uint8List pixels; // rawRgba of the flattened canvas
  final int width;
  final int height;
  final int seedX;
  final int seedY;
  final double tolerance; // 0..1: color spread from the seed
  final double edgeSensitivity; // 0..1: how faint an edge still counts as a wall
  final int gap; // px: bridge grain gaps in a boundary up to this radius
}

/// Result of a wand flood fill: an rgba mask (white = selected), its size, and
/// the selection's bounding box in mask pixels (empty if nothing was selected).
/// The mask runs at half the buffer resolution, which equals the logical canvas
/// resolution (buffer = logical * 2, downsample = 2), so these bounds double as
/// logical-coordinate bounds.
class _WandResult {
  const _WandResult(this.mask, this.width, this.height, this.bounds);
  final Uint8List mask;
  final int width;
  final int height;
  final Rect bounds;
}

/// Edge-aware flood fill with gap closing, over the PAINT layer only (surface
/// texture never participates). From the seed it grows over pixels within
/// [tolerance] of the seed's premultiplied RGBA AND not blocked by a drawn edge.
/// Boundaries come from two signals: paint coverage (the alpha channel), which
/// catches even a faint pencil line the way a color signal can't, and premult
/// luminance, which catches color edges between opaque regions.
///
/// For speed the heavy passes run at half resolution, downsampling by keeping
/// the MAX-alpha sample of each 2x2 block (never averaging — that would erase a
/// faint speckly line). Steps: reduce, Sobel → barrier, dilate the barrier by
/// [gap] (seals speckle holes), scanline-fill inside it, grow the result back up
/// to the edge. Returns a half-res mask. Runs in a background isolate.
_WandResult _wandFloodFill(_WandRequest r) {
  const ds = 2;
  final fw = r.width, fh = r.height;
  final w = (fw + ds - 1) ~/ ds, h = (fh + ds - 1) ~/ ds, n = w * h;
  final px = r.pixels;

  // Downsample to a representative pixel per block (the most-painted sample),
  // plus the two boundary signals.
  final small = Uint8List(n * 4);
  final alpha = Uint8List(n);
  final lum = Uint8List(n);
  for (var y = 0; y < h; y++) {
    for (var x = 0; x < w; x++) {
      var bestA = -1, br = 0, bg = 0, bb = 0;
      for (var dy = 0; dy < ds; dy++) {
        final yy = y * ds + dy;
        if (yy >= fh) break;
        for (var dx = 0; dx < ds; dx++) {
          final xx = x * ds + dx;
          if (xx >= fw) break;
          final o = (yy * fw + xx) * 4;
          if (px[o + 3] > bestA) {
            bestA = px[o + 3];
            br = px[o];
            bg = px[o + 1];
            bb = px[o + 2];
          }
        }
      }
      final j = y * w + x;
      final o = j * 4;
      small[o] = br;
      small[o + 1] = bg;
      small[o + 2] = bb;
      small[o + 3] = bestA;
      alpha[j] = bestA;
      lum[j] = (br * 77 + bg * 150 + bb * 29) >> 8;
    }
  }

  // Sobel edge barrier. Higher sensitivity -> lower thresholds -> fainter lines
  // still register as walls. Alpha (paint coverage) gets a much lower threshold
  // than luminance: a light pencil's coverage gradient is small, and because the
  // surface is excluded, empty areas are perfectly flat so there's no noise to
  // trip over. Luminance separates touching opaque colors.
  final alphaThreshold = (24.0 + (4.0 - 24.0) * r.edgeSensitivity);
  final lumThreshold = (60.0 + (6.0 - 60.0) * r.edgeSensitivity);
  final barrier = Uint8List(n);
  for (var y = 1; y < h - 1; y++) {
    for (var x = 1; x < w - 1; x++) {
      final i = y * w + x;
      final aGx = -alpha[i - w - 1] - 2 * alpha[i - 1] - alpha[i + w - 1] +
          alpha[i - w + 1] + 2 * alpha[i + 1] + alpha[i + w + 1];
      final aGy = -alpha[i - w - 1] - 2 * alpha[i - w] - alpha[i - w + 1] +
          alpha[i + w - 1] + 2 * alpha[i + w] + alpha[i + w + 1];
      final lGx = -lum[i - w - 1] - 2 * lum[i - 1] - lum[i + w - 1] +
          lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1];
      final lGy = -lum[i - w - 1] - 2 * lum[i - w] - lum[i - w + 1] +
          lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1];
      if (aGx.abs() + aGy.abs() > alphaThreshold ||
          lGx.abs() + lGy.abs() > lumThreshold) {
        barrier[i] = 1;
      }
    }
  }

  // Seal small gaps in the boundary so the fill can't leak through the speckle.
  final gap = r.gap <= 0 ? 0 : ((r.gap + ds - 1) ~/ ds);
  final wall = gap > 0 ? _dilate(barrier, w, h, gap) : barrier;

  final sx = (r.seedX ~/ ds).clamp(0, w - 1);
  final sy = (r.seedY ~/ ds).clamp(0, h - 1);
  final seedO = (sy * w + sx) * 4;
  final sr = small[seedO],
      sg = small[seedO + 1],
      sb = small[seedO + 2],
      sa = small[seedO + 3];
  final colorThreshold = (r.tolerance * 1020).round(); // 4 channels incl. alpha
  final visited = Uint8List(n);

  bool fillable(int i) {
    if (wall[i] == 1) return false;
    final o = i * 4;
    final d = (small[o] - sr).abs() +
        (small[o + 1] - sg).abs() +
        (small[o + 2] - sb).abs() +
        (small[o + 3] - sa).abs();
    return d <= colorThreshold;
  }

  final stack = <int>[sy * w + sx];
  while (stack.isNotEmpty) {
    final p = stack.removeLast();
    if (visited[p] == 1) continue;
    final y = p ~/ w;
    final rowStart = y * w;
    var xl = p - rowStart;
    var xr = xl;
    while (xl > 0 && visited[rowStart + xl - 1] == 0 && fillable(rowStart + xl - 1)) {
      xl--;
    }
    while (xr < w - 1 && visited[rowStart + xr + 1] == 0 && fillable(rowStart + xr + 1)) {
      xr++;
    }
    for (var x = xl; x <= xr; x++) {
      visited[rowStart + x] = 1;
    }
    var ny = y - 1;
    for (var pass = 0; pass < 2; pass++, ny += 2) {
      if (ny < 0 || ny >= h) continue;
      final nRow = ny * w;
      var x = xl;
      while (x <= xr) {
        while (x <= xr && (visited[nRow + x] == 1 || !fillable(nRow + x))) {
          x++;
        }
        if (x > xr) break;
        stack.add(nRow + x);
        while (x <= xr && visited[nRow + x] == 0 && fillable(nRow + x)) {
          x++;
        }
      }
    }
  }

  // Grow the selection back up to the true edge (recovering the band the barrier
  // dilation ate), but never onto the edge itself.
  final selected = gap > 0 ? _dilate(visited, w, h, gap) : visited;

  final mask = Uint8List(n * 4);
  var minX = w, minY = h, maxX = -1, maxY = -1;
  for (var i = 0; i < n; i++) {
    if (selected[i] == 1 && barrier[i] == 0) {
      final o = i * 4;
      mask[o] = 255;
      mask[o + 1] = 255;
      mask[o + 2] = 255;
      mask[o + 3] = 255;
      final x = i % w, y = i ~/ w;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  final bounds = maxX < minX
      ? Rect.zero
      : Rect.fromLTRB((minX).toDouble(), (minY).toDouble(),
          (maxX + 1).toDouble(), (maxY + 1).toDouble());
  return _WandResult(mask, w, h, bounds);
}

/// Separable binary morphological dilation (square structuring element).
Uint8List _dilate(Uint8List src, int w, int h, int radius) {
  final tmp = Uint8List(w * h);
  for (var y = 0; y < h; y++) {
    final row = y * w;
    for (var x = 0; x < w; x++) {
      final x0 = x - radius < 0 ? 0 : x - radius;
      final x1 = x + radius >= w ? w - 1 : x + radius;
      var v = 0;
      for (var xx = x0; xx <= x1; xx++) {
        if (src[row + xx] == 1) {
          v = 1;
          break;
        }
      }
      tmp[row + x] = v;
    }
  }
  final out = Uint8List(w * h);
  for (var x = 0; x < w; x++) {
    for (var y = 0; y < h; y++) {
      final y0 = y - radius < 0 ? 0 : y - radius;
      final y1 = y + radius >= h ? h - 1 : y + radius;
      var v = 0;
      for (var yy = y0; yy <= y1; yy++) {
        if (tmp[yy * w + x] == 1) {
          v = 1;
          break;
        }
      }
      out[y * w + x] = v;
    }
  }
  return out;
}

/// Decodes raw rgba bytes into a [ui.Image].
Future<ui.Image> _decodeRgba(Uint8List rgba, int w, int h) {
  final completer = Completer<ui.Image>();
  ui.decodeImageFromPixels(
      rgba, w, h, ui.PixelFormat.rgba8888, completer.complete);
  return completer.future;
}

class _CanvasPainter extends CustomPainter {
  _CanvasPainter({
    required this.surface,
    required this.surfaceKind,
    required this.paintLayer,
    required this.wetPreview,
    required this.clip,
    required this.unbaked,
    required this.active,
    required this.floating,
    required this.paintHole,
    required this.floatMatrix,
  });

  final ui.Image surface;
  final SurfaceKind surfaceKind;
  final ui.Image paintLayer;

  /// While a selection is being moved: [floating] is the lifted paint and
  /// [paintHole] is the committed paint with that region removed. The float is
  /// drawn over the hole under [floatMatrix] (logical/display affine).
  final ui.Image? floating;
  final ui.Image? paintHole;
  final Float64List? floatMatrix;

  /// Live spectral composite of committed paint + pending strokes while a
  /// watercolor stroke is in progress. When present it fully replaces the paint
  /// layer (it already contains the pending strokes, mixed exactly as they will
  /// commit), so no on-the-fly wash approximation is needed.
  final ui.Image? wetPreview;

  /// Frisket for the pending/preview content (white where paint is allowed), or
  /// null when unconstrained. Distinct from the live selection: it's the mask the
  /// current strokes were drawn under, so the preview stays clipped even after
  /// the selection is dismissed. The marching-ants visualization lives in
  /// [_SelectionPainter] on a separate layer.
  final ui.Image? clip;

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

    // Moving a selection: the committed paint with a hole where the region was
    // lifted, then the floating paint on top at its dragged offset. The hole is
    // transparent, so the surface shows through the vacated spot.
    final float = floating;
    if (float != null && paintHole != null) {
      final holeSrc = Rect.fromLTWH(
          0, 0, paintHole!.width.toDouble(), paintHole!.height.toDouble());
      final floatSrc =
          Rect.fromLTWH(0, 0, float.width.toDouble(), float.height.toDouble());
      canvas.drawImageRect(paintHole!, holeSrc, dst, hq);
      canvas.save();
      if (floatMatrix != null) canvas.transform(floatMatrix!);
      canvas.drawImageRect(float, floatSrc, dst, hq);
      canvas.restore();
      return;
    }

    // The edited paint: the committed layer (or its live watercolor composite)
    // plus any pending strokes rendered on the fly.
    final base = wetPreview ?? paintLayer;
    final baseSrc =
        Rect.fromLTWH(0, 0, base.width.toDouble(), base.height.toDouble());
    final paintSrc = Rect.fromLTWH(
        0, 0, paintLayer.width.toDouble(), paintLayer.height.toDouble());

    void drawEdited() {
      canvas.drawImageRect(base, baseSrc, dst, hq);
      if (wetPreview != null) {
        // The preview already holds every pending watercolor stroke, spectrally
        // mixed. Only a non-watercolor active stroke (e.g. a pen drawn while a
        // wash is still baking) needs a live overlay.
        if (active != null && active!.tool != Tool.watercolor) {
          paintStroke(canvas, active!, surface: surfaceKind);
        }
        return;
      }
      // No spectral preview yet: render pending strokes on the fly. A watercolor
      // stroke re-wets the paint beneath it (same wet recipe as the bake, in
      // logical coords) with a cheap multiply approximation of the mix; this
      // only shows for the frame or two before the first spectral preview lands.
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
    }

    // Composite the paint in an isolated layer so an eraser stroke
    // (BlendMode.clear) punches through to reveal the surface rather than the
    // widget behind the canvas.
    canvas.saveLayer(dst, Paint());
    final mask = clip;
    final hasEdits =
        wetPreview != null || unbaked.isNotEmpty || active != null;
    if (mask == null || !hasEdits) {
      // Unconstrained, or nothing live to constrain — draw the edit straight.
      drawEdited();
    } else {
      // The frisket: pending edits stay inside it. Show the committed paint
      // outside the frisket, and the edited result inside —
      // `committed*(1-m) + edited*m` — so strokes (and the eraser) can't spill
      // past its boundary. Committed edits already carry their own clip, so this
      // only constrains what's still live on top.
      final msrc =
          Rect.fromLTWH(0, 0, mask.width.toDouble(), mask.height.toDouble());
      canvas.saveLayer(dst, Paint());
      canvas.drawImageRect(paintLayer, paintSrc, dst, hq);
      canvas.drawImageRect(
          mask, msrc, dst, Paint()..blendMode = BlendMode.dstOut);
      canvas.restore();
      canvas.saveLayer(dst, Paint());
      drawEdited();
      canvas.drawImageRect(
          mask, msrc, dst, Paint()..blendMode = BlendMode.dstIn);
      canvas.restore();
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_CanvasPainter old) =>
      old.surface != surface ||
      old.surfaceKind != surfaceKind ||
      old.paintLayer != paintLayer ||
      old.wetPreview != wetPreview ||
      old.clip != clip ||
      old.floating != floating ||
      old.paintHole != paintHole ||
      old.floatMatrix != floatMatrix ||
      old.active != active ||
      old.unbaked.length != unbaked.length ||
      (active != null && active!.points.length != (old.active?.points.length ?? -1));
}

/// Paints the magic-wand selection visualization on its own layer: dims outside
/// the selection and animates marching ants along the border (via the overlay
/// shader). Kept separate from [_CanvasPainter] and driven by [antsPhase] so the
/// per-frame animation repaints only this cheap overlay, never the paint stack.
/// Falls back to a dim/tint overlay if the shader didn't load.
class _SelectionPainter extends CustomPainter {
  _SelectionPainter({
    required this.selectionMask,
    required this.antsShader,
    required this.antsPhase,
    this.corners,
    this.rotateHandle,
  }) : super(repaint: antsPhase);

  /// The selection mask (marching ants source), or null while moving.
  final ui.Image? selectionMask;
  final ui.FragmentShader? antsShader;
  final Animation<double> antsPhase;

  /// When set, a selection is being transformed: draw the frame (through these
  /// four corners, logical/display coords) with scale/rotate handles instead of
  /// the ants.
  final List<Offset>? corners;
  final Offset? rotateHandle;

  @override
  void paint(Canvas canvas, Size size) {
    final dst = Rect.fromLTWH(0, 0, size.width, size.height);

    // Moving: draw the transform frame + handles at the dragged location rather
    // than the ants (which trace the un-moved mask).
    final quad = corners;
    if (quad != null) {
      _paintFrame(canvas, quad, rotateHandle);
      return;
    }

    final mask = selectionMask;
    if (mask == null) return;
    final src =
        Rect.fromLTWH(0, 0, mask.width.toDouble(), mask.height.toDouble());

    final shader = antsShader;
    if (shader != null) {
      shader
        ..setFloat(0, dst.width)
        ..setFloat(1, dst.height)
        ..setFloat(2, antsPhase.value)
        ..setImageSampler(0, mask);
      canvas.drawRect(dst, Paint()..shader = shader);
      return;
    }

    // Fallback: dim outside + faint tint.
    canvas.saveLayer(dst, Paint());
    canvas.drawRect(dst, Paint()..color = Colors.black.withValues(alpha: 0.28));
    canvas.drawImageRect(mask, src, dst, Paint()..blendMode = BlendMode.dstOut);
    canvas.restore();
    canvas.drawImageRect(
        mask,
        src,
        dst,
        Paint()
          ..colorFilter =
              const ColorFilter.mode(Color(0x3300B8D4), BlendMode.srcIn));
  }

  /// Draws the transform frame: a marching black/white dashed quad through
  /// [quad], square scale handles at each corner, and a rotate handle on a stalk
  /// past the top edge.
  void _paintFrame(Canvas canvas, List<Offset> quad, Offset? rotate) {
    final frame = Path()..addPolygon(quad, true);
    final white = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.6;
    final black = Paint()
      ..color = Colors.black
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.6;

    // Stalk to the rotate handle (drawn as part of the dashed outline).
    final outline = Path.from(frame);
    if (rotate != null) {
      final topMid = (quad[0] + quad[1]) / 2;
      outline
        ..moveTo(topMid.dx, topMid.dy)
        ..lineTo(rotate.dx, rotate.dy);
    }

    const dash = 9.0;
    final phase = antsPhase.value * dash * 2; // one dash pair per cycle
    for (final m in outline.computeMetrics()) {
      var d = -phase;
      var i = 0;
      while (d < m.length) {
        final start = d < 0 ? 0.0 : d;
        final end = (d + dash).clamp(0.0, m.length);
        if (end > start) {
          canvas.drawPath(m.extractPath(start, end), i.isEven ? white : black);
        }
        d += dash;
        i++;
      }
    }

    // Handles: filled white with a dark border.
    final fill = Paint()..color = Colors.white;
    final border = Paint()
      ..color = Colors.black87
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4;
    for (final c in quad) {
      final r = Rect.fromCenter(center: c, width: 12, height: 12);
      canvas.drawRect(r, fill);
      canvas.drawRect(r, border);
    }
    if (rotate != null) {
      canvas.drawCircle(rotate, 7, fill);
      canvas.drawCircle(rotate, 7, border);
    }
  }

  @override
  bool shouldRepaint(_SelectionPainter old) =>
      old.selectionMask != selectionMask ||
      old.antsShader != antsShader ||
      !listEquals(old.corners, corners) ||
      old.rotateHandle != rotateHandle;
}
