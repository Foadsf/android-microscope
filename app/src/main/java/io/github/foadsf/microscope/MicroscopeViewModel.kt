package io.github.foadsf.microscope

import android.app.Application
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.foadsf.microscope.camera.Lens
import io.github.foadsf.microscope.camera.LensCatalog
import io.github.foadsf.microscope.camera.MicroscopeCamera
import io.github.foadsf.microscope.gl.Filter
import io.github.foadsf.microscope.imaging.Calibration
import io.github.foadsf.microscope.imaging.CaptureMode
import io.github.foadsf.microscope.imaging.HeightMap
import io.github.foadsf.microscope.imaging.Intrinsics
import io.github.foadsf.microscope.imaging.PlaneParallax
import io.github.foadsf.microscope.imaging.Rotations
import io.github.foadsf.microscope.imaging.TiltGuide
import io.github.foadsf.microscope.imaging.YuvFrame
import io.github.foadsf.microscope.imaging.toRgb
import io.github.foadsf.microscope.imaging.RgbImage
import io.github.foadsf.microscope.imaging.Optics
import io.github.foadsf.microscope.imaging.Pipeline
import io.github.foadsf.microscope.imaging.Scale
import io.github.foadsf.microscope.imaging.ScaleEstimate
import io.github.foadsf.microscope.imaging.SteadyGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.log2
import kotlin.math.max

enum class TorchMode { OFF, AUTO, ON }

data class Processing(val label: String, val progress: Float)

data class Note(val text: String, val id: Long = System.nanoTime())

/**
 * A tilt series in progress: the phone's current tilt from the reference view (direction in the
 * sensor image, radians from +x towards +y), and which sectors of the ring are captured.
 */
data class TiltState(
    val tiltDeg: Float = 0f,
    val direction: Float = 0f,
    val filled: List<Boolean>,
    val minDeg: Float,
    val maxDeg: Float,
    val referenceTaken: Boolean = false,
    val capturing: Boolean = false,
    val steady: Boolean = false,
)

/** The two ends of the measuring line, in view-normalised coordinates. */
data class Ruler(val x0: Float = 0.3f, val y0: Float = 0.5f, val x1: Float = 0.7f, val y1: Float = 0.5f)

data class UiState(
    val hasPermission: Boolean = false,
    val lenses: List<Lens> = emptyList(),
    val lens: Lens? = null,
    val streams: MicroscopeCamera.Streams? = null,
    /** Requested focus, diopters. */
    val focus: Float = 0f,
    val zoom: Float = 1f,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val aperture: Float? = null,
    val torchMode: TorchMode = TorchMode.AUTO,
    val torchOn: Boolean = false,
    /** Torch brightness step where the device supports it (see Lens.torchStrengthMax). */
    val torchLevel: Int = 1,
    val showInfo: Boolean = false,
    /** Exposure compensation in Lens.evStep units. */
    val ev: Int = 0,
    /** The exposure slider is open (and the live view marks clipped highlights). */
    val showExposure: Boolean = false,
    val measuring: Boolean = false,
    val ruler: Ruler = Ruler(),
    /** The current lens's calibration against a known length, if the user made one. */
    val calibration: Calibration? = null,
    val filter: Filter = Filter.COLOUR,
    val peaking: Boolean = true,
    val mode: CaptureMode = CaptureMode.SUPER_RES,
    val burstFrames: Int = 8,
    val stackFrames: Int = 9,
    /** Tilted views in a tilt series, besides the reference. */
    val tiltViews: Int = 6,
    /** The tilt series being captured, if any. */
    val tilt: TiltState? = null,
    val processing: Processing? = null,
    val lastUri: Uri? = null,
    val thumbnail: Bitmap? = null,
    val telemetry: MicroscopeCamera.Telemetry = MicroscopeCamera.Telemetry(),
    val note: Note? = null,
    val fatal: String? = null,
) {
    val geometry: Geometry? get() = lens?.let { Geometry(it, zoom, cx, cy) }

    /** Focus distance actually reached if the HAL reports it, else the requested one. */
    val effectiveFocus: Float get() = telemetry.focusDiopters ?: focus

    /**
     * Specimen size of one active-array pixel: the thin-lens estimate, corrected by the user's
     * calibration where there is one. Null when neither can tell.
     */
    val scale: ScaleEstimate?
        get() {
            val l = lens ?: return null
            val model = if (l.manualFocus && l.focusIsMetric) {
                { d: Float -> if (d > 0.05f) Optics.objectPixelUm(l.pixelPitchUm, l.focalMm, Optics.distanceMm(d)) else null }
            } else {
                null
            }
            return Scale.estimate(model, calibration, effectiveFocus)
        }

    val umPerSensorPixel: Float? get() = scale?.umPerSensorPixel

    /** Length of the measuring line on the specimen, in µm. */
    val rulerUm: Float?
        get() {
            val g = geometry ?: return null
            val um = umPerSensorPixel ?: return null
            return g.sensorLength(ruler.x0, ruler.y0, ruler.x1, ruler.y1) * um
        }

    /** Width of specimen in view, in µm. */
    val fieldOfViewUm: Float?
        get() {
            val g = geometry ?: return null
            return umPerSensorPixel?.let { it * g.uprightSensorWidth / zoom }
        }

    /** Frame count for the current mode. */
    val frames: Int get() = when (mode) {
        CaptureMode.PHOTO -> 1
        CaptureMode.SUPER_RES -> burstFrames
        CaptureMode.FOCUS_STACK -> stackFrames
        CaptureMode.TILT -> tiltViews + 1
    }

    /** Focus, zoom, pan and lens are fixed while a capture is processed or a tilt series runs. */
    val locked: Boolean get() = processing != null || tilt != null
}

