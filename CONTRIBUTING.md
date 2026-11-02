# Contributing

Thanks for helping. A few ground rules keep this app small, honest and free:

- **Keep the UI minimal.** One screen, controls you can reach with a thumb. Open an issue before a
  feature that adds settings screens or modes.
- **No network access, ever.** No `INTERNET` permission, no analytics, no crash reporting, no ads.
  CI fails if `INTERNET` appears in the APK.
- **No proprietary dependencies.** Everything must be FLOSS and buildable from source, so the app
  can be packaged for F-Droid.
- **No device-specific hacks.** Read capabilities from `CameraCharacteristics`. Do not hard-code
  camera IDs or probe hidden ones.
- **Algorithms come with a test.** Code in `imaging/` has no Android dependencies. A change there
  needs a unit test against ground truth (see `ImagingTest`) and a reference for the method. If it
  is slower or faster, include `-Pbench=1` timings.
- **Pin versions exactly.** Bump a dependency in `gradle/libs.versions.toml` deliberately, one
  change per PR, and say why.

## Pull requests

1. Build and test locally: `./gradlew assembleDebug testDebugUnitTest lintDebug`. CI runs the same.
2. Test on a real phone if you touch `camera/`, `gl/` or the UI. Say which device and Android
   version, which lenses it exposed, and what you checked. Typical checks: focus, zoom and pan,
   each capture mode, and whether the saved picture matches the live view.

By contributing, you agree that your work is licensed under the GPL-3.0.
