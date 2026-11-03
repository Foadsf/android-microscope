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
import io.github.foadsf.microscope.imaging.RgbImage
import io.github.foadsf.microscope.imaging.Optics
import io.github.foadsf.microscope.imaging.Pipeline
import io.github.foadsf.microscope.imaging.Scale
import io.github.foadsf.microscope.imaging.ScaleEstimate
import io.github.foadsf.microscope.imaging.SteadyGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    }
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
        resumed = false
        reconcile()
    }

    fun selectLens(lens: Lens) {
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

    fun setMode(mode: CaptureMode) = state.update { it.copy(mode = mode) }

    fun cycleFrames() = state.update {
        when (it.mode) {
            CaptureMode.SUPER_RES -> it.copy(burstFrames = next(BURST_OPTIONS, it.burstFrames))
            CaptureMode.FOCUS_STACK -> it.copy(stackFrames = next(STACK_OPTIONS, it.stackFrames))
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
        val n = s.frames
        val spec = MicroscopeCamera.CaptureSpec(
            focus = if (mode == CaptureMode.FOCUS_STACK) stackPlan(s) else FloatArray(n) { s.focus },
            burst = mode != CaptureMode.FOCUS_STACK,
            roi = g.captureRoi(streams.captureSize),
            highQuality = mode != CaptureMode.SUPER_RES,
        )
        val roiPixels = spec.roi.width() * spec.roi.height()
        val scale = if (mode == CaptureMode.SUPER_RES) Pipeline.superResScale(roiPixels) else 1
        val app = getApplication<Application>()
        viewModelScope.launch {
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
                val (uri, thumb) = withContext(Dispatchers.IO) {
                    val bmp = Bitmap.createBitmap(rgb.argb, rgb.width, rgb.height, Bitmap.Config.ARGB_8888)
                    val umPerOut = s.umPerSensorPixel?.let { it * g.lens.activeArray.width() / streams.captureSize.width / scale }
                    val meta = CaptureMeta(
                        description = describe(mode, frames.size, umPerOut, s.scale?.calibrated == true, s),
                        focalMm = g.lens.focalMm,
                        fNumber = s.telemetry.aperture ?: s.aperture,
                        iso = s.telemetry.iso,
                        exposureNs = s.telemetry.exposureNs,
                        umPerPixel = umPerOut,
                    )
                    val stem = Gallery.stem()
                    val uri = Gallery.save(app, bmp, meta, stem)
                    if (height != null && height.validCount > 0) saveHeight(app, stem, height, rgb, bmp, meta, umPerOut)
                    val tw = 192
                    val thumb = bmp.scale(tw, max(1, tw * bmp.height / bmp.width))
                    bmp.recycle()
                    uri to thumb
                }
                state.update { it.copy(lastUri = uri, thumbnail = thumb) }
                val extra = if (height != null && height.validCount > 0) " with its height map" else ""
                note("Saved ${rgb.width} × ${rgb.height}$extra to Pictures/Microscope")
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                note("Not enough memory: zoom in to capture a smaller area")
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
                note("Capture failed: ${e.message}")
            } finally {
                state.update { it.copy(processing = null) }
            }
        }
    }

    /**
     * Saves the height map as a picture beside the fused one ([stem]_height.jpg) and, when the lateral
     * scale is known, as a point cloud in Documents/Microscope ([stem].ply).
     */
    private fun saveHeight(
        app: Application, stem: String, height: HeightMap, rgb: RgbImage, fused: Bitmap, meta: CaptureMeta, umPerOut: Float?,
    ) {
        val top = height.maxMm ?: 0f
        val share = 100f * height.validCount / (height.width * height.height)
        val picture = HeightRender.picture(height, fused.width, fused.height, approximate = true)
        val text = "Surface height by focus variation, 0 to approx. %.2f mm, measured at %.0f%% of points; from %s"
            .format(Locale.ROOT, top, share, meta.description)
        Gallery.save(app, picture, meta.copy(description = text), stem + "_height")
        picture.recycle()
        if (umPerOut != null) {
            Gallery.saveDocument(app, "$stem.ply", "application/octet-stream", HeightRender.ply(height, rgb, umPerOut * height.step))
        }
    }

    private fun describe(mode: CaptureMode, frames: Int, umPerPx: Float?, calibrated: Boolean, s: UiState): String {
        val what = when (mode) {
            CaptureMode.PHOTO -> "Single frame"
            CaptureMode.SUPER_RES -> "Super-resolution merge of $frames frames"
            CaptureMode.FOCUS_STACK -> "Focus stack of $frames frames"
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
    }
}
