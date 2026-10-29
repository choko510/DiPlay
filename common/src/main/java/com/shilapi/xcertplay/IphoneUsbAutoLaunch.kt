package com.shilapi.xcertplay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration

internal data class IphoneUsbAutoAttachCandidate(
    val identity: UsbAttachDeviceIdentity,
    val hasCarPlayConfiguration: Boolean,
)

internal enum class IphoneUsbAutoAttachRejection {
    WRONG_ACTION,
    DISABLED,
    MISSING_DEVICE,
    STALE_DEVICE,
    NOT_APPLE,
    NO_CARPLAY_CONFIGURATION,
}

internal sealed interface IphoneUsbAutoAttachValidation {
    data class Accepted(val identity: UsbAttachDeviceIdentity) : IphoneUsbAutoAttachValidation
    data class Rejected(val reason: IphoneUsbAutoAttachRejection) : IphoneUsbAutoAttachValidation
}

internal sealed interface IphoneUsbInventoryRead {
    data class Available(val devices: List<UsbDevice>) : IphoneUsbInventoryRead
    data object Unavailable : IphoneUsbInventoryRead
}

internal object IphoneUsbAutoLaunchPolicy {
    const val APPLE_VENDOR_ID = 0x05ac

    fun evaluate(
        action: String?,
        enabled: Boolean,
        eventDevice: UsbAttachDeviceIdentity?,
        attachedDevices: Collection<IphoneUsbAutoAttachCandidate>,
    ): IphoneUsbAutoAttachValidation {
        if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            return IphoneUsbAutoAttachValidation.Rejected(IphoneUsbAutoAttachRejection.WRONG_ACTION)
        }
        if (!enabled) return IphoneUsbAutoAttachValidation.Rejected(IphoneUsbAutoAttachRejection.DISABLED)
        val supplied = eventDevice
            ?: return IphoneUsbAutoAttachValidation.Rejected(IphoneUsbAutoAttachRejection.MISSING_DEVICE)
        val current = attachedDevices.firstOrNull { it.identity == supplied }
            ?: return IphoneUsbAutoAttachValidation.Rejected(IphoneUsbAutoAttachRejection.STALE_DEVICE)
        if (supplied.vendorId != APPLE_VENDOR_ID) {
            return IphoneUsbAutoAttachValidation.Rejected(IphoneUsbAutoAttachRejection.NOT_APPLE)
        }
        if (!current.hasCarPlayConfiguration) {
            return IphoneUsbAutoAttachValidation.Rejected(
                IphoneUsbAutoAttachRejection.NO_CARPLAY_CONFIGURATION,
            )
        }
        return IphoneUsbAutoAttachValidation.Accepted(supplied)
    }

    fun candidate(device: UsbDevice): IphoneUsbAutoAttachCandidate {
        val hasCarPlayConfiguration = if (device.vendorId != APPLE_VENDOR_ID) {
            false
        } else {
            try {
                IphoneCarPlayConfiguration.find(device) != null
            } catch (_: RuntimeException) {
                false
            }
        }
        return IphoneUsbAutoAttachCandidate(device.toUsbAttachDeviceIdentity(), hasCarPlayConfiguration)
    }
}

internal enum class IphoneUsbSessionSnapshot {
    NONE,
    WIRELESS,
    WIRED,
    TRANSITIONING,
}

internal object CarPlaySessionTransportPolicy {
    fun shouldAdoptBackgroundSession(existingWired: Boolean, requestedWired: Boolean): Boolean =
        existingWired == requestedWired
}

internal sealed interface IphoneUsbAutoLaunchDecision {
    data class Launch(val generation: Long) : IphoneUsbAutoLaunchDecision
    data object KeepCurrentWiredSession : IphoneUsbAutoLaunchDecision
    data object DeferToCurrentSession : IphoneUsbAutoLaunchDecision
    data object IgnoreDuplicate : IphoneUsbAutoLaunchDecision
}

