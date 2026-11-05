<div align="center">
  <img src="docs/app-icon.svg" width="72" height="72" alt="Brushkin app icon">
</div>

<p align="center"><strong>Brushkin</strong></p>
<div align="center"><a href="https://komistore.app/app/?repo=nielstron/brushkin"><img src="https://img.shields.io/github/v/release/nielstron/brushkin?label=komi%20store"></a></div>

-----

A layered image editor for Android with GIMP's toolbox, redesigned for fingers and styluses:

- Insert other images, emojis, stickers and text.
- Paint with brushes, pencil, airbrush, ink and MyPaint-style brushes, plus smudge, clone, heal, dodge/burn and symmetry painting.
- Select with rectangle, lasso, fuzzy, Intelligent Scissors and quick mask.
- Lightweight AI tools: select objects, remove objects, background depth blur, upscale, denoise and deblur.
- Transform with move, scale, rotate, perspective, warp, cage and more, snapping to guides and a grid.
- Over 30 filters with live preview: curves, levels, colorize, blurs, noise reduction, drop shadow, distortions…
- Work with layers, groups, blend modes, masks, editable text and non-destructive filters, and import GIMP brushes, gradients, palettes and patterns.
- Sharing, export PNG/JPEG/WebP/GIF, export as WhatsApp/Telegram Sticker, open and save GIMP `.xcf` and OpenRaster `.ora`.


## Screenshots

<div align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="200" alt="Canvas with the brush tool">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="200" alt="Tool palette">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="200" alt="Layers">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="200" alt="Color picker">
</div>

## Installation

Install it with [Komi Store](https://komistore.app) (formerly GitHub Store), which also keeps it updated, or download the APK from the [Releases page](https://github.com/nielstron/brushkin/releases). F-Droid submission pending.

The AI tools use open models that aren't part of the APK. Each one is downloaded the first time its tool is used, after asking, and kept in the app's cache: [MobileSAM](https://github.com/ChaoningZhang/MobileSAM) (object selection), [MI-GAN](https://github.com/Picsart-AI-Research/MI-GAN) (object removal), [ZipDepth](https://github.com/fabiotosi92/ZipDepth) (depth of field), [PLKSR-Rep](https://github.com/Innocentlove321/ntire2026-mobile-realworld-sr-vipsl19) (upscaling) and [NAFNet](https://github.com/megvii-research/NAFNet) (denoise, deblur). They run on the GPU where possible.

## Build

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Device tests: `./gradlew :app:connectedDebugAndroidTest`. `tools/export_mobilesam.py` and `tools/export_models.py` reproduce the model files.

The default build stays ad-free and contains only free software. The Google Play distribution
includes a small banner below the title bar and a one-time ad-removal purchase (US$5 base price;
Google Play shows the local price). Build it explicitly with:

```sh
./gradlew -PplayStore=true :app:testDebugUnitTest :app:assembleDebug
./gradlew -PplayStore=true :app:bundleRelease
```

Debug Play builds use Google's test ads. Production AdMob IDs and the Play billing verification
public key are in `app/src/play/res/values/store.xml`. The non-consumable product ID is
`remove_ads`. The menu includes purchase restoration and, where required, ad privacy choices.
Signing still uses the `BRUSHKIN_KEYSTORE`, `BRUSHKIN_KEYSTORE_PASSWORD`, `BRUSHKIN_KEY_ALIAS`
and `BRUSHKIN_KEY_PASSWORD` environment variables. Keep private signing material outside the repository.

## License

GPL-3.0-or-later. Emoji names and keywords come from Unicode CLDR (Unicode License v3); the meme font is Anton (SIL Open Font License 1.1); the downloadable models are Apache-2.0 (MobileSAM) and MIT (the others).
