# Microscope

A free Android app that turns a phone's rear cameras into a close-up inspection tool: manual focus
in millimetres, deep zoom, focus peaking, an approximate scale bar, and two computational capture
modes that do more than one frame can.

- **Live view on the GPU:** sharpening, focus peaking, and colour, mono or edge (dark-field-like)
  rendering, in one OpenGL ES 3 pass.
- **Exposure compensation** with a zebra that stripes clipped highlights, for shiny metal and
  solder.
- **Measuring:** drag a line across the specimen to read its length. Calibrate once against a
  ruler and readings at that focus are exact to the calibration, not ≈.
- **Manual focus** in physical distance where the phone reports it, plus tap-to-focus that hands
  over to manual. The slider starts at the lens's closest focus.
- **Zoom up to 16×.** The camera's ISP crops the sensor first, so zoomed views keep real detail;
  only past the ISP's limit does the GPU magnify further (the readout turns amber).
- **Super-resolution:** a handheld burst of 4, 8 or 12 frames, registered to sub-pixel accuracy
  and merged onto a 2× finer grid. It uses the hand tremor between frames for extra detail and
  averages away noise.
- **Focus stacking:** 5, 9 or 15 frames bracketed around your focus, one depth of field apart,
  fused into a single all-in-focus picture.
- **Every lens the phone exposes.** Lenses are ranked by how small a specimen pixel they can
  resolve at closest focus, and the best one opens first.
- **Torch:** off, on, or auto (turns on when the scene is dark). The aperture switches too on
  phones that have a variable aperture, such as the Galaxy S9/S10/Note10 main cameras.
- **Waits for the phone to settle** after you press the shutter (gyroscope), so the press itself
  does not blur the picture.
- **Saves JPEGs** to *Pictures/Microscope* with the scale in EXIF (X/Y resolution, in pixels per
  centimetre on the specimen) and a plain-text description of how each picture was made.

**One permission: the camera.** No network permission, no analytics, no ads, no tracking. CI
fails any build that adds `INTERNET`. Licensed under the [GPL-3.0](LICENSE).

It needs Android 10 (API 29) or newer and OpenGL ES 3.0. It was written for, and its numbers were
checked against, a Galaxy Note10 (SM-N970F).

## How to use it

1. Hold the phone 5 to 10 cm above the specimen, or rest it on a stand. Closer than the lens's
   minimum focus distance nothing will be sharp.
2. Tap the specimen to focus, then fine-tune with the slider. Cyan peaking marks what is in focus.
3. Pinch or slide to zoom, and drag to pan. Double-tap resets the view.
4. If highlights are blown out, tap **EV** and lower it until the zebra stripes go.
5. Pick a mode and press the shutter. The app waits up to 1.5 s for the phone to stop shaking:
   - **Photo:** one frame, instantly.
   - **Super-res:** hold the phone by hand. A rigid stand gives no sub-pixel motion, so the burst
     can only denoise. Tap the caption to change the frame count.
   - **Stack:** use a stand. The caption shows how deep the stack reaches.

The scale bar and the "WD"/"FOV" readout come from thin-lens optics and the focus distance the
camera reports. They are **approximate** (±10 % or worse), and are hidden on phones that report
focus in arbitrary units.

To measure, tap the ruler button and drag the ends of the line. To calibrate, put a known length
under the lens (steel-ruler divisions, a stage micrometer, IC pin pitch: 1.27 mm for SOIC), lay
the line across it, tap **Calibrate** and enter the length. The calibration is kept per lens. It
is exact at the focus setting where you made it (the "≈" disappears) and corrects the estimate at
other settings. It also works on phones whose focus units are arbitrary, at that one setting.

## How it works

The design and the algorithms, with references, are in [docs/DESIGN.md](docs/DESIGN.md). In
short:

| Stage | Method |
|---|---|
| Registration | Coarse-to-fine Gaussian pyramid, exhaustive search at the top, then inverse-compositional Lucas-Kanade with a similarity warp (Baker & Matthews 2004) |
| Super-resolution | Forward-mapped samples, two-scale first-order kernel regression (Takeda, Farsiu & Milanfar 2007) with edge-steered kernels (Wronski et al. 2019), robust weights against motion |
| Focus stacking | Laplacian-pyramid fusion (Burt & Adelson 1983) with energy-weighted coefficients, tiled across all cores |
| Live view | OpenGL ES 3 external-texture shader: unsharp mask, Sobel peaking, clipping zebra |

