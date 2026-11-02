# Design

This note explains what the app does with the camera hardware and why, and where each algorithm
comes from. The code lives in four packages:

| Package | Contents | Android? |
|---|---|---|
| `imaging` | Pyramids, registration, super-resolution, focus stacking, optics | No: plain Kotlin, unit-tested on the JVM |
| `camera` | Lens discovery, Camera2 session, frame copy | Yes |
| `gl` | Live view renderer | Yes |
| `ui` + root | Compose UI, view model, coordinate geometry, gallery | Yes |

## Using several cameras

A phone with several rear cameras tempts you to fuse them. At microscope distances (5 to 10 cm)
that does not work:

- **Focus.** Most secondary cameras cannot focus that close. Ultra-wides are usually fixed-focus,
  and telephotos typically focus no closer than tens of centimetres. A defocused stream adds blur,
  not information.
- **Parallax.** Lenses 12 to 24 mm apart see a specimen 80 mm away from directions 8.5° to 16.7°
  apart. Relief on the specimen then occludes differently in each view, which no global warp can
  undo.
- **Access.** Manufacturers decide which physical cameras ordinary apps may open, and many expose
  only some of them.

So the app uses **all exposed cameras by choosing between them**. Each one is described from its
`CameraCharacteristics`: focal length, pixel pitch, closest focus, apertures and focus
calibration. Each is ranked by the specimen-side size of one pixel at its closest focus:

```
object pixel = pixel pitch × (u − f) / f,   u = 1 / LENS_INFO_MINIMUM_FOCUS_DISTANCE
```

The smallest wins and opens first; the lens button cycles through the rest. Physical cameras of a
logical multi-camera are streamed with `OutputConfiguration.setPhysicalCameraId`, and their
controls are set with `setPhysicalCameraKey`. No hidden camera IDs are probed: those are
undocumented and vary by firmware.

The extra information the app extracts comes from **time**, not from other lenses: several frames
from one camera, with sub-pixel motion between them (super-resolution) or with different focus
(stacking).

## Camera2 session

- Two outputs: a `PRIVATE` preview into the renderer's `SurfaceTexture`, capped at 1920×1440 with
  the sensor's aspect ratio, and a full-resolution `YUV_420_888` `ImageReader` for captures. "PRIV
  preview + YUV maximum" is guaranteed only at the FULL hardware level, so the app checks each pair
  with `isSessionConfigurationSupported` and steps down the YUV size until the HAL accepts it.
- **Manual focus** uses `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE`, in diopters measured from the
  front of the lens. If `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` is `UNCALIBRATED` the units are
  arbitrary, and the app shows a percentage instead of millimetres and hides the scale bar.
- **Tap-to-focus** runs one `CONTROL_AF_MODE_AUTO` trigger on the tapped region, reads the
  `LENS_FOCUS_DISTANCE` it settled on, and returns to manual focus there.
- **Zoom** sets `SCALER_CROP_REGION`, so the ISP crops the full sensor before scaling to preview
  size: zoomed previews keep real detail. Past `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` the renderer
  magnifies further.
- **Captures** take the full sensor with no crop and copy only the visible region, at full
  resolution, out of each YUV frame, so what you see is what is processed. Before a burst, AE and
  AWB are locked so every frame has the same exposure and colour.
  - Bursts use `captureBurst` with minimal noise reduction and no edge enhancement: the merge
    averages noise itself and sharpens once at the end.
  - Focus brackets move the lens with the preview stream, wait for `LENS_STATE` to report
    stationary at the target distance, and then expose with the ISP's high-quality processing.
- **Torch** is `FLASH_MODE_TORCH` in the repeating request. In auto, it turns on after about a second
  of a scene darker than EV₁₀₀ 5. It never turns itself off: once the torch lights the scene, the
  meter can no longer judge the ambient light.

## Live view

A `GLSurfaceView` renders only when a frame or a setting changes. The camera's `SurfaceTexture`
transform matrix already contains the sensor rotation (the camera service sets it on preview
streams), so the renderer applies its own zoom and then that matrix. The YUV capture path has no
such transform, so the app rotates captures by `SENSOR_ORIENTATION` itself. One fragment pass
does:

- an unsharp mask against a 3×3 binomial blur, then contrast;
- Sobel gradient magnitude of luma, for **focus peaking** (cyan) and for the **edge** filter, a
  dark-field-like rendering of relief and scratches;
- mono.

Neighbourhood taps are one preview-buffer pixel apart in the buffer's own orientation, so peaking
behaves the same at every zoom.

## Registration

`Aligner` estimates a similarity warp (scale, rotation, translation) between two frames:

1. Build a Gaussian pyramid (5-tap binomial kernel) of each frame's luma down to about 48 px.
2. At the coarsest level, search integer translations of ±6 px exhaustively. That gives a capture
   range of about ±6 × 2^levels at full resolution.
3. At every level, coarse to fine, refine with **inverse-compositional Lucas-Kanade** (Baker &
   Matthews 2004). It uses up to 30,000 textured sample pixels, a Huber weight against outliers,
   and a running brightness offset.