internal class IphoneUsbAutoLaunchCoordinator(
    private val duplicateWindowMillis: Long = 1_500L,
) {
    private var lastIdentity: UsbAttachDeviceIdentity? = null
    private var lastLaunchAtMillis = Long.MIN_VALUE
    private var generation = 0L

    @Synchronized
    fun decide(
        identity: UsbAttachDeviceIdentity,
        session: IphoneUsbSessionSnapshot,
        nowMillis: Long,
        previousIdentityStillAttached: Boolean = false,
        allowRetryInsideDuplicateWindow: Boolean = false,
    ): IphoneUsbAutoLaunchDecision {
        if (session == IphoneUsbSessionSnapshot.WIRED) {
            return IphoneUsbAutoLaunchDecision.KeepCurrentWiredSession
        }
        if (session == IphoneUsbSessionSnapshot.TRANSITIONING) {
            return IphoneUsbAutoLaunchDecision.DeferToCurrentSession
        }
        val elapsed = nowMillis - lastLaunchAtMillis
        if (!allowRetryInsideDuplicateWindow && elapsed in 0..duplicateWindowMillis &&
            (identity == lastIdentity || previousIdentityStillAttached)
        ) {
            lastLaunchAtMillis = nowMillis
            return IphoneUsbAutoLaunchDecision.IgnoreDuplicate
        }
        lastIdentity = identity
        lastLaunchAtMillis = nowMillis
        generation += 1
        return IphoneUsbAutoLaunchDecision.Launch(generation)
    }

    @Synchronized
    fun lastIdentity(): UsbAttachDeviceIdentity? = lastIdentity

    @Synchronized
    fun cancelDispatch(identity: UsbAttachDeviceIdentity, expectedGeneration: Long) {
        if (identity == lastIdentity && expectedGeneration == generation) {
            lastIdentity = null
            lastLaunchAtMillis = Long.MIN_VALUE
        }
    }

    @Synchronized
    fun cancelCurrent(identity: UsbAttachDeviceIdentity) {
        if (identity == lastIdentity) {
            lastIdentity = null
            lastLaunchAtMillis = Long.MIN_VALUE
        }
    }
}

internal object IphoneUsbAutoLaunchSettings {
    private const val TAG = "xcertplay-usb-auto"

    fun isComponentEnabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (error: RuntimeException) {
        Log.w(TAG, "event=APPLE_USB_AUTO_ATTACH result=component_state_unavailable error=${error.javaClass.simpleName}")
        false
    }

    fun isOperationallyEnabled(context: Context): Boolean =
        AirPlayPersistence.loadAutoLaunchOnIphoneUsbAttach(context) && isComponentEnabled(context)

    fun reconcile(context: Context): Boolean {
        val desired = AirPlayPersistence.loadAutoLaunchOnIphoneUsbAttach(context)
        if (isComponentEnabled(context) == desired) return true
        return setComponentEnabled(context, desired)
    }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val previousPreference = AirPlayPersistence.loadAutoLaunchOnIphoneUsbAttach(context)
        val previousComponent = isComponentEnabled(context)
        if (enabled) {
            if (!savePreference(context, true)) return false
            if (setComponentEnabled(context, true)) return true
            savePreference(context, previousPreference)
            setComponentEnabled(context, previousComponent)
            return false
        }

