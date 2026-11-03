package io.github.foadsf.microscope

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.os.Build
import io.github.foadsf.microscope.camera.Lens
import io.github.foadsf.microscope.imaging.Optics
import java.util.Locale

/**
 * What the app detected about this phone, in plain text a tester can paste into an issue.
 * Everything comes from Build and CameraCharacteristics; nothing here is model-specific.
 */
object Diagnostics {
    fun report(context: Context, s: UiState): String = buildString {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("Microscope $version")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
        appendLine("Build: ${Build.DISPLAY}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        val heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        appendLine("Cores: ${Runtime.getRuntime().availableProcessors()}, heap limit $heapMb MB, gyroscope ${yes(SteadyHold.available(context))}")
        s.streams?.let { appendLine("Open: ${it.lens.label}, preview ${it.previewSize}, capture ${it.captureSize}") }
        appendLine()
        appendLine("Rear lenses (${s.lenses.size}), best for close-up first:")
        for (l in s.lenses) describe(this, l)
    }

    private fun describe(out: StringBuilder, l: Lens) = with(out) {
        val id = if (l.physicalId != null) "camera ${l.openId} → physical ${l.physicalId}" else "camera ${l.openId}"
        appendLine("• ${l.label} [$id]")
        val apertures = l.apertures.joinToString(" ") { "f/" + fmt(it, 1) }.ifEmpty { "unknown" }
        appendLine("  f = ${fmt(l.focalMm, 2)} mm (≈ ${l.equivalentMm} mm equiv.), pixel ${fmt(l.pixelPitchUm, 2)} µm, aperture $apertures")
        if (l.minFocusDiopters > 0f) {
            val mm = Optics.distanceMm(l.minFocusDiopters)
            appendLine("  closest focus ${fmt(l.minFocusDiopters, 2)} D (≈ ${mm.toInt()} mm), calibration ${calibration(l.focusCalibration)}")
        } else {
            appendLine("  fixed focus")
        }
        appendLine("  manual focus ${yes(l.manualFocus)}, tap AF ${yes(l.afAuto)}, OIS ${yes(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in l.oisModes)}")
        val crop = if (l.centerOnlyCrop) "centre only" else "freeform"
        appendLine("  hardware level ${level(l.hardwareLevel)}, crop $crop, max ISP zoom ${fmt(l.maxDigitalZoom, 1)}×")
        appendLine("  sensor orientation ${l.sensorOrientation}°, frame clock shared with gyroscope ${yes(l.realtimeTimestamps)}")
        appendLine("  capabilities: ${l.capabilities.joinToString(", ") { capability(it) }}")
        val torch = when {
            !l.hasFlash -> "no flash"
            l.torchStrengthMax > 1 -> "torch with ${l.torchStrengthMax} brightness levels"
            else -> "torch on/off"
        }
        val yuv = l.yuvSizes.maxByOrNull { it.width.toLong() * it.height }
        val ev = if (l.evRange.first < l.evRange.last) {
            "EV ${fmt(l.evRange.first * l.evStep, 1)}..${fmt(l.evRange.last * l.evStep, 1)}"
        } else {
            "no EV compensation"
        }
        appendLine("  $torch, $ev, largest YUV $yuv")
        if (l.closestObjectPixelUm.isFinite()) {
            appendLine("  at closest focus ≈ ${fmt(l.closestObjectPixelUm, 1)} µm per sensor pixel")
        }
    }

    private fun fmt(v: Float, digits: Int) = "%.${digits}f".format(Locale.ROOT, v)

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    private fun calibration(c: Int) = when (c) {
        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
        else -> "UNCALIBRATED (units are not diopters)"
    }

    private fun level(l: Int) = when (l) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "level $l"
    }

    private val CAPABILITIES = listOf(
        "BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW", "PRIVATE_REPROCESSING",
        "READ_SENSOR_SETTINGS", "BURST_CAPTURE", "YUV_REPROCESSING", "DEPTH_OUTPUT", "HIGH_SPEED_VIDEO",
        "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA", "MONOCHROME", "SECURE_IMAGE_DATA", "SYSTEM_CAMERA",
        "OFFLINE_PROCESSING", "ULTRA_HIGH_RESOLUTION_SENSOR", "REMOSAIC_REPROCESSING", "TEN_BIT_OUTPUT",
        "STREAM_USE_CASE", "COLOR_SPACE_PROFILES",
    )

    private fun capability(c: Int) = CAPABILITIES.getOrNull(c) ?: c.toString()
}
