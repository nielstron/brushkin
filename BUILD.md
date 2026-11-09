## Build

Use JDK 21. Install Python 3.12+, pkg-config, Meson 1.12.1, and Ninja (for example,
`uv tool install meson==1.12.1` and `brew install pkg-config ninja` on macOS).
The Android SDK needs NDK 27.1.12297006 and CMake 3.22.1. The first native build
also downloads and compiles GEGL (the gegl-opencl fork), babl 0.1.128,
GLib 2.84.4, and json-glib 1.10.8 for both ARM architectures; subsequent builds
reuse the cache. `tools/build_gegl.py` pins the GEGL commit, source checksums, and
the libffi dependency revision. To build a local GEGL checkout instead, set
`BRUSHKIN_GEGL_SOURCE=<path>`; it builds that checkout's committed HEAD.
`BRUSHKIN_BUILD_JOBS` sets the native build's parallelism (default 6).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Device tests (excluding the hardware-only survey):

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=io.github.nielstron.brushkin.OpenClSurveyTest
```

To install a separate preview without replacing the normal app, build with `-PopenClPrototype=true`;
this changes only the debug app ID/version suffix, not which filters are accelerated.
`OpenClPrototypeTest` checks enabled GPU paths and CPU fallback;
`OpenClSurveyTest` benchmarks OpenCL candidates and requires an
OpenCL-capable device. Use `normalRoute=true` to measure the app's allowlist
and `timeAlpha=true` to time transparent inputs. Run the survey explicitly
on an OpenCL-capable device. `tools/export_mobilesam.py` and
`tools/export_models.py` reproduce the model files.

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