        if (!setComponentEnabled(context, false)) return false
        if (savePreference(context, false)) return true
        setComponentEnabled(context, previousComponent)
        return false
    }

    fun isUsbHostSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun connectedCandidates(context: Context): List<IphoneUsbAutoAttachCandidate> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return attachedDevices(manager).map(IphoneUsbAutoLaunchPolicy::candidate)
            .filter { it.identity.vendorId == IphoneUsbAutoLaunchPolicy.APPLE_VENDOR_ID && it.hasCarPlayConfiguration }
    }

    fun hasPermission(context: Context, identity: UsbAttachDeviceIdentity): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = attachedDevices(manager).firstOrNull { it.toUsbAttachDeviceIdentity() == identity } ?: return false
        return try {
            manager.hasPermission(device)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun currentCandidate(context: Context, identity: UsbAttachDeviceIdentity): UsbDevice? {
        if (!isOperationallyEnabled(context)) return null
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val devices = attachedDevices(manager)
        val validation = IphoneUsbAutoLaunchPolicy.evaluate(
            action = UsbManager.ACTION_USB_DEVICE_ATTACHED,
            enabled = true,
            eventDevice = identity,
            attachedDevices = devices.map(IphoneUsbAutoLaunchPolicy::candidate),
        )
        if (validation !is IphoneUsbAutoAttachValidation.Accepted) return null
        return devices.firstOrNull { it.toUsbAttachDeviceIdentity() == identity }
    }

    private fun savePreference(context: Context, enabled: Boolean): Boolean = try {
        AirPlayPersistence.saveAutoLaunchOnIphoneUsbAttach(context, enabled)
    } catch (error: RuntimeException) {
        Log.w(TAG, "event=APPLE_USB_AUTO_ATTACH result=preference_update_failed error=${error.javaClass.simpleName}")
        false
    }

    internal fun readAttachedDevices(manager: UsbManager): IphoneUsbInventoryRead = try {
        IphoneUsbInventoryRead.Available(manager.deviceList.values.toList())
    } catch (error: RuntimeException) {
        Log.w(TAG, "event=APPLE_USB_AUTO_ATTACH result=usb_inventory_unavailable error=${error.javaClass.simpleName}")
        IphoneUsbInventoryRead.Unavailable
    }

    internal fun attachedDevices(manager: UsbManager): List<UsbDevice> =
        (readAttachedDevices(manager) as? IphoneUsbInventoryRead.Available)?.devices.orEmpty()

    private fun setComponentEnabled(context: Context, enabled: Boolean): Boolean = try {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
        true
    } catch (error: RuntimeException) {
        Log.w(
            TAG,
            "event=APPLE_USB_AUTO_ATTACH result=component_update_failed enabled=$enabled " +
                "error=${error.javaClass.simpleName}",
        )
        false
    }

    private fun component(context: Context) =
        ComponentName(context, IphoneUsbAutoAttachActivity::class.java)
}

internal enum class IphoneUsbAutoLaunchSource(val label: String) {
    ATTACH("attach"),
    FOREGROUND("foreground"),
    SETTINGS("settings"),
}

internal fun shouldBringWiredHostToFront(source: IphoneUsbAutoLaunchSource): Boolean =
    source != IphoneUsbAutoLaunchSource.ATTACH

internal fun shouldBypassUsbAttachDebounce(
    source: IphoneUsbAutoLaunchSource,
    deferredReplay: Boolean,
): Boolean = deferredReplay || source != IphoneUsbAutoLaunchSource.ATTACH

internal data class DeferredIphoneUsbAutoLaunch(
    val identity: UsbAttachDeviceIdentity,
    val source: IphoneUsbAutoLaunchSource,
    val hostLaunchState: CarPlayHostLaunchState? = null,
)

internal class IphoneUsbAutoLaunchRequestQueue {
    private var latestDeferred: DeferredIphoneUsbAutoLaunch? = null
    private var handoffActive = false
    private var latestHandoff: DeferredIphoneUsbAutoLaunch? = null
    private var handoffGeneration = 0L

    @Synchronized
    fun defer(request: DeferredIphoneUsbAutoLaunch) {
        if (latestDeferred?.let { it.identity == request.identity && it.source == request.source } == true) return
        latestDeferred = request
    }

    @Synchronized
    fun takeDeferred(): DeferredIphoneUsbAutoLaunch? = latestDeferred.also { latestDeferred = null }

    @Synchronized
    fun peekDeferred(): DeferredIphoneUsbAutoLaunch? = latestDeferred

    @Synchronized
    fun completeDeferred(expected: DeferredIphoneUsbAutoLaunch): Boolean {
        if (latestDeferred !== expected) return false
        latestDeferred = null
        return true
    }

    @Synchronized
    fun beginHandoff(request: DeferredIphoneUsbAutoLaunch): Long {
        if (handoffActive) {
            replaceDuringHandoff(request)
        } else {
            latestDeferred = null
            handoffActive = true
            handoffGeneration += 1
            latestHandoff = request
        }
        return handoffGeneration
    }

    @Synchronized
    fun replaceDuringHandoff(request: DeferredIphoneUsbAutoLaunch): Boolean {
        if (!handoffActive) return false
        val priorState = latestHandoff?.hostLaunchState
        latestHandoff = request.copy(hostLaunchState = request.hostLaunchState ?: priorState)
        return true
    }

