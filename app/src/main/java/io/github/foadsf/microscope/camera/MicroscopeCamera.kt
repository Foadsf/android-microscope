package io.github.foadsf.microscope.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import io.github.foadsf.microscope.imaging.YuvFrame
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Camera2 controller for microscopy: a preview stream into the GL renderer's SurfaceTexture plus a
 * full-resolution `YUV_420_888` stream for computational captures, manual focus in diopters,
 * aperture, torch, ISP crop for zoom, tap-to-focus, AE/AWB-locked bursts and settled focus
 * brackets. All Camera2 state lives on one handler thread; public suspend functions hop onto it.
 */
class MicroscopeCamera(context: Context) {
    data class Controls(
        val focusDiopters: Float,
        val aperture: Float?,
        val torch: Boolean,
        /** SCALER_CROP_REGION for the preview (active-array coordinates), null for the full sensor. */
        val crop: Rect?,
    )

    data class Telemetry(
        val focusDiopters: Float? = null,
        val iso: Int? = null,
        val exposureNs: Long? = null,
        val aperture: Float? = null,
    )

    data class Streams(val lens: Lens, val previewSize: Size, val captureSize: Size)

    /** One computational capture: a focus distance per frame, and the crop to keep. */
    class CaptureSpec(val focus: FloatArray, val burst: Boolean, val roi: Rect, val highQuality: Boolean)

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher("camera")
    private val executor = Executor { handler.post(it) }
    private val imageThread = HandlerThread("camera-frames").apply { start() }
    private val imageHandler = Handler(imageThread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var lens: Lens? = null
    private var controls: Controls? = null
    private var exposureLocked = false

    private val telemetryState = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = telemetryState
    private val errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<String> = errorFlow
    private val results = MutableSharedFlow<TotalCaptureResult>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            telemetryState.value = Telemetry(
                focusDiopters = value(result, CaptureResult.LENS_FOCUS_DISTANCE),
                iso = value(result, CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = value(result, CaptureResult.SENSOR_EXPOSURE_TIME),
                aperture = value(result, CaptureResult.LENS_APERTURE),
            )
            results.tryEmit(result)
        }
    }

    /** Prefer the streaming physical camera's own metadata when the HAL provides it. */
    private fun <T> value(result: TotalCaptureResult, key: CaptureResult.Key<T>): T? {
        val pid = lens?.physicalId
        val physical = when {
            pid == null -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> result.physicalCameraTotalResults[pid]
            else -> @Suppress("DEPRECATION") result.physicalCameraResults[pid]
        }
        return physical?.get(key) ?: result.get(key)
    }

