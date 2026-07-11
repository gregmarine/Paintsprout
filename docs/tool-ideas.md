# Paintsprout — Tool & Feature Ideas

A running catalog of paint/art-app tools and features, tracked by status. This is
a **backlog for safe-keeping**, not a prioritized roadmap — no priority is implied
by ordering. Add, split, and re-status freely.

## Status legend

| Mark | Meaning |
|------|---------|
| ✅ | Done — shipped in the app |
| 🚧 | In progress |
| 🔬 | Prototyping / spike |
| ⬜ | Not started |
| ❄️ | Deferred — see [For consideration](#for-consideration-not-aligned-with-current-philosophy) |

## Guiding philosophy

Paintsprout is **WYSIWYG**: a physical artist using a digital canvas, not a digital
artist using computer tools. When a feature exists mainly to give the artist powers
they wouldn't have with real media on a real surface, it's a candidate for the
[For consideration](#for-consideration-not-aligned-with-current-philosophy) list
rather than the main backlog. Example: rotating the canvas — the intent is that you
physically rotate the tablet instead.

---

## What exists today ✅

- ✅ **Tools:** Pencil, Pen, Line, Arc, Polyline, Polyarc, Brush, Watercolor, Marker, Spray, Eraser, Magic Wand
- ✅ **Render styles:** solid, soft, grain, bristle, wash
- ✅ **Surfaces (9):** Plain, Paper, Canvas, Watercolor, Wood, Stone, Concrete, Metal, Chalkboard — each with per-surface custom parameters + per-artwork seed, and a tooth field that breaks up strokes
- ✅ **Pigment mixing:** spectral Kubelka-Munk (blue + yellow = green) at stroke time
- ✅ **Undo/redo:** unlimited, via replayable ops
- ✅ **Magic-wand selection:** select, fill/erase, frisket-constrained painting, move/scale/rotate
- ✅ **Watercolor interaction:** washes out existing paint
- ✅ **Color:** HSV wheel + swatches
- ✅ **Editable shape tools:** line, arc, polyline, polyarc (handle-edit before bake)
- ✅ **Export:** PNG to device gallery
- ✅ **True-size (1:1) output:** screen calibration to real PPI (physical-reference match), brush/tool sizes in millimetres, and DPI-stamped PNG that prints at the exact on-screen physical size — verified on paper
- ✅ **Input:** pressure + tilt, palm rejection, stylus-only

---

## Media & marking tools

### Dry media
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Chalk / pastel / conte | Most conspicuous gap — we have a chalkboard surface and nothing to draw on it with. Smudgeable. |
| ⬜ | Charcoal (vine + compressed) | Smudgeable, heavy tooth response |
| ⬜ | Crayon / wax | Wax resist over a watercolor wash — a real technique the wash system could support |
| ⬜ | Graphite stick | Broad-side shading, tilt-driven; distinct from pencil |

### Wet media
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Oil / impasto | Thick body, wet-on-wet smear, bristle drag picks up underlying paint |
| ⬜ | Acrylic / gouache | Opaque, fast-drying, layers over rather than blending in |
| ⬜ | Ink: dip pen / nib | Width from stroke direction vs. fixed nib angle — calligraphy in one tool |
| ⬜ | Fountain pen | Distinct flow/pooling behavior |
| ⬜ | Ballpoint | Skipping and pooling failure modes |
| ⬜ | Felt tip | |
| ⬜ | Highlighter | Multiplies over what's beneath |
| ⬜ | Clean-water brush | Rewets and lifts pigment — variant of machinery we already own (watercolor washes out paint) |
| ⬜ | Palette knife | Scrapes and deposits |
| ⬜ | Airbrush | Dwell-based buildup (accumulates while held still) — differs from spray's stamp-along-path |

### Modifying tools (act on existing paint)
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Smudge / smear / finger | Pushes pigment along drag direction |
| ⬜ | Blur | |
| ⬜ | Sharpen | |
| ⬜ | Dodge / Burn | |
| ⬜ | Sponge (saturate/desaturate) | |
| ⬜ | Kneaded eraser | Lifts partially rather than clearing to surface |
| ⬜ | Pressure-sensitive eraser | |
| ⬜ | Textured eraser | Respects current brush's texture |

### Brush engine
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Custom brush editor | Spacing, scatter, jitter (size/angle/hue), dual-brush, texture, tip-from-image |
| ⬜ | Stamp / scatter brushes | Foliage, grass, gravel, stars |
| ⬜ | Pattern brushes | Repeat art along a path |
| ⬜ | Brush presets library | Per-tool saved settings + favorites |
| ⬜ | Pressure-curve editor | Per tool |
| ⬜ | Stroke stabilization / streamline | Largest single improvement to perceived line quality on a tablet; noticed immediately |

---

## Shapes & construction
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Rectangle, ellipse, polygon, star | Natural siblings to existing editable-shape scaffolding |
| ⬜ | Bezier pen tool | Cubic, with off-curve control handles (arc is on-curve quadratic pull; this is the real thing) |
| ⬜ | Filled shapes | Everything is a stroke today — fill-vs-stroke is a genuine model change |
| ⬜ | Stroke styling | Dashes, arrowheads, caps/joins, tapered ends |
| ⬜ | Constrain modifiers | Snap to 15°, perfect square/circle |
| ⬜ | Ruler / straightedge / french curve / ellipse guide | Draggable physical overlays you draw *along* — very on-philosophy |
| ⬜ | Perspective guides (1/2/3-point) | |
| ⬜ | Isometric grid | |
| ⬜ | Symmetry / mirror painting | Vertical, horizontal, radial mandala — cheap, disproportionately loved |
| ⬜ | Grid and snapping | |

---

## Fill & gradient
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Paint bucket | Direct one-tap flood fill (wand-then-fill is two steps, different mental model) |
| ⬜ | Gradient tool | Linear, radial, angular, editable stops |
| ⬜ | Pattern fill | |
| ⬜ | Gradient map | As an adjustment |

---

## Selection
The wand is the only selection today.
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Rectangular / elliptical marquee | |
| ⬜ | Freehand lasso | |
| ⬜ | Polygonal lasso | |
| ⬜ | Boolean modes | Add, subtract, intersect |
| ⬜ | Select all, invert | |
| ⬜ | Edge ops | Feather, grow, shrink, smooth |
| ⬜ | Select by color range | Global, non-contiguous — the wand's opposite |
| ⬜ | Copy / cut / paste | No clipboard at all right now |
| ⬜ | Richer transforms | Skew, distort, perspective, warp/liquify mesh, flip, numeric entry |
| ⬜ | Save / restore a selection | |

---

## Layers
Biggest structural gap. Two layers today (surface, paint); paint layer is flat raster.
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Arbitrary layers | Reorder, rename, lock, hide, per-layer opacity |
| ⬜ | Blend modes | Multiply, screen, overlay, add, … |
| ⬜ | Clipping masks | |
| ⬜ | Alpha lock | Paint only where pixels already exist |
| ⬜ | Layer masks | |
| ⬜ | Adjustment layers | |
| ⬜ | Groups / folders | Merge down, duplicate, flatten |
| ⬜ | Reference layer | Line art the bucket respects while filling on a layer beneath |
| ⬜ | Onion skinning | If animation is ever on the table |

> **Design note:** layers interact awkwardly with KM pigment mixing. Real pigment
> mixing is a physical event between wet paints on one surface; layer compositing is
> an optical stack. Decide deliberately whether layers mix spectrally or composite
> conventionally.

---

## Canvas & document
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Save / reopen a project | Only PNG export today; no resumable document. Op-replay history is already a serializable document — may be closer than it looks |
| ⬜ | Autosave & crash recovery | |
| ⬜ | Multiple documents / gallery browser | |
| ⬜ | Import an image | As a layer or reference |
| ⬜ | Reference panel | Floating window with a source photo |
| ✅ | Print-accurate export (1:1) | Tool sizes in mm + DPI-stamped PNG (pHYs) → prints at the exact on-screen physical size. Needs a calibrated screen |
| ⬜ | Canvas presets | Real dimensions + DPI |
| ⬜ | Crop / resize / trim / straighten | |
| ⬜ | Export formats | JPG/WebP, layered ORA/PSD, SVG for vector shapes |
| ⬜ | Time-lapse recording | Procreate headline feature; people share the videos |

---

## Color
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Eyedropper | Can't sample a color from our own canvas — strange hole given we have pigment mixing |
| ⬜ | Foreground/background pair | With swap |
| ⬜ | User-controlled alpha | Per-tool opacity exists; user alpha does not |
| ⬜ | Saved palettes | Named, import/export |
| ⬜ | Palette from an image | Extract |
| ⬜ | Recent colors history | |
| ⬜ | Color harmony wheel | Complementary, triadic, analogous |
| ⬜ | Numeric entry | Hex, RGB, HSL sliders |
| ⬜ | Physical mixing tray | Drag pigments together, mix *before* painting, load brush from tray. KM engine already does the hard part — **most Paintsprout-shaped idea on the list** |
| ⬜ | Named real pigments | Ultramarine, Cadmium Yellow, Quinacridone Magenta, Phthalo Green — measured KM coefficients, not arbitrary RGB |
| ⬜ | Grayscale value preview | Check values |
| ⬜ | Colorblind preview | |

---

## Adjustments & filters
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Brightness / contrast | |
| ⬜ | Curves | |
| ⬜ | Levels | |
| ⬜ | Hue / saturation | |
| ⬜ | Gaussian / motion blur | |
| ⬜ | Sharpen | |
| ⬜ | Add noise / grain | |
| ⬜ | Bloom | |
| ⬜ | Liquify | |
| ⬜ | Posterize | |
| ⬜ | Halftone | |
| ⬜ | Chromatic aberration | |

> Mostly conventional and straightforward given we already run fragment shaders.
> Applied to a selection or a layer.

---

## Text
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Text tool | Fonts, kerning, text on a path. Noticed the moment someone wants to sign a piece or letter a poster |

---

## Physical simulation
Where Paintsprout could be genuinely unlike anything else — the hard parts are already committed.
| Status | Item | Notes |
|--------|------|-------|
| ⬜ | Wet-on-wet fluid diffusion | Real bleeding that evolves over time, not a static blur at stroke time |
| ⬜ | Drying timer | Pigment stays wet/reactive for seconds, then sets — timing becomes a skill |
| ⬜ | Pre-wetting the paper | A later stroke blooms |
| ⬜ | Gravity / drips | Runs when you tilt the device — accelerometer is right there |
| ⬜ | Impasto height field | Thickness → normal map; movable light source raking across it — the whole appeal of oil |
| ⬜ | Bidirectional paint pickup | Brush carries, deposits, and picks up underlying pigment; dragging through wet paint smears and contaminates the brush |
| ⬜ | Brush load | Paint runs out; reload from palette — forces the rhythm of real painting |
| ⬜ | Salt & masking fluid | Watercolor resist effects |

---

## Input & device
| Status | Item | Notes |
|--------|------|-------|
| ✅ | Screen calibration | Match a physical reference (ID card / business / index card / ruler) with the stylus to store true PPI per device; underpins 1:1 print |
| ⬜ | Stylus barrel-button mapping | Movink has buttons we're not using |
| ⬜ | Eraser end of stylus | Flips to eraser tool automatically |
| ⬜ | Speed → width tapering | For ink strokes |
| ⬜ | Radial quick menu | Long-press or button |
| ⬜ | Keyboard shortcuts | |
| ⬜ | Left-handed UI mirroring | Rail is on one side |
| ⬜ | Brush cursor outline | Shows actual tip shape + size before committing |

---

## For consideration (not aligned with current philosophy)

These are conventional **digital-artist** conveniences that conflict with the WYSIWYG /
physical-canvas philosophy. Intentionally set aside — the intent is that the artist
manipulates the physical tablet, not a virtual canvas. Kept here because eventual users
coming from other apps will expect them, and we'll remain open to reconsidering "someday."

| Status | Item | Physical-canvas stance |
|--------|------|------------------------|
| ❄️ | Canvas zoom | Move closer / use a real magnifier |
| ❄️ | Canvas pan | The canvas is the screen; there's nothing off-screen |
| ❄️ | Canvas rotation (two-finger) | Physically rotate the tablet |
| ❄️ | Flip canvas horizontally | Physical artists use a mirror to check composition |