    @Synchronized
    fun isHandoffActive(): Boolean = handoffActive

    @Synchronized
    fun completeHandoff(expectedGeneration: Long? = null): DeferredIphoneUsbAutoLaunch? {
        if (!handoffActive || (expectedGeneration != null && expectedGeneration != handoffGeneration)) return null
        handoffActive = false
        return latestHandoff.also { latestHandoff = null }
    }

    @Synchronized
    fun failHandoff(expectedGeneration: Long): DeferredIphoneUsbAutoLaunch? {
        if (!handoffActive || expectedGeneration != handoffGeneration) return null
        handoffActive = false
        val pending = latestHandoff.also { latestHandoff = null }
        if (pending != null) latestDeferred = pending
        return pending
    }
}

internal object IphoneUsbAutoLaunchDispatcher {
    private const val TAG = "xcertplay-usb-auto"
    private const val MAX_DEFERRED_RETRIES = 3
    private val coordinator = IphoneUsbAutoLaunchCoordinator()
    private val requests = IphoneUsbAutoLaunchRequestQueue()
    private val deferredRetryHandler = Handler(Looper.getMainLooper())
    private var deferredRetry: Runnable? = null
    private var deferredRetryRequest: DeferredIphoneUsbAutoLaunch? = null
    private var deferredRetryAttempts = 0

    fun beginHandoff(identity: UsbAttachDeviceIdentity, hostLaunchState: CarPlayHostLaunchState?): Long =
        requests.beginHandoff(
            DeferredIphoneUsbAutoLaunch(
                identity,
                IphoneUsbAutoLaunchSource.ATTACH,
                hostLaunchState,
            ),
        )

    fun replaceHandoffRequest(
        identity: UsbAttachDeviceIdentity,
        hostLaunchState: CarPlayHostLaunchState? = null,
    ): Boolean = requests.replaceDuringHandoff(
        DeferredIphoneUsbAutoLaunch(
            identity,
            IphoneUsbAutoLaunchSource.ATTACH,
            hostLaunchState,
        ),
    )

    fun completeHandoff(expectedGeneration: Long? = null): DeferredIphoneUsbAutoLaunch? =
        requests.completeHandoff(expectedGeneration)

    fun failHandoff(expectedGeneration: Long): DeferredIphoneUsbAutoLaunch? =
        requests.failHandoff(expectedGeneration)

    fun failLaunch(identity: UsbAttachDeviceIdentity) = coordinator.cancelCurrent(identity)

    fun deferTransition(identity: UsbAttachDeviceIdentity, source: IphoneUsbAutoLaunchSource) {
        requests.defer(DeferredIphoneUsbAutoLaunch(identity, source))
    }

    @Synchronized
    fun resumeDeferred(context: Context) {
        if (requests.isHandoffActive()) return
        val pending = requests.peekDeferred() ?: return
        if (deferredRetryRequest !== pending) resetDeferredRetry(pending)
        dispatchDeferred(context.applicationContext, pending)
    }

    fun dispatch(
        context: Context,
        identity: UsbAttachDeviceIdentity,
        source: IphoneUsbAutoLaunchSource,
        deferredReplay: Boolean = false,
    ): Boolean {
        val outcome = dispatchDetailed(context, identity, source, deferredReplay)
        if (outcome == DispatchOutcome.RETRY) {
            requests.defer(DeferredIphoneUsbAutoLaunch(identity, source))
            val pending = requests.peekDeferred()
            if (pending != null) synchronized(this) {
                if (deferredRetryRequest !== pending) resetDeferredRetry(pending)
                scheduleDeferredRetry(context.applicationContext, pending)
            }
        }
        return outcome == DispatchOutcome.ACCEPTED
    }

    private enum class DispatchOutcome { ACCEPTED, RETRY, REJECTED }

    private fun dispatchDeferred(context: Context, pending: DeferredIphoneUsbAutoLaunch) {
        val outcome = dispatchDetailed(context, pending.identity, pending.source, deferredReplay = true)
        if (outcome == DispatchOutcome.RETRY) {
            val latest = requests.peekDeferred() ?: pending
            scheduleDeferredRetry(context, latest)
        } else {
            val completed = requests.completeDeferred(pending)
            if (completed) {
                if (deferredRetryRequest === pending) clearDeferredRetry()
            } else {
                requests.peekDeferred()?.let { latest ->
                    resetDeferredRetry(latest)
                    scheduleDeferredRetry(context, latest)
                }
            }
        }
    }

