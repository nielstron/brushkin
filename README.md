<div align="center">
  <img src="docs/app-icon.svg" width="72" height="72" alt="Brushkin app icon">
</div>

<p align="center"><strong>Brushkin</strong></p>

-----

A layered image editor for Android with GIMP's toolbox, redesigned for fingers and styluses:

- Paint with brushes, pencil, airbrush, ink and MyPaint-style brushes, plus smudge, clone, heal, dodge/burn and symmetry painting.
- Select with rectangle, lasso, fuzzy, Intelligent Scissors, foreground select, quick mask and on-device object selection.
- Work with layers, groups, blend modes, masks, editable text and non-destructive filters.
- Transform with move, scale, rotate, perspective, warp, cage and more, snapping to guides and a grid.
- Over 30 filters with live preview: curves, levels, colorize, blurs, noise reduction, drop shadow, distortions…
- Open and save GIMP `.xcf` and OpenRaster `.ora`, export PNG/JPEG/WebP, and import GIMP brushes, gradients, palettes and patterns.

Everything is composited in linear light on the GPU, like GIMP, and heavy work never blocks the interface.

## Screenshots

<div align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="200" alt="Canvas with the brush tool">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="200" alt="Tool palette">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="200" alt="Layers">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="200" alt="Color picker">
</div>

## Installation

Install the APK from the [Releases page](https://github.com/nielstron/brushkin/releases). `brushkin-<version>.apk` includes object selection via Google Play services; `brushkin-<version>-foss.apk` contains only free software (this is also the version submitted to F-Droid).

## Build

```sh
./gradlew :app:testFullDebugUnitTest :app:assembleFullDebug
```

Use the `foss` flavor (`assembleFossDebug`) for a build without Google Play services. Device tests: `./gradlew :app:connectedFullDebugAndroidTest`.

## License

GPL-3.0-or-later. Emoji names and keywords come from Unicode CLDR (Unicode License v3).
