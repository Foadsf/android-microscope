package io.github.foadsf.microscope.imaging

import kotlin.math.abs

/** A known length measured with one lens at one focus setting: the specimen size of a sensor pixel there. */
data class Calibration(val focus: Float, val umPerSensorPixel: Float) {
    /** True when [focus] is close enough to the calibrated setting for the calibration to hold as measured. */
    fun covers(focus: Float) = abs(focus - this.focus) <= TOLERANCE_ABS + TOLERANCE_REL * this.focus

    companion object {
        // Magnification is roughly proportional to the focus setting in diopters at macro range, so
        // 2 % of the setting is about 2 % of scale.
        const val TOLERANCE_REL = 0.02f
        const val TOLERANCE_ABS = 0.02f

        fun encode(c: Calibration) = "${c.focus};${c.umPerSensorPixel}"

        fun decode(s: String?): Calibration? {
            val parts = s?.split(';') ?: return null
            if (parts.size != 2) return null
            val focus = parts[0].toFloatOrNull() ?: return null
            val um = parts[1].toFloatOrNull() ?: return null
            return if (um > 0f && um.isFinite()) Calibration(focus, um) else null
        }
    }
}

/** Specimen size of one sensor pixel, and whether it rests on a calibration rather than the lens model. */
data class ScaleEstimate(val umPerSensorPixel: Float, val calibrated: Boolean)

object Scale {
    /**
     * The scale at focus setting [focus]. [model] is the thin-lens estimate for a focus setting, or
     * null when the lens does not report metric focus distances.
     *
     * A calibration corrects the model by the ratio found at its own setting. Near that setting the
     * result is as good as the calibration; further away it is still better than the bare model
     * (most of the model's error is a slowly varying offset in the reported distance) but is marked
     * approximate. Without a model a calibration only holds at its own setting.
     */
    fun estimate(model: ((Float) -> Float?)?, calibration: Calibration?, focus: Float): ScaleEstimate? {
        val here = model?.invoke(focus)?.takeIf { it.isFinite() && it > 0f }
        if (calibration == null) return here?.let { ScaleEstimate(it, calibrated = false) }
        val covered = calibration.covers(focus)
        if (here == null) return if (covered) ScaleEstimate(calibration.umPerSensorPixel, calibrated = true) else null
        val there = model.invoke(calibration.focus)?.takeIf { it.isFinite() && it > 0f }
            ?: return ScaleEstimate(here, calibrated = false)
        return ScaleEstimate(here * calibration.umPerSensorPixel / there, calibrated = covered)
    }
}