    private fun dispatchDetailed(
        context: Context,
        identity: UsbAttachDeviceIdentity,
        source: IphoneUsbAutoLaunchSource,
        deferredReplay: Boolean,
    ): DispatchOutcome {
        val enabled = try {
            IphoneUsbAutoLaunchSettings.isOperationallyEnabled(context)
        } catch (error: RuntimeException) {
            Log.w(TAG, "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=settings_unavailable " +
                "error=${error.javaClass.simpleName}")
            return DispatchOutcome.RETRY
        }
        if (!enabled) return DispatchOutcome.REJECTED
        val manager = try {
            context.getSystemService(Context.USB_SERVICE) as? UsbManager
        } catch (error: RuntimeException) {
            Log.w(TAG, "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=usb_manager_unavailable " +
                "error=${error.javaClass.simpleName}")
            return DispatchOutcome.RETRY
        } ?: return DispatchOutcome.RETRY
        val devices = when (val inventory = IphoneUsbAutoLaunchSettings.readAttachedDevices(manager)) {
            is IphoneUsbInventoryRead.Available -> inventory.devices
            IphoneUsbInventoryRead.Unavailable -> return DispatchOutcome.RETRY
        }
        val candidates = devices.map(IphoneUsbAutoLaunchPolicy::candidate)
        val validation = IphoneUsbAutoLaunchPolicy.evaluate(
            action = UsbManager.ACTION_USB_DEVICE_ATTACHED,
            enabled = true,
            eventDevice = identity,
            attachedDevices = candidates,
        )
        if (validation !is IphoneUsbAutoAttachValidation.Accepted) {
            Log.i(TAG, "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=${validation.rejectionLabel()}")
            return DispatchOutcome.REJECTED
        }
        if (requests.replaceDuringHandoff(DeferredIphoneUsbAutoLaunch(identity, source))) {
            Log.i(TAG, "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=queued_during_handoff")
            return DispatchOutcome.ACCEPTED
        }

        val sessionSnapshot = CarPlayBackgroundSession.snapshot()
        val session = when {
            sessionSnapshot?.controller?.isUsingWiredTransport() == true -> IphoneUsbSessionSnapshot.WIRED
            sessionSnapshot != null -> IphoneUsbSessionSnapshot.WIRELESS
            CarPlayBackgroundSession.hasSession() -> IphoneUsbSessionSnapshot.TRANSITIONING
            else -> IphoneUsbSessionSnapshot.NONE
        }
        val previousIdentityStillAttached = coordinator.lastIdentity()?.let { last ->
            candidates.any { it.identity == last }
        } ?: false
        return when (
            val decision = coordinator.decide(
                identity,
                session,
                SystemClock.elapsedRealtime(),
                previousIdentityStillAttached,
                allowRetryInsideDuplicateWindow = shouldBypassUsbAttachDebounce(source, deferredReplay),
            )
        ) {
            IphoneUsbAutoLaunchDecision.KeepCurrentWiredSession -> {
                Log.i(TAG, "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=keep_wired")
                if (shouldBringWiredHostToFront(source)) {
                    if (bringExistingWiredHostToFront(context)) DispatchOutcome.ACCEPTED else DispatchOutcome.RETRY
                } else DispatchOutcome.ACCEPTED
            }
            IphoneUsbAutoLaunchDecision.DeferToCurrentSession -> {
                deferTransition(identity, source)
                Log.i(TAG, "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=defer_to_controller")
                DispatchOutcome.RETRY
            }
            IphoneUsbAutoLaunchDecision.IgnoreDuplicate -> {
                Log.i(TAG, "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=duplicate")
                DispatchOutcome.ACCEPTED
            }
            is IphoneUsbAutoLaunchDecision.Launch -> {
                val stillEnabled = runCatching { IphoneUsbAutoLaunchSettings.isOperationallyEnabled(context) }
                    .getOrElse {
                        coordinator.cancelDispatch(identity, decision.generation)
                        return DispatchOutcome.RETRY
                    }
                val currentDevices = when (val inventory = IphoneUsbAutoLaunchSettings.readAttachedDevices(manager)) {
                    is IphoneUsbInventoryRead.Available -> inventory.devices
                    IphoneUsbInventoryRead.Unavailable -> {
                        coordinator.cancelDispatch(identity, decision.generation)
                        return DispatchOutcome.RETRY
                    }
                }
                val currentCandidates = currentDevices.map(IphoneUsbAutoLaunchPolicy::candidate)
                val currentValidation = IphoneUsbAutoLaunchPolicy.evaluate(
                    action = UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    enabled = stillEnabled,
                    eventDevice = identity,
                    attachedDevices = currentCandidates,
                )
                val device = currentDevices.firstOrNull { it.toUsbAttachDeviceIdentity() == identity }
                if (!stillEnabled || currentValidation !is IphoneUsbAutoAttachValidation.Accepted || device == null) {
                    coordinator.cancelDispatch(identity, decision.generation)
                    Log.i(TAG, "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=stale_before_dispatch")
                    return DispatchOutcome.REJECTED
                }
                try {
                    val intent = CarPlayHostActions.createInternalIntent(
                        context,
                        CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
                        identity,
                    )
                    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    IphoneUsbAutoLaunchNotification.cancel(context)
                    Log.i(
                        TAG,
                        "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=host_dispatched " +
                        "hasPermission=${try { manager.hasPermission(device) } catch (_: RuntimeException) { false }} " +
                            "generation=${decision.generation}",
                    )
                    DispatchOutcome.ACCEPTED
                } catch (error: RuntimeException) {
                    coordinator.cancelDispatch(identity, decision.generation)
                    Log.w(
                        TAG,
                        "event=APPLE_USB_AUTO_ATTACH source=${source.label} result=launch_failed " +
                            "generation=${decision.generation} error=${error.javaClass.simpleName}",
                    )
                    IphoneUsbAutoLaunchNotification.show(context)
                    DispatchOutcome.RETRY
                }
            }
        }
    }

