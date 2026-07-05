import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_colorpicker/flutter_colorpicker.dart';

import 'drawing_canvas.dart';
import 'stroke.dart';
import 'surface.dart';
import 'tools.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initGrains();
  await initPigmentShader();
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  runApp(const PaintsproutApp());
}

class PaintsproutApp extends StatelessWidget {
  const PaintsproutApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Paintsprout',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
      home: const CanvasScreen(),
    );
  }
}

class CanvasScreen extends StatefulWidget {
  const CanvasScreen({super.key});

  @override
  State<CanvasScreen> createState() => _CanvasScreenState();
}

class _CanvasScreenState extends State<CanvasScreen> {
  final _controller = DrawingController();

  Tool _tool = Tool.pencil;
  Color _color = Colors.black;
  SurfaceKind _surfaceKind = SurfaceKind.paper;
  Color _plainColor = Colors.white;
  bool _railVisible = true;

  // Each tool remembers its own base size.
  final Map<Tool, double> _sizes = {
    for (final t in Tool.values) t: t.defaultSize,
  };

  double get _size => _sizes[_tool]!;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: Stack(
        children: [
          Positioned.fill(
            child: DrawingCanvas(
              controller: _controller,
              tool: _tool,
              color: _color,
              baseSize: _size,
              surface: _surfaceKind,
              plainColor: _plainColor,
            ),
          ),
          Positioned(
            left: 12,
            top: 12,
            bottom: 12,
            child: _railVisible
                ? _ToolRail(
                    tool: _tool,
                    color: _color,
                    size: _size,
                    surface: _surfaceKind,
                    onToolChanged: (t) => setState(() => _tool = t),
                    onPickColor: _pickColor,
                    onPickSize: _pickSize,
                    onPickSurface: _pickSurface,
                    onSave: _save,
                    onClear: _clear,
                    onHide: () => setState(() => _railVisible = false),
                  )
                : _ShowRailButton(
                    onShow: () => setState(() => _railVisible = true)),
          ),
        ],
      ),
    );
  }

  Future<void> _pickColor() async {
    Color working = _color;
    // Bumped when a swatch is tapped, so the gradient picker below rebuilds to
    // the new color. It stays constant while dragging the gradient itself, so
    // that interaction isn't reset mid-drag.
    var swatchTick = 0;
    final chosen = await showDialog<Color>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setInner) => AlertDialog(
          title: const Text('Stroke color'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                BlockPicker(
                  pickerColor: working,
                  availableColors: _swatches,
                  onColorChanged: (c) =>
                      setInner(() {
                        working = c;
                        swatchTick++;
                      }),
                ),
                const Divider(),
                ColorPicker(
                  key: ValueKey(swatchTick),
                  pickerColor: working,
                  enableAlpha: false,
                  labelTypes: const [],
                  onColorChanged: (c) => setInner(() => working = c),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Cancel')),
            FilledButton(
                onPressed: () => Navigator.pop(context, working),
                child: const Text('Use')),
          ],
        ),
      ),
    );
    if (chosen != null) setState(() => _color = chosen);
  }

  Future<void> _pickSize() async {
    double working = _size;
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('${_tool.label} size'),
        content: StatefulBuilder(
          builder: (context, setInner) => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(working.toStringAsFixed(0),
                  style: Theme.of(context).textTheme.headlineMedium),
              Slider(
                value: working,
                min: 1,
                max: 80,
                onChanged: (v) => setInner(() => working = v),
              ),
            ],
          ),
        ),
        actions: [
          FilledButton(
            onPressed: () {
              setState(() => _sizes[_tool] = working);
              Navigator.pop(context);
            },
            child: const Text('Done'),
          ),
        ],
      ),
    );
  }

  Future<void> _pickSurface() async {
    final chosen = await showModalBottomSheet<SurfaceKind>(
      context: context,
      builder: (context) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Surface',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
              const SizedBox(height: 12),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  for (final s in kAvailableSurfaces)
                    ChoiceChip(
                      avatar: Icon(s.icon, size: 18),
                      label: Text(s.label),
                      selected: s == _surfaceKind,
                      onSelected: (_) => Navigator.pop(context, s),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
    if (chosen != null) {
      // Plain always opens the color chooser (so you can recolor it too);
      // other surfaces just switch.
      if (chosen == SurfaceKind.plain) {
        setState(() => _surfaceKind = chosen);
        await _pickPlainColor();
      } else if (chosen != _surfaceKind) {
        setState(() => _surfaceKind = chosen);
      }
    }
  }

  Future<void> _pickPlainColor() async {
    Color working = _plainColor;
    final chosen = await showDialog<Color>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Background color'),
        content: SingleChildScrollView(
          child: ColorPicker(
            pickerColor: working,
            enableAlpha: false,
            labelTypes: const [],
            onColorChanged: (c) => working = c,
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, working),
              child: const Text('Use')),
        ],
      ),
    );
    if (chosen != null) setState(() => _plainColor = chosen);
  }

  Future<void> _save() async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final location = await _controller.savePng();
      messenger.showSnackBar(SnackBar(content: Text('Saved to $location')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('Save failed: $e')));
    }
  }

  Future<void> _clear() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Clear canvas?'),
        content: const Text('This erases everything. There is no undo.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Clear')),
        ],
      ),
    );
    if (ok == true) await _controller.clear();
  }

  static const List<Color> _swatches = [
    Colors.black,
    Colors.white,
    Colors.grey,
    Colors.brown,
    Colors.red,
    Colors.deepOrange,
    Colors.orange,
    Colors.amber,
    Colors.yellow,
    Colors.lightGreen,
    Colors.green,
    Colors.teal,
    Colors.cyan,
    Colors.lightBlue,
    Colors.blue,
    Colors.indigo,
    Colors.purple,
    Colors.pink,
  ];
}

