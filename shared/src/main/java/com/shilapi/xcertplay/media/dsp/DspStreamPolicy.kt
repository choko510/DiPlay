package com.shilapi.xcertplay.media.dsp

internal object DspStreamPolicy {
    fun allowsFullDsp(role: DspStreamRole): Boolean = role == DspStreamRole.MEDIA

    fun reportPlaybackSampleRate(
        role: DspStreamRole,
        owner: Any,
        sampleRateHz: Int?,
        report: (Any, Int?) -> Unit,
    ) {
        if (role == DspStreamRole.MEDIA) report(owner, sampleRateHz)
    }

    fun shouldProcess(configuredEnabled: Boolean, role: DspStreamRole): Boolean =
        configuredEnabled && allowsFullDsp(role)
}