class MicroscopeViewModel(app: Application) : AndroidViewModel(app) {
    private val camera = MicroscopeCamera(app)
    private val calibrations = app.getSharedPreferences("calibration", android.content.Context.MODE_PRIVATE)
    private val state = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = state

    private var texture: SurfaceTexture? = null
    private var resumed = false
    private var cameraJob: Job? = null
    private var openedFor: Pair<String, SurfaceTexture>? = null
    private var darkFrames = 0
    private var tiltJob: Job? = null
    private var tiltFinish = false

    init {
        viewModelScope.launch {
            camera.telemetry.collect { t ->
                state.update { it.copy(telemetry = t) }
                autoTorch(t)
            }
        }
        viewModelScope.launch { camera.errors.collect { note(it) } }
    }

    fun onPermission(granted: Boolean) {
        if (!granted) {
            state.update { it.copy(hasPermission = false) }
            return
        }
        if (state.value.lenses.isEmpty()) {
            val manager = getApplication<Application>().getSystemService(CameraManager::class.java)
            val lenses = runCatching { LensCatalog.rearLenses(manager) }.getOrElse { emptyList() }
            if (lenses.isEmpty()) {
                state.update { it.copy(hasPermission = true, fatal = "No usable rear camera was found.") }
                return
            }
            state.update { it.copy(hasPermission = true, lenses = lenses) }
            selectLens(lenses.first())
        } else {
            state.update { it.copy(hasPermission = true) }
        }
        reconcile()
    }

    /** Called on the GL thread whenever the renderer creates its SurfaceTexture. */
    fun onTexture(st: SurfaceTexture) {
        viewModelScope.launch {
            texture = st
            openedFor = null
            reconcile()
        }
    }

    fun onResume() {
        resumed = true
        reconcile()
    }

    fun onPause() {
        cancelTilt()
        resumed = false
        reconcile()
    }

    fun selectLens(lens: Lens) {
        cancelTilt()
        state.update {
            val (cx, cy) = Geometry.clampCentre(lens, it.zoom, it.cx, it.cy)
            it.copy(
                lens = lens,
                streams = null,
                // Start at the closest focus: that is where a microscope lives.
                focus = if (lens.manualFocus) lens.minFocusDiopters else 0f,
                aperture = lens.apertures.maxOrNull(),
                cx = cx,
                cy = cy,
                torchOn = it.torchMode == TorchMode.ON,
                torchLevel = lens.torchStrengthDefault.coerceIn(1, lens.torchStrengthMax),
                ev = 0,
                calibration = Calibration.decode(calibrations.getString(lens.key, null)),
            )
        }
        darkFrames = 0
        reconcile()
    }

