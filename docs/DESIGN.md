# Design

This note explains what the app does with the camera hardware and why, and where each algorithm
comes from. The code lives in four packages:

| Package | Contents | Android? |
|---|---|---|
| `imaging` | Pyramids, registration, super-resolution, focus stacking, surface height, multi-view geometry, optics | No: plain Kotlin, unit-tested on the JVM |
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
- **Exposure compensation** is `CONTROL_AE_EXPOSURE_COMPENSATION` within
  `CONTROL_AE_COMPENSATION_RANGE`. It stays in force while AE is locked for a capture.
- **Steady hold.** Pressing the shutter jolts the phone. Before capturing, the app waits (up to
  1.5 s) until the gyroscope's angular speed has stayed for 120 ms below the speed that would move
  the image 1.5 px during one exposure: `ω = 1.5 · pitch / (f · t)`. Rotation moves the image by
  `ω·t·f / pitch` pixels whatever the working distance. The threshold never goes below 0.03 rad/s
  (≈ 1.7°/s), or a handheld phone would rarely qualify, and the burst merge wants some tremor
  anyway. Translation at macro distances also blurs, and a gyroscope cannot see it.
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
- mono;
- while the exposure slider is open, a **zebra**: diagonal screen-space stripes where any colour
  channel exceeds 0.98, so clipped highlights are visible while setting exposure.

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

For tilted views (Tilt mode) `Aligner.alignHomography` continues to the full **eight-parameter
homography**, the exact map between two views of a plane. The coarse levels hold too few pixels to
pin down eight parameters, so they run the similarity search and refinement above; the levels at
least 128 px across then refine all eight by inverse-compositional Lucas-Kanade in coordinates
normalised to about ±1, so the perspective terms are conditioned like the others. On a flat
specimen seen from a 6° tilt it is within 0.006 px at every corner; the similarity model misses by
1.5 px there.

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
  - The narrow kernel is **steered** along edges (Wronski et al. 2019). It takes its shape from the
    reference frame's smoothed structure tensor at the sample's position: narrowed across the edge
    by up to 35 % and stretched along it by up to 100 % (capped at the wide radius), in proportion
    to the coherence (λ₁ − λ₂)/(λ₁ + λ₂). The gradient energy must also exceed the noise, so flat
    or noisy areas keep the round kernel. Shapes are computed once per reference pixel.

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
| Moving burst, merged | 34.5 dB |
| Still burst, merged | 30.4 dB |
| One frame, bilinear 2× | 30.2 dB |

Steering, against the same merge with round kernels, adds about 1 dB on a sharp-edged scene
(38.0 vs 37.0 dB) and 0.3 dB on the band-limited texture (35.4 vs 35.2 dB). Once the JIT has
warmed up, it costs no measurable time on the desktop JVM. The test requires a gain on edges and
no loss on texture.

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

## Surface height

`DepthFromFocus` measures the surface from the same registered bracket by **focus variation**, also
called shape from focus (Nayar & Nakagawa 1994). It is the method ISO 25178-606 standardises for
areal topography, and the one focus-variation microscopes use. A phone has the essential part: a
lens whose focus it can step.

1. Each slice is resampled onto the reference with the stack's registration, so focus breathing
   does not move features between slices.
2. Its focus measure is the squared modified Laplacian, `(|2I − I₋ₓ − I₊ₓ| + |2I − I₋ᵧ − I₊ᵧ|)²`,
   pooled down to a grid of at most 0.6 MP and then over a window of σ ≈ 1.7 cells.
3. Each cell's focus curve across the slices peaks where that part of the surface was in focus.
   A Gaussian through the best slice and its two neighbours gives the peak to a fraction of a
   slice. The bracket is evenly spaced in diopters, so that fraction converts to a focus distance,
   and the distance converts to height above the farthest point (its 99th percentile, so stray
   cells do not set the zero).
4. A cell is reported only if the curve really peaks:
   - the peak is not at either end of the bracket;
   - it is at least 2× the curve's mean;
   - the curve falls for two slices on each side, as defocus does.

   Pooled noise alone passes these tests at under 0.5 % of cells. Untextured areas have no curve
   and stay unknown.

Outputs: a viridis picture the size of the fused one, with unknown cells dark grey and a legend,
and a binary PLY point cloud (x, y, z in mm, coloured from the fused picture).

Tests on a 7-slice depth ramp with σ = 1.5 noise:

| Grid | Slice error (RMS) | Coverage |
|---|---|---|
| Full resolution | 0.07 | 92 % |
| Half resolution | 0.06 | 99.5 % |

Without the Gaussian interpolation the error is 0.28 slices, and the test fails.

**What that means in millimetres.** A slice is 0.8 of the depth of field: about 3.7 mm at 80 mm
on a Note10 at f/2.4, and 2.3 mm at f/1.5. A tenth of a slice is therefore 0.2 to 0.4 mm. This is a
coarse profilometer: it shows relief, warp and steps, not micrometre roughness.

