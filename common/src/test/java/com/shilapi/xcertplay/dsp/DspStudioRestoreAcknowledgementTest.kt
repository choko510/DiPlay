package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DspStudioRestoreAcknowledgementTest {
    @Test
    fun noRendererOwnerMeansCommittedWorkspaceIsReadyForNextTrack() {
        assertEquals(
            DspStudioRestoreAcknowledgement.APPLIED,
            evaluateStudioRestoreAcknowledgement(
                request = DspStudioRestoreRequest(configGeneration = 5, rendererOwnerToken = null),
                status = null,
                committedWorkspaceActive = true,
            ),
        )
    }

    @Test
    fun ownedRequestWaitsWhenRendererStatusDisappearsOrLosesItsOwner() {
        val request = DspStudioRestoreRequest(configGeneration = 5, rendererOwnerToken = 9)

        assertEquals(
            DspStudioRestoreAcknowledgement.WAITING,
            evaluateStudioRestoreAcknowledgement(request, status = null, committedWorkspaceActive = true),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.WAITING,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(phase = DspRendererApplyPhase.ACTIVE, generation = 5, ownerToken = null),
                committedWorkspaceActive = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.REJECTED,
            evaluateStudioRestoreAcknowledgement(
                request,
                status = null,
                committedWorkspaceActive = true,
                timedOut = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.REJECTED,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(phase = DspRendererApplyPhase.ACTIVE, generation = 5, ownerToken = null),
                committedWorkspaceActive = true,
                timedOut = true,
            ),
        )
    }

    @Test
    fun staleRendererOwnerCannotAcknowledgeAnOwnedRestore() {
        val request = DspStudioRestoreRequest(configGeneration = 5, rendererOwnerToken = 9)

        assertEquals(
            DspStudioRestoreAcknowledgement.WAITING,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.ACTIVE, generation = 5, activeGeneration = 5, ownerToken = 8),
                committedWorkspaceActive = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.REJECTED,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.ACTIVE, generation = 5, activeGeneration = 5, ownerToken = 8),
                committedWorkspaceActive = true,
                timedOut = true,
            ),
        )
    }

    @Test
    fun pendingOrOlderActiveGenerationDoesNotConfirmCancel() {
        val request = DspStudioRestoreRequest(configGeneration = 5, rendererOwnerToken = 9)

        assertEquals(
            DspStudioRestoreAcknowledgement.WAITING,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.PREPARING, generation = 5),
                committedWorkspaceActive = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.WAITING,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.ACTIVE, generation = 5, activeGeneration = 4),
                committedWorkspaceActive = true,
            ),
        )
    }

    @Test
    fun staleOwnerFailureCannotResolveTheCurrentOwnersRestoreRequest() {
        val request = DspStudioRestoreRequest(configGeneration = 5, rendererOwnerToken = 9)
        val staleOwnerFailure = status(
            DspRendererApplyPhase.FAILED,
            generation = 5,
            ownerToken = 8,
        )

        assertEquals(
            DspStudioRestoreAcknowledgement.WAITING,
            evaluateStudioRestoreAcknowledgement(
                request,
                staleOwnerFailure,
                committedWorkspaceActive = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.REJECTED,
            evaluateStudioRestoreAcknowledgement(
                request,
                staleOwnerFailure,
                committedWorkspaceActive = true,
                timedOut = true,
            ),
        )
    }

    @Test
    fun onlyTheRequestedGenerationWithCommittedWorkspaceAcknowledgesAudioRestore() {
        val request = DspStudioRestoreRequest(configGeneration = 5, rendererOwnerToken = 9)

        assertEquals(
            DspStudioRestoreAcknowledgement.WAITING,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.ACTIVE, generation = 5, activeGeneration = 5),
                committedWorkspaceActive = false,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.APPLIED,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.ACTIVE, generation = 5, activeGeneration = 5),
                committedWorkspaceActive = true,
            ),
        )
    }

    @Test
    fun replacementRendererMustApplyTheCommittedGenerationBeforeAcknowledging() {
        val request = DspStudioRestoreRequest(configGeneration = 5, rendererOwnerToken = 9)

        assertEquals(
            DspStudioRestoreAcknowledgement.WAITING,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.REQUESTED, generation = 5, ownerToken = 10),
                committedWorkspaceActive = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.WAITING,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.ACTIVE, generation = 5, activeGeneration = 4, ownerToken = 10),
                committedWorkspaceActive = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.APPLIED,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.ACTIVE, generation = 5, activeGeneration = 5, ownerToken = 10),
                committedWorkspaceActive = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.REJECTED,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.ACTIVE, generation = 6, activeGeneration = 4, ownerToken = 10),
                committedWorkspaceActive = true,
            ),
        )
    }

    @Test
    fun deferredFailedAndSupersededRestoreRequestsAreRejected() {
        val request = DspStudioRestoreRequest(configGeneration = 5, rendererOwnerToken = 9)

        assertEquals(
            DspStudioRestoreAcknowledgement.REJECTED,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.DEFERRED, generation = 5),
                committedWorkspaceActive = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.REJECTED,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.FAILED, generation = 5),
                committedWorkspaceActive = true,
            ),
        )
        assertEquals(
            DspStudioRestoreAcknowledgement.REJECTED,
            evaluateStudioRestoreAcknowledgement(
                request,
                status(DspRendererApplyPhase.ACTIVE, generation = 6, activeGeneration = 4),
                committedWorkspaceActive = true,
            ),
        )
    }

    @Test
    fun timeoutRejectsAnUnacknowledgedRestore() {
        assertEquals(
            DspStudioRestoreAcknowledgement.REJECTED,
            evaluateStudioRestoreAcknowledgement(
                DspStudioRestoreRequest(configGeneration = 5, rendererOwnerToken = 9),
                status(DspRendererApplyPhase.PREPARED, generation = 5),
                committedWorkspaceActive = true,
                timedOut = true,
            ),
        )
    }

    private fun status(
        phase: DspRendererApplyPhase,
        generation: Long,
        activeGeneration: Long? = null,
        ownerToken: Long? = 9,
    ) = DspRendererApplyStatus(
        generation = generation,
        phase = phase,
        activeGeneration = activeGeneration,
        rendererOwnerToken = ownerToken,
    )
}
