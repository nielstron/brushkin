<div align="center">
  <img src="docs/app-icon.svg" width="72" height="72" alt="Brushkin app icon">
</div>

<p align="center"><strong>Brushkin</strong></p>
<div align="center"><a href="https://komistore.app/app/?repo=nielstron/brushkin"><img src="https://img.shields.io/github/v/release/nielstron/brushkin?label=komi%20store"></a></div>

-----

A layered image editor for Android, inspired by GIMP and designed for fingers and styluses:

- Insert other images, emojis, stickers and text.
- Paint with brushes, pencil, airbrush, ink and MyPaint-style brushes, plus smudge, clone, heal, dodge/burn and symmetry painting.
- Work on several images at once in tabs.
- Select with rectangle, lasso, fuzzy, Intelligent Scissors and quick mask.
- Lightweight AI tools: select objects, remove objects, background depth blur, upscale, denoise and deblur.
- Transform with move, scale, rotate, perspective, warp, cage and more, snapping to guides and a grid.
- Color adjustments and filters with live preview, including GIMP’s upstream GEGL operations: curves, levels, blur, noise, edge detection, lighting, distortions and artistic effects.
- Work with layers, groups, blend modes, masks and editable text; import GIMP brushes, gradients, palettes and patterns. Use **Effect** mode for editable, non-destructive filters.
- Share images, save projects as GIMP `.xcf` or OpenRaster `.ora`, and export PNG, JPEG, WebP or GIF, and send custom stickers to WhatsApp or Telegram.

## Screenshots

<div align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="150" alt="Drawing with the brush tool">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="150" alt="Tab grid with a drawing and a photo open side by side">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="150" alt="Tool palette">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="150" alt="Layers">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="150" alt="Color picker">
</div>

## Demos

<div align="center">
  <img src="docs/demo.gif" width="200" alt="Drawing a scene: a sailboat emoji, a blurred sun, filled water and brush waves">
  <img src="docs/brushkin-demo.gif" width="200" alt="Editing a portrait: selecting the face with object selection, pixelizing it, drawing a meme face in a new tab, then pasting it onto the photo as a layer">
</div>

## Installation

Install it with [Komi Store](https://komistore.app) or download the APK from the [Releases page](https://github.com/nielstron/brushkin/releases).

## Filters and performance

Brushkin builds its filters from [gegl-opencl](https://github.com/nielstron/gegl-opencl),
a fork of GEGL master with fixed and new OpenCL kernels. 


## License

GPL-3.0-or-later. Emoji names and keywords come from Unicode CLDR (Unicode License v3); the meme font is Anton (SIL Open Font License 1.1); the downloadable models are Apache-2.0 (MobileSAM) and MIT (the others).
