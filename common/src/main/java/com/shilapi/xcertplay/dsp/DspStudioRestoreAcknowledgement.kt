package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus

internal enum class DspStudioRestoreAcknowledgement {
    WAITING,
    APPLIED,
    REJECTED,
}

internal fun evaluateStudioRestoreAcknowledgement(
    request: DspStudioRestoreRequest,
    status: DspRendererApplyStatus?,
    committedWorkspaceActive: Boolean,
    timedOut: Boolean = false,
): DspStudioRestoreAcknowledgement {
    val requestedOwnerToken = request.rendererOwnerToken
    if (requestedOwnerToken == null) {
        return DspStudioRestoreAcknowledgement.APPLIED
    }
    val statusOwnerToken = status?.rendererOwnerToken
    if (status == null || statusOwnerToken == null || statusOwnerToken < requestedOwnerToken) {
        return if (timedOut) DspStudioRestoreAcknowledgement.REJECTED else DspStudioRestoreAcknowledgement.WAITING
    }
    if (status.generation >= request.configGeneration &&
        status.phase == DspRendererApplyPhase.ACTIVE &&
        (status.activeGeneration ?: status.generation) >= request.configGeneration &&
        committedWorkspaceActive
    ) {
        return DspStudioRestoreAcknowledgement.APPLIED
    }
    if (status.generation >= request.configGeneration &&
        (status.phase == DspRendererApplyPhase.DEFERRED || status.phase == DspRendererApplyPhase.FAILED)
    ) {
        return DspStudioRestoreAcknowledgement.REJECTED
    }
    if (status.generation > request.configGeneration || timedOut) {
        return DspStudioRestoreAcknowledgement.REJECTED
    }
    return DspStudioRestoreAcknowledgement.WAITING
}
