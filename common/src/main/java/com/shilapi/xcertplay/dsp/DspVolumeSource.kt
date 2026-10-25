package com.shilapi.xcertplay.dsp

/** Supplies user playback-volume state outside the audio processing callback. */
interface DspVolumeSource {
    fun read(): DspVolumeReading
}

/** Adapter for a platform stream-volume API after its device/route checks have completed. */
class AndroidStreamVolumeSource(
    private val readStreamVolume: () -> DspVolumeReading,
) : DspVolumeSource {
    override fun read(): DspVolumeReading = runCatching { readStreamVolume().validated() }
        .getOrDefault(DspVolumeReading())
}

class ManualVolumeSource(initialLevel: Double = 0.5) : DspVolumeSource {
    var level: Double = initialLevel
        set(value) {
            require(value.isFinite() && value in 0.0..1.0)
            field = value
        }

    init {
        require(initialLevel.isFinite() && initialLevel in 0.0..1.0)
    }

    override fun read() = DspVolumeReading(volumeFraction = level, trustworthy = false)
}

class FixedVolumeSource(initialAmount: Double = 0.5) : DspVolumeSource {
    var amount: Double = initialAmount
        set(value) {
            require(value.isFinite() && value in 0.0..1.0)
            field = value
        }

    init {
        require(initialAmount.isFinite() && initialAmount in 0.0..1.0)
    }

    override fun read() = DspVolumeReading(volumeFraction = amount, trustworthy = false)
}

/** Collects a two-point, route-specific calibration without replacing an existing one implicitly. */
class DspLoudnessCalibrationController(
    initialCalibration: DspLoudnessCalibration? = null,
) {
    var calibration: DspLoudnessCalibration? = initialCalibration
        private set

    private var quietPoint: DspVolumeReading? = null

    fun captureQuietPoint(reading: DspVolumeReading): Boolean {
        val valid = reading.validTrustedReading() ?: return false
        quietPoint = valid
        return true
    }

    fun captureReferencePoint(reading: DspVolumeReading): DspLoudnessCalibration? {
        val quiet = quietPoint ?: return null
        val reference = reading.validTrustedReading() ?: return null
        if (quiet.routeToken != reference.routeToken) return null
        val quietVolume = quiet.volumeFraction ?: return null
        val referenceVolume = reference.volumeFraction ?: return null
        if (referenceVolume <= quietVolume) return null
        val next = DspLoudnessCalibration(
            routeToken = quiet.routeToken ?: return null,
            quietVolumeFraction = quietVolume,
            referenceVolumeFraction = referenceVolume,
        )
        calibration = next
        quietPoint = null
        return next
    }

    fun cancel() {
        quietPoint = null
    }

    /** Calibration replacement is explicit so an accidental route/sample cannot erase user work. */
    fun replaceCalibration(newCalibration: DspLoudnessCalibration?) {
        calibration = newCalibration
        quietPoint = null
    }
}

private fun DspVolumeReading.validated(): DspVolumeReading {
    if (!trustworthy || routeToken == null || volumeFraction == null) return DspVolumeReading()
    return this
}

private fun DspVolumeReading.validTrustedReading(): DspVolumeReading? {
    if (!trustworthy || routeToken == null || volumeFraction == null) return null
    if (!volumeFraction.isFinite() || volumeFraction !in 0.0..1.0) return null
    return this
}
