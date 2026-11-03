# Brushkin

A layered image editor for Android phones, aiming at GIMP's feature set with controls designed for
touch and stylus.

## Features

**Painting.** Paintbrush, pencil, airbrush, eraser, smudge, clone, heal, perspective clone, ink,
MyPaint brush, dodge/burn, blur/sharpen, bucket fill (color or pattern) and gradient (linear/radial,
any gradient). Brush presets plus a brush editor (circle/square/diamond, spikes, aspect, angle,
hardness) with size, opacity, flow, spacing, smoothing and stylus pressure. Paint dynamics: speed,
randomness and stylus tilt can drive size, opacity and angle. Symmetry painting (mirror, both axes,
mandala). Stroke opacity works like GIMP's: overlapping dabs within one stroke never exceed it.

**Resources.** Imports GIMP brushes (.gbr, animated .gih), gradients (.ggr), palettes (.gpl),
patterns (.pat) and MyPaint brushes (.myb). Gradient and palette editors; new brushes and patterns
from the selection.

**Selections.** Rectangle (optionally rounded), ellipse, free (lasso), fuzzy select, select by
color, Intelligent Scissors (edge-following live wire), foreground select (refines a rough outline)
and **object select**: tap a subject to select it, or select the whole foreground (on-device ML Kit,
model provided by Google Play services). Replace/add/subtract/intersect, feather, grow, shrink,
border, invert, alpha/mask to selection, quick mask (paint the selection with any brush), and saved
channels. All painting and filters respect the selection.

**Layers.** Layer groups (isolated or pass-through), 17 blend modes, opacity, visibility, lock
alpha, lock position, linked layers, layer masks (paint, filter, apply, disable, from
selection/alpha/grayscale), editable text layers (also along a path, with a resizable text box),
merge down, flatten, duplicate, reorder. Layers can be smaller than the image (a layer boundary that
clips painting) or reach past it (content moved off the canvas is kept). Non-destructive filters:
any filter can be added to a layer as an effect, toggled, removed or merged later.

**Paths.** Bézier pen: tap for corners, drag for smooth points, then stroke with the current brush or
turn into a selection.

**Insert.** Images from the gallery or files, text (four styles), stickers and every system emoji
(full picker with categories and skin tones) — each placed with move/scale/rotate handles as a new
layer.

**Transform.** Separate Move (drag, applies on release), Scale, Rotate, Flip, Shear, Perspective,
3D Transform, Handle Transform, Warp, Cage and Align tools, acting on the active layer's content or
the selected part of it.

**Guides, grid, rulers.** Drag guides out of the rulers, show a grid, and snap shapes, crops, paths,
text and moved layers to guides and grid lines.

**Colors and filters.** Curves, Levels, Brightness-Contrast, Hue-Saturation, Color Balance,
Colorize, Channel Mixer, Exposure, Shadows-Highlights, White Balance, Stretch Contrast, Threshold,
Posterize, Desaturate, Invert, Color to Alpha, Gaussian and motion blur, Unsharp mask, Noise
reduction, Pixelize, RGB noise, Drop shadow, Vignette, Ripple, Whirl & Pinch and Lens distortion —
all with live preview, applied directly or as a layer effect.

**Image.** Flip, rotate, scale, canvas size, crop tool, crop to selection.

**Files.** Opens and saves GIMP **.xcf** (layers with their own size and offset, groups, masks,
modes, guides, channels, the selection; 8/16/32-bit integer and 16/32/64-bit float files are read)
and OpenRaster **.ora**. Exports PNG, JPEG and WebP to Pictures/Brushkin or shares
them. Autosaves a few seconds after every edit.

**Touch controls.** Two fingers pan/zoom/rotate the view (rotation snaps straight); two-finger tap
undoes, three-finger tap redoes; long-press picks a color (or sets the clone source). GIMP's
modifier keys become chips: *Line* (Shift), *Pick* (Ctrl), *Erase*, *Add/Subtract/Intersect*,
*From center*, *Fixed 1:1*. A magnifier loupe appears while drawing or selecting. Every command is
reachable from a searchable menu; search ranks with BM25 over plain-language descriptions, so
"make it brighter" finds Curves and Levels.

## Architecture

- **Tiled, copy-on-write pixels** (`engine/Tiles.kt`). Layers are 256×256 tile bitmaps; an edit
  copies only the tiles it touches. Undo/redo swap tile references (`engine/History.kt`), so they
  are instant and memory-proportional to what changed.
- **One engine thread** (`engine/Engine.kt`). Every document mutation is posted to a dedicated
  thread; the UI thread only reads immutable snapshots, so the interface never blocks. Heavy
  filters fan out to all cores, and previews run on a downscaled proxy with only the newest request
  processed.
- **Linear-light compositing**, like GIMP 2.10+. The display is an OpenGL ES 3 renderer
  (`ui/GlRenderer.kt`): tiles are linearized in the shader and layers composited in half-float
  buffers with the W3C blend formulas; masks, groups and the loupe go through the same pipeline.
  Export, color picking and merging do the same math on the CPU in a half-float linear bitmap
  (`engine/Render.kt`), and paint is blended onto layers in linear light too. The
  instrumentation tests check the output against files rendered by GIMP 3.2.
- **Large images.** Filters are applied in overlapping horizontal bands, so a 6000×4000 photo is
  processed without holding several full-size copies in memory.
- **Isolated ML.** Object selection runs in a separate `:ml` process; a crash in the native model
  only shows a message.

## Building

Requires the Android SDK (compile SDK 36) and JDK 17+.

```sh
./gradlew :app:assembleDebug           # build
./gradlew :app:installDebug            # install on a connected device
./gradlew :app:testDebugUnitTest       # JVM tests (command search)
./gradlew :app:connectedDebugAndroidTest   # device tests: XCF/ORA vs. GIMP renders, every tool and filter
```

## Known limitations

- Pixels are stored at 8 bits per channel (GIMP's default precision). Higher-precision XCF files
  are read and converted; XCF files are written as 8-bit sRGB, which is lossless for Brushkin's
  data.
- XCF's reserved "fractal" compression isn't supported (no GIMP version has ever written it).
- Images larger than 8192 px or 40 megapixels are scaled down on import (with a message).
- Masks, saved channels, guides and layer effects are stored in .ora files with Brushkin-specific
  attributes that other apps ignore.
- Layer effects are baked into the pixels when saving .xcf (GIMP keeps its own filter graph).
- MyPaint brushes are mapped onto Brushkin's dab engine (size, opacity, hardness, spacing, ellipse,
  smoothing, pressure/speed/random inputs); effects without an equivalent are ignored. Color GIMP
  brushes paint with the current color.
- Object selection needs Google Play services. ML Kit's per-subject masks crash in the current
  beta, so the tapped object is isolated from the foreground mask instead; subjects that touch
  each other are selected together.
