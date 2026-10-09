package com.shilapi.xcertplay.media.dsp

internal object DspStreamPolicy {
    fun allowsFullDsp(role: DspStreamRole): Boolean = role == DspStreamRole.MEDIA

    fun shouldProcess(configuredEnabled: Boolean, role: DspStreamRole): Boolean =
        configuredEnabled && allowsFullDsp(role)
}
