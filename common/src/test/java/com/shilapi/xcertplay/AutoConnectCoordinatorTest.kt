package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoConnectCoordinatorTest {
    private var now = 1_000L
    private val events = mutableListOf<String>()
    private lateinit var coordinator: AutoConnectCoordinator

    @Before
    fun setUp() {
        now = 1_000L
        events.clear()
        coordinator = AutoConnectCoordinator(
            clock = { now },
            logger = { events += it },
            maxAttempts = 3,
            retryDelaysMillis = longArrayOf(1_000L, 2_000L),
            staleRequestMillis = 10_000L,
        )
    }

    @Test
    fun ac01_enabledLaunchStartsOnce() {
        val outcome = coordinator.request(request(), conditions())

        assertTrue(outcome is AutoConnectOutcome.Start)
        val start = outcome as AutoConnectOutcome.Start
        assertEquals(1, start.attempt)
        assertEquals(AutoConnectTransport.WIRELESS, start.request.transport)
        assertTrue(events.any { it.contains("decision=START") })
        assertTrue(events.any { it.contains("source=APP_LAUNCH") })
    }

    @Test
    fun ac02_disabledSettingNeverStarts() {
        val outcome = coordinator.request(request(), conditions(enabled = false))

        assertEquals(AutoConnectSkipReason.DISABLED, (outcome as AutoConnectOutcome.Skip).reason)
        assertEquals(AutoConnectState.IDLE, coordinator.currentState())
        assertTrue(events.any { it.contains("decision=SKIP reason=DISABLED") })
    }

    @Test
    fun ac03_permissionGrantContinuesTheSameRequest() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        assertTrue(coordinator.postponeForPermission(start.generation))
        assertEquals(AutoConnectState.WAITING_FOR_PERMISSION, coordinator.currentState())

        val waiting = coordinator.request(request(), conditions())
        assertEquals(AutoConnectSkipReason.WAITING_FOR_PERMISSION, (waiting as AutoConnectOutcome.Skip).reason)

        assertSame(
            AutoConnectOutcome.ContinueAfterPermission,
            coordinator.onPermissionResult(start.generation, granted = true),
        )
        assertEquals(AutoConnectState.STARTING, coordinator.currentState())

        coordinator.onSessionActive()
        assertEquals(AutoConnectState.ACTIVE, coordinator.currentState())
    }

    @Test
    fun ac04_permissionDenialBlocksWithoutRetryStorm() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.postponeForPermission(start.generation)

        assertEquals(
            AutoConnectOutcome.Blocked(AutoConnectSkipReason.PERMISSION_DENIED),
            coordinator.onPermissionResult(start.generation, granted = false),
        )
        assertEquals(AutoConnectState.BLOCKED, coordinator.currentState())

        repeat(5) {
            val outcome = coordinator.request(request(), conditions())
            assertEquals(AutoConnectSkipReason.PERMISSION_DENIED, (outcome as AutoConnectOutcome.Skip).reason)
        }
        assertEquals(AutoConnectSkipReason.PERMISSION_DENIED, (coordinator.retryDue(conditions()) as AutoConnectOutcome.Skip).reason)
        assertFalse(events.any { it.contains("decision=START") && events.count { event -> event.contains("decision=START") } > 1 })
    }

    @Test
    fun ac04_hostPermissionDenialBlocksAutomaticRecovery() {
        coordinator.request(request(), conditions())
        coordinator.onHostPermissionDenied()

        assertEquals(AutoConnectState.BLOCKED, coordinator.currentState())
        assertEquals(
            AutoConnectSkipReason.PERMISSION_DENIED,
            (coordinator.request(request(), conditions()) as AutoConnectOutcome.Skip).reason,
        )
        assertTrue(coordinator.suppressesHostRecovery(autoConnectEnabled = true))
    }

    @Test
    fun ac05_recreatedActivityReusesTheSameRequest() {
        coordinator.request(request(), conditions())

        val duplicate = coordinator.request(request(), conditions())
        assertEquals(AutoConnectSkipReason.REQUEST_IN_FLIGHT, (duplicate as AutoConnectOutcome.Skip).reason)

        now += 10_001L
        val restarted = coordinator.request(request(), conditions())
        assertTrue(restarted is AutoConnectOutcome.Start)
    }

    @Test
    fun ac06_repeatedOnResumeDoesNotStartTwice() {
        val first = coordinator.request(request(), conditions())
        val second = coordinator.request(request(), conditions())
        val third = coordinator.request(request(), conditions())

        assertTrue(first is AutoConnectOutcome.Start)
        assertEquals(AutoConnectSkipReason.REQUEST_IN_FLIGHT, (second as AutoConnectOutcome.Skip).reason)
        assertEquals(AutoConnectSkipReason.REQUEST_IN_FLIGHT, (third as AutoConnectOutcome.Skip).reason)
        assertEquals(1, events.count { it.contains("decision=START") })
    }

    @Test
    fun ac07_newIntentWhileStartingIsDeduplicated() {
        coordinator.request(request(key = "launch"), conditions())

        val newIntent = coordinator.request(
            request(key = "new-intent", source = AutoConnectSource.NEW_INTENT),
            conditions(),
        )

        assertEquals(AutoConnectSkipReason.REQUEST_IN_FLIGHT, (newIntent as AutoConnectOutcome.Skip).reason)

        coordinator.onSessionActive()
        val withSession = coordinator.request(
            request(key = "new-intent-2", source = AutoConnectSource.NEW_INTENT),
            conditions(session = AutoConnectSessionState.ACTIVE),
        )
        assertEquals(AutoConnectSkipReason.SESSION_EXISTS, (withSession as AutoConnectOutcome.Skip).reason)
    }

    @Test
    fun ac08_existingSessionNeverStartsAnotherConnection() {
        val outcome = coordinator.request(request(), conditions(session = AutoConnectSessionState.ACTIVE))

        assertEquals(AutoConnectSkipReason.SESSION_EXISTS, (outcome as AutoConnectOutcome.Skip).reason)
        assertEquals(AutoConnectState.ACTIVE, coordinator.currentState())
    }

    @Test
    fun ac09_secondLaunchRequestDuringConnectionIsIgnored() {
        coordinator.request(request(key = "first"), conditions())

        val second = coordinator.request(request(key = "second"), conditions())

        assertEquals(AutoConnectSkipReason.REQUEST_IN_FLIGHT, (second as AutoConnectOutcome.Skip).reason)
    }

    @Test
    fun ac10_activityRecreationImmediatelyAfterConnectionDoesNotDuplicate() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.onHostLaunchFailed(start.generation, AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE)
        now += 1_000L
        val retry = coordinator.retryDue(conditions()) as AutoConnectOutcome.Start

        now += 50L
        val duplicate = coordinator.request(request(), conditions())

        assertEquals(AutoConnectSkipReason.REQUEST_IN_FLIGHT, (duplicate as AutoConnectOutcome.Skip).reason)
        assertEquals(retry.generation, coordinator.activeGeneration())
    }

    @Test
    fun ac11_launchFailureRetriesWithBackoffThenStops() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        val firstRetry = coordinator.onHostLaunchFailed(
            start.generation,
            AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE,
        ) as AutoConnectOutcome.RetryScheduled
        assertEquals(1_000L, firstRetry.delayMillis)
        assertEquals(2, firstRetry.attempt)

        assertEquals(
            AutoConnectSkipReason.RETRY_NOT_DUE,
            (coordinator.retryDue(conditions(), now + 500L) as AutoConnectOutcome.Skip).reason,
        )
        now += 1_000L
        val second = coordinator.retryDue(conditions()) as AutoConnectOutcome.Start
        assertEquals(2, second.attempt)

        val secondRetry = coordinator.onHostLaunchFailed(
            second.generation,
            AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE,
        ) as AutoConnectOutcome.RetryScheduled
        assertEquals(2_000L, secondRetry.delayMillis)
        now += 2_000L
        val third = coordinator.retryDue(conditions()) as AutoConnectOutcome.Start
        assertEquals(3, third.attempt)

        assertEquals(
            AutoConnectOutcome.Exhausted(third.generation),
            coordinator.onHostLaunchFailed(third.generation, AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE),
        )
        assertEquals(AutoConnectState.EXHAUSTED, coordinator.currentState())
        assertEquals(
            AutoConnectSkipReason.RETRY_EXHAUSTED,
            (coordinator.retryDue(conditions()) as AutoConnectOutcome.Skip).reason,
        )
        assertEquals(
            AutoConnectSkipReason.RETRY_EXHAUSTED,
            (coordinator.request(request(), conditions()) as AutoConnectOutcome.Skip).reason,
        )
    }

    @Test
    fun ac12_userDisconnectCancelsAScheduledRetry() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.onHostLaunchFailed(start.generation, AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE)

        coordinator.onUserDisconnect()
        now += 5_000L

        assertEquals(
            AutoConnectSkipReason.USER_DISCONNECTED,
            (coordinator.retryDue(conditions()) as AutoConnectOutcome.Skip).reason,
        )
        assertEquals(AutoConnectState.SUPPRESSED, coordinator.currentState())
    }

    @Test
    fun ac13_autoConnectOffDuringRetryCancelsTheRetry() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.onHostLaunchFailed(start.generation, AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE)
        now += 5_000L

        assertEquals(
            AutoConnectSkipReason.DISABLED,
            (coordinator.retryDue(conditions(enabled = false)) as AutoConnectOutcome.Skip).reason,
        )
        assertEquals(AutoConnectState.IDLE, coordinator.currentState())
    }

    @Test
    fun ac13_hostRecoveryIsSuppressedOnlyForAutoInitiatedSessions() {
        coordinator.request(request(), conditions())
        coordinator.onSessionActive()

        assertFalse(coordinator.suppressesHostRecovery(autoConnectEnabled = true))
        assertTrue(coordinator.suppressesHostRecovery(autoConnectEnabled = false))

        coordinator.onExplicitConnect()
        assertFalse(coordinator.suppressesHostRecovery(autoConnectEnabled = false))
    }

    @Test
    fun ac13_userDisconnectSuppressesAStaleHostRecovery() {
        coordinator.request(request(), conditions())
        coordinator.onUserDisconnect()

        assertTrue(coordinator.suppressesHostRecovery(autoConnectEnabled = true))
    }

    @Test
    fun ac13_blockedPermissionSuppressesHostRecovery() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.postponeForPermission(start.generation)
        coordinator.onPermissionResult(start.generation, granted = false)

        assertTrue(coordinator.suppressesHostRecovery(autoConnectEnabled = true))
    }

    @Test
    fun ac14_oldCallbackCannotDisturbTheNewAttempt() {
        val first = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.onHostLaunchFailed(first.generation, AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE)
        now += 1_000L
        val second = coordinator.retryDue(conditions()) as AutoConnectOutcome.Start

        assertSame(
            AutoConnectOutcome.StaleCallbackIgnored,
            coordinator.onHostLaunchFailed(first.generation, AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE),
        )
        assertEquals(second.generation, coordinator.activeGeneration())
        assertEquals(AutoConnectState.STARTING, coordinator.currentState())
    }

    @Test
    fun ac15_usbAutoLaunchWinsOverWirelessAutoConnect() {
        val outcome = coordinator.request(request(), conditions(usbAutoLaunchActive = true))

        assertEquals(AutoConnectSkipReason.USB_AUTO_LAUNCH_ACTIVE, (outcome as AutoConnectOutcome.Skip).reason)
    }

    @Test
    fun ac16_previousWiredSelectionIsPreserved() {
        val start = coordinator.request(
            request(transport = AutoConnectTransport.WIRED),
            conditions(),
        ) as AutoConnectOutcome.Start

        assertEquals(AutoConnectTransport.WIRED, start.request.transport)
    }

    @Test
    fun ac17_previousWirelessSelectionIsPreserved() {
        val start = coordinator.request(
            request(transport = AutoConnectTransport.WIRELESS),
            conditions(),
        ) as AutoConnectOutcome.Start

        assertEquals(AutoConnectTransport.WIRELESS, start.request.transport)
    }

    @Test
    fun ac18_manualConnectTakesPriorityOverAutoConnect() {
        coordinator.onExplicitConnect()

        val duringManual = coordinator.request(request(), conditions())
        assertEquals(
            AutoConnectSkipReason.MANUAL_CONNECT_IN_PROGRESS,
            (duringManual as AutoConnectOutcome.Skip).reason,
        )

        coordinator.onSessionActive()
        val withSession = coordinator.request(request(), conditions(session = AutoConnectSessionState.ACTIVE))
        assertEquals(AutoConnectSkipReason.SESSION_EXISTS, (withSession as AutoConnectOutcome.Skip).reason)
    }

    @Test
    fun ac19_initializationFailureDoesNotRetryOnItsOwn() {
        val failed = coordinator.request(request(), conditions(setupReady = false))
        assertEquals(AutoConnectSkipReason.SETUP_FAILED, (failed as AutoConnectOutcome.Skip).reason)
        assertEquals(AutoConnectState.BLOCKED, coordinator.currentState())

        val stillBlocked = coordinator.request(request(), conditions(setupReady = true))
        assertEquals(AutoConnectSkipReason.SETUP_FAILED, (stillBlocked as AutoConnectOutcome.Skip).reason)

        coordinator.onExplicitConnect()
        now += 10_001L
        assertTrue(coordinator.request(request(), conditions(setupReady = true)) is AutoConnectOutcome.Start)
    }

    @Test
    fun ac20_bootRequestIsAcceptedWithBootSource() {
        val outcome = coordinator.request(
            request(source = AutoConnectSource.BOOT),
            conditions(),
        )

        assertTrue(outcome is AutoConnectOutcome.Start)
        assertTrue(events.any { it.contains("event=REQUESTED source=BOOT") })
    }

    @Test
    fun ac22_sessionLossRequiresANewExplicitOrLaunchTrigger() {
        coordinator.request(request(), conditions())
        coordinator.onSessionActive()
        assertEquals(AutoConnectState.ACTIVE, coordinator.currentState())

        coordinator.observeSession(activeSession = false, sessionPresent = false)
        assertEquals(AutoConnectState.IDLE, coordinator.currentState())
        assertEquals(1, events.count { it.contains("decision=START") })

        val nextLaunch = coordinator.request(request(key = "next-launch"), conditions())
        assertTrue(nextLaunch is AutoConnectOutcome.Start)
    }

    @Test
    fun ac25_explicitDisconnectSuppressesFurtherAutomaticConnections() {
        coordinator.onUserDisconnect()

        repeat(3) {
            assertEquals(
                AutoConnectSkipReason.USER_DISCONNECTED,
                (coordinator.request(request(), conditions()) as AutoConnectOutcome.Skip).reason,
            )
        }
        assertNull(coordinator.activeGeneration())

        coordinator.onExplicitConnect()
        now += 10_001L
        assertNotNull(coordinator.request(request(), conditions()) as? AutoConnectOutcome.Start)
    }

    @Test
    fun logsContainNoTransportAddressOrPairingSecrets() {
        coordinator.request(request(transport = AutoConnectTransport.WIRED), conditions())
        val log = events.joinToString("\n")

        assertFalse(log.contains(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")))
        assertFalse(log.contains("pk8", ignoreCase = true))
        assertFalse(log.contains("pair", ignoreCase = true))
    }

    @Test
    fun newLaunchKeyResetsAnExhaustedRetryBudget() {
        exhaustRetries()
        assertEquals(AutoConnectState.EXHAUSTED, coordinator.currentState())
        assertEquals(
            AutoConnectSkipReason.RETRY_EXHAUSTED,
            (coordinator.request(request(), conditions()) as AutoConnectOutcome.Skip).reason,
        )

        now += 1_000L
        assertTrue(coordinator.request(request(key = "next-launch"), conditions()) is AutoConnectOutcome.Start)
    }

    @Test
    fun newLaunchKeyResetsAPermissionBlockAfterTheUserGrantsItInSettings() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.postponeForPermission(start.generation)
        coordinator.onPermissionResult(start.generation, granted = false)

        assertEquals(
            AutoConnectSkipReason.PERMISSION_DENIED,
            (coordinator.request(request(), conditions()) as AutoConnectOutcome.Skip).reason,
        )
        now += 1_000L
        assertTrue(coordinator.request(request(key = "after-settings"), conditions()) is AutoConnectOutcome.Start)
    }

    @Test
    fun autoConnectOffKeepsSuppressingRecoveryForAnAutoInitiatedSession() {
        coordinator.request(request(), conditions())
        coordinator.onAutoConnectDisabled()

        assertTrue(coordinator.suppressesHostRecovery(autoConnectEnabled = false))
    }

    @Test
    fun externalUsbSessionClearsAutoRecoverySuppression() {
        coordinator.request(request(), conditions())
        assertTrue(coordinator.suppressesHostRecovery(autoConnectEnabled = false))

        coordinator.onExternalSessionStart()

        assertFalse(coordinator.suppressesHostRecovery(autoConnectEnabled = false))
    }

    @Test
    fun sessionTeardownIsObservedEvenWhenActiveGoesFalseBeforePresence() {
        coordinator.request(request(), conditions())
        coordinator.onSessionActive()

        coordinator.observeSession(activeSession = false, sessionPresent = true)
        assertEquals(AutoConnectState.ACTIVE, coordinator.currentState())
        coordinator.observeSession(activeSession = false, sessionPresent = false)

        assertEquals(AutoConnectState.IDLE, coordinator.currentState())
    }

    @Test
    fun retryDueYieldsToUsbAutoLaunch() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        assertTrue(
            coordinator.onHostLaunchFailed(start.generation, AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE) is
                AutoConnectOutcome.RetryScheduled,
        )
        now += 3_000L

        val skipped = coordinator.retryDue(conditions(usbAutoLaunchActive = true))

        assertEquals(AutoConnectSkipReason.USB_AUTO_LAUNCH_ACTIVE, (skipped as AutoConnectOutcome.Skip).reason)
        assertEquals(AutoConnectState.IDLE, coordinator.currentState())
    }

    @Test
    fun retryDueKeepsTheTransportSavedWithTheOriginalRequest() {
        val start = coordinator.request(request(transport = AutoConnectTransport.WIRED), conditions()) as
            AutoConnectOutcome.Start
        assertTrue(
            coordinator.onHostLaunchFailed(start.generation, AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE) is
                AutoConnectOutcome.RetryScheduled,
        )
        now += 3_000L

        val retry = coordinator.retryDue(conditions()) as AutoConnectOutcome.Start

        assertEquals(AutoConnectTransport.WIRED, retry.request.transport)
    }

    @Test
    fun permissionDialogDoesNotExpireIntoASecondRequest() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.postponeForPermission(start.generation)
        now += 60_000L

        val duplicate = coordinator.request(request(), conditions())

        assertEquals(
            AutoConnectSkipReason.WAITING_FOR_PERMISSION,
            (duplicate as AutoConnectOutcome.Skip).reason,
        )
        assertEquals(1, events.count { it.contains("decision=START") })
    }

    @Test
    fun dispatchedHostLaunchIsNotDuplicatedAfterTheStaleWindow() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.onHostLaunchDispatched(start.generation)
        now += 60_000L

        val duplicate = coordinator.request(request(), conditions())

        assertEquals(AutoConnectSkipReason.REQUEST_IN_FLIGHT, (duplicate as AutoConnectOutcome.Skip).reason)
        assertEquals(1, events.count { it.contains("decision=START") })
    }

    @Test
    fun userSelectionWaitDoesNotExpireOrDuplicateAcrossNewIntents() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        assertTrue(coordinator.postponeForUser(start.generation))
        now += 60_000L

        val sameKey = coordinator.request(request(), conditions())
        val newKey = coordinator.request(request(key = "new-intent"), conditions())

        assertEquals(AutoConnectSkipReason.WAITING_FOR_USER, (sameKey as AutoConnectOutcome.Skip).reason)
        assertEquals(AutoConnectSkipReason.WAITING_FOR_USER, (newKey as AutoConnectOutcome.Skip).reason)
        assertEquals(1, events.count { it.contains("decision=START") })
        assertTrue(coordinator.onUserSelection(start.generation))
        assertEquals(AutoConnectState.STARTING, coordinator.currentState())
    }

    @Test
    fun userSelectionWaitIsAbandonedWhenTheForegroundIsGone() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        assertTrue(coordinator.postponeForUser(start.generation))

        coordinator.onForegroundGone()

        assertEquals(AutoConnectState.IDLE, coordinator.currentState())
        assertEquals(null, coordinator.activeGeneration())
    }

    @Test
    fun hostStartFailureBlocksTheLaunchAndANewLaunchCanRetry() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.onHostLaunchDispatched(start.generation)
        coordinator.onHostStartFailed(start.generation, AutoConnectFailure.INITIALIZATION_FAILED)

        assertEquals(AutoConnectState.BLOCKED, coordinator.currentState())
        assertEquals(
            AutoConnectSkipReason.SETUP_FAILED,
            (coordinator.request(request(), conditions()) as AutoConnectOutcome.Skip).reason,
        )
        assertTrue(coordinator.request(request(key = "next-launch"), conditions()) is AutoConnectOutcome.Start)
    }

    @Test
    fun hostSessionCallbacksDriveTheCoordinatorWithoutHomePolling() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.onHostCreated(start.generation)
        assertEquals(AutoConnectState.STARTING, coordinator.currentState())

        coordinator.onHostSessionActive(start.generation)
        assertEquals(AutoConnectState.ACTIVE, coordinator.currentState())

        coordinator.onHostSessionEnded(start.generation)
        assertEquals(AutoConnectState.IDLE, coordinator.currentState())
    }

    @Test
    fun ac37_oldHostAndPermissionCallbacksAreIgnoredAfterReset() {
        val first = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        coordinator.resetForTests()
        val second = coordinator.request(request(key = "new-launch"), conditions()) as AutoConnectOutcome.Start

        assertSame(
            AutoConnectOutcome.StaleCallbackIgnored,
            coordinator.onPermissionResult(first.generation, granted = true),
        )
        coordinator.onHostSessionActive(first.generation)
        coordinator.onHostStartFailed(first.generation, AutoConnectFailure.INITIALIZATION_FAILED)

        assertEquals(AutoConnectState.STARTING, coordinator.currentState())
        assertEquals(second.generation, coordinator.activeGeneration())
    }

    @Test
    fun requestActivityEndsWhenTheSessionStarts() {
        val start = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        assertTrue(coordinator.isRequestActive(start.generation))
        assertFalse(coordinator.isRequestActive(start.generation + 1))

        coordinator.onSessionActive()

        assertFalse(coordinator.isRequestActive(start.generation))
    }

    private fun exhaustRetries() {
        var outcome = coordinator.request(request(), conditions()) as AutoConnectOutcome.Start
        repeat(3) {
            val next = coordinator.onHostLaunchFailed(
                outcome.generation,
                AutoConnectFailure.HOST_ACTIVITY_UNAVAILABLE,
                now,
            )
            if (it < 2) {
                now += 3_000L
                outcome = coordinator.retryDue(conditions()) as AutoConnectOutcome.Start
            } else {
                assertTrue(next is AutoConnectOutcome.Exhausted)
            }
        }
    }

    private fun request(
        key: String = "launch-key",
        source: AutoConnectSource = AutoConnectSource.APP_LAUNCH,
        transport: AutoConnectTransport = AutoConnectTransport.WIRELESS,
    ) = AutoConnectRequest(key, source, transport)

    private fun conditions(
        enabled: Boolean = true,
        setupReady: Boolean = true,
        session: AutoConnectSessionState = AutoConnectSessionState.NONE,
        usbAutoLaunchActive: Boolean = false,
    ) = AutoConnectConditions(enabled, setupReady, session, usbAutoLaunchActive)
}