    fun nextLens() {
        val s = state.value
        if (s.lenses.size < 2) return
        val i = s.lenses.indexOfFirst { it.key == s.lens?.key }
        selectLens(s.lenses[(i + 1) % s.lenses.size])
    }

    fun setFocus(diopters: Float) {
        val l = state.value.lens ?: return
        state.update { it.copy(focus = diopters.coerceIn(0f, l.minFocusDiopters)) }
        pushControls()
    }

    fun setZoom(zoom: Float) {
        val l = state.value.lens ?: return
        val z = zoom.coerceIn(1f, MAX_ZOOM)
        state.update {
            val (cx, cy) = Geometry.clampCentre(l, z, it.cx, it.cy)
            it.copy(zoom = z, cx = cx, cy = cy)
        }
        pushControls()
    }

    /** Pan by a drag of (dx, dy) in view-normalised units. */
    fun pan(dx: Float, dy: Float) {
        val l = state.value.lens ?: return
        state.update {
            val (cx, cy) = Geometry.clampCentre(l, it.zoom, it.cx - dx / it.zoom, it.cy - dy / it.zoom)
            it.copy(cx = cx, cy = cy)
        }
        pushControls()
    }

    fun resetView() {
        state.update { it.copy(zoom = 1f, cx = 0.5f, cy = 0.5f) }
        pushControls()
    }

    /** Tap-to-focus at view-normalised (vx, vy): one AF sweep there, then manual from the result. */
    fun focusAt(vx: Float, vy: Float) {
        val s = state.value
        val g = s.geometry ?: return
        if (s.lens?.afAuto != true || s.processing != null) return
        viewModelScope.launch {
            val d = camera.autofocus(g.meteringRegion(vx, vy))
            if (d != null) {
                state.update { it.copy(focus = d.coerceIn(0f, it.lens?.minFocusDiopters ?: d)) }
            } else {
                note("Could not focus there")
            }
            pushControls()
        }
    }

    fun cycleTorch() {
        state.update {
            val next = TorchMode.entries[(it.torchMode.ordinal + 1) % TorchMode.entries.size]
            it.copy(torchMode = next, torchOn = next == TorchMode.ON)
        }
        darkFrames = 0
        pushControls()
    }

    fun setTorchLevel(level: Int) {
        val max = state.value.lens?.torchStrengthMax ?: return
        state.update { it.copy(torchLevel = level.coerceIn(1, max)) }
        pushControls()
    }

    fun showInfo(show: Boolean) = state.update { it.copy(showInfo = show) }

    fun toggleExposure() = state.update { it.copy(showExposure = !it.showExposure) }

    fun setEv(steps: Int) {
        val range = state.value.lens?.evRange ?: return
        state.update { it.copy(ev = steps.coerceIn(range)) }
        pushControls()
    }

    fun toggleMeasure() = state.update { it.copy(measuring = !it.measuring) }

    /** Moves end [end] (0 or 1) of the measuring line to view-normalised (vx, vy). */
    fun moveRuler(end: Int, vx: Float, vy: Float) = state.update {
        val x = vx.coerceIn(0f, 1f)
        val y = vy.coerceIn(0f, 1f)
        it.copy(ruler = if (end == 0) it.ruler.copy(x0 = x, y0 = y) else it.ruler.copy(x1 = x, y1 = y))
    }

    /**
     * Calibrates the current lens at the current focus: the measuring line spans [knownUm] on the
     * specimen. Returns false if the line is too short to calibrate against.
     */
    fun calibrate(knownUm: Float): Boolean {
        val s = state.value
        val lens = s.lens ?: return false
        val g = s.geometry ?: return false
        val px = g.sensorLength(s.ruler.x0, s.ruler.y0, s.ruler.x1, s.ruler.y1)
        if (px < MIN_CALIBRATION_PX || !(knownUm > 0f) || !knownUm.isFinite()) return false
        val c = Calibration(s.effectiveFocus, knownUm / px)
        calibrations.edit { putString(lens.key, Calibration.encode(c)) }
        state.update { it.copy(calibration = c) }
        note("Calibrated: %.2f µm per sensor pixel".format(c.umPerSensorPixel))
        return true
    }

    fun clearCalibration() {
        val lens = state.value.lens ?: return
        calibrations.edit { remove(lens.key) }
        state.update { it.copy(calibration = null) }
    }