    @Synchronized
    private fun scheduleDeferredRetry(context: Context, pending: DeferredIphoneUsbAutoLaunch) {
        if (deferredRetryRequest !== pending) resetDeferredRetry(pending)
        if (deferredRetry != null) return
        if (deferredRetryAttempts >= MAX_DEFERRED_RETRIES) {
            Log.w(TAG, "event=APPLE_USB_AUTO_ATTACH source=${pending.source.label} " +
                "result=deferred_retry_limit_reached; request retained")
            return
        }
        deferredRetryAttempts++
        val attempt = deferredRetryAttempts
        val retry = Runnable {
            synchronized(this) {
                deferredRetry = null
                if (requests.peekDeferred() === pending && !requests.isHandoffActive()) {
                    dispatchDeferred(context, pending)
                }
            }
        }
        deferredRetry = retry
        deferredRetryHandler.postDelayed(retry, DEFERRED_RETRY_DELAYS_MILLIS[attempt - 1])
    }

    private fun resetDeferredRetry(pending: DeferredIphoneUsbAutoLaunch) {
        clearDeferredRetry()
        deferredRetryRequest = pending
        deferredRetryAttempts = 0
    }

    private fun clearDeferredRetry() {
        deferredRetry?.let(deferredRetryHandler::removeCallbacks)
        deferredRetry = null
        deferredRetryRequest = null
        deferredRetryAttempts = 0
    }

