package com.shilapi.xcertplay.media.dsp

internal object DspStreamPolicy {
    fun allowsFullDsp(role: DspStreamRole): Boolean = role == DspStreamRole.MEDIA
}