**Limits.**

- Heights depend on the focus distances the HAL reports, so they carry its error and are marked
  "≈".
- The map is only as good as the stack's registration. Unregistered shifts of up to ±16 px on a
  textured scene raised the error only to 0.2 slices. But on the emulator, whose fake camera jumps
  between frames, sparse edges produced false heights, just as they ghost the fused picture. Use a
  stand.

## Surface height from tilted views

Tilt mode measures the surface by **plane-plus-parallax** (Kumar, Anandan & Hanna 1994; Irani &
Anandan 1996) from a square-on reference view and a few views tilted by hand about the specimen.
Parallax resolves depth far more finely than focus does. Tilted 5°, a point 1 mm high moves by
about 1 mm · sin 5° ≈ 87 µm sideways against the plane, 3.5 px at 24.6 µm per pixel (Note10, 80 mm).

Every view k sees the specimen's mean plane through a homography, and every point off the plane
through the same homography plus a parallax along the view's epipolar direction:

```
x_k ≃ H_k·x + γ·K·t_k / d       H_k = K·(R_k + (t_k / d)·nᵀ)·K⁻¹       γ = height / depth
```

1. **Registration.** Each view is registered to the reference with the eight-parameter homography.
2. **Pose, without decomposition.** R_k comes from the gyroscope (integrated at 200 Hz and read at
   the frame's exposure time; see below), and the plane normal n is taken as the reference optical
   axis. Then `K⁻¹·H_k·K = λ·(R_k + t'·nᵀ)` is linear in the unknowns: the first two columns give
   the scale λ, the third gives t' = t_k / d. Decomposing H alone has a twofold ambiguity, and in a
   narrow field it cannot tell a pan from a sideways shift; the gyro settles both.
3. **Gyro check.** How far λ·(the first two columns) misses R measures how well gyro and image
   agree. The perspective row counts only as much as it moves the frame's corners, because a narrow
   field hardly constrains it. A view that misses by more than 1.1° is dropped ("gyro and image
   disagree"), and so is one whose baseline |t'ₓᵧ| is under 0.02, about 1.1° of tilt about the
   specimen ("too little parallax"). Turning the phone about its own lens instead of about the
   specimen gives no baseline at all.
4. **Matching.** γ is found per grid cell by **multi-baseline** matching (Okutomi & Kanade 1993):
   one γ predicts the cell's position in every view at once, and the cost over γ is the views' mean
   1 − ZNCC over a 7 × 7 window (larger on coarse grids). It runs coarse to fine: a full search of
   ±3 mm at the pyramid level where that is at most 4 px, then ±1.5 px around the level above,
   each refined to a fraction of a step by a parabola. Between levels each cell takes the median of
   its neighbours, so one bad match cannot steer the narrower search below. The grid is at most
   0.25 MP, and frames above 4 MP are matched at half resolution.
5. **Glare.** The phone's LED moves with it, so a specular highlight moves between views.
   Saturated pixels and a 2 px margin are left out of every match, and at each γ the worst third of
   the views (at least one) is dropped (Kang, Szeliski & Chai 2001), so a highlight in one or two
   views does not pull the match.
6. **Checks.** A cell is reported only if the kept views correlate at ZNCC ≥ 0.6, the cost has a
   real minimum inside the search, at least half the views see it, and it agrees within 1.5 px of
   parallax with the median of at least five matched neighbours.
7. **Heights.** h = γ·d / (1 + γ), with d the working distance from the reported focus, reported
   above the lowest point (1st percentile) like focus variation. K uses the image distance
   v = u·f / (u − f), not f: close up the image plane sits 5 % behind the focal plane for a 4.3 mm
   lens at 80 mm.

Heights are relative to the plane the registration found, the specimen's dominant plane, so the
specimen's tilt to the camera is removed. Outputs are the same as Stack mode's: a viridis picture,
saved beside the reference view, and a PLY point cloud.

**Capture.** The shutter takes the reference once the phone is steady, then exposure and white
balance stay locked for the series. A guide shows the ring of tilts to visit (3–8°, in 4, 6 or 8
sectors) and a dot for the current tilt from the gyroscope. A view is taken automatically when the
dot is in an empty sector and the phone has been steady for 150 ms. The series ends when every
sector is filled, or at a second press of the shutter (at least two tilted views are needed).
Where the camera's frame timestamps share the gyroscope's clock (`SENSOR_INFO_TIMESTAMP_SOURCE`
REALTIME), the orientation is read at the frame's exposure; otherwise a view is retaken if the
phone turned by more than 0.17° during the capture.

**Tests** render a relief by ray casting from known hand-tilted poses, as a Note10 main camera
sees it at 80 mm: two bumps (+0.5 mm, −0.35 mm) and a ±0.1 mm ripple under band-limited texture,
a 320 × 240 crop, six views at 4–7° around the ring. Each view has hand errors (pivot off the
specimen by σ = 0.25 mm sideways and 0.5 mm along the axis, roll up to ±3°, exposure ±2 %), the
gyro rotations carry 1 mrad of error per axis, and the sensor noise is σ = 1.5. Two views carry
glare, one saturated. Errors are after removing the best-fitting plane, since the reference plane
is a gauge:

| Case | Height error (RMS) | Coverage |
|---|---|---|
| Relief (140 µm RMS) | 6.7 µm | 99.5 % |
| Inside the glare | 7.1 µm (11.0 µm without glare rejection) | 100 % |
| Flat specimen (negative control) | 3.8 µm of false relief | 98.7 % |

Turning about the lens instead of the specimen, an inverted gyro and an untextured specimen all
give no heights. For comparison, focus variation resolves 0.2 to 0.4 mm on the same phone.

**Speed** on an 8-thread laptop JVM, reference plus six views: 4.2 s at 2.6 MP and 2.1 s at 12 MP
(matched at half resolution).

**Not yet verified on a real phone.** The emulator's fake camera has no parallax, so everything
above is synthetic.

- **The gyro axes.** Device axes are mapped to camera axes from `SENSOR_ORIENTATION`. If that is
  wrong in the tilt axes, the relief comes out inverted, and in a narrow field the gyro check cannot
  see it: images cannot tell a tilt from the opposite tilt, only the roll part shows (the test with
  inverted rotations caught five of six views). Check with a known bump, such as a coin on paper,
  which must come out raised.
- **The height scale** rests on the reported focus distance and the thin-lens model, like the
  lateral scale. Check it against a known step (a feeler gauge, a coin's thickness).
- Gyro drift over a series, whether the Note10's frame timestamps are REALTIME, whether the Samsung
  HAL keeps exposure locked across separate captures, real LED glare on metal, depth of field at
  8° of tilt, and time and memory on the phone.
- Whether the guide's dot moves the way the hand expects.

## Scale

```
m = f / (u − f)                 lateral magnification, thin lens
µm per sensor pixel = pitch / m
```

`u` comes from the reported focus distance, which is measured from the front of the lens, not from
the principal plane, so this is first-order. The app labels every derived number "≈", and writes
the scale into EXIF X/Y resolution in pixels per centimetre.

**Calibration.** The user lays the measuring line across a known length. The app stores, per lens,
the focus setting and the true µm per sensor pixel there (`Scale`, `Calibration`).

- Within 2 % (+0.02 D) of that setting the stored scale is used and shown without "≈".
- At other settings the thin-lens model is scaled by the ratio found at calibration. Most of the
  model's error is a slowly varying offset in the reported distance, so this is better than the
  bare model, but it is still marked "≈".
- On lenses whose focus units are arbitrary there is no model, and the calibration holds only at
  its own setting.

Line lengths are measured in sensor pixels from view coordinates, so calibration does not depend
on zoom or on screen size.

## References

- P. J. Burt, E. H. Adelson, "The Laplacian Pyramid as a Compact Image Code", *IEEE Trans.
  Communications* 31(4), 1983.
- P. J. Burt, R. J. Kolczynski, "Enhanced image capture through fusion", *ICCV* 1993.
- S. Baker, I. Matthews, "Lucas-Kanade 20 Years On: A Unifying Framework", *IJCV* 56(3), 2004.
- H. Takeda, S. Farsiu, P. Milanfar, "Kernel Regression for Image Processing and Reconstruction",
  *IEEE Trans. Image Processing* 16(2), 2007.
- B. Wronski et al., "Handheld Multi-Frame Super-Resolution", *ACM Trans. Graphics* 38(4), 2019.
- S. K. Nayar, Y. Nakagawa, "Shape from Focus", *IEEE Trans. PAMI* 16(8), 1994.
- R. Kumar, P. Anandan, K. Hanna, "Direct recovery of shape from multiple views: a parallax based
  approach", *ICPR* 1994.
- M. Irani, P. Anandan, "Parallax geometry of pairs of points for 3D scene analysis", *ECCV* 1996.
- M. Okutomi, T. Kanade, "A Multiple-Baseline Stereo", *IEEE Trans. PAMI* 15(4), 1993.
- S. B. Kang, R. Szeliski, J. Chai, "Handling occlusions in dense multi-view stereo", *CVPR* 2001.
- ISO 25178-606:2015, Geometrical product specifications — Surface texture: Areal — Part 606:
  Nominal characteristics of non-contact (focus variation) instruments.
- S. W. Hasinoff et al., "Burst photography for high dynamic range and low-light imaging on mobile
  cameras", *ACM Trans. Graphics* 35(6), 2016.
- Android `CameraDevice`, `CaptureRequest` and `CameraCharacteristics` reference documentation.