The scale term absorbs **focus breathing**. Moving the lens shifts magnification: for a Note10-like
4.3 mm lens, a 9-slice bracket around 80 mm changes it by about 2 %. That is about 40 px at the
edge of a 12 MP frame, far beyond what translation alone can absorb. Bursts are registered to frame 0. Focus brackets are registered **neighbour to
neighbour**, outwards from the middle frame, and the steps are composed: adjacent slices share most
of their blur while the ends of a bracket may share almost nothing. Registrations run in parallel,
bounded by available memory.

The unit test recovers a known 1.2 % scale, 0.34° rotation and (7.3, −4.6) px shift to within
0.1 px at every corner.

## Super-resolution

`SuperResolution.merge` places every luma sample of every frame at its registered sub-pixel
position on the output grid:

- **2× output: two-scale first-order kernel regression** (Takeda, Farsiu & Milanfar 2007). Each
  output pixel fits a local plane `v ≈ a + b·dx + c·dy` to the samples around it, by weighted
  least squares, at two kernel sizes:
  - The narrow kernel (1.2 output px) resolves detail, but only where samples surround the pixel,
    which needs sub-pixel motion between frames.
  - The wide kernel (2 output px) reaches the neighbouring input pixels. When frames coincide (a
    still burst) it interpolates about as well as bilinear.
  - Each pixel blends the two by the spread of the narrow kernel's sample positions, measured as
    the determinant of their weighted covariance.

  Zeroth-order averaging cannot do both: a narrow kernel turns a still burst blocky, and a wide one
  blurs a moving burst. The unit test found exactly that before this design.
- **1× output:** a robust temporal average of the frames resampled onto the reference grid, the
  classic align-and-merge denoiser.
- **Robustness:** a sample is down-weighted by `1 / (1 + d² / τ²)`. Here `d` is its difference from
  the reference, and `τ²` is (3σ + 2)² plus a term for the reference's local gradient, so
  legitimate interpolation error at edges is tolerated. σ is the noise level, estimated from the
  median absolute difference of neighbouring pixels.
- A weak prior from the bilinearly upsampled reference fills any pixel no sample reached. Chroma is
  a robust temporal average at half resolution. A light unsharp mask finishes the luma.

Test result on a band-limited synthetic scene, 8 frames, noise σ = 3:

| Input | PSNR |
|---|---|
| Moving burst, merged | 34.1 dB |
| Still burst, merged | 30.4 dB |
| One frame, bilinear 2× | 30.2 dB |

**Limits.** Phone YUV has been demosaiced, denoised and sharpened by the ISP. True multi-frame
super-resolution (Wronski et al. 2019, on Pixel phones) merges RAW Bayer frames and also replaces
demosaicing. On a tripod, Pixel phones move the OIS deliberately, which Camera2 does not let an app
do. Expect more modest resolution gains here than those papers report.

## Focus stacking

`FocusStack.fuse` uses Laplacian-pyramid fusion (Burt & Adelson 1983; Burt & Kolczynski 1993):

- 5 levels. Every band-pass coefficient of the result is a weighted mean of the frames'
  coefficients, with weights equal to the cube of the local band energy (the squared coefficient,
  blurred). When one frame is clearly sharper, this approaches max-selection. When all frames are
  alike, it averages them instead of selecting noise.
- The low-pass residual is averaged.
- Chroma takes the weights of pyramid level 1, which has exactly the chroma resolution.
- Processing runs in 512 px tiles with 64 px margins, which exceed the 4-level kernel support, so
  tiles join without seams. Memory stays bounded, and tiles run on all cores.

Bracket spacing comes from depth of field in diopters, `ΔD ≈ 2·N·c / f²`, which does not depend on
distance well inside the hyperfocal distance. With c = 2 pixels and 20 % overlap, a Note10 main
camera at f/2.4 steps about 0.58 D, which is about 3.7 mm at 80 mm. The test fuses 5 frames of a
depth ramp to 34.4 dB, against 23.2 dB for the best single frame.

## Scale

```
m = f / (u − f)                 lateral magnification, thin lens
µm per sensor pixel = pitch / m
```

`u` comes from the reported focus distance, which is measured from the front of the lens, not from
the principal plane, so this is first-order. The app labels every derived number "≈", and writes
the scale into EXIF X/Y resolution in pixels per centimetre. Calibrate against a stage micrometer
if you need real measurements.

## References

- P. J. Burt, E. H. Adelson, "The Laplacian Pyramid as a Compact Image Code", *IEEE Trans.
  Communications* 31(4), 1983.
- P. J. Burt, R. J. Kolczynski, "Enhanced image capture through fusion", *ICCV* 1993.
- S. Baker, I. Matthews, "Lucas-Kanade 20 Years On: A Unifying Framework", *IJCV* 56(3), 2004.
- H. Takeda, S. Farsiu, P. Milanfar, "Kernel Regression for Image Processing and Reconstruction",
  *IEEE Trans. Image Processing* 16(2), 2007.
- B. Wronski et al., "Handheld Multi-Frame Super-Resolution", *ACM Trans. Graphics* 38(4), 2019.
- S. W. Hasinoff et al., "Burst photography for high dynamic range and low-light imaging on mobile
  cameras", *ACM Trans. Graphics* 35(6), 2016.
- Android `CameraDevice`, `CaptureRequest` and `CameraCharacteristics` reference documentation.
