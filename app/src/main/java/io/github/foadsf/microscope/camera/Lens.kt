package io.github.foadsf.microscope.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import io.github.foadsf.microscope.imaging.Optics
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One rear camera module the app can stream from: either a camera ID listed by [CameraManager], or
 * a physical sub-camera of a logical multi-camera (opened through [openId], streamed from
 * [physicalId]). Everything here comes from [CameraCharacteristics]; nothing is hard-coded per
 * phone model.
 */
data class Lens(
    val openId: String,
    val physicalId: String?,
    val label: String,
    val focalMm: Float,
    val equivalentMm: Int,
    val apertures: List<Float>,
    /** Closest focus in diopters; 0 means fixed focus. */
    val minFocusDiopters: Float,
    val focusCalibration: Int,
    val pixelPitchUm: Float,
    val activeArray: Rect,
    val sensorOrientation: Int,
    val hasFlash: Boolean,
    val manualFocus: Boolean,
    val afAuto: Boolean,
    val maxAfRegions: Int,
    val maxDigitalZoom: Float,
    val noiseModes: List<Int>,
    val edgeModes: List<Int>,
    val yuvSizes: List<Size>,
    val previewSizes: List<Size>,
    val hardwareLevel: Int,
    val capabilities: List<Int>,
    /** The ISP can only crop around the sensor centre (all LEGACY devices, and some others). */
    val centerOnlyCrop: Boolean,
    /** Torch brightness steps usable inside a capture session (Android 15+); 1 means on/off only. */
    val torchStrengthMax: Int,
    val torchStrengthDefault: Int,
    val oisModes: List<Int>,
) {
    val key get() = "$openId/${physicalId ?: ""}"

    /** Focus distances are physical units (diopters), not just an ordering. */
    val focusIsMetric get() = focusCalibration != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED

    /** Specimen-side size of one sensor pixel at closest focus: what ranks lenses for macro work. */
    val closestObjectPixelUm: Float
        get() = if (!manualFocus || !focusIsMetric) Float.POSITIVE_INFINITY
        else Optics.objectPixelUm(pixelPitchUm, focalMm, Optics.distanceMm(minFocusDiopters))
}

object LensCatalog {
    fun rearLenses(manager: CameraManager): List<Lens> {
        val found = ArrayList<Lens>()
        val listed = manager.cameraIdList.toSet()
        for (id in manager.cameraIdList) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            describe(id, null, chars)?.let(found::add)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                for (pid in chars.physicalCameraIds) {
                    if (pid in listed) continue
                    val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    describe(id, pid, pc)?.let(found::add)
                }
            }
        }
        // A logical camera usually duplicates one of its physical modules; keep one of each.
        val unique = found.distinctBy { Triple(it.focalMm, it.minFocusDiopters, it.activeArray.width()) }
        val sorted = unique.sortedWith(compareBy<Lens>({ it.closestObjectPixelUm }, { -it.minFocusDiopters }, { it.equivalentMm }))
        // Disambiguate identical labels.
        val counts = sorted.groupingBy { it.label }.eachCount()
        return sorted.map { if (counts.getValue(it.label) > 1) it.copy(label = "${it.label} · ${it.physicalId ?: it.openId}") else it }
    }

    private fun describe(openId: String, physicalId: String?, c: CameraCharacteristics): Lens? {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val preview = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (yuv.isEmpty() || preview.isEmpty()) return null

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        // Manual focus needs AF_MODE_OFF plus a focusable lens; MANUAL_SENSOR makes it reliable.
        val manualFocus = minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes &&
            (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps ||
                c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY)

        val sensorW = physical?.width ?: 0f
        val sensorH = physical?.height ?: 0f
        val pitchUm = if (physical != null && pixels != null && pixels.width > 0) physical.width / pixels.width * 1000f else 1.0f
        val diag = sqrt(sensorW * sensorW + sensorH * sensorH)
        val equiv = if (diag > 0f) (focal * 43.27f / diag).roundToInt() else 0
        val kind = when {
            equiv in 1..19 -> "Ultra-wide"
            equiv in 20..39 -> "Wide"
            equiv >= 40 -> "Tele"
            else -> "Camera"
        }
        return Lens(
            openId = openId,
            physicalId = physicalId,
            label = if (equiv > 0) "$kind $equiv mm" else "$kind ${physicalId ?: openId}",
            focalMm = focal,
            equivalentMm = equiv,
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList()?.sorted().orEmpty(),
            minFocusDiopters = minFocus,
            focusCalibration = c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
                ?: CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED,
            pixelPitchUm = pitchUm,
            activeArray = active,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            manualFocus = manualFocus,
            afAuto = CameraMetadata.CONTROL_AF_MODE_AUTO in afModes && minFocus > 0f,
            maxAfRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            noiseModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty(),
            edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty(),
            yuvSizes = yuv,
            previewSizes = preview,
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            capabilities = caps.toList(),
            centerOnlyCrop = c.get(CameraCharacteristics.SCALER_CROPPING_TYPE) != CameraMetadata.SCALER_CROPPING_TYPE_FREEFORM,
            torchStrengthMax = if (Build.VERSION.SDK_INT >= 35) c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1 else 1,
            torchStrengthDefault = if (Build.VERSION.SDK_INT >= 35) c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_DEFAULT_LEVEL) ?: 1 else 1,
            oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty(),
        )
    }

    /**
     * YUV sizes worth trying for the capture stream, best first: the four largest, then the
     * largest few at or below preview size, which every hardware level guarantees alongside a
     * preview stream.
     */
    fun captureCandidates(lens: Lens): List<Size> {
        val all = matchingAspect(lens.yuvSizes, lens.activeArray)
        val small = all.filter { it.width.toLong() * it.height <= 1920L * 1440 }
        return (all.take(4) + small.take(3)).distinct()
    }

    /** Output sizes with the active array's aspect ratio, largest first. */
    fun matchingAspect(sizes: List<Size>, active: Rect): List<Size> {
        val target = active.width().toFloat() / active.height()
        val same = sizes.filter { abs(it.width.toFloat() / it.height - target) < 0.02f }
        return (same.ifEmpty { sizes }).sortedByDescending { it.width.toLong() * it.height }
    }
}