    private fun bringExistingWiredHostToFront(context: Context): Boolean {
        try {
            val intent = CarPlayHostActions.createInternalIntent(context, CarPlayHostActions.OPEN_CARPLAY)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (error: RuntimeException) {
            Log.w(TAG, "event=APPLE_USB_AUTO_ATTACH source=foreground result=host_focus_failed " +
                "error=${error.javaClass.simpleName}")
            IphoneUsbAutoLaunchNotification.show(context)
            return false
        }
    }

    private fun IphoneUsbAutoAttachValidation.rejectionLabel(): String = when (this) {
        is IphoneUsbAutoAttachValidation.Accepted -> "accepted"
        is IphoneUsbAutoAttachValidation.Rejected -> when (reason) {
            IphoneUsbAutoAttachRejection.WRONG_ACTION -> "wrong_action"
            IphoneUsbAutoAttachRejection.DISABLED -> "disabled"
            IphoneUsbAutoAttachRejection.MISSING_DEVICE -> "missing_device"
            IphoneUsbAutoAttachRejection.STALE_DEVICE -> "stale_device"
            IphoneUsbAutoAttachRejection.NOT_APPLE -> "not_apple"
            IphoneUsbAutoAttachRejection.NO_CARPLAY_CONFIGURATION -> "no_carplay_configuration"
        }
    }

    private val DEFERRED_RETRY_DELAYS_MILLIS = longArrayOf(250L, 500L, 1_000L)
}

class IphoneUsbAutoAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.USB_SERVICE) as? UsbManager
        val eventDevice = intent.usbDeviceExtra()
        val candidates = manager?.let(IphoneUsbAutoLaunchSettings::attachedDevices)
            ?.map(IphoneUsbAutoLaunchPolicy::candidate)
            .orEmpty()
        val validation = IphoneUsbAutoLaunchPolicy.evaluate(
            action = intent.action,
            enabled = IphoneUsbAutoLaunchSettings.isOperationallyEnabled(this),
            eventDevice = eventDevice?.toUsbAttachDeviceIdentity(),
            attachedDevices = candidates,
        )
        when (validation) {
            is IphoneUsbAutoAttachValidation.Accepted -> {
                if (IphoneUsbAutoLaunchSettings.isOperationallyEnabled(this)) {
                    IphoneUsbAutoLaunchDispatcher.dispatch(
                        this,
                        validation.identity,
                        IphoneUsbAutoLaunchSource.ATTACH,
                    )
                }
            }
            is IphoneUsbAutoAttachValidation.Rejected -> Log.i(
                TAG,
                "event=APPLE_USB_AUTO_ATTACH source=attach result=${validation.reason.name.lowercase()}",
            )
        }
        finish()
    }

    private companion object {
        const val TAG = "xcertplay-usb-auto"
    }
}

internal object IphoneUsbBootReconciler {
    private const val TAG = "xcertplay-usb-auto"

    fun reconcile(context: Context) {
        if (!IphoneUsbAutoLaunchSettings.reconcile(context) ||
            !IphoneUsbAutoLaunchSettings.isOperationallyEnabled(context)
        ) return
        val candidates = IphoneUsbAutoLaunchSettings.connectedCandidates(context)
        if (candidates.size != 1) {
            Log.i(
                TAG,
                "event=APPLE_USB_AUTO_ATTACH source=boot_scan result=" +
                    if (candidates.isEmpty()) "no_candidate" else "ambiguous_candidates",
            )
            return
        }
        val shown = IphoneUsbAutoLaunchNotification.show(context)
        Log.i(
            TAG,
            "event=APPLE_USB_AUTO_ATTACH source=boot_scan result=" +
                if (shown) "user_action_required" else "notification_unavailable",
        )
    }
}

internal object IphoneUsbAutoLaunchNotification {
    private const val CHANNEL_ID = "iphone_usb_auto_launch"
    private const val NOTIFICATION_ID = 1452
    const val EXTRA_OPEN_CONNECTED_IPHONE = "com.shilapi.xcertplay.extra.OPEN_CONNECTED_IPHONE"

    fun show(context: Context): Boolean {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.ui_iphone_usb_autolaunch_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val openApp = Intent(context, DiPlayActivity::class.java)
            .putExtra(EXTRA_OPEN_CONNECTED_IPHONE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            notifications.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_carplay)
                    .setContentTitle(context.getString(R.string.ui_iphone_usb_autolaunch_notification_title))
                    .setContentText(context.getString(R.string.ui_iphone_usb_autolaunch_notification_body))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build(),
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

internal fun UsbDevice.toUsbAttachDeviceIdentity(): UsbAttachDeviceIdentity = UsbAttachDeviceIdentity(
    deviceId = deviceId,
    deviceName = deviceName,
    vendorId = vendorId,
    productId = productId,
)

@Suppress("DEPRECATION")
internal fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
