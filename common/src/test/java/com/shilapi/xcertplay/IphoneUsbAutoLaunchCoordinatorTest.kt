package com.shilapi.xcertplay

import com.shilapi.xcertplay.youtube.HostLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneUsbAutoLaunchCoordinatorTest {
    private val identity = UsbAttachDeviceIdentity(
        deviceId = 31,
        deviceName = "test-apple-device",
        vendorId = 0x05ac,
        productId = 0x12a8,
    )

    @Test
    fun repeatedAttachForOneDeviceLaunchesOnlyOnceWithinTheDebounceWindow() {
        val coordinator = IphoneUsbAutoLaunchCoordinator()
        val first = coordinator.decide(identity, IphoneUsbSessionSnapshot.NONE, nowMillis = 100L)

        assertTrue(first is IphoneUsbAutoLaunchDecision.Launch)
        repeat(100) {
            assertEquals(
                IphoneUsbAutoLaunchDecision.IgnoreDuplicate,
                coordinator.decide(identity, IphoneUsbSessionSnapshot.NONE, nowMillis = 1_000L),
            )
        }
    }

    @Test
    fun wiredSessionsAndTransitionsRemainOwnedByTheExistingController() {
        val coordinator = IphoneUsbAutoLaunchCoordinator()

        assertEquals(
            IphoneUsbAutoLaunchDecision.KeepCurrentWiredSession,
            coordinator.decide(identity, IphoneUsbSessionSnapshot.WIRED, nowMillis = 0L),
        )
        assertEquals(
            IphoneUsbAutoLaunchDecision.DeferToCurrentSession,
            coordinator.decide(identity, IphoneUsbSessionSnapshot.TRANSITIONING, nowMillis = 0L),
        )
    }

    @Test
    fun suppressesASecondLaunchWhileThePreviousAppleDeviceIsStillAttached() {
        val coordinator = IphoneUsbAutoLaunchCoordinator()
        coordinator.decide(identity, IphoneUsbSessionSnapshot.NONE, nowMillis = 100L)

        assertEquals(
            IphoneUsbAutoLaunchDecision.IgnoreDuplicate,
            coordinator.decide(
                identity.copy(deviceId = 32, deviceName = "reenumerated-test-device"),
                IphoneUsbSessionSnapshot.NONE,
                nowMillis = 200L,
                previousIdentityStillAttached = true,
            ),
        )
    }

    @Test
    fun aNewCurrentIdentityCanReplaceAnAttachWhoseDeviceWasRemoved() {
        val coordinator = IphoneUsbAutoLaunchCoordinator()
        coordinator.decide(identity, IphoneUsbSessionSnapshot.NONE, nowMillis = 100L)

        assertTrue(
            coordinator.decide(
                identity.copy(deviceId = 32, deviceName = "reenumerated-test-device"),
                IphoneUsbSessionSnapshot.NONE,
                nowMillis = 200L,
                previousIdentityStillAttached = false,
            ) is IphoneUsbAutoLaunchDecision.Launch,
        )
    }

    @Test
    fun wirelessCanSwitchToWiredAndANewGenerationCanRetryAfterTheDebounceWindow() {
        val coordinator = IphoneUsbAutoLaunchCoordinator()
        val initial = coordinator.decide(identity, IphoneUsbSessionSnapshot.WIRELESS, nowMillis = 0L)
        val retry = coordinator.decide(identity, IphoneUsbSessionSnapshot.NONE, nowMillis = 1_501L)

        assertTrue(initial is IphoneUsbAutoLaunchDecision.Launch)
        assertTrue(retry is IphoneUsbAutoLaunchDecision.Launch)
        assertTrue((retry as IphoneUsbAutoLaunchDecision.Launch).generation >
            (initial as IphoneUsbAutoLaunchDecision.Launch).generation)
    }

    @Test
    fun failedDispatchCanReleaseItsOwnGenerationForRetry() {
        val coordinator = IphoneUsbAutoLaunchCoordinator()
        val launch = coordinator.decide(identity, IphoneUsbSessionSnapshot.NONE, nowMillis = 10L)
            as IphoneUsbAutoLaunchDecision.Launch
        coordinator.cancelDispatch(identity, launch.generation)

        assertTrue(coordinator.decide(identity, IphoneUsbSessionSnapshot.NONE, nowMillis = 11L)
            is IphoneUsbAutoLaunchDecision.Launch)
    }

    @Test
    fun deferredQueueKeepsOnlyTheLatestCurrentUsbIdentity() {
        val queue = IphoneUsbAutoLaunchRequestQueue()
        val first = DeferredIphoneUsbAutoLaunch(identity, IphoneUsbAutoLaunchSource.ATTACH)
        val latest = DeferredIphoneUsbAutoLaunch(
            identity.copy(deviceId = 32, deviceName = "reenumerated-test-device"),
            IphoneUsbAutoLaunchSource.ATTACH,
        )

        queue.defer(first)
        queue.defer(latest)

        assertEquals(latest, queue.takeDeferred())
        assertEquals(null, queue.takeDeferred())
    }

    @Test
    fun handoffQueueUsesTheLatestIdentityAndPreservesTheSplitSnapshot() {
        val queue = IphoneUsbAutoLaunchRequestQueue()
        val layoutState = CarPlayHostLaunchState(
            HostLayoutMode.CARPLAY_DSP_SPLIT,
            0.62f,
            HostLayoutMode.CARPLAY_DSP_SPLIT,
        )
        queue.beginHandoff(
            DeferredIphoneUsbAutoLaunch(identity, IphoneUsbAutoLaunchSource.ATTACH, layoutState),
        )

        assertTrue(
            queue.replaceDuringHandoff(
                DeferredIphoneUsbAutoLaunch(
                    identity.copy(deviceId = 33, deviceName = "latest-test-device"),
                    IphoneUsbAutoLaunchSource.ATTACH,
                ),
            ),
        )
        assertEquals(
            DeferredIphoneUsbAutoLaunch(
                identity.copy(deviceId = 33, deviceName = "latest-test-device"),
                IphoneUsbAutoLaunchSource.ATTACH,
                layoutState,
            ),
            queue.completeHandoff(),
        )
        assertEquals(null, queue.completeHandoff())
    }

    @Test
    fun duplicateSystemAttachDoesNotRaiseTheHostButAnExplicitOpenDoes() {
        assertTrue(shouldBringWiredHostToFront(IphoneUsbAutoLaunchSource.SETTINGS))
        assertTrue(shouldBringWiredHostToFront(IphoneUsbAutoLaunchSource.FOREGROUND))
        assertEquals(false, shouldBringWiredHostToFront(IphoneUsbAutoLaunchSource.ATTACH))
    }

    @Test
    fun backgroundControllerIsReusableOnlyWhenItsTransportMatchesTheRequestedTransport() {
        assertTrue(CarPlaySessionTransportPolicy.shouldAdoptBackgroundSession(existingWired = true, requestedWired = true))
        assertTrue(CarPlaySessionTransportPolicy.shouldAdoptBackgroundSession(existingWired = false, requestedWired = false))
        assertEquals(false, CarPlaySessionTransportPolicy.shouldAdoptBackgroundSession(existingWired = false, requestedWired = true))
        assertEquals(false, CarPlaySessionTransportPolicy.shouldAdoptBackgroundSession(existingWired = true, requestedWired = false))
    }
}