class _ToolRail extends StatelessWidget {
  const _ToolRail({
    required this.tool,
    required this.color,
    required this.size,
    required this.surface,
    required this.onToolChanged,
    required this.onPickColor,
    required this.onPickSize,
    required this.onPickSurface,
    required this.onSave,
    required this.onClear,
    required this.onHide,
  });

  final Tool tool;
  final Color color;
  final double size;
  final SurfaceKind surface;
  final ValueChanged<Tool> onToolChanged;
  final VoidCallback onPickColor;
  final VoidCallback onPickSize;
  final VoidCallback onPickSurface;
  final VoidCallback onSave;
  final VoidCallback onClear;
  final VoidCallback onHide;

  @override
  Widget build(BuildContext context) {
    return Material(
      elevation: 6,
      borderRadius: BorderRadius.circular(28),
      color: Colors.white.withValues(alpha: 0.94),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final t in Tool.values)
              IconButton(
                tooltip: t.label,
                isSelected: t == tool,
                onPressed: () => onToolChanged(t),
                icon: Icon(t.icon),
                style: IconButton.styleFrom(
                  backgroundColor:
                      t == tool ? Colors.teal.withValues(alpha: 0.18) : null,
                ),
              ),
            const Divider(height: 12, indent: 8, endIndent: 8),
            // Color swatch (hidden for eraser, which paints paper).
            if (tool != Tool.eraser)
              IconButton(
                tooltip: 'Color',
                onPressed: onPickColor,
                icon: Container(
                  width: 24,
                  height: 24,
                  decoration: BoxDecoration(
                    color: color,
                    shape: BoxShape.circle,
                    border: Border.all(color: Colors.black26),
                  ),
                ),
              ),
            // Size control shows the current base size for this tool.
            IconButton(
              tooltip: '${tool.label} size',
              onPressed: onPickSize,
              icon: SizedBox(
                width: 28,
                child: Text(
                  size.toStringAsFixed(0),
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
              ),
            ),
            // Surface picker shows the current surface.
            IconButton(
              tooltip: 'Surface: ${surface.label}',
              onPressed: onPickSurface,
              icon: Icon(surface.icon),
            ),
            const Divider(height: 12, indent: 8, endIndent: 8),
            IconButton(
                tooltip: 'Save PNG',
                onPressed: onSave,
                icon: const Icon(Icons.save_alt)),
            IconButton(
                tooltip: 'Clear',
                onPressed: onClear,
                icon: const Icon(Icons.delete_outline)),
            IconButton(
                tooltip: 'Hide toolbar',
                onPressed: onHide,
                icon: const Icon(Icons.chevron_left)),
          ],
        ),
      ),
    );
  }
}

class _ShowRailButton extends StatelessWidget {
  const _ShowRailButton({required this.onShow});
  final VoidCallback onShow;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: Material(
        elevation: 6,
        shape: const CircleBorder(),
        color: Colors.white.withValues(alpha: 0.94),
        child: IconButton(
          tooltip: 'Show toolbar',
          onPressed: onShow,
          icon: const Icon(Icons.chevron_right),
        ),
      ),
    );
  }
}