The imaging code (`app/src/main/java/.../imaging`) is plain Kotlin with no Android dependencies.
Its unit tests check each algorithm against ground truth on synthetic scenes. Super-resolution
must beat both a single-frame upscale and the same burst without motion, and the focus stack must
beat every input frame.

## Install

Download `microscope-<version>.apk` from the [latest release](../../releases/latest) and open it
on your phone, or install it from a computer:

```bash
adb install -r microscope-0.2.0.apk
```

Releases are signed with this certificate. Check it before installing if you got the APK from
anywhere else, e.g. with `apksigner verify --print-certs` or the AppVerifier app:

```
CN=Foad S. Farimani, O=io.github.foadsf.microscope
SHA-256: 27:2D:66:4C:7B:D4:1A:9B:AB:C3:92:25:3F:E5:E8:2F:8E:48:C1:26:89:4D:A7:76:3F:A7:A2:83:7E:79:47:F9
```

CI also uploads `app-debug.apk` from every [build run](../../actions/workflows/build.yml) (under
*Artifacts*). It is fine for a quick look, but use a release for real work. Debug builds are
debuggable, which makes Android run the pixel loops much slower: on the emulator, a 9-frame stack
took 42 s in debug and 27 s in release. A debug build and a release are signed with different
keys, so uninstall one before installing the other.

## Build

Needs Java 17+ and an Android SDK with **platform `android-37.0`** and **build-tools `37.0.0`**.
That is only the *compile* SDK: `targetSdk` is 35 and `minSdk` is 29.

```bash
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
```
```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleDebug testDebugUnitTest
```

The APK lands in `app/build/outputs/apk/debug/`. `./gradlew assembleRelease` makes a minified
release (about 1.8 MB), unsigned unless you configure a key (see [Signing](#signing)). The Gradle wrapper fetches Gradle 9.8.1 and checks
its SHA-256. Every plugin and library version is pinned in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

To time each pipeline stage at phone-like frame sizes:

```bash
./gradlew testDebugUnitTest -Pbench=1 --tests '*BenchmarkTest*' -i
```

### Signing

Release builds are signed only when you give Gradle a properties file with `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`: pass `-Pmicroscope.signing=/path/to/file`, or put a
`keystore.properties` next to `settings.gradle.kts` (gitignored). Without one, `assembleRelease`
produces `app-release-unsigned.apk`, which is what CI and F-Droid want. The release key itself
lives outside the repository.

Debug builds are signed with your machine's own `~/.android/debug.keystore`. To make builds from
several machines update one installed copy, put a shared `debug.keystore` (alias
`androiddebugkey`, passwords `android`) next to `settings.gradle.kts`. It is gitignored: **never
commit a signing key here.**

## Limits

- **Third-party apps only see the cameras the manufacturer exposes.** Samsung, for example, hides
  the Note10's telephoto from ordinary apps. The app uses whatever Camera2 lists, including the
  physical cameras of a logical multi-camera, and probes no hidden IDs.
- **No fusion across different lenses.** At 5 to 10 cm the lenses sit 1 to 2 cm apart, which gives
  about 8 to 17° of parallax, and most of them cannot focus that close. Each capture uses one lens;
  the app picks the right one.
- **Super-resolution works on processed YUV, not RAW.** The resolution gain is real but modest; the
  noise reduction is the dependable part. On a stand there is no motion to exploit.
- **Speed.** Fusion runs on all CPU cores in Kotlin. Measured on an 8-thread laptop JVM: a 9-frame
  stack of 2.6 MP takes 5 to 6 s, and a 2× super-resolution merge of an 8-frame 2.6 MP burst
  takes 9 to 10 s (alignment included). A phone will be somewhat slower. Zoom in to process a smaller area faster.
- **The NPU is not used.** There is no trained model worth shipping, and Android's NNAPI is
  deprecated. The GPU runs the live view; the CPU runs the fusion.
- **Portrait only.** The sensor-to-screen mapping assumes it.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).
