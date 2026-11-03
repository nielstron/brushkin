<div align="center">
  <img src="docs/app-icon.svg" width="72" height="72" alt="Brushkin app icon">
</div>

<p align="center"><strong>Brushkin</strong></p>

-----

A layered image editor for Android with GIMP's toolbox, redesigned for fingers and styluses:

- Insert other images, emojis, stickers and text.
- Paint with brushes, pencil, airbrush, ink and MyPaint-style brushes, plus smudge, clone, heal, dodge/burn and symmetry painting.
- Select with rectangle, lasso, fuzzy, Intelligent Scissors and quick mask.
- On-device AI, no cloud: select objects with a tap and refine them, remove objects, blur the background by depth, upscale ×2/×4, denoise and deblur.
- Work with layers, groups, blend modes, masks, editable text and non-destructive filters.
- Transform with move, scale, rotate, perspective, warp, cage and more, snapping to guides and a grid.
- Over 30 filters with live preview: curves, levels, colorize, blurs, noise reduction, drop shadow, distortions…
- Open and save GIMP `.xcf` and OpenRaster `.ora`, export PNG/JPEG/WebP, and import GIMP brushes, gradients, palettes and patterns.


## Screenshots

<div align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="200" alt="Canvas with the brush tool">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="200" alt="Tool palette">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="200" alt="Layers">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="200" alt="Color picker">
</div>

## Installation

Install the APK from the [Releases page](https://github.com/nielstron/brushkin/releases) (F-Droid submission pending).

The AI tools use open models that aren't part of the APK. Each one is downloaded the first time its tool is used, after asking, and kept in the app's cache: [MobileSAM](https://github.com/ChaoningZhang/MobileSAM) (object selection), [MI-GAN](https://github.com/Picsart-AI-Research/MI-GAN) (object removal), [ZipDepth](https://github.com/fabiotosi92/ZipDepth) (depth of field), [PLKSR-Rep](https://github.com/Innocentlove321/ntire2026-mobile-realworld-sr-vipsl19) (upscaling) and [NAFNet](https://github.com/megvii-research/NAFNet) (denoise, deblur). They run on the GPU where possible.

## Build

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Device tests: `./gradlew :app:connectedDebugAndroidTest`. `tools/export_mobilesam.py` and `tools/export_models.py` reproduce the model files.

## License

GPL-3.0-or-later. Emoji names and keywords come from Unicode CLDR (Unicode License v3); the downloadable models are Apache-2.0 (MobileSAM) and MIT (the others).