    /** Plain-text report of the device and every lens the app found, for bug reports. */
    fun diagnostics(): String = Diagnostics.report(getApplication(), state.value)

    fun cycleAperture() {
        val s = state.value
        val list = s.lens?.apertures ?: return
        if (list.size < 2) return
        val i = list.indexOf(s.aperture)
        state.update { it.copy(aperture = list[(i + 1) % list.size]) }
        pushControls()
    }

    fun cycleFilter() = state.update { it.copy(filter = Filter.entries[(it.filter.ordinal + 1) % Filter.entries.size]) }

    fun togglePeaking() = state.update { it.copy(peaking = !it.peaking) }

    fun setMode(mode: CaptureMode) {
        if (mode != CaptureMode.TILT) cancelTilt()
        state.update { it.copy(mode = mode) }
    }

    fun cycleFrames() = state.update {
        when (it.mode) {
            CaptureMode.SUPER_RES -> it.copy(burstFrames = next(BURST_OPTIONS, it.burstFrames))
            CaptureMode.FOCUS_STACK -> it.copy(stackFrames = next(STACK_OPTIONS, it.stackFrames))
            CaptureMode.TILT -> if (it.tilt == null) it.copy(tiltViews = next(TILT_OPTIONS, it.tiltViews)) else it
            CaptureMode.PHOTO -> it
        }
    }

    private fun next(options: IntArray, current: Int) = options[(options.indexOf(current) + 1) % options.size]

    /** Focus distances for a stack centred on the current focus, one depth of field apart. */
    fun stackPlan(s: UiState = state.value): FloatArray {
        val l = s.lens ?: return floatArrayOf(s.focus)
        if (!l.manualFocus) return FloatArray(s.stackFrames) { s.focus }
        val step = if (l.focusIsMetric) {
            val n = s.telemetry.aperture ?: s.aperture ?: l.apertures.maxOrNull() ?: 2f
            // Circle of confusion = 2 pixels; 20 % overlap between neighbouring slices.
            0.8f * Optics.depthOfFieldDiopters(n, 2f * l.pixelPitchUm, l.focalMm)
        } else {
            l.minFocusDiopters * 0.03f
        }
        return Optics.bracket(s.focus, s.stackFrames, step, l.minFocusDiopters)
    }