    suspend fun open(lens: Lens, texture: SurfaceTexture): Streams = withContext(dispatcher) {
        closeInternal()
        val dev = openDevice(lens.openId)
        device = dev
        this@MicroscopeCamera.lens = lens
        val previews = LensCatalog.matchingAspect(lens.previewSizes, lens.activeArray)
        val preview = previews.firstOrNull { it.width <= 1920 && it.height <= 1440 } ?: previews.last()
        // The largest YUV size is not guaranteed alongside a preview stream on every hardware
        // level, so walk down until the HAL accepts the pair.
        for (yuv in LensCatalog.matchingAspect(lens.yuvSizes, lens.activeArray).take(8)) {
            texture.setDefaultBufferSize(preview.width, preview.height)
            val surface = Surface(texture)
            val ir = ImageReader.newInstance(yuv.width, yuv.height, ImageFormat.YUV_420_888, MAX_IMAGES)
            val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(ir.surface))
            lens.physicalId?.let { pid -> outputs.forEach { it.setPhysicalCameraId(pid) } }
            val s = runCatching { createSession(dev, outputs) }
                .onFailure { Log.w(TAG, "session ${preview} + ${yuv} rejected: ${it.message}") }
                .getOrNull()
            if (s == null) {
                ir.close()
                surface.release()
                continue
            }
            session = s
            reader = ir
            previewSurface = surface
            controls?.let { startPreview() }
            return@withContext Streams(lens, preview, yuv)
        }
        closeInternal()
        throw IllegalStateException("The camera rejected every stream size")
    }

    fun setControls(c: Controls) {
        handler.post {
            controls = c
            startPreview()
        }
    }

    /**
     * One-shot autofocus on [region] (active-array coordinates). Returns the focus distance it
     * settled on, so the caller can continue in manual focus from there, or null if unavailable.
     */
    suspend fun autofocus(region: Rect?): Float? = withContext(dispatcher) {
        val l = lens ?: return@withContext null
        val s = session ?: return@withContext null
        val c = controls ?: return@withContext null
        val surface = previewSurface ?: return@withContext null
        if (!l.afAuto) return@withContext null
        fun request(trigger: Int) = builder(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyControls(this, c, c.focusDiopters, manualFocus = false)
            put(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            if (region != null && l.maxAfRegions > 0) {
                put(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX - 1)))
            }
            c.crop?.let { put(CaptureRequest.SCALER_CROP_REGION, it) }
            put(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        }.build()
        try {
            s.setRepeatingRequest(request(CameraMetadata.CONTROL_AF_TRIGGER_IDLE), resultCallback, handler)
            s.capture(request(CameraMetadata.CONTROL_AF_TRIGGER_START), resultCallback, handler)
            val done = withTimeoutOrNull(3000) {
                results.first {
                    val st = value(it, CaptureResult.CONTROL_AF_STATE)
                    st == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || st == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                }
            }
            s.capture(request(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL), null, handler)
            done?.let { value(it, CaptureResult.LENS_FOCUS_DISTANCE) }
        } catch (e: Exception) {
            report("Autofocus failed: ${e.message}")
            null
        }
    }

    /** Captures [spec] and returns the cropped frames. Exposure and white balance stay locked. */
    suspend fun capture(spec: CaptureSpec, onFrame: (Int) -> Unit): List<YuvFrame> = withContext(dispatcher) {
        val s = session ?: error("Camera not ready")
        val ir = reader ?: error("Camera not ready")
        val l = lens ?: error("Camera not ready")
        val frames = Channel<YuvFrame>(Channel.UNLIMITED)
        while (true) ir.acquireNextImage()?.close() ?: break
        ir.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                frames.trySend(copyRoi(img, spec.roi))
            } catch (t: Throwable) {
                frames.close(t)
            } finally {
                img.close()
            }
        }, imageHandler)
        try {
            exposureLocked = true
            startPreview(spec.focus.first())
            withTimeoutOrNull(800) {
                results.first { value(it, CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_LOCKED }
            }
            val out = ArrayList<YuvFrame>(spec.focus.size)
            if (spec.burst) {
                s.captureBurst(spec.focus.map { still(it, spec, ir.surface) }, resultCallback, handler)
                repeat(spec.focus.size) {
                    out.add(withTimeout(FRAME_TIMEOUT_MS) { frames.receive() })
                    onFrame(out.size)
                }
            } else {
                for (d in spec.focus) {
                    // Move the lens with the preview stream, wait for it to stop, then expose.
                    startPreview(d)
                    withTimeoutOrNull(1500) {
                        results.first {
                            val st = value(it, CaptureResult.LENS_STATE)
                            val at = value(it, CaptureResult.LENS_FOCUS_DISTANCE) ?: d
                            st != CameraMetadata.LENS_STATE_MOVING && (!l.focusIsMetric || abs(at - d) <= 0.1f + 0.03f * d)
                        }
                    }
                    s.capture(still(d, spec, ir.surface), resultCallback, handler)
                    out.add(withTimeout(FRAME_TIMEOUT_MS) { frames.receive() })
                    onFrame(out.size)
                }
            }
            out
        } finally {
            ir.setOnImageAvailableListener(null, null)
            frames.close()
            exposureLocked = false
            startPreview()
        }
    }

    suspend fun close() = withContext(dispatcher) { closeInternal() }

    /** Final shutdown; the instance is unusable afterwards. */
    fun release() {
        handler.post {
            closeInternal()
            thread.quitSafely()
            imageThread.quitSafely()
        }
    }

    private fun still(focus: Float, spec: CaptureSpec, target: Surface): CaptureRequest {
        val l = lens!!
        val c = controls!!
        return builder(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(target)
            applyControls(this, c, focus)
            put(CaptureRequest.SCALER_CROP_REGION, Rect(0, 0, l.activeArray.width(), l.activeArray.height()))
            // A merge averages noise itself and sharpens once at the end, so per-frame denoising
            // and sharpening are kept minimal for bursts; a focus bracket keeps the ISP's best.
            if (spec.highQuality) {
                pick(l.noiseModes, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)?.let { put(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                pick(l.edgeModes, CameraMetadata.EDGE_MODE_HIGH_QUALITY)?.let { put(CaptureRequest.EDGE_MODE, it) }
            } else {
                pick(l.noiseModes, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    ?.let { put(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                pick(l.edgeModes, CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)?.let { put(CaptureRequest.EDGE_MODE, it) }
            }
        }.build()
    }

    private fun pick(available: List<Int>, vararg preferred: Int): Int? = preferred.firstOrNull { it in available }

    private fun startPreview(focusOverride: Float? = null) {
        val s = session ?: return
        val c = controls ?: return
        val surface = previewSurface ?: return
        try {
            val b = builder(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            applyControls(b, c, focusOverride ?: c.focusDiopters)
            c.crop?.let { b.put(CaptureRequest.SCALER_CROP_REGION, it) }
            s.setRepeatingRequest(b.build(), resultCallback, handler)
        } catch (e: CameraAccessException) {
            report("Camera unavailable: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "preview on a closed session", e)
        }
    }

    private fun applyControls(b: CaptureRequest.Builder, c: Controls, focus: Float, manualFocus: Boolean = true) {
        val l = lens ?: return
        b.put(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        if (l.manualFocus && manualFocus) {
            b.put(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.put(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, l.minFocusDiopters))
        }
        if (l.apertures.size > 1 && c.aperture != null) b.put(CaptureRequest.LENS_APERTURE, c.aperture)
        if (l.hasFlash) {
            b.put(CaptureRequest.FLASH_MODE, if (c.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        }
        b.put(CaptureRequest.CONTROL_AE_LOCK, exposureLocked)
        b.put(CaptureRequest.CONTROL_AWB_LOCK, exposureLocked)
    }

    private fun builder(template: Int): CaptureRequest.Builder {
        val dev = device ?: error("Camera not open")
        val pid = lens?.physicalId
        return if (pid != null) dev.createCaptureRequest(template, setOf(pid)) else dev.createCaptureRequest(template)
    }

    /** Sets a key on the logical request and, when streaming a physical sub-camera, on that too. */
    private fun <T> CaptureRequest.Builder.put(key: CaptureRequest.Key<T>, value: T) {
        set(key, value)
        lens?.physicalId?.let { pid -> runCatching { setPhysicalCameraKey(key, value, pid) } }
    }

    @SuppressLint("MissingPermission") // The UI only opens the camera after CAMERA is granted.
    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) dropDevice()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                    else report("The camera was taken by another app")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) dropDevice()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                    else report("Camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    private suspend fun createSession(dev: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s) else s.close()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("configuration rejected"))
                    }
                })
            try {
                val supported = try {
                    dev.isSessionConfigurationSupported(config)
                } catch (_: UnsupportedOperationException) {
                    true // The HAL cannot answer; just try it.
                }
                if (supported) dev.createCaptureSession(config)
                else cont.resumeWithException(IllegalStateException("combination unsupported"))
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    private fun dropDevice() {
        session = null
        device = null
    }

    private fun closeInternal() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        runCatching { previewSurface?.release() }
        session = null
        device = null
        reader = null
        previewSurface = null
    }

    private fun report(message: String) {
        Log.w(TAG, message)
        errorFlow.tryEmit(message)
    }

    private companion object {
        const val TAG = "MicroscopeCamera"
        const val MAX_IMAGES = 4
        const val FRAME_TIMEOUT_MS = 6000L
    }
}
