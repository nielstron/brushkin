# Brushkin

A layered image editor for Android phones, aiming at GIMP's feature set with controls designed for
touch and stylus.

## Features

**Painting.** Paintbrush, pencil, airbrush, eraser, smudge, clone, heal, dodge/burn, blur/sharpen,
bucket fill and gradient (linear/radial). Brush presets (soft, hard, chalk, flat, ink, splatter) with
size, opacity, hardness, flow, spacing, angle/aspect, smoothing and stylus pressure. Stroke opacity
works like GIMP's: overlapping dabs within one stroke never exceed it.

**Selections.** Rectangle, ellipse, free (lasso), fuzzy select, select by color and **object
select**: tap a subject to select it, or select the whole foreground (on-device ML Kit, model
provided by Google Play services). Replace/add/subtract/intersect, feather, grow, shrink, invert,
alpha/mask to selection. All painting and filters respect the selection.

**Layers.** Layer groups, 17 blend modes, opacity, visibility, lock alpha, layer masks (paint,
filter, apply, disable, from selection/alpha/grayscale), merge down, flatten, duplicate, reorder.

**Paths.** Bézier pen: tap for corners, drag for smooth points, then stroke with the current brush or
turn into a selection.

**Insert.** Images from the gallery or files, text (four styles), stickers and emoji — each placed
with move/scale/rotate handles as a new layer. Transform tool for the active layer or selection.

**Colors and filters.** Curves, Levels, Brightness-Contrast, Hue-Saturation, Color Balance,
Threshold, Posterize, Desaturate, Invert, Gaussian blur, Unsharp mask, Pixelize, RGB noise — all
with live preview.

**Image.** Flip, rotate, scale, canvas size, crop tool, crop to selection.

**Files.** Opens and saves GIMP **.xcf** (layers, groups, masks, modes; 8/16/32-bit and float
files are read) and OpenRaster **.ora**. Exports PNG, JPEG and WebP to Pictures/Brushkin or shares
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
  (`engine/Render.kt`). The instrumentation tests check both against files rendered by GIMP 3.2.
- **Isolated ML.** Object selection runs in a separate `:ml` process; a crash in the native model
  only shows a message.

## Building

Requires the Android SDK (compile SDK 36) and JDK 17+.

```sh
./gradlew :app:assembleDebug           # build
./gradlew :app:installDebug            # install on a connected device
./gradlew :app:testDebugUnitTest       # JVM tests (command search)
./gradlew :app:connectedDebugAndroidTest   # device tests (XCF/ORA vs. GIMP reference renders)
```

## Known limitations

- Painting itself (dabs within one layer) blends in sRGB; only layer compositing is linear.
- 64-bit (double) precision XCF files and fractal-compressed XCF tiles aren't supported; XCF files
  are always written as 8-bit sRGB.
- Masks are stored in .ora files with Brushkin-specific attributes that other apps ignore.
- Object selection needs Google Play services. ML Kit's per-subject masks crash in the current
  beta, so the tapped object is isolated from the foreground mask instead.
- Imported images are limited to 4096 px on the long side.