    fun shutter() {
        val s = state.value
        val streams = s.streams ?: return
        val g = s.geometry ?: return
        if (s.processing != null) return
        val mode = s.mode
        if (mode == CaptureMode.TILT) {
            if (s.tilt == null) startTilt() else tiltFinish = true
            return
        }
        val app = getApplication<Application>()
        viewModelScope.launch {
            // A large sensor at low zoom may not fit in memory at full resolution: then capture
            // again with every 2 × 2 block averaged (same field, half the resolution).
            for (bin in intArrayOf(1, 2, 4)) {
                try {
                    capture(app, s, g, streams, bin)
                    return@launch
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "out of memory at bin $bin")
                    if (bin == 4) note("Not enough memory: zoom in to capture a smaller area")
                } finally {
                    state.update { it.copy(processing = null) }
                }
            }
        }
    }

    private suspend fun capture(app: Application, s: UiState, g: Geometry, streams: MicroscopeCamera.Streams, bin: Int) {
        val mode = s.mode
        val n = s.frames
        val spec = MicroscopeCamera.CaptureSpec(
            focus = if (mode == CaptureMode.FOCUS_STACK) stackPlan(s) else FloatArray(n) { s.focus },
            burst = mode != CaptureMode.FOCUS_STACK,
            roi = g.captureRoi(streams.captureSize),
            highQuality = mode != CaptureMode.SUPER_RES,
            bin = bin,
        )
        val roiPixels = spec.roi.width() / bin * (spec.roi.height() / bin)
        val scale = if (mode == CaptureMode.SUPER_RES) Pipeline.superResScale(roiPixels) else 1
        run {
            try {
                // Let the jolt of pressing the shutter die away before exposing.
                progress("Hold still", 0f)
                val t0Steady = System.nanoTime()
                val threshold = SteadyGate.threshold(g.lens.pixelPitchUm, g.lens.focalMm, s.telemetry.exposureNs ?: 33_000_000L)
                val steady = SteadyHold.await(app, threshold, STEADY_TIMEOUT_MS)
                Log.i(TAG, "steady=$steady after ${(System.nanoTime() - t0Steady) / 1_000_000} ms (threshold %.3f rad/s)".format(threshold))
                progress("Capturing", 0f)
                val frames = camera.capture(spec) { k -> progress("Capturing $k / $n", 0.3f * k / n) }
                progress(if (mode == CaptureMode.PHOTO) "Developing" else "Aligning ${frames.size} frames", 0.3f)
                val t0 = System.nanoTime()
                // A bracket in metric diopters also yields the surface height (focus variation).
                val measure = mode == CaptureMode.FOCUS_STACK && g.lens.manualFocus && g.lens.focusIsMetric
                val rotation = g.lens.sensorOrientation
                val (rgb, height) = withContext(Dispatchers.Default) {
                    val onProgress = { p: Float ->
                        val label = when {
                            p < 0.3f -> "Aligning ${frames.size} frames"
                            mode == CaptureMode.FOCUS_STACK && measure && p >= 0.8f -> "Measuring height"
                            mode == CaptureMode.FOCUS_STACK -> "Fusing focus stack"
                            else -> "Merging burst"
                        }
                        progress(label, 0.3f + 0.6f * p)
                    }
                    if (mode == CaptureMode.FOCUS_STACK && frames.size > 1) {
                        val r = Pipeline.focusStack(frames, if (measure) spec.focus else null, onProgress)
                        r.image.rotated(rotation) to r.height?.rotated(rotation)
                    } else {
                        Pipeline.process(mode, frames, sharpen = if (mode == CaptureMode.SUPER_RES) 0.5f else 0f, onProgress)
                            .rotated(rotation) to null
                    }
                }
                Log.i(TAG, "$mode: ${frames.size} x ${spec.roi.width()}x${spec.roi.height()} -> ${rgb.width}x${rgb.height} in ${(System.nanoTime() - t0) / 1_000_000} ms")
                progress("Saving", 0.95f)
                val umPerOut = s.umPerSensorPixel?.let { it * g.lens.activeArray.width() / streams.captureSize.width * bin / scale }
                val meta = CaptureMeta(
                    description = describe(mode, frames.size, umPerOut, s.scale?.calibrated == true, s),
                    focalMm = g.lens.focalMm,
                    fNumber = s.telemetry.aperture ?: s.aperture,
                    iso = s.telemetry.iso,
                    exposureNs = s.telemetry.exposureNs,
                    umPerPixel = umPerOut,
                )
                store(app, rgb, height, meta, umPerOut, "focus variation")
                if (bin > 1) note("Saved at 1/$bin resolution to fit in memory; zoom in for full detail")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
                note("Capture failed: ${e.message}")
            } finally {
                state.update { it.copy(processing = null) }
            }
        }
    }

    /**
     * A tilt series: a reference view, then one view per sector of the tilt ring, each taken
     * automatically once the phone is held steady there. Exposure stays locked throughout. A second
     * press of the shutter ends the series early; with fewer than two tilted views it is dropped.
     * Then [PlaneParallax] measures the relief, with each view's rotation from the gyroscope.
     */
    private fun startTilt() {
        val s = state.value
        val streams = s.streams ?: return
        val g = s.geometry ?: return
        val lens = g.lens
        if (!lens.manualFocus || !lens.focusIsMetric || s.effectiveFocus <= 0.05f) {
            note("Tilt needs a lens that reports its focus distance")
            return
        }
        val app = getApplication<Application>()
        val tracker = TiltTracker(app)
        if (!tracker.start()) {
            note("Tilt needs a gyroscope")
            return
        }
        val spec = MicroscopeCamera.CaptureSpec(floatArrayOf(s.focus), burst = true, roi = g.captureRoi(streams.captureSize), highQuality = true)
        val distanceMm = Optics.distanceMm(s.effectiveFocus).toDouble()
        val toCamera = Rotations.cameraFromDevice(lens.sensorOrientation)
        val guide = TiltGuide(s.tiltViews, TILT_MIN_DEG, TILT_MAX_DEG)
        tracker.calmBelow = SteadyGate.threshold(lens.pixelPitchUm, lens.focalMm, s.telemetry.exposureNs ?: 33_000_000L)
        tiltFinish = false
        fun show(update: (TiltState) -> TiltState) = state.update { st -> st.tilt?.let { st.copy(tilt = update(it)) } ?: st }
        state.update {
            it.copy(tilt = TiltState(filled = List(guide.bins) { false }, minDeg = guide.minDeg.toFloat(), maxDeg = guide.maxDeg.toFloat()))
        }
        camera.holdExposure(true)

        // One frame, and the device orientation during its exposure; null if the phone moved.
        suspend fun take(): Pair<YuvFrame, DoubleArray>? {
            val before = tracker.track.latest() ?: return null
            show { it.copy(capturing = true) }
            val frame = try {
                camera.capture(spec) {}.first()
            } finally {
                show { it.copy(capturing = false) }
            }
            val after = tracker.track.latest() ?: return null
            return if (lens.realtimeTimestamps && frame.timestampNs > 0) {
                tracker.track.at(frame.timestampNs)?.let { frame to it }
            } else {
                // Without a shared clock, accept the frame only if the phone held still throughout.
                if (Rotations.angleBetween(before, after) > MAX_DRIFT_RAD) null else frame to after
            }
        }

        tiltJob = viewModelScope.launch {
            val frames = ArrayList<YuvFrame>()
            val orientations = ArrayList<DoubleArray>()
            try {
                // The reference: held as square to the specimen as the user can.
                while (frames.isEmpty() && !tiltFinish) {
                    if (!tracker.steadyFor(HOLD_NS)) {
                        delay(POLL_MS)
                        continue
                    }
                    take()?.let { (f, q) -> frames.add(f); orientations.add(q) }
                }
                show { it.copy(referenceTaken = true) }
                while (!guide.complete && !tiltFinish) {
                    delay(POLL_MS)
                    val q = tracker.track.latest() ?: continue
                    val (tilt, direction) = Rotations.tilt(Rotations.relativeCamera(toCamera, orientations[0], q))
                    val steady = tracker.steadyFor(HOLD_NS)
                    show { it.copy(tiltDeg = Math.toDegrees(tilt).toFloat(), direction = direction.toFloat(), steady = steady) }
                    val bin = guide.wanted(tilt, direction) ?: continue
                    if (!steady) continue
                    val (f, qv) = take() ?: continue
                    // Re-check where the view was really taken: it must still fall in that sector.
                    val (t2, d2) = Rotations.tilt(Rotations.relativeCamera(toCamera, orientations[0], qv))
                    if (guide.bin(t2, d2) != bin) continue
                    frames.add(f)
                    orientations.add(qv)
                    guide.fill(bin)
                    show { it.copy(filled = List(guide.bins) { b -> guide.isFilled(b) }) }
                }
            } catch (e: CancellationException) {
                tracker.stop()
                camera.holdExposure(false)
                state.update { it.copy(tilt = null) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "tilt capture failed", e)
                note("Capture failed: ${e.message}")
                frames.clear()
            }
            tracker.stop()
            camera.holdExposure(false)
            state.update { it.copy(tilt = null) }
            if (frames.size < 3) {
                note("Tilt series cancelled: it needs at least two tilted views")
                return@launch
            }
            measureTilt(app, s, g, streams, spec, frames, orientations.map { Rotations.relativeCamera(toCamera, orientations[0], it) }, distanceMm)
        }
    }

    private suspend fun measureTilt(
        app: Application, s: UiState, g: Geometry, streams: MicroscopeCamera.Streams, spec: MicroscopeCamera.CaptureSpec,
        frames: List<YuvFrame>, rotations: List<DoubleArray>, distanceMm: Double,
    ) {
        val lens = g.lens
        try {
            progress("Measuring relief", 0f)
            val pixelUm = lens.pixelPitchUm.toDouble() * lens.activeArray.width() / streams.captureSize.width
            val k = Intrinsics.focused(
                lens.focalMm.toDouble(), pixelUm, distanceMm,
                (streams.captureSize.width - 1) / 2.0 - spec.roi.left, (streams.captureSize.height - 1) / 2.0 - spec.roi.top,
            )
            val t0 = System.nanoTime()
            val (rgb, result) = withContext(Dispatchers.Default) {
                val r = PlaneParallax.estimate(frames, rotations, k, distanceMm) { progress("Measuring relief", 0.95f * it) }
                frames[0].toRgb().rotated(lens.sensorOrientation) to r
            }
            for ((i, v) in result.views.withIndex()) {
                Log.i(TAG, "tilt view ${i + 1}: %.1f°, baseline %.3f, gyro mismatch %.2f°, %s".format(v.tiltDeg, v.baseline, v.gyroMismatchDeg, v.reason ?: "used"))
            }
            Log.i(TAG, "TILT: ${frames.size} x ${spec.roi.width()}x${spec.roi.height()}, ${result.height.validCount} heights in ${(System.nanoTime() - t0) / 1_000_000} ms")
            val used = result.views.count { it.used }
            if (used < 2) {
                val why = result.views.mapNotNull { it.reason }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "no usable views"
                note("No height map: $why. Tilt about the specimen, keeping it centred.")
            }
            progress("Saving", 0.95f)
            val umPerOut = s.umPerSensorPixel?.let { it * lens.activeArray.width() / streams.captureSize.width }
            val meta = CaptureMeta(
                description = describe(CaptureMode.TILT, frames.size, umPerOut, s.scale?.calibrated == true, s),
                focalMm = lens.focalMm,
                fNumber = s.telemetry.aperture ?: s.aperture,
                iso = s.telemetry.iso,
                exposureNs = s.telemetry.exposureNs,
                umPerPixel = umPerOut,
            )
            store(app, rgb, result.height.rotated(lens.sensorOrientation), meta, umPerOut, "plane-plus-parallax from $used tilted views")
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            note("Not enough memory: zoom in to capture a smaller area")
        } catch (e: Exception) {
            Log.e(TAG, "tilt measurement failed", e)
            note("Measurement failed: ${e.message}")
        } finally {
            state.update { it.copy(processing = null) }
        }
    }

    private fun cancelTilt() {
        if (state.value.tilt == null) return
        tiltJob?.cancel()
        tiltJob = null
    }

    /** Saves [rgb] (and [height], if it has any heights) and shows the result. */
    private suspend fun store(app: Application, rgb: RgbImage, height: HeightMap?, meta: CaptureMeta, umPerOut: Float?, method: String) {
        val (uri, thumb) = withContext(Dispatchers.IO) {
            val bmp = Bitmap.createBitmap(rgb.argb, rgb.width, rgb.height, Bitmap.Config.ARGB_8888)
            val stem = Gallery.stem()
            val uri = Gallery.save(app, bmp, meta, stem)
            if (height != null && height.validCount > 0) saveHeight(app, stem, height, rgb, bmp, meta, umPerOut, method)
            val tw = 192
            val thumb = bmp.scale(tw, max(1, tw * bmp.height / bmp.width))
            bmp.recycle()
            uri to thumb
        }
        state.update { it.copy(lastUri = uri, thumbnail = thumb) }
        val extra = if (height != null && height.validCount > 0) " with its height map" else ""
        note("Saved ${rgb.width} × ${rgb.height}$extra to Pictures/Microscope")
    }

    /**
     * Saves the height map as a picture beside the fused one ([stem]_height.jpg) and, when the lateral
     * scale is known, as a point cloud in Documents/Microscope ([stem].ply).
     */
    private fun saveHeight(
        app: Application, stem: String, height: HeightMap, rgb: RgbImage, fused: Bitmap, meta: CaptureMeta, umPerOut: Float?, method: String,
    ) {
        val top = height.maxMm ?: 0f
        val share = 100f * height.validCount / (height.width * height.height)
        val picture = HeightRender.picture(height, fused.width, fused.height, approximate = true)
        val text = "Surface height by %s, 0 to approx. %.3f mm, measured at %.0f%% of points; from %s"
            .format(Locale.ROOT, method, top, share, meta.description)
        Gallery.save(app, picture, meta.copy(description = text), stem + "_height")
        picture.recycle()
        if (umPerOut != null) {
            Gallery.saveDocument(app, "$stem.ply", "application/octet-stream", HeightRender.ply(height, rgb, umPerOut * height.step, method))
        }
    }

    private fun describe(mode: CaptureMode, frames: Int, umPerPx: Float?, calibrated: Boolean, s: UiState): String {
        val what = when (mode) {
            CaptureMode.PHOTO -> "Single frame"
            CaptureMode.SUPER_RES -> "Super-resolution merge of $frames frames"
            CaptureMode.FOCUS_STACK -> "Focus stack of $frames frames"
            CaptureMode.TILT -> "Reference view of a tilt series of $frames views"
        }
        // EXIF text fields are ASCII, so no "≈" or "µ" here.
        val lens = s.lens?.label ?: ""
        val scale = umPerPx?.let {
            if (calibrated) ", %.3f um/px (calibrated)".format(Locale.ROOT, it) else ", approx. %.2f um/px (uncalibrated)".format(Locale.ROOT, it)
        } ?: ""
        val dist = if (s.lens?.focusIsMetric == true && s.effectiveFocus > 0.05f) {
            ", focus approx. %.0f mm".format(Locale.ROOT, Optics.distanceMm(s.effectiveFocus))
        } else {
            ""
        }
        return "$what, $lens$dist$scale"
    }

    private fun progress(label: String, p: Float) = state.update { it.copy(processing = Processing(label, p)) }

    fun note(text: String) = state.update { it.copy(note = Note(text)) }

    fun clearNote(id: Long) = state.update { if (it.note?.id == id) it.copy(note = null) else it }

    /**
     * Latches the torch on after about a second of a dark scene (EV100 < 5, i.e. dim indoor light).
     * It never switches itself off: the torch lights the scene, so the meter can no longer tell.
     */
    private fun autoTorch(t: MicroscopeCamera.Telemetry) {
        val s = state.value
        if (s.torchMode != TorchMode.AUTO || s.torchOn || s.lens?.hasFlash != true || s.processing != null) return
        val iso = t.iso ?: return
        val exp = t.exposureNs ?: return
        val n = t.aperture ?: s.aperture ?: 2f
        val ev100 = log2(n * n / (exp / 1e9)) - log2(iso / 100.0)
        darkFrames = if (ev100 < 5.0) darkFrames + 1 else 0
        if (darkFrames > 30) {
            state.update { it.copy(torchOn = true) }
            pushControls()
            note("Low light: torch on")
        }
    }

    private fun pushControls() {
        val s = state.value
        val g = s.geometry ?: return
        camera.setControls(MicroscopeCamera.Controls(s.focus, s.aperture, s.torchOn, s.torchLevel, s.ev, g.cropRegion()))
    }

    /** Opens or closes the camera to match permission, lifecycle, surface and lens. */
    private fun reconcile() {
        val s = state.value
        val st = texture
        val lens = s.lens
        val want = s.hasPermission && resumed && st != null && lens != null
        if (!want) {
            if (openedFor != null || cameraJob != null) {
                cameraJob?.cancel()
                cameraJob = null
                openedFor = null
                state.update { it.copy(streams = null) }
                viewModelScope.launch { camera.close() }
            }
            return
        }
        val target = lens.key to st
        if (openedFor == target) return
        openedFor = target
        cameraJob?.cancel()
        cameraJob = viewModelScope.launch {
            try {
                pushControls()
                val streams = camera.open(lens, st)
                state.update { it.copy(streams = streams) }
                pushControls()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "open ${lens.key}", e)
                openedFor = null
                note("Cannot open ${lens.label}: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        camera.release()
    }

    companion object {
        private const val TAG = "Microscope"
        const val MAX_ZOOM = 16f
        private const val STEADY_TIMEOUT_MS = 1500L

        /** A calibration line shorter than this many sensor pixels is too imprecise to use. */
        private const val MIN_CALIBRATION_PX = 50f
        val BURST_OPTIONS = intArrayOf(4, 8, 12)
        val STACK_OPTIONS = intArrayOf(5, 9, 15)
        val TILT_OPTIONS = intArrayOf(4, 6, 8)
        const val TILT_MIN_DEG = 3.0
        const val TILT_MAX_DEG = 8.0
        private const val HOLD_NS = 150_000_000L
        private const val POLL_MS = 30L

        /** Largest rotation during a capture when frame and gyro clocks differ (≈ 0.17°). */
        private const val MAX_DRIFT_RAD = 0.003
    }
}
