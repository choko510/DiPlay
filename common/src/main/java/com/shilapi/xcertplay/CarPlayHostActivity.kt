package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.dsp.DspProfileRuntime
import com.shilapi.xcertplay.dsp.DspEditorSavedState
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.NavigationAudioRoute
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.shared.AppLanguage
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NcmDiagnosticProfile
import com.shilapi.xcertplay.transport.NcmDiagnosticProfileStore
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.youtube.CarPlayRestartHandoff
import com.shilapi.xcertplay.youtube.DisplayResizeCoordinator
import com.shilapi.xcertplay.youtube.DisplaySize
import com.shilapi.xcertplay.youtube.HostLayoutMode
import com.shilapi.xcertplay.youtube.HostLayoutState
import com.shilapi.xcertplay.youtube.ImeResizeResumePolicy
import com.shilapi.xcertplay.youtube.IphoneIdentityResolver
import com.shilapi.xcertplay.youtube.LocalCarPlayContentBounds
import com.shilapi.xcertplay.youtube.LocalCarPlayScalePolicy
import com.shilapi.xcertplay.youtube.SplitLayoutConfig
import com.shilapi.xcertplay.youtube.SplitPerformanceCounter
import com.shilapi.xcertplay.youtube.SplitPerformanceTracer
import com.shilapi.xcertplay.youtube.YoutubeBrowserController
import com.shilapi.xcertplay.youtube.YoutubeBrowserMemoryPolicy
import com.shilapi.xcertplay.youtube.YoutubeDeviceProfile
import com.shilapi.xcertplay.youtube.YoutubeDeviceProfileManager
import com.shilapi.xcertplay.youtube.YoutubeFullscreenStatusPolicy
import com.shilapi.xcertplay.youtube.YoutubeGeckoRuntimeProvider
import com.shilapi.xcertplay.youtube.YoutubeLoadState
import com.shilapi.xcertplay.youtube.YoutubePaneLayering
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class HostDisplaySizeCause {
    PHYSICAL_DISPLAY_CHANGED,
    LOCAL_PANE_LAYOUT_CHANGED,
    IME_OR_INSETS_CHANGED,
    SURFACE_LIFECYCLE_CHANGED,
}

internal enum class CarPlayHostShutdownResult {
    CLOSED,
    TIMED_OUT,
    FAILED,
}

internal enum class CarPlayHostLayoutCommand {
    ENTER_YOUTUBE_SPLIT,
    ENTER_DSP_SPLIT,
    EXIT_SPLIT_TO_CARPLAY,
    NONE,
}

internal data class CarPlayHostLaunchState(
    val layoutMode: HostLayoutMode,
    val carPlayFraction: Float,
    val pendingSplitMode: HostLayoutMode?,
)

internal object CarPlayHostActions {
    const val ENTER_YOUTUBE_SPLIT = "com.shilapi.xcertplay.action.ENTER_YOUTUBE_SPLIT"
    const val EXIT_YOUTUBE_SPLIT = "com.shilapi.xcertplay.action.EXIT_YOUTUBE_SPLIT"
    const val ENTER_DSP_SPLIT = "com.shilapi.xcertplay.action.ENTER_DSP_SPLIT"
    const val OPEN_CARPLAY = "com.shilapi.xcertplay.action.OPEN_CARPLAY"
    const val USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
    const val AUTO_IPHONE_USB_ATTACHED = "com.shilapi.xcertplay.action.AUTO_IPHONE_USB_ATTACHED"
    private const val INTERNAL_HOST_ACTION = "com.shilapi.xcertplay.action.INTERNAL_HOST_ACTION"
    private const val NOTIFICATION_OPEN_ACTION = "com.shilapi.xcertplay.action.NOTIFICATION_OPEN_HOST"
    private const val EXTRA_INTERNAL_ACTION_TOKEN = "com.shilapi.xcertplay.extra.INTERNAL_ACTION_TOKEN"
    private const val EXTRA_FALLBACK_ACTION = "com.shilapi.xcertplay.extra.INTERNAL_FALLBACK_ACTION"
    private const val EXTRA_USB_DEVICE_ID = "com.shilapi.xcertplay.extra.USB_DEVICE_ID"
    private const val EXTRA_USB_DEVICE_NAME = "com.shilapi.xcertplay.extra.USB_DEVICE_NAME"
    private const val EXTRA_USB_VENDOR_ID = "com.shilapi.xcertplay.extra.USB_VENDOR_ID"
    private const val EXTRA_USB_PRODUCT_ID = "com.shilapi.xcertplay.extra.USB_PRODUCT_ID"
    private const val MAX_PENDING_ACTIONS = 16
    private data class PendingAction(
        val action: String,
        val usbDeviceIdentity: UsbAttachDeviceIdentity? = null,
        val hostLaunchState: CarPlayHostLaunchState? = null,
    )
    data class InternalRequest(
        val action: String,
        val usbDeviceIdentity: UsbAttachDeviceIdentity? = null,
        val hostLaunchState: CarPlayHostLaunchState? = null,
    )
    private val pendingActions = LinkedHashMap<String, PendingAction>()

    fun createInternalIntent(
        context: Context,
        action: String,
        usbDeviceIdentity: UsbAttachDeviceIdentity? = null,
        hostLaunchState: CarPlayHostLaunchState? = null,
    ): Intent {
        require(action == OPEN_CARPLAY || action == ENTER_YOUTUBE_SPLIT || action == ENTER_DSP_SPLIT ||
            action == EXIT_YOUTUBE_SPLIT || action == USB_DEVICE_ATTACHED || action == AUTO_IPHONE_USB_ATTACHED
        ) { "Unsupported CarPlay host action" }
        require(usbDeviceIdentity == null || action == AUTO_IPHONE_USB_ATTACHED) {
            "USB device identity is only supported for iPhone USB auto-launch"
        }
        require(hostLaunchState == null || action == AUTO_IPHONE_USB_ATTACHED) {
            "Host launch state is only supported for iPhone USB auto-launch"
        }
        val token = UUID.randomUUID().toString()
        synchronized(pendingActions) {
            while (pendingActions.size >= MAX_PENDING_ACTIONS) {
                pendingActions.remove(pendingActions.keys.first())
            }
            pendingActions[token] = PendingAction(action, usbDeviceIdentity, hostLaunchState)
        }
        return Intent(context, CarPlayHostActivity::class.java)
            .setAction(INTERNAL_HOST_ACTION)
            .putExtra(EXTRA_INTERNAL_ACTION_TOKEN, token)
            .also { intent ->
                if (usbDeviceIdentity != null) {
                    intent.putExtra(EXTRA_FALLBACK_ACTION, AUTO_IPHONE_USB_ATTACHED)
                        .putExtra(EXTRA_USB_DEVICE_ID, usbDeviceIdentity.deviceId)
                        .putExtra(EXTRA_USB_DEVICE_NAME, usbDeviceIdentity.deviceName)
                        .putExtra(EXTRA_USB_VENDOR_ID, usbDeviceIdentity.vendorId)
                        .putExtra(EXTRA_USB_PRODUCT_ID, usbDeviceIdentity.productId)
                }
            }
    }

    fun createNotificationOpenIntent(context: Context): Intent =
        Intent(context, CarPlayHostActivity::class.java).setAction(NOTIFICATION_OPEN_ACTION)

    fun consumeInternalAction(intent: Intent): String? = consumeInternalRequest(intent)?.action

    fun consumeInternalRequest(intent: Intent): InternalRequest? {
        if (intent.action == NOTIFICATION_OPEN_ACTION) return InternalRequest(OPEN_CARPLAY)
        if (intent.action != INTERNAL_HOST_ACTION) return null
        val token = intent.getStringExtra(EXTRA_INTERNAL_ACTION_TOKEN) ?: return null
        val pending = synchronized(pendingActions) { pendingActions.remove(token) }
        if (pending != null) {
            return InternalRequest(pending.action, pending.usbDeviceIdentity, pending.hostLaunchState)
        }
        if (intent.getStringExtra(EXTRA_FALLBACK_ACTION) != AUTO_IPHONE_USB_ATTACHED) return null
        val identity = intent.usbDeviceIdentityExtra() ?: return null
        return InternalRequest(AUTO_IPHONE_USB_ATTACHED, identity)
    }

    private fun Intent.usbDeviceIdentityExtra(): UsbAttachDeviceIdentity? {
        if (!hasExtra(EXTRA_USB_DEVICE_ID) || !hasExtra(EXTRA_USB_DEVICE_NAME) ||
            !hasExtra(EXTRA_USB_VENDOR_ID) || !hasExtra(EXTRA_USB_PRODUCT_ID)
        ) return null
        val name = getStringExtra(EXTRA_USB_DEVICE_NAME) ?: return null
        return UsbAttachDeviceIdentity(
            deviceId = getIntExtra(EXTRA_USB_DEVICE_ID, -1),
            deviceName = name,
            vendorId = getIntExtra(EXTRA_USB_VENDOR_ID, -1),
            productId = getIntExtra(EXTRA_USB_PRODUCT_ID, -1),
        )
    }

    fun sanitizedIntent(intent: Intent): Intent = Intent(intent).apply {
        action = null
        removeExtra(EXTRA_INTERNAL_ACTION_TOKEN)
        removeExtra(EXTRA_FALLBACK_ACTION)
        removeExtra(EXTRA_USB_DEVICE_ID)
        removeExtra(EXTRA_USB_DEVICE_NAME)
        removeExtra(EXTRA_USB_VENDOR_ID)
        removeExtra(EXTRA_USB_PRODUCT_ID)
    }

    fun shouldClearRestartSuppression(action: String?): Boolean =
        action == USB_DEVICE_ATTACHED || action == AUTO_IPHONE_USB_ATTACHED ||
            command(action) != CarPlayHostLayoutCommand.NONE || action == OPEN_CARPLAY

    fun command(action: String?): CarPlayHostLayoutCommand = when (action) {
        ENTER_YOUTUBE_SPLIT -> CarPlayHostLayoutCommand.ENTER_YOUTUBE_SPLIT
        ENTER_DSP_SPLIT -> CarPlayHostLayoutCommand.ENTER_DSP_SPLIT
        EXIT_YOUTUBE_SPLIT, OPEN_CARPLAY -> CarPlayHostLayoutCommand.EXIT_SPLIT_TO_CARPLAY
        else -> CarPlayHostLayoutCommand.NONE
    }

    fun applyLayoutCommand(state: HostLayoutState, action: String?): HostLayoutState =
        when (command(action)) {
            CarPlayHostLayoutCommand.ENTER_YOUTUBE_SPLIT -> state.enterYoutubeSplit()
            CarPlayHostLayoutCommand.ENTER_DSP_SPLIT -> state.enterDspSplit()
            CarPlayHostLayoutCommand.EXIT_SPLIT_TO_CARPLAY -> state.returnToCarPlay()
            CarPlayHostLayoutCommand.NONE -> state
        }

    fun pendingSplitModeAfterLaunch(savedPending: HostLayoutMode?, action: String?): HostLayoutMode? =
        if (action == null || action == AUTO_IPHONE_USB_ATTACHED) savedPending else null
}

/**
 * CarPlay host for full-screen and split layouts. It renders decoded video through a [TextureView],
 * forwards touch to the active AirPlay session, and drives the complete wired or wireless bring-up
 * through [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
    )

    private var connectionPanel: View? = null
    private var wifiRecoveryButton: View? = null
    private var reconnectAttempts = 0
    private lateinit var airPlayIdentity: AirPlayIdentity

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
    }

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = normalizedCarPlayName(),
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
        ),
        label = "DiPlay",
        hostName = "diplay-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = DiPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus("VPN consent was denied")
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            wirelessPermissionsRefused = wirelessEnabled && !wirelessPermissionsReady
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            showMissingWirelessPermission()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }

    private var videoView: TextureView? = null
    private var gestureOverlay: View? = null
    private var carPlayContent: FrameLayout? = null
    private var hostRoot: FrameLayout? = null
    private var hostContentRow: LinearLayout? = null
    private var carPlayPane: FrameLayout? = null
    private var youtubePane: FrameLayout? = null
    private var dspPane: FrameLayout? = null
    private var dspComposeView: ComposeView? = null
    private val dspPaneVisible = mutableStateOf(false)
    private var pendingSplitStatusView: TextView? = null
    private var youtubeContent: FrameLayout? = null
    private var youtubeStatusView: TextView? = null
    private var youtubeBackButton: Button? = null
    private var youtubeBrowser: YoutubeBrowserController? = null
    private var youtubeProfile: YoutubeDeviceProfile? = null
    private var youtubeFullscreen = false
    private var imeVisible = false
    private var imeAnimationInProgress = false
    private var imeResizeSuppressed = false
    private var hostLayoutState = HostLayoutState()
    private var lastViewportLayoutMode = HostLayoutMode.CARPLAY_FULL
    private var pendingLocalPaneViewport: DisplaySize? = null
    private var pendingSplitMode: HostLayoutMode? = null
    private var localPaneLayoutPending = false
    private var activityVisible = false
    private var profileResolutionStartedElapsed = 0L
    private var profileResolutionTimedOut = false
    private var splitCarPlayReadyForYoutube = false
    private var youtubeStartupStartedElapsed = 0L
    private var youtubeLayoutStartupLogged = false
    private var youtubeProfileResolutionResetTimeout = false
    private var restartHandoffToken: Long? = null
    private var restartHandoffRetryScheduled = false
    private var splitTraceCookie: Int? = null
    private var carPlayTeardownTraceCookie: Int? = null
    private var carPlayRestartTraceCookie: Int? = null
    private var deviceInfoSession: AirPlaySession? = null
    private var connectedDeviceInfo: AirPlayDeviceInfo? = null
    private var settingsMenu: View? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var carPlayNameInput: EditText? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: Switch? = null
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var deferredSurfaceTexture: SurfaceTexture? = null
    private var surfaceGenerationBackpressureLogged = false
    private val surfaceRetirementLock = Any()
    private val surfaceRetirementGate =
        SurfaceRetirementGate<SurfaceTexture, Surface>(MAX_RETAINED_SURFACE_GENERATIONS)
    private val releasedSurfaceTextures = Collections.newSetFromMap(WeakHashMap<SurfaceTexture, Boolean>())
    private var activeDisplaySize: DisplaySize? = null
    private var localViewportSize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private val displayResizeCoordinator = DisplayResizeCoordinator()
    private var restartReadyToStart = false
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationAudioRoute = NavigationAudioRoute.FULL_BAND
    private var autoStartOnBoot = false
    private var carPlayName = AirPlayPersistence.DEFAULT_CARPLAY_NAME
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = false
    private var autoUsbLaunchIdentity: UsbAttachDeviceIdentity? = null
    private var autoUsbLaunchBlocked = false
    private var autoUsbSwitchGeneration = 0L
    private var backgroundTransportHandoffPending = false
    private var backgroundTransportHandoffGeneration = 0L
    private var ncmDiagnosticProfile = NcmDiagnosticProfile.AUTO
    private var mfiTarget = MfiTarget.USB_CH341
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessPermissionsRefused = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var latestStage = "Preparing CarPlay"
    private var darkMode = false
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var connectionEpoch = 0L
    private val reconnectAfterLossGate = ReconnectAfterLossGate()
    private var scheduledReconnectTicket: ReconnectAfterLossTicket? = null
    private var scheduledReconnectReason: String? = null
    private var sessionLog: SessionLogFile? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var carPlayTouchSequenceCancelled = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private val shuttingDown = AtomicBoolean(false)
    private val wirelessUsbSwitchRequested = AtomicBoolean(false)
    private val wiredUsbResumeAfterShutdownRequested = AtomicBoolean(false)
    private val shutdownTerminatesProcess = AtomicBoolean(false)
    private val shutdownCompletionLock = Any()
    private val shutdownCompletionCallbacks = mutableListOf<(CarPlayHostShutdownResult) -> Unit>()
    private var shutdownCompleted = false
    internal var startCarPlayForTest: ((DisplaySize) -> Unit)? = null
    internal var awaitControllerClosedForTest: ((CarPlayController, Long) -> Boolean)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<LogEntry>()
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private var restartFallbackSize: DisplaySize? = null
    private val finishRestartAfterResize = Runnable {
        restartFallbackSize?.let(::finishRestartAtLatestSize)
    }
    private val retryRestartHandoff = Runnable {
        restartHandoffRetryScheduled = false
        maybeStartCarPlay()
    }
    private val restartAfterReconnectDelay = Runnable {
        val reason = scheduledReconnectReason ?: return@Runnable
        val ticket = scheduledReconnectTicket ?: return@Runnable
        scheduledReconnectReason = null
        scheduledReconnectTicket = null
        val hasActiveSession = controller?.currentAirPlaySession()?.isClosed == false
        val blockers = ReconnectAfterLossBlockers(
            shuttingDown = shuttingDown.get(),
            finishing = isFinishing,
            destroyed = isDestroyed,
            menuOpen = menuOpen,
            handshakeResetInProgress = handshakeResetInProgress,
            pendingDisplayResize = pendingDisplaySize != null,
            controllerManaged = controller?.isWiredRecoveryManagedByController() == true,
        )
        if (!reconnectAfterLossGate.shouldRestart(
                ticket = ticket,
                currentRestartGeneration = restartGeneration,
                currentConnectionEpoch = connectionEpoch,
                currentOwnerEpoch = CarPlayBackgroundSession.ownerEpoch(),
                isOwner = CarPlayBackgroundSession.isOwner(this),
                hasActiveSession = hasActiveSession,
                blockers = blockers,
            )
        ) {
            return@Runnable
        }
        restartCarPlay("Reconnecting after $reason")
    }
    private val finishSplitYoutubeStartup = Runnable {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT ||
            pendingSplitMode != null ||
            pendingDisplaySize != null || handshakeResetInProgress || restartReadyToStart ||
            controller == null || shuttingDown.get() || isFinishing || isDestroyed
        ) {
            return@Runnable
        }
        logYoutubeStartup("layout-stable")
        splitCarPlayReadyForYoutube = true
        resolveYoutubeProfile()
        youtubeProfile?.let(::attachYoutubeBrowser)
    }
    private val prepareYoutubeAfterLayout = Runnable {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT ||
            pendingSplitMode != null ||
            shuttingDown.get() || isFinishing || isDestroyed
        ) {
            return@Runnable
        }
        logYoutubeStartup("gecko-warmup-start")
        YoutubeGeckoRuntimeProvider.warmUp(applicationContext)
        logYoutubeStartup("gecko-warmup-returned")
        startYoutubeProfileResolution(resetTimeout = youtubeProfileResolutionResetTimeout)
    }
    private val deferYoutubePreparationOneFrame = Runnable {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT ||
            pendingSplitMode != null ||
            shuttingDown.get() || isFinishing || isDestroyed
        ) {
            return@Runnable
        }
        videoView?.postOnAnimation(prepareYoutubeAfterLayout)
    }
    private var lastImeResizeSize: DisplaySize? = null
    private var stableImeResizeFrames = 0
    private val waitForImeResizeToSettle = object : Runnable {
        override fun run() {
            if (shuttingDown.get() || isFinishing || isDestroyed) {
                resetImeResizeTracking()
                return
            }
            val decor = window.decorView
            imeVisible = ViewCompat.getRootWindowInsets(decor)?.let(::hasImeInsets) ?: imeVisible
            if (imeAnimationInProgress || imeVisible) {
                if (lastImeResizeSize != null || stableImeResizeFrames != 0) resetImeResizeTracking()
                decor.postOnAnimation(this)
                return
            }
            val size = currentCarPlayViewportSize() ?: return
            if (size.width <= 0 || size.height <= 0) {
                decor.postOnAnimation(this)
                return
            }
            if (lastImeResizeSize == size) {
                stableImeResizeFrames += 1
            } else {
                lastImeResizeSize = size
                stableImeResizeFrames = 1
            }
            if (stableImeResizeFrames >= IME_RESIZE_STABLE_FRAME_COUNT) {
                resetImeResizeTracking()
                imeResizeSuppressed = false
                scheduleDisplaySize(
                    size.width,
                    size.height,
                    if (hostLayoutState.mode == HostLayoutMode.CARPLAY_FULL) {
                        HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED
                    } else {
                        HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED
                    },
                )
            } else {
                decor.postOnAnimation(this)
            }
        }
    }
    private val retryYoutubeProfileResolution = object : Runnable {
        override fun run() {
            if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT || pendingSplitMode != null) return
            if (resolveYoutubeProfile()) return
            if (SystemClock.elapsedRealtime() - profileResolutionStartedElapsed >= PROFILE_IDENTITY_TIMEOUT_MILLIS) {
                profileResolutionTimedOut = true
                showYoutubeStatus(R.string.youtube_identity_error)
                finishSplitTrace()
            } else {
                mainHandler.postDelayed(this, PROFILE_IDENTITY_RETRY_INTERVAL_MILLIS)
            }
        }
    }
    private val applyDisplaySize = Runnable {
        if (isImeResizeActive()) {
            ignoreImeDisplayResize()
            return@Runnable
        }
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val existingTexture = currentSurfaceTexture
            val surface = if (
                existing != null &&
                existingTexture === texture &&
                existing.isValid
            ) {
                existing
            } else if (!canAcceptSurfaceGeneration()) {
                deferredSurfaceTexture = texture
                null
            } else {
                Surface(texture).also {
                    existing?.let { oldSurface ->
                        retireTextureSurface(
                            oldSurface,
                            existingTexture,
                            releaseTexture = existingTexture !== texture,
                        )
                    }
                    currentSurface = it
                    currentSurfaceTexture = texture
                    deferredSurfaceTexture = null
                }
            }
            if (surface == null) {
                if (!surfaceGenerationBackpressureLogged) {
                    surfaceGenerationBackpressureLogged = true
                    appendLog("Texture surface deferred while prior surfaces retire")
                }
            } else {
                surfaceGenerationBackpressureLogged = false
                appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
                attachSurface(surface)
            }
            scheduleCurrentCarPlayViewportSize(
                width,
                height,
                HostDisplaySizeCause.SURFACE_LIFECYCLE_CHANGED,
            )
            tryApplyPendingLocalPaneViewport()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            scheduleCurrentCarPlayViewportSize(
                width,
                height,
                HostDisplaySizeCause.SURFACE_LIFECYCLE_CHANGED,
            )
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) {
                return synchronized(surfaceRetirementLock) {
                    if (deferredSurfaceTexture === texture) deferredSurfaceTexture = null
                    when {
                        surfaceRetirementGate.hasPendingTexture(texture) -> false
                        releasedSurfaceTextures.remove(texture) -> false
                        else -> true
                    }
                }
            }
            if (deferredSurfaceTexture === texture) deferredSurfaceTexture = null
            val surface = currentSurface
            currentSurface = null
            currentSurfaceTexture = null
            if (surface != null) {
                retireTextureSurface(surface, texture)
            } else {
                texture.release()
            }
            appendLog("Texture surface destroyed")
            return false
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SplitPerformanceTracer.configure(applicationContext)
        val initialRequest = CarPlayHostActions.consumeInternalRequest(intent)
        val initialAction = initialRequest?.action
        if (initialAction == CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED) {
            val identity = initialRequest.usbDeviceIdentity
            if (identity == null || IphoneUsbAutoLaunchSettings.currentCandidate(this, identity) == null) {
                identity?.let(IphoneUsbAutoLaunchDispatcher::failLaunch)
                Log.i(TAG, "event=APPLE_USB_AUTO_ATTACH source=host_create result=stale_or_disabled")
                finish()
                return
            }
            autoUsbLaunchIdentity = identity
            IphoneUsbAutoLaunchNotification.cancel(this)
        } else if (savedInstanceState != null) {
            autoUsbLaunchIdentity = readAutoUsbDeviceIdentity(savedInstanceState)
            autoUsbLaunchBlocked = savedInstanceState.getBoolean(STATE_AUTO_USB_LAUNCH_BLOCKED)
            val restoredIdentity = autoUsbLaunchIdentity
            if (restoredIdentity != null &&
                IphoneUsbAutoLaunchSettings.currentCandidate(this, restoredIdentity) == null
            ) {
                autoUsbLaunchBlocked = true
            }
        }
        if (CarPlayHostActions.shouldClearRestartSuppression(initialAction)) {
            CarPlayBackgroundSession.clearRestartSuppression()
        }
        val launchState = initialRequest?.hostLaunchState
            ?: CarPlayBackgroundSession.snapshot()?.hostLaunchState
        hostLayoutState = HostLayoutState(
            mode = HostLayoutState.restoreMode(
                savedMode = savedInstanceState?.getString(STATE_HOST_LAYOUT_MODE) ?: launchState?.layoutMode?.name,
                legacyYoutubeSplit = savedInstanceState?.getBoolean(STATE_YOUTUBE_SPLIT) == true ||
                    launchState?.layoutMode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT,
            ),
            carPlayFraction = SplitLayoutConfig.normalizeCarPlayFraction(
                savedInstanceState?.getFloat(STATE_CARPLAY_FRACTION)
                    ?: launchState?.carPlayFraction
                    ?: SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION,
            ),
        )
        val savedPendingSplitMode = savedInstanceState?.getString(STATE_PENDING_SPLIT_MODE)?.let { savedMode ->
            HostLayoutState.restoreMode(savedMode, legacyYoutubeSplit = false)
                .takeUnless { it == HostLayoutMode.CARPLAY_FULL }
        } ?: launchState?.pendingSplitMode
        pendingSplitMode = CarPlayHostActions.pendingSplitModeAfterLaunch(savedPendingSplitMode, initialAction)
        savedInstanceState?.getString(DspEditorSavedState.DRAFT_PROFILE)?.let { savedDraft ->
            val runtime = DspProfileRuntime.get(applicationContext)
            runtime.decodeProfileDraftFromSavedState(savedDraft)?.let(runtime.editorSession::restoreDraft)
        }
        hostLayoutState = CarPlayHostActions.applyLayoutCommand(hostLayoutState, initialAction)
        if (
            hostLayoutState.mode != HostLayoutMode.CARPLAY_FULL &&
            (controller != null || CarPlayBackgroundSession.hasSession()) &&
            !canScaleCarPlayLocally()
        ) {
            pendingSplitMode = hostLayoutState.mode
        }
        when (initialAction) {
            CarPlayHostActions.USB_DEVICE_ATTACHED ->
                AirPlayPersistence.saveWirelessEnabled(this, false)
            CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED ->
                AirPlayPersistence.saveWirelessEnabled(this, false)
            else -> Unit
        }
        if (intent.action != null) setIntent(CarPlayHostActions.sanitizedIntent(intent))
        if (runCatching { DiPlayBootstrap.ensure(this) }.isFailure) {
            startActivity(Intent(this, DiPlayActivity::class.java))
            finish(); return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initializeSessionLog()
        darkMode = isDarkMode(resources.configuration.uiMode)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        observeImeInsets()
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else if (youtubeFullscreen) {
                        exitYoutubeFullscreen()
                    } else if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
                        if (youtubeBrowser?.goBack() != true) exitYoutubeSplit()
                    } else if (hostLayoutState.mode == HostLayoutMode.CARPLAY_DSP_SPLIT) {
                        exitDspSplit()
                    } else {
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT && pendingSplitMode == null) {
            SplitPerformanceTracer.increment(SplitPerformanceCounter.SPLIT_ENTRIES)
            splitTraceCookie = SplitPerformanceTracer.beginAsync("diplay.split.total")
            youtubeStartupStartedElapsed = SystemClock.elapsedRealtime()
            logYoutubeStartup("split-restored")
            scheduleYoutubePreparationAfterLayout(resetTimeout = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_HOST_LAYOUT_MODE, hostLayoutState.mode.name)
        outState.putString(STATE_PENDING_SPLIT_MODE, pendingSplitMode?.name)
        outState.putBoolean(
            STATE_YOUTUBE_SPLIT,
            hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT,
        )
        outState.putFloat(STATE_CARPLAY_FRACTION, hostLayoutState.carPlayFraction)
        autoUsbLaunchIdentity?.let { identity ->
            outState.putInt(STATE_AUTO_USB_DEVICE_ID, identity.deviceId)
            outState.putString(STATE_AUTO_USB_DEVICE_NAME, identity.deviceName)
            outState.putInt(STATE_AUTO_USB_VENDOR_ID, identity.vendorId)
            outState.putInt(STATE_AUTO_USB_PRODUCT_ID, identity.productId)
        }
        outState.putBoolean(STATE_AUTO_USB_LAUNCH_BLOCKED, autoUsbLaunchBlocked)
        DspProfileRuntime.peekInstance()?.let { runtime ->
            outState.putString(
                DspEditorSavedState.DRAFT_PROFILE,
                runtime.encodeProfileDraftForSavedState(runtime.editorSession.draftProfile),
            )
        }
        super.onSaveInstanceState(outState)
    }

    private fun readAutoUsbDeviceIdentity(state: Bundle): UsbAttachDeviceIdentity? {
        if (!state.containsKey(STATE_AUTO_USB_DEVICE_ID) ||
            !state.containsKey(STATE_AUTO_USB_DEVICE_NAME) ||
            !state.containsKey(STATE_AUTO_USB_VENDOR_ID) ||
            !state.containsKey(STATE_AUTO_USB_PRODUCT_ID)
        ) return null
        val name = state.getString(STATE_AUTO_USB_DEVICE_NAME) ?: return null
        return UsbAttachDeviceIdentity(
            deviceId = state.getInt(STATE_AUTO_USB_DEVICE_ID, -1),
            deviceName = name,
            vendorId = state.getInt(STATE_AUTO_USB_VENDOR_ID, -1),
            productId = state.getInt(STATE_AUTO_USB_PRODUCT_ID, -1),
        )
    }

    private fun loadPersistedSettings() {
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationAudioRoute = AirPlayPersistence.loadNavigationAudioRoute(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        carPlayName = AirPlayPersistence.loadCarPlayName(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        ncmDiagnosticProfile = NcmDiagnosticProfileStore.load(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            wirelessPermissionsRefused = false
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun showMissingWirelessPermission() {
        if (!wirelessEnabled || wirelessPermissionsReady) {
            if (wirelessPermissionsRefused) setConnectionStageResource(R.string.host_stage_ready)
            wirelessPermissionsRefused = false
            return
        }
        if (!wirelessPermissionsRefused) return
        val missing = requiredWirelessPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        setConnectionStageResource(WirelessPermissions.refusalMessage(missing))
    }

    private fun setConnectionStageResource(@StringRes messageRes: Int) {
        latestStage = getString(messageRes)
        stageStatusView?.text = latestStage
        updateDebugOverlays()
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val request = CarPlayHostActions.consumeInternalRequest(intent)
        val action = request?.action
        setIntent(CarPlayHostActions.sanitizedIntent(intent))
        if (action == null) return
        if (action == CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED) {
            val identity = request.usbDeviceIdentity
            if (identity == null || IphoneUsbAutoLaunchSettings.currentCandidate(this, identity) == null) {
                identity?.let(IphoneUsbAutoLaunchDispatcher::failLaunch)
                Log.i(TAG, "event=APPLE_USB_AUTO_ATTACH source=host_new_intent result=stale_or_disabled")
                return
            }
            if (isDestroyed || shutdownTerminatesProcess.get()) {
                IphoneUsbAutoLaunchDispatcher.failLaunch(identity)
                Log.i(TAG, "event=APPLE_USB_AUTO_ATTACH source=host_new_intent result=shutdown_blocked")
                return
            }
            val requestedLayout = request.hostLaunchState ?: currentHostLaunchState()
            if (IphoneUsbAutoLaunchDispatcher.replaceHandoffRequest(identity, requestedLayout)) {
                autoUsbLaunchIdentity = identity
                autoUsbLaunchBlocked = false
                return
            }
            if (shuttingDown.get()) {
                autoUsbLaunchIdentity = identity
                IphoneUsbAutoLaunchDispatcher.deferTransition(
                    identity,
                    IphoneUsbAutoLaunchSource.ATTACH,
                )
                return
            }
            if (isFinishing) {
                IphoneUsbAutoLaunchDispatcher.failLaunch(identity)
                return
            }
            autoUsbLaunchIdentity = identity
            autoUsbLaunchBlocked = false
            IphoneUsbAutoLaunchNotification.cancel(this)
            CarPlayBackgroundSession.clearRestartSuppression()
            val snapshot = CarPlayBackgroundSession.snapshot()
            val currentController = controller ?: snapshot?.controller
            if (currentController?.isUsingWiredTransport() == true) {
                AirPlayPersistence.saveWirelessEnabled(this, false)
                wirelessEnabled = false
                maybeStartCarPlay()
                return
            }
            if (currentController != null) {
                AirPlayPersistence.saveWirelessEnabled(this, false)
                wirelessEnabled = false
                if (CarPlayBackgroundSession.isOwner(this)) {
                    if (controller == null && snapshot != null) {
                        controller = snapshot.controller
                        sink = snapshot.sink
                    }
                    beginOwnedUsbTransportHandoff(identity, requestedLayout)
                } else if (snapshot != null) {
                    requestBackgroundTransportHandoff(snapshot)
                } else {
                    IphoneUsbAutoLaunchDispatcher.deferTransition(
                        identity,
                        IphoneUsbAutoLaunchSource.ATTACH,
                    )
                }
                return
            }
            if (CarPlayBackgroundSession.hasPendingRestart() || CarPlayBackgroundSession.hasSession()) {
                IphoneUsbAutoLaunchDispatcher.deferTransition(identity, IphoneUsbAutoLaunchSource.ATTACH)
                return
            }
            AirPlayPersistence.saveWirelessEnabled(this, false)
            wirelessEnabled = false
            wirelessPermissionsReady = true
            if (microphonePermissionResolved) requestStartupPrerequisites()
            maybeStartCarPlay()
            return
        }
        if (action == CarPlayHostActions.USB_DEVICE_ATTACHED) {
            autoUsbLaunchIdentity = null
            autoUsbLaunchBlocked = false
            if (isDestroyed) {
                Log.i(TAG, "USB_ATTACH wired resume blocked reason=host_shutting_down")
                return
            }
            if (shuttingDown.get()) {
                if (shutdownTerminatesProcess.get()) {
                    Log.i(TAG, "USB_ATTACH wired resume blocked reason=process_shutdown")
                    return
                }
                if (wirelessEnabled) {
                    if (!wirelessUsbSwitchRequested.compareAndSet(false, true)) return
                    enqueueShutdownCompletion { result ->
                        if (result != CarPlayHostShutdownResult.CLOSED) return@enqueueShutdownCompletion
                        AirPlayPersistence.saveWirelessEnabled(this, false)
                        startActivity(
                            CarPlayHostActions.createInternalIntent(this, CarPlayHostActions.OPEN_CARPLAY),
                        )
                        finish()
                    }
                } else {
                    if (!wiredUsbResumeAfterShutdownRequested.compareAndSet(false, true)) return
                    enqueueShutdownCompletion { result ->
                        if (result != CarPlayHostShutdownResult.CLOSED) return@enqueueShutdownCompletion
                        startActivity(
                            CarPlayHostActions.createInternalIntent(this, CarPlayHostActions.OPEN_CARPLAY),
                        )
                        finish()
                    }
                }
                finish()
                return
            }
            if (isFinishing) {
                Log.i(TAG, "USB_ATTACH wired resume blocked reason=host_finishing")
                return
            }
            CarPlayBackgroundSession.clearRestartSuppression()
            if (wirelessEnabled) {
                if (!wirelessUsbSwitchRequested.compareAndSet(false, true)) {
                    Log.i(TAG, "USB_ATTACH wireless switch already requested")
                    return
                }
                shutdown(false, "switching to USB") { result ->
                    if (result != CarPlayHostShutdownResult.CLOSED) return@shutdown
                    AirPlayPersistence.saveWirelessEnabled(this, false)
                    startActivity(
                        CarPlayHostActions.createInternalIntent(this, CarPlayHostActions.OPEN_CARPLAY),
                    )
                }
                finish()
            } else {
                Log.i(TAG, "USB_ATTACH wired resume requested")
                maybeStartCarPlay()
            }
            return
        }
        if (CarPlayHostActions.shouldClearRestartSuppression(action)) {
            CarPlayBackgroundSession.clearRestartSuppression()
        }
        if (action == CarPlayHostActions.OPEN_CARPLAY) {
            autoUsbLaunchIdentity = null
            autoUsbLaunchBlocked = false
            pendingSplitMode = null
            returnToCarPlay()
            maybeStartCarPlay()
            return
        }
        when (CarPlayHostActions.command(action)) {
            CarPlayHostLayoutCommand.ENTER_YOUTUBE_SPLIT -> {
                pendingSplitMode = null
                enterYoutubeSplit()
            }
            CarPlayHostLayoutCommand.ENTER_DSP_SPLIT -> {
                pendingSplitMode = null
                enterDspSplit()
            }
            CarPlayHostLayoutCommand.EXIT_SPLIT_TO_CARPLAY -> {
                pendingSplitMode = null
                returnToCarPlay()
            }
            CarPlayHostLayoutCommand.NONE -> Unit
        }
    }

    private fun beginOwnedUsbTransportHandoff(
        identity: UsbAttachDeviceIdentity,
        hostLaunchState: CarPlayHostLaunchState,
    ) {
        val transition = ++autoUsbSwitchGeneration
        val handoffGeneration = IphoneUsbAutoLaunchDispatcher.beginHandoff(identity, hostLaunchState)
        var expectedOwnerEpoch = -1L
        shutdown(false, "switching to wired USB CarPlay") { shutdownResult ->
            if (shutdownResult != CarPlayHostShutdownResult.CLOSED) return@shutdown
            if (shutdownTerminatesProcess.get()) {
                IphoneUsbAutoLaunchDispatcher.completeHandoff(handoffGeneration)
                return@shutdown
            }
            if (transition != autoUsbSwitchGeneration ||
                CarPlayBackgroundSession.ownerEpoch() != expectedOwnerEpoch || isDestroyed
            ) {
                IphoneUsbAutoLaunchDispatcher.failHandoff(handoffGeneration)
                return@shutdown
            }
            val latestRequest = IphoneUsbAutoLaunchDispatcher.completeHandoff(handoffGeneration)
                ?: DeferredIphoneUsbAutoLaunch(
                    identity,
                    IphoneUsbAutoLaunchSource.ATTACH,
                    hostLaunchState,
                )
            val latestIdentity = latestRequest.identity
            if (IphoneUsbAutoLaunchSettings.currentCandidate(this, latestIdentity) == null) {
                IphoneUsbAutoLaunchDispatcher.failLaunch(latestIdentity)
                AirPlayPersistence.saveWirelessEnabled(this, true)
                try {
                    startActivity(CarPlayHostActions.createInternalIntent(this, CarPlayHostActions.OPEN_CARPLAY))
                } catch (error: RuntimeException) {
                    Log.w(
                        TAG,
                        "event=APPLE_USB_AUTO_ATTACH source=wireless_switch result=restore_failed " +
                            "error=${error.javaClass.simpleName}",
                    )
                }
                return@shutdown
            }
            autoUsbLaunchIdentity = latestIdentity
            autoUsbLaunchBlocked = false
            AirPlayPersistence.saveWirelessEnabled(this, false)
            try {
                startActivity(
                    CarPlayHostActions.createInternalIntent(
                        this,
                        CarPlayHostActions.AUTO_IPHONE_USB_ATTACHED,
                        latestIdentity,
                        latestRequest.hostLaunchState ?: hostLaunchState,
                    ),
                )
            } catch (error: RuntimeException) {
                Log.w(
                    TAG,
                    "event=APPLE_USB_AUTO_ATTACH source=wireless_switch result=launch_failed " +
                        "error=${error.javaClass.simpleName}",
                )
                IphoneUsbAutoLaunchDispatcher.failLaunch(latestIdentity)
                IphoneUsbAutoLaunchNotification.show(this)
            }
        }
        expectedOwnerEpoch = CarPlayBackgroundSession.ownerEpoch()
        finish()
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        if (!AppLanguage.isApplied(this)) {
            // Recreate only the UI. onDestroy leaves the background controller alive and the
            // replacement activity adopts it in onCreate via adoptBackgroundSession().
            recreate()
            return
        }
        if (!menuOpen) {
            carPlayName = AirPlayPersistence.loadCarPlayName(this)
            carPlayNameInput?.takeIf { it.text.toString() != carPlayName }?.setText(carPlayName)
        }
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        showMissingWirelessPermission()
        maybeStartCarPlay()
        applyFullscreenMode()
        reconcileImeResizeState()
        DspProfileRuntime.peekInstance()?.let { runtime ->
            runtime.editorSession.refreshFromRuntime(runtime.selectedProfile(), runtime.isDspEnabled())
        }
        carPlayPane?.post { tryApplyPendingLocalPaneViewport() }
        youtubeBrowser?.setActive(
            pendingSplitMode == null && hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT &&
                youtubeProfile != null &&
                activeAirPlaySession != null,
        )
    }

    override fun onPause() {
        activityVisible = false
        youtubeBrowser?.setActive(false)
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (
            YoutubeBrowserMemoryPolicy.shouldEvictSuspendedSession(
                sdkInt = Build.VERSION.SDK_INT,
                trimLevel = level,
                splitMode = hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT,
                browserSuspended = youtubeBrowser?.isSuspendedForReuse == true,
            )
        ) {
            Log.i(TAG, "Discarding suspended YouTube session under memory pressure")
            destroyYoutubeBrowser()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyFullscreenMode()
            reconcileImeResizeState()
        }
    }

    override fun onStop() {
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        YoutubeGeckoRuntimeProvider.configurationChanged(newConfig)
        val nextDarkMode = isDarkMode(newConfig.uiMode)
        if (nextDarkMode != darkMode) {
            darkMode = nextDarkMode
            syncAirPlayDarkMode()
        }
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        hostRoot?.post { scheduleDisplaySizeForConfigurationChange() }
    }

    override fun onDestroy() {
        SplitPerformanceTracer.endAsync("diplay.split.total", splitTraceCookie)
        SplitPerformanceTracer.endAsync("diplay.split.carplay_teardown", carPlayTeardownTraceCookie)
        finishCarPlayRestartTrace()
        splitTraceCookie = null
        carPlayTeardownTraceCookie = null
        carPlayRestartTraceCookie = null
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(finishRestartAfterResize)
        mainHandler.removeCallbacks(finishSplitYoutubeStartup)
        mainHandler.removeCallbacks(retryRestartHandoff)
        cancelPendingReconnectAfterLoss()
        window.decorView.removeCallbacks(waitForImeResizeToSettle)
        videoView?.removeCallbacks(deferYoutubePreparationOneFrame)
        videoView?.removeCallbacks(prepareYoutubeAfterLayout)
        restartHandoffRetryScheduled = false
        mainHandler.removeCallbacks(retryYoutubeProfileResolution)
        mainHandler.removeCallbacks(expireOldLogLines)
        if (restartHandoffToken != null || CarPlayBackgroundSession.isClaimedRestartOwner(this)) {
            CarPlayBackgroundSession.releaseRestartOwner(this)
            restartHandoffToken = null
        }
        sink?.setVideoFrameSubmittedToSurfaceListener(null)
        destroyYoutubeBrowser()
        currentSurface?.let { surface -> retireTextureSurface(surface, currentSurfaceTexture) }
        currentSurface = null
        currentSurfaceTexture = null
        deferredSurfaceTexture = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        teardownExecutor.shutdown()
        airPlayCommandExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 17, 27))
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val width = right - left
                val height = bottom - top
                if (hostLayoutState.mode == HostLayoutMode.CARPLAY_FULL &&
                    (width != oldRight - oldLeft || height != oldBottom - oldTop)
                ) {
                    scheduleDisplaySize(width, height, HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED)
                }
            }
        }
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val carPlay = FrameLayout(this)
        carPlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val cause = viewportCauseForCurrentLayout()
            scheduleCurrentCarPlayViewportSize(cause = cause)
            tryApplyPendingLocalPaneViewport()
        }
        val carPlayContent = FrameLayout(this)
        val video = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = textureListener
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        carPlayContent.addView(video, FrameLayout.LayoutParams(-1, -1))
        carPlayContent.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        carPlay.addView(carPlayContent, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(Color.rgb(12, 17, 27))
            isClickable = true
        }
        panel.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay); contentDescription = getString(R.string.host_accessibility_carplay)
        }, LinearLayout.LayoutParams(dp(88), dp(88)))
        panel.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 34f; setTextColor(Color.rgb(241, 245, 252))
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(14))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        val stage = TextView(this).apply {
            text = getString(R.string.host_stage_ready); textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(241, 245, 252))
        }
        panel.addView(stage)
        panel.addView(TextView(this).apply {
            text = getString(
                if (wirelessEnabled) R.string.host_connection_hint_wireless
                else R.string.host_connection_hint_wired,
            )
            textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.rgb(168, 182, 202))
            setPadding(0, dp(14), 0, dp(24))
        })
        panel.addView(Button(this).apply {
            text = getString(R.string.host_reset_carplay_wifi); isAllCaps = false; textSize = 18f
            visibility = View.GONE
            setOnClickListener { showDiPlayHome("wireless-recovery") }
            wifiRecoveryButton = this
        }, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        panel.addView(Button(this).apply {
            text = getString(R.string.host_back_to_diplay); isAllCaps = false; textSize = 18f
            setTextColor(Color.rgb(12, 17, 27))
            background = GradientDrawable().apply { setColor(Color.rgb(166, 200, 255)); cornerRadius = dp(20).toFloat() }
            setOnClickListener { showDiPlayHome() }
        }, LinearLayout.LayoutParams(dp(300), dp(64)))
        panel.addView(TextView(this).apply {
            text = getString(R.string.host_open_settings_gesture)
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.rgb(168, 182, 202)); setPadding(0, dp(20), 0, 0)
        })
        carPlay.addView(panel, FrameLayout.LayoutParams(-1, -1))

        val youtube = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(0, 0, 0))
            visibility = View.GONE
        }
        val dsp = createDspMeasurementPane().apply {
            setBackgroundColor(Color.rgb(12, 17, 27))
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val pendingSplitStatus = TextView(this).apply {
            text = getString(R.string.host_split_waiting)
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBackground(Color.rgb(24, 28, 34), dp(12))
            visibility = View.GONE
        }
        val browserContent = FrameLayout(this)
        val status = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = roundedBackground(Color.rgb(24, 28, 34), dp(12))
            visibility = View.GONE
        }
        browserContent.addView(status, FrameLayout.LayoutParams(-1, -1))
        youtube.addView(
            browserContent,
            FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(YOUTUBE_TOOLBAR_HEIGHT_DP) },
        )
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(18, 22, 28))
        }
        val backButton = youtubeButton(R.string.youtube_back) {
            if (youtubeBrowser?.goBack() != true) exitYoutubeSplit()
        }.apply {
            isEnabled = false
            youtubeBackButton = this
        }
        toolbar.addView(backButton, LinearLayout.LayoutParams(0, -1, 1f))
        toolbar.addView(
            youtubeButton(R.string.youtube_reload) { reloadYoutube() },
            LinearLayout.LayoutParams(0, -1, 1f),
        )
        toolbar.addView(
            youtubeButton(R.string.youtube_close) { exitYoutubeSplit() },
            LinearLayout.LayoutParams(0, -1, 1f),
        )
        youtube.addView(
            toolbar,
            FrameLayout.LayoutParams(-1, dp(YOUTUBE_TOOLBAR_HEIGHT_DP), Gravity.TOP),
        )

        contentRow.addView(carPlay, LinearLayout.LayoutParams(0, -1, 1f))
        contentRow.addView(youtube, LinearLayout.LayoutParams(0, -1, 0f))
        contentRow.addView(dsp, LinearLayout.LayoutParams(0, -1, 0f))
        root.addView(contentRow, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            pendingSplitStatus,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(12)
            },
        )
        hostRoot = root
        hostContentRow = contentRow
        carPlayPane = carPlay
        dspPane = dsp
        pendingSplitStatusView = pendingSplitStatus
        this.carPlayContent = carPlayContent
        youtubePane = youtube
        youtubeContent = browserContent
        youtubeStatusView = status
        videoView = video
        gestureOverlay = gestureLayer
        stageStatusView = stage
        connectionPanel = panel
        if (hostLayoutState.mode == HostLayoutMode.CARPLAY_DSP_SPLIT && pendingSplitMode == null) {
            ensureDspComposeView()
        }
        updateHostLayout()
        updateDebugOverlays()
        return root
    }

    private fun youtubeButton(resourceId: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = getString(resourceId)
            isAllCaps = false
            minHeight = dp(YOUTUBE_BUTTON_MIN_HEIGHT_DP)
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = getString(resourceId)
            setOnClickListener { action() }
        }

    private fun roundedBackground(color: Int, radiusPx: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }

    private fun updateHostLayout() {
        SplitPerformanceTracer.section("diplay.split.layout") {
            updateHostLayoutInternal()
        }
    }

    private fun updateHostLayoutInternal() {
        val carPlay = carPlayPane ?: return
        val youtube = youtubePane ?: return
        val dsp = dspPane ?: return
        val visibleMode = if (pendingSplitMode == null) hostLayoutState.mode else HostLayoutMode.CARPLAY_FULL
        val split = visibleMode != HostLayoutMode.CARPLAY_FULL
        val youtubeSplit = visibleMode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT
        val dspSplit = visibleMode == HostLayoutMode.CARPLAY_DSP_SPLIT
        val carPlayFraction = SplitLayoutConfig.normalizeCarPlayFraction(hostLayoutState.carPlayFraction)
        (carPlay.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.weight = if (split) carPlayFraction else 1f
            carPlay.layoutParams = params
        }
        (youtube.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.weight = if (youtubeSplit) 1f - carPlayFraction else 0f
            youtube.layoutParams = params
        }
        (dsp.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.weight = if (dspSplit) 1f - carPlayFraction else 0f
            dsp.layoutParams = params
        }
        youtube.visibility = if (youtubeSplit) View.VISIBLE else View.GONE
        dsp.visibility = if (dspSplit) View.VISIBLE else View.GONE
        dspPaneVisible.value = dspSplit
        pendingSplitStatusView?.visibility = if (pendingSplitMode == null) View.GONE else View.VISIBLE
        hostContentRow?.requestLayout()
        CarPlayBackgroundSession.updateHostLaunchState(this, currentHostLaunchState())
    }

    private fun ensureDspComposeView() {
        val pane = dspPane ?: return
        if (dspComposeView != null) return
        val runtime = DspProfileRuntime.get(applicationContext)
        val view = ComposeView(this).apply {
            id = R.id.dsp_split_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(colorScheme = dspColorScheme()) {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = DspPresentationMode.COMPACT_SPLIT,
                        isVisible = dspPaneVisible.value,
                        onBack = ::exitDspSplit,
                    )
                }
            }
        }
        dspComposeView = view
        pane.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun createDspMeasurementPane(): FrameLayout = object : FrameLayout(this) {
        override fun measureChildWithMargins(
            child: View,
            parentWidthMeasureSpec: Int,
            widthUsed: Int,
            parentHeightMeasureSpec: Int,
            heightUsed: Int,
        ) {
            if (child.id != R.id.dsp_split_compose_view) {
                super.measureChildWithMargins(
                    child,
                    parentWidthMeasureSpec,
                    widthUsed,
                    parentHeightMeasureSpec,
                    heightUsed,
                )
                return
            }
            val params = child.layoutParams as ViewGroup.MarginLayoutParams
            val widthPadding = paddingLeft + paddingRight + params.leftMargin + params.rightMargin + widthUsed
            val heightPadding = paddingTop + paddingBottom + params.topMargin + params.bottomMargin + heightUsed
            val composeWidthSpec = getChildMeasureSpec(parentWidthMeasureSpec, widthPadding, params.width)
            val composeHeightSpec = getChildMeasureSpec(parentHeightMeasureSpec, heightPadding, params.height)
            child.measure(composeWidthSpec, composeHeightSpec)
            if (
                DspSplitMeasureDiagnostics.recordViewMeasurement(
                    View.MeasureSpec.getMode(composeWidthSpec),
                    View.MeasureSpec.getSize(composeWidthSpec),
                    View.MeasureSpec.getMode(composeHeightSpec),
                    View.MeasureSpec.getSize(composeHeightSpec),
                    child.measuredWidth,
                    child.measuredHeight,
                )
            ) {
                val measurement = "widthMode=${measureMode(composeWidthSpec)} " +
                    "widthSize=${View.MeasureSpec.getSize(composeWidthSpec)} " +
                    "heightMode=${measureMode(composeHeightSpec)} " +
                    "heightSize=${View.MeasureSpec.getSize(composeHeightSpec)} " +
                    "measured=${child.measuredWidth}x${child.measuredHeight}"
                Log.i(TAG, "DSP_SPLIT_COMPOSE_MEASURE $measurement")
            }
        }

        private fun measureMode(spec: Int): String = when (View.MeasureSpec.getMode(spec)) {
            View.MeasureSpec.EXACTLY -> "EXACTLY"
            View.MeasureSpec.AT_MOST -> "AT_MOST"
            else -> "UNSPECIFIED"
        }
    }

    private fun enterYoutubeSplit() {
        if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
            if (pendingSplitMode != null) {
                tryApplyPendingLocalPaneViewport()
                return
            }
            startYoutubeProfileResolution(resetTimeout = true)
            return
        }
        dspComposeView?.clearFocus()
        youtubeBrowser?.let { browser ->
            browser.view.visibility = View.GONE
            browser.setActive(false)
        }
        localPaneLayoutPending = true
        hostLayoutState = hostLayoutState.enterYoutubeSplit()
        pendingSplitMode = if ((controller != null || CarPlayBackgroundSession.hasSession()) && !canScaleCarPlayLocally()) {
            HostLayoutMode.CARPLAY_YOUTUBE_SPLIT
        } else {
            null
        }
        SplitPerformanceTracer.increment(SplitPerformanceCounter.SPLIT_ENTRIES)
        splitTraceCookie = SplitPerformanceTracer.beginAsync("diplay.split.total")
        splitCarPlayReadyForYoutube = false
        youtubeStartupStartedElapsed = SystemClock.elapsedRealtime()
        youtubeLayoutStartupLogged = false
        youtubeFullscreen = false
        updateHostLayout()
        carPlayPane?.post {
            scheduleCurrentCarPlayViewportSize(cause = HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED)
            tryApplyPendingLocalPaneViewport()
        }
        Log.i(TAG, "YouTube split requested")
        logYoutubeStartup("split-request")
        showYoutubeStatus(R.string.youtube_waiting_for_iphone_profile)
        if (pendingSplitMode == null) scheduleYoutubePreparationAfterLayout(resetTimeout = true)
    }

    private fun exitYoutubeSplit() {
        SplitPerformanceTracer.section("diplay.split.exit") {
            exitYoutubeSplitInternal()
        }
    }

    private fun exitYoutubeSplitInternal() {
        val wasSplit = hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT
        if (!wasSplit) return
        if (wasSplit) {
            SplitPerformanceTracer.increment(SplitPerformanceCounter.SPLIT_EXITS)
            finishSplitTrace()
        }
        splitCarPlayReadyForYoutube = false
        mainHandler.removeCallbacks(finishSplitYoutubeStartup)
        videoView?.removeCallbacks(deferYoutubePreparationOneFrame)
        videoView?.removeCallbacks(prepareYoutubeAfterLayout)
        mainHandler.removeCallbacks(retryYoutubeProfileResolution)
        if (youtubeFullscreen) exitYoutubeFullscreen()
        youtubeBrowser?.suspendForReuse()
        profileResolutionTimedOut = false
        youtubeProfile = null
        YoutubeDeviceProfileManager.clearSplitSelection()
        youtubeStartupStartedElapsed = 0L
        youtubeLayoutStartupLogged = false
        pendingSplitMode = null
        localPaneLayoutPending = true
        hostLayoutState = hostLayoutState.returnToCarPlay()
        updateHostLayout()
        if (wasSplit) Log.i(TAG, "YouTube split exited")
        SplitPerformanceTracer.summary()?.let { Log.i(TAG, "Split performance $it") }
        carPlayPane?.post {
            scheduleCurrentCarPlayViewportSize(cause = HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED)
            tryApplyPendingLocalPaneViewport()
        }
    }

    private fun enterDspSplit() {
        if (hostLayoutState.mode == HostLayoutMode.CARPLAY_DSP_SPLIT) {
            tryApplyPendingLocalPaneViewport()
            return
        }
        if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
            SplitPerformanceTracer.increment(SplitPerformanceCounter.SPLIT_EXITS)
            finishSplitTrace()
            mainHandler.removeCallbacks(finishSplitYoutubeStartup)
            videoView?.removeCallbacks(deferYoutubePreparationOneFrame)
            videoView?.removeCallbacks(prepareYoutubeAfterLayout)
            mainHandler.removeCallbacks(retryYoutubeProfileResolution)
            if (youtubeFullscreen) exitYoutubeFullscreen()
            youtubeBrowser?.let { browser ->
                browser.view.visibility = View.GONE
                browser.suspendForReuse()
            }
            splitCarPlayReadyForYoutube = false
        } else {
            youtubeBrowser?.setActive(false)
        }
        dspComposeView?.clearFocus()
        localPaneLayoutPending = true
        hostLayoutState = hostLayoutState.enterDspSplit()
        pendingSplitMode = if ((controller != null || CarPlayBackgroundSession.hasSession()) && !canScaleCarPlayLocally()) {
            HostLayoutMode.CARPLAY_DSP_SPLIT
        } else {
            null
        }
        if (pendingSplitMode == null) ensureDspComposeView()
        updateHostLayout()
        carPlayPane?.post {
            scheduleCurrentCarPlayViewportSize(cause = HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED)
            tryApplyPendingLocalPaneViewport()
        }
        Log.i(TAG, "DSP split requested")
    }

    private fun exitDspSplit() {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_DSP_SPLIT) return
        dspComposeView?.clearFocus()
        if (imeVisible || isImeResizeActive()) {
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        localPaneLayoutPending = true
        hostLayoutState = hostLayoutState.returnToCarPlay()
        pendingSplitMode = null
        updateHostLayout()
        carPlayPane?.post {
            scheduleCurrentCarPlayViewportSize(cause = HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED)
        }
        Log.i(TAG, "DSP split exited")
    }

    private fun returnToCarPlay() {
        when (hostLayoutState.mode) {
            HostLayoutMode.CARPLAY_YOUTUBE_SPLIT -> exitYoutubeSplit()
            HostLayoutMode.CARPLAY_DSP_SPLIT -> exitDspSplit()
            HostLayoutMode.CARPLAY_FULL -> Unit
        }
    }

    private fun scheduleYoutubePreparationAfterLayout(resetTimeout: Boolean) {
        val view = videoView ?: return
        youtubeProfileResolutionResetTimeout = resetTimeout
        view.removeCallbacks(prepareYoutubeAfterLayout)
        view.post {
            val layoutView = videoView ?: return@post
            if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT || pendingSplitMode != null) return@post
            scheduleCurrentCarPlayViewportSize(cause = HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED)
            if (!youtubeLayoutStartupLogged) {
                youtubeLayoutStartupLogged = true
                logYoutubeStartup("layout-requested")
            }
            layoutView.removeCallbacks(deferYoutubePreparationOneFrame)
            layoutView.removeCallbacks(prepareYoutubeAfterLayout)
            layoutView.postOnAnimation(deferYoutubePreparationOneFrame)
        }
    }

    private fun logYoutubeStartup(stage: String) {
        val startedAt = youtubeStartupStartedElapsed
        if (startedAt == 0L) return
        Log.i(TAG, "YouTube split startup $stage elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
    }

    private fun startYoutubeProfileResolution(resetTimeout: Boolean) {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT || pendingSplitMode != null) return
        if (resetTimeout || profileResolutionStartedElapsed == 0L || profileResolutionTimedOut) {
            profileResolutionStartedElapsed = SystemClock.elapsedRealtime()
            profileResolutionTimedOut = false
        }
        showYoutubeStatus(R.string.youtube_waiting_for_iphone_profile)
        mainHandler.removeCallbacks(retryYoutubeProfileResolution)
        if (!resolveYoutubeProfile()) {
            mainHandler.postDelayed(retryYoutubeProfileResolution, PROFILE_IDENTITY_RETRY_INTERVAL_MILLIS)
        }
    }

    private fun resolveYoutubeProfile(): Boolean =
        SplitPerformanceTracer.section("diplay.split.profile_resolution") {
            resolveYoutubeProfileNow()
        }

    private fun resolveYoutubeProfileNow(): Boolean {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT || pendingSplitMode != null) return false
        val session = activeAirPlaySession ?: return false
        if (session.isClosed || controller?.currentAirPlaySession() !== session) return false
        val info = if (deviceInfoSession === session) connectedDeviceInfo else session.deviceInfo
        val controllerId = session.controllerId
        val fallbackIdentity = IphoneIdentityResolver.resolve(
            controllerId = null,
            deviceId = info?.deviceId,
            wifiMac = info?.wifiMac,
            displayName = info?.name,
            model = info?.model,
        )
        if (
            controllerId.isNullOrBlank() && fallbackIdentity != null &&
            !session.isControllerIdentityResolved &&
            (profileResolutionStartedElapsed == 0L ||
                SystemClock.elapsedRealtime() - profileResolutionStartedElapsed < PROFILE_IDENTITY_TIMEOUT_MILLIS)
        ) {
            return false
        }
        val identity = IphoneIdentityResolver.resolve(
            controllerId = controllerId,
            deviceId = info?.deviceId,
            wifiMac = info?.wifiMac,
            displayName = info?.name,
            model = info?.model,
        ) ?: return false
        val identityEvidence = listOfNotNull(
            controllerId?.let {
                IphoneIdentityResolver.resolve(
                    controllerId = it,
                    deviceId = null,
                    wifiMac = null,
                    displayName = info?.name,
                    model = info?.model,
                )
            },
            info?.deviceId?.let {
                IphoneIdentityResolver.resolve(
                    controllerId = null,
                    deviceId = it,
                    wifiMac = null,
                    displayName = info.name,
                    model = info.model,
                )
            },
            info?.wifiMac?.let {
                IphoneIdentityResolver.resolve(
                    controllerId = null,
                    deviceId = null,
                    wifiMac = it,
                    displayName = info.name,
                    model = info.model,
                )
            },
        )
        val profile = YoutubeDeviceProfileManager.getOrCreateForSplit(
            applicationContext,
            session,
            identity,
            identityEvidence,
            hostLayoutState.carPlayFraction,
        )
        val profileChanged = youtubeProfile?.youtubeContextId != profile.youtubeContextId
        if (profileChanged) {
            if (youtubeFullscreen) exitYoutubeFullscreen()
            Log.i(
                TAG,
                "YouTube profile resolved source=${profile.identitySource.logName} " +
                    "profile=${profile.profileKey.take(PROFILE_LOG_PREFIX_LENGTH)}",
            )
        }
        youtubeProfile = profile
        if (splitCarPlayReadyForYoutube) attachYoutubeBrowser(profile)
        profileResolutionTimedOut = false
        mainHandler.removeCallbacks(retryYoutubeProfileResolution)
        return true
    }

    private fun attachYoutubeBrowser(profile: YoutubeDeviceProfile) {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT ||
            !splitCarPlayReadyForYoutube || pendingDisplaySize != null || handshakeResetInProgress ||
            restartReadyToStart || shuttingDown.get() || isFinishing || isDestroyed
        ) {
            return
        }
        val browser = youtubeBrowser ?: createYoutubeBrowser()?.also { created ->
            youtubeBrowser = created
            val content = youtubeContent
            val status = youtubeStatusView
            if (content != null && status != null) {
                YoutubePaneLayering.attachBrowserBelowStatus(content, created.view, status)
            } else {
                content?.addView(created.view, 0, FrameLayout.LayoutParams(-1, -1))
            }
        } ?: return
        logYoutubeStartup("gecko-session-open-requested")
        browser.open(profile)
        browser.view.visibility = View.VISIBLE
        browser.setActive(
            activityVisible && pendingSplitMode == null &&
                hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT,
        )
    }

    private fun createYoutubeBrowser(): YoutubeBrowserController? = try {
        YoutubeBrowserController(
            context = this,
            onLoadStateChanged = ::onYoutubeLoadStateChanged,
            onCanGoBackChanged = { canGoBack -> youtubeBackButton?.isEnabled = canGoBack },
            onFullscreenChanged = ::setYoutubeFullscreen,
            onFirstVisiblePaint = ::onYoutubeFirstVisiblePaint,
            onVisiblePaintTimeout = ::onYoutubeVisiblePaintTimedOut,
        )
    } catch (_: RuntimeException) {
        showYoutubeStatus(R.string.youtube_load_error)
        finishSplitTrace()
        Log.w(TAG, "GeckoView could not be created")
        null
    } catch (_: LinkageError) {
        showYoutubeStatus(R.string.youtube_load_error)
        finishSplitTrace()
        Log.w(TAG, "GeckoView could not be created")
        null
    }

    private fun onYoutubeLoadStateChanged(state: YoutubeLoadState) {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) return
        if (youtubeFullscreen && YoutubeFullscreenStatusPolicy.shouldExitFullscreen(state)) {
            exitYoutubeFullscreen()
        }
        when (state) {
            YoutubeLoadState.LOADING -> {
                logYoutubeStartup("youtube-page-loading")
                showYoutubeStatus(R.string.youtube_loading)
            }
            YoutubeLoadState.READY -> {
                logYoutubeStartup("youtube-page-ready")
                youtubeStatusView?.visibility = View.GONE
            }
            YoutubeLoadState.ERROR -> {
                showYoutubeStatus(R.string.youtube_load_error)
                finishSplitTrace()
            }
        }
    }

    private fun onYoutubeFirstVisiblePaint() {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) return
        finishSplitTrace()
    }

    private fun onYoutubeVisiblePaintTimedOut() {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) return
        Log.w(TAG, "YouTube split visible paint was not observed before the diagnostic timeout")
        finishSplitTrace()
    }

    private fun finishSplitTrace() {
        youtubeBrowser?.finishVisiblePaintMeasurement()
        SplitPerformanceTracer.endAsync("diplay.split.total", splitTraceCookie)
        splitTraceCookie = null
    }

    private fun finishCarPlayRestartTrace() {
        SplitPerformanceTracer.endAsync("diplay.split.carplay_restart", carPlayRestartTraceCookie)
        carPlayRestartTraceCookie = null
    }

    private fun showYoutubeStatus(resourceId: Int) {
        youtubeStatusView?.apply {
            setText(resourceId)
            visibility = View.VISIBLE
            YoutubePaneLayering.bringStatusToFront(this)
        }
    }

    private fun reloadYoutube() {
        if (youtubeProfile == null) {
            startYoutubeProfileResolution(resetTimeout = true)
            return
        }
        val browser = youtubeBrowser
        if (browser == null) {
            attachYoutubeBrowser(youtubeProfile ?: return)
        } else {
            browser.reload()
        }
    }

    private fun setYoutubeFullscreen(fullscreen: Boolean) {
        if (youtubeFullscreen == fullscreen) return
        youtubeFullscreen = fullscreen
        val view = youtubeBrowser?.view ?: return
        val destination = if (fullscreen) hostRoot else youtubeContent
        val parent = view.parent as? ViewGroup
        if (parent !== destination) {
            parent?.removeView(view)
            if (!fullscreen && destination === youtubeContent && youtubeStatusView != null) {
                YoutubePaneLayering.attachBrowserBelowStatus(
                    youtubeContent ?: return,
                    view,
                    youtubeStatusView ?: return,
                )
            } else {
                destination?.addView(view, FrameLayout.LayoutParams(-1, -1))
            }
        }
    }

    private fun exitYoutubeFullscreen() {
        if (!youtubeFullscreen) return
        youtubeBrowser?.exitFullscreen()
        setYoutubeFullscreen(false)
    }

    private fun disconnectYoutubeProfile(retryForCurrentSession: Boolean = true) {
        mainHandler.removeCallbacks(retryYoutubeProfileResolution)
        if (youtubeFullscreen) exitYoutubeFullscreen()
        destroyYoutubeBrowser()
        youtubeProfile = null
        if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
            showYoutubeStatus(R.string.youtube_waiting_for_iphone_profile)
            if (retryForCurrentSession) startYoutubeProfileResolution(resetTimeout = true)
        }
    }

    private fun suspendYoutubeForProfileRevalidation(previousSession: AirPlaySession?) {
        if (youtubeFullscreen) exitYoutubeFullscreen()
        youtubeBrowser?.let { browser ->
            browser.view.visibility = View.GONE
            browser.suspendForReuse()
        }
        youtubeProfile = null
        profileResolutionTimedOut = false
        YoutubeDeviceProfileManager.clearSplitSelection()
        if (previousSession == null) {
            YoutubeDeviceProfileManager.clearSessionCache()
        } else {
            YoutubeDeviceProfileManager.clearSessionCache(previousSession)
        }
        showYoutubeStatus(R.string.youtube_waiting_for_iphone_profile)
    }

    private fun destroyYoutubeBrowser() {
        youtubeBrowser?.let { browser ->
            (browser.view.parent as? ViewGroup)?.removeView(browser.view)
            browser.destroy()
        }
        youtubeBrowser = null
    }

    private fun buildSettingsMenu(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val panel = FrameLayout(this).apply {
            setBackgroundColor(MENU_BACKGROUND)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }
        content.addView(
            menuText(getString(R.string.host_settings_title), 32f, Color.WHITE, bold = true).apply {
                setPadding(dp(56), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            settingsCategoryHeader(getString(R.string.host_section_connection)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(32) },
        )

        content.addView(
            buildMfiTargetSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val wirelessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wirelessRow.addView(
            menuText(getString(R.string.host_wireless_carplay), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val wirelessSwitch = Switch(this).apply {
            isChecked = wirelessEnabled
            contentDescription = getString(R.string.host_accessibility_wireless_carplay)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (wirelessEnabled == checked) return@setOnCheckedChangeListener
                wirelessEnabled = checked
                hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
                updateHotspotStatusBlock()
                appendLog(
                    "Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                requestStartupPrerequisites()
            }
        }
        wirelessRow.addView(
            wirelessSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            wirelessRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            buildHotspotModeSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            menuText(getString(R.string.host_hotspot_status_title), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        val hotspotStatusView = menuText("", 16f, MENU_ACCENT)
        content.addView(
            hotspotStatusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        if (NcmDiagnosticProfileStore.isDebuggable(this)) {
            content.addView(
                buildNcmDiagnosticProfileSection(),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(36) },
            )
        }

        content.addView(
            settingsCategoryHeader(getString(R.string.host_section_location)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildLocationReportingSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.host_section_startup)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            settingsSwitchRow(
                label = getString(R.string.host_auto_start),
                checked = autoStartOnBoot,
                description = getString(R.string.host_auto_start_description),
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start ${if (checked) "enabled" else "disabled"}")
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.host_section_audio)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        if (advancedAudioChannelMappingSupported) {
            content.addView(
                settingsSwitchRow(
                    label = getString(R.string.host_advanced_audio_mapping),
                    checked = advancedAudioChannelMapping,
                    description = getString(R.string.host_advanced_audio_mapping_description),
                ) { checked ->
                    advancedAudioChannelMapping = checked
                    appendLog(
                        "Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; " +
                            "applies when settings close",
                    )
                    updateResolutionMenu()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        } else {
            content.addView(
                settingsChoiceRow(
                    label = getString(R.string.host_navigation_audio_route),
                    options = listOf(
                        NavigationAudioRoute.FULL_BAND to getString(R.string.host_navigation_audio_full_band),
                        NavigationAudioRoute.SYSTEM_NAVIGATION to getString(R.string.host_navigation_audio_system),
                        NavigationAudioRoute.LEGACY_STREAM_MUSIC to getString(R.string.host_navigation_audio_legacy_music),
                    ),
                    selected = navigationAudioRoute,
                ) { route ->
                    navigationAudioRoute = route
                    appendLog("Navigation audio route=$route; applies when the next audio stream starts")
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        content.addView(
            settingsCategoryHeader(getString(R.string.host_section_identity_appearance)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildIdentitySettingsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildAirPlayIconSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            buildDrivingSideSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            settingsCategoryHeader(getString(R.string.host_section_display_video)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )

        val resolutionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resolutionHeader.addView(
            menuText(getString(R.string.host_resolution), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val resolutionValue = menuText(
            CarPlayDisplayScale.label(displayScaleTenths),
            28f,
            MENU_ACCENT,
            bold = true,
        )
        resolutionHeader.addView(
            resolutionValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            resolutionHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val seekBar = SeekBar(this).apply {
            max = CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS
            progress = displayScaleTenths - CarPlayDisplayScale.MIN_TENTHS
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        displayScaleTenths = CarPlayDisplayScale.sanitize(
                            CarPlayDisplayScale.MIN_TENTHS + progress,
                        )
                        updateResolutionMenu()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        content.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        val range = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        range.addView(
            menuText(getString(R.string.host_scale_min), 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        range.addView(
            menuText(getString(R.string.host_scale_max), 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.host_frame_rate),
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                    step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { value -> getString(R.string.host_frame_rate_value, value) },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            settingsChoiceRow(
                label = getString(R.string.host_physical_size_basis),
                options = listOf(
                    AirPlayPhysicalSizeBasis.WIDTH to getString(R.string.host_widest_width),
                    AirPlayPhysicalSizeBasis.HEIGHT to getString(R.string.host_longest_height),
                ),
                selected = physicalSizeBasis,
            ) { value ->
                physicalSizeBasis = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.host_physical_length),
                values = (
                    AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..
                        AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                    step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                    ).toList(),
                selectedValue = widthPhysicalMm,
                label = { value -> getString(R.string.host_physical_length_value, value) },
                onValueChanged = { value ->
                    widthPhysicalMm = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        val hevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hevcRow.addView(
            menuText(getString(R.string.host_hevc), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val hevcSwitch = Switch(this).apply {
            isChecked = hevcEnabled
            contentDescription = getString(R.string.host_accessibility_hevc)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcEnabled == checked) return@setOnCheckedChangeListener
                hevcEnabled = checked
                appendLog(
                    "HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        hevcRow.addView(
            hevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            hevcRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val softwareHevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        softwareHevcRow.addView(
            menuText(getString(R.string.host_hevc_software_decoder), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val softwareHevcSwitch = Switch(this).apply {
            isChecked = hevcSoftwareDecoderEnabled
            contentDescription = getString(R.string.host_accessibility_hevc_software_decoder)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcSoftwareDecoderEnabled == checked) return@setOnCheckedChangeListener
                hevcSoftwareDecoderEnabled = checked
                appendLog(
                    "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        softwareHevcRow.addView(
            softwareHevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            content.addView(
                softwareHevcRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }

        content.addView(
            buildSafeAreaSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.host_section_window)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildFullscreenSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            content.addView(
                settingsCategoryHeader(getString(R.string.host_android_9_compatibility)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(40) },
            )
            content.addView(
                menuText(
                    getString(R.string.host_android_9_explanation),
                    16f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        val preview = menuText("", 17f, MENU_SECONDARY)
        content.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val save = Button(this).apply {
            text = getString(R.string.host_save_reconnect)
            isAllCaps = false
            textSize = 17f
            setTextColor(MENU_BUTTON_TEXT)
            backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
            minHeight = dp(52)
            setOnClickListener { saveSettingsAndReconnect() }
        }
        content.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(46) },
        )

        val exitApplicationButton = Button(this).apply {
            text = getString(R.string.host_exit_application)
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(MENU_DANGER)
            minHeight = dp(52)
            setOnClickListener { exitApplication() }
        }
        content.addView(
            exitApplicationButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panel.addView(
            Button(this).apply {
                text = "X"
                isAllCaps = false
                textSize = 22f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
                contentDescription = getString(R.string.host_discard_settings_accessibility)
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { cancelSettingsEdits() }
            },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(16)
                topMargin = dp(16)
            },
        )
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels, MAX_SETTINGS_MENU_WIDTH_PX),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        overlay.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val desiredWidth = minOf(view.width, MAX_SETTINGS_MENU_WIDTH_PX)
            val params = panel.layoutParams
            if (params.width != desiredWidth) {
                params.width = desiredWidth
                panel.layoutParams = params
            }
        }

        resolutionValueView = resolutionValue
        resolutionPreviewView = preview
        this.hotspotStatusView = hotspotStatusView
        updateHotspotStatusBlock()
        updateResolutionMenu()
        return overlay
    }

    private fun buildNcmDiagnosticProfileSection(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            settingsCategoryHeader(getString(R.string.host_section_ncm_diagnostics)),
        )
        addView(
            menuText(getString(R.string.host_ncm_diagnostic_help), 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        val labels = mapOf(
            NcmDiagnosticProfile.AUTO to R.string.host_ncm_profile_current,
            NcmDiagnosticProfile.NO_STATUS_POLLING to R.string.host_ncm_profile_no_status_polling,
            NcmDiagnosticProfile.OUT_TIMEOUT_250 to R.string.host_ncm_profile_out_250,
            NcmDiagnosticProfile.OUT_TIMEOUT_500 to R.string.host_ncm_profile_out_500,
            NcmDiagnosticProfile.OUT_TIMEOUT_1000 to R.string.host_ncm_profile_out_1000,
            NcmDiagnosticProfile.LEGACY_OUT_TIMEOUT to R.string.host_ncm_profile_out_2000,
            NcmDiagnosticProfile.SYNC_BULK_IN to R.string.host_ncm_profile_sync_bulk_in,
            NcmDiagnosticProfile.FORCE_3_4 to R.string.host_ncm_profile_force_3_4,
            NcmDiagnosticProfile.FORCE_5_6 to R.string.host_ncm_profile_force_5_6,
        )
        addView(
            settingsChoiceRow(
                label = getString(R.string.host_ncm_diagnostic_profile),
                options = NcmDiagnosticProfile.entries.map { it to getString(labels.getValue(it)) },
                selected = ncmDiagnosticProfile,
            ) { selected ->
                ncmDiagnosticProfile = selected
                appendLog("NCM diagnostic profile=${selected.name}; applies after saving and reconnecting")
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
    }

    private fun persistMenuSettings(): Boolean {
        carPlayName = AirPlayPersistence.normalizeCarPlayName(carPlayName)
        if (!AirPlayPersistence.saveCarPlayName(this, carPlayName)) {
            carPlayNameInput?.error = getString(R.string.host_error_carplay_name_length)
            carPlayNameInput?.requestFocus()
            return false
        }
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveNavigationAudioRoute(this, navigationAudioRoute)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
        NcmDiagnosticProfileStore.save(this, ncmDiagnosticProfile)
        return true
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback error=${error.javaClass.simpleName}")
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        carPlayNameInput?.setText(carPlayName)
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon error=${error.javaClass.simpleName}")
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        syncMfiSettingsControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = settingsChoiceRow(
            label = getString(R.string.host_mfi_target),
            options = listOf(
                MfiTarget.USB_CH341 to getString(R.string.host_mfi_usb_ch341),
                MfiTarget.I2C to getString(R.string.host_mfi_i2c),
                MfiTarget.REMOTE to getString(R.string.host_mfi_remote),
            ),
            selected = mfiTarget,
        ) { target ->
            if (mfiTarget == target) return@settingsChoiceRow
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = (targetChoice as ViewGroup).getChildAt(1) as RadioGroup
        section.addView(
            targetChoice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.host_i2c_device),
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(getString(R.string.host_i2c_device_path_help), 14f, MENU_SECONDARY),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.host_server_address),
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    getString(R.string.host_token_optional),
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    getString(R.string.host_remote_mfi_help),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> "Local offline"
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "I2C"
        MfiTarget.REMOTE -> "Remote"
    }

    private fun buildIdentitySettingsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            settingsInputRow(
                getString(R.string.host_carplay_name),
                carPlayName,
                onInputCreated = { carPlayNameInput = it },
            ) { value ->
                carPlayName = value
                carPlayNameInput?.error = null
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsInputRow(getString(R.string.host_manufacturer), manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow(getString(R.string.host_model), model) { value ->
                model = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow(getString(R.string.host_oem_label), oemLabel) { value ->
                oemLabel = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun settingsCategoryHeader(title: String): TextView =
        menuText(title, 16f, MENU_ACCENT, bold = true)

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText(getString(R.string.host_report_location), 20f, MENU_SECONDARY),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = Switch(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = getString(R.string.host_accessibility_report_location)
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    getString(R.string.host_report_location_description),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = SeekBar(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.host_airplay_icon), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            Button(this).apply {
                text = getString(R.string.host_choose_image)
                isAllCaps = false
                setOnClickListener {
                    externalActivityInProgress = true
                    imagePicker.launch("image/*")
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            Button(this).apply {
                text = getString(R.string.host_default_icon)
                isAllCaps = false
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.host_driving_side), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val left = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.host_left_hand_drive)
            setTextColor(Color.WHITE)
            isChecked = !rightHandDrive
        }
        val right = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.host_right_hand_drive)
            setTextColor(Color.WHITE)
            isChecked = rightHandDrive
        }
        group.addView(left)
        group.addView(right)
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.host_fullscreen), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.host_hide_top_bar),
                checked = hideTopBar,
                description = getString(R.string.host_hide_top_bar_description),
            ) { checked ->
                hideTopBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.host_hide_bottom_bar),
                checked = hideBottomBar,
                description = getString(R.string.host_hide_bottom_bar_description),
            ) { checked ->
                hideBottomBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.host_safe_area), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.host_set)
                isAllCaps = false
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.host_reset)
                isAllCaps = false
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.host_draw_outside_safe_area),
                checked = safeAreaDrawOutside,
                description = getString(R.string.host_draw_outside_safe_area_description),
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.host_safe_area), 24f, Color.WHITE, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = getString(R.string.host_cancel)
                isAllCaps = false
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.host_save)
                isAllCaps = false
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 18f
                setTextColor(Color.WHITE)
                setHintTextColor(MENU_SECONDARY)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(48)
                isSingleLine = true
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Switch(this@CarPlayHostActivity).apply {
                isChecked = checked
                contentDescription = description
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.host_wifi_session), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to getString(R.string.host_wifi_p2p_5ghz))
            }
            add(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT to getString(R.string.host_local_only_hotspot))
            add(WirelessHotspotMode.MANUAL to getString(R.string.host_manual_hotspot))
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = mode
                isChecked = wirelessHotspotMode == mode
            }
            if (wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            wirelessHotspotMode = selected
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            settingsInputRow(getString(R.string.host_hotspot_ssid), manualHotspotSsid) { value ->
                manualHotspotSsid = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.host_band),
                options = listOf(
                    ManualHotspotBand.AUTO to getString(R.string.host_auto),
                    ManualHotspotBand.GHZ_2_4 to getString(R.string.host_band_24ghz),
                    ManualHotspotBand.GHZ_5 to getString(R.string.host_band_5ghz),
                ),
                selected = manualHotspotBand,
            ) { value ->
                manualHotspotBand = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.host_channel),
                value = manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                manualHotspotChannel = value.toIntOrNull() ?: -1
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.host_hotspot_password),
                value = manualHotspotPassphrase,
                password = true,
            ) { value ->
                manualHotspotPassphrase = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.host_security),
                options = listOf(
                    ManualHotspotSecurity.OPEN to getString(R.string.host_security_open),
                    ManualHotspotSecurity.WPA2 to getString(R.string.host_security_wpa2),
                    ManualHotspotSecurity.WPA3_TRANSITION to getString(R.string.host_security_wpa3_transition),
                    ManualHotspotSecurity.WPA3 to getString(R.string.host_security_wpa3),
                ),
                selected = manualHotspotSecurity,
            ) { value ->
                manualHotspotSecurity = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val error = menuText("", 14f, Color.rgb(0xff, 0x7a, 0x7a)).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        manualHotspotFields = manualFields
        manualHotspotErrorView = error
        updateManualHotspotFields()
        return section
    }

    private fun updateManualHotspotFields() {
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                getString(R.string.host_error_i2c_path_required)
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                getString(R.string.host_error_remote_server_required)
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                getString(R.string.host_error_remote_server_scheme)
            '\u0000' in mfiI2cPath -> getString(R.string.host_error_i2c_path_nul)
            '\u0000' in remoteMfiServer -> getString(R.string.host_error_remote_server_nul)
            '\u0000' in remoteMfiToken -> getString(R.string.host_error_remote_token_nul)
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> getString(R.string.host_error_hotspot_ssid_required)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.host_error_hotspot_ssid_length)
            '\u0000' in manualHotspotSsid -> getString(R.string.host_error_hotspot_ssid_nul)
            manualHotspotChannel !in 0..196 -> getString(R.string.host_error_channel_range)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.host_error_channel_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.host_error_hotspot_password_nul)
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.host_error_open_password)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.host_error_secure_password_length)
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> "Wi-Fi P2P (5 GHz)"
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "LocalOnlyHotspot"
        WirelessHotspotMode.MANUAL -> "Manual hotspot"
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = "Starting")
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = "Ready",
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = "Waiting for paired iPhone")
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = "Connecting Bluetooth")
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = "Running")
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = "Active")
            CarPlayStatus.WirelessActiveBootstrapControl ->
                hotspotStatus.copy(state = "Active")
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = "Starting AirPlay service")
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = "Error")
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.host_hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.host_hotspot_status, localizedHotspotState(status.state)))
            status.ssid?.let { append(getString(R.string.host_status_ssid, it)) }
            status.backend?.let { append(getString(R.string.host_status_backend, it)) }
            status.band?.let { append(getString(R.string.host_status_band, it)) }
            status.channel?.let {
                append(
                    getString(
                        R.string.host_status_channel,
                        if (it == 0) getString(R.string.host_auto) else it.toString(),
                    ),
                )
            }
        }
    }

    private fun localizedHotspotState(state: String): String {
        val stringResource = when (state) {
            "stopped" -> R.string.host_hotspot_state_stopped
            "Starting" -> R.string.host_hotspot_state_starting
            "Ready" -> R.string.host_hotspot_state_ready
            "Waiting for paired iPhone" -> R.string.host_hotspot_state_waiting_iphone
            "Connecting Bluetooth" -> R.string.host_hotspot_state_connecting_bluetooth
            "Running" -> R.string.host_hotspot_state_running
            "Active" -> R.string.host_hotspot_state_active
            "Starting AirPlay service" -> R.string.host_hotspot_state_starting_airplay
            "Error" -> R.string.host_hotspot_state_error
            else -> return state
        }
        return getString(stringResource)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this@CarPlayHostActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(this@CarPlayHostActivity).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val value = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? T ?: return@setOnCheckedChangeListener
            onSelected(value)
        }
        addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun updateResolutionMenu() {
        resolutionValueView?.text = CarPlayDisplayScale.label(displayScaleTenths)
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            getString(R.string.host_resolution_waiting)
        } else {
            val negotiated = CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            )
            getString(
                R.string.host_resolution_format,
                native.width,
                native.height,
                negotiated.widthPixels,
                negotiated.heightPixels,
            )
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            getString(
                R.string.host_preview_hevc_transport,
                getString(
                    if (hevcSoftwareDecoderEnabled) R.string.host_preview_software
                    else R.string.host_preview_hardware,
                ),
            )
        }
        val fullscreen = getString(
            R.string.host_preview_fullscreen_parts,
            getString(if (hideTopBar) R.string.host_preview_top_hidden else R.string.host_preview_top_shown),
            getString(if (hideBottomBar) R.string.host_preview_bottom_hidden else R.string.host_preview_bottom_shown),
        )
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(getString(R.string.host_preview_carplay_name, normalizedCarPlayName())).append('\n')
            append(
                getString(
                    R.string.host_preview_identity,
                    normalizedManufacturer(),
                    normalizedModel(),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.host_preview_oem_label,
                    oemLabel.ifBlank { getString(R.string.host_empty_value) },
                ),
            ).append('\n')
            append(getString(R.string.host_preview_frame_rate, fps)).append('\n')
            append(
                getString(
                    R.string.host_preview_detected_maximum,
                    maximumDetectedWidthPixels,
                    maximumDetectedHeightPixels,
                ),
            ).append('\n')
            append(
                getString(
                    R.string.host_preview_physical_reference,
                    getString(
                        if (physicalSizeBasis == AirPlayPhysicalSizeBasis.WIDTH) {
                            R.string.host_widest_width
                        } else {
                            R.string.host_longest_height
                        },
                    ),
                    widthPhysicalMm,
                ),
            ).append('\n')
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(
                    getString(
                        R.string.host_preview_physical_size,
                        physical.widthMm,
                        physical.heightMm,
                    ),
                ).append('\n')
            }
            append(
                getString(
                    R.string.host_preview_driving_side,
                    getString(if (rightHandDrive) R.string.host_preview_right else R.string.host_preview_left),
                ),
            ).append('\n')
            append(getString(R.string.host_preview_fullscreen, fullscreen)).append('\n')
            append(getString(R.string.host_preview_video_transport, transport)).append('\n')
            append(
                getString(
                    R.string.host_preview_location_reporting,
                    getString(
                        if (locationReportingEnabled) R.string.host_preview_enabled
                        else R.string.host_preview_disabled,
                    ),
                ),
            ).append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(
                    getString(
                        R.string.host_preview_audio_mapping,
                        getString(
                            if (advancedAudioChannelMapping) R.string.host_preview_aaos_buses
                            else R.string.host_preview_mobile_compatible,
                        ),
                    ),
                ).append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport = try {
        val mime = if (hevcEnabled) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // Match MediaCodec.createDecoderByType's first suitable decoder; do not silently force
        // an enlarged stream through a software decoder on a slower head unit.
        val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        if (decoder == null) {
            CanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            val hardware = if (Build.VERSION.SDK_INT >= 29) decoder.isHardwareAccelerated
                else !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            val video: MediaCodecInfo.VideoCapabilities? =
                decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video.areSizeAndRateSupported(
                display.widthPixels, display.heightPixels, display.fps.toDouble(),
            ) == true
            val reason = when {
                !hardware -> "software_decoder"
                hevcEnabled && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            CanvasSupport(reason == "supported", reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware " +
                    "sizeSupported=$sizeSupported rateSupported=$rateSupported " +
                    "widths=${video?.supportedWidths} heights=${video?.supportedHeights} " +
                    "alignment=${video?.widthAlignment}x${video?.heightAlignment} " +
                    "fpsRange=${video?.supportedFrameRates} result=$reason")
        }
    } catch (error: Exception) {
        CanvasSupport(false, "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}")
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
        )
        val resolutionDisplay = CarPlayDisplayScale.apply(baseDisplay, displayScaleTenths)
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        val support = when {
            uiScalePercent >= CarPlayUiScale.DEFAULT -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
            scaledDisplay === resolutionDisplay -> CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            else -> largerCanvasSupport(scaledDisplay)
        }
        if (!support.supported) {
            scaledDisplay = resolutionDisplay
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            appendLog("Larger CarPlay canvas unavailable reason=${support.reason}; using Default icon and text size")
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    getString(R.string.host_unsupported_display_scale_toast),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(uiScalePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent " +
            "surface=${size.width}x${size.height} resolution=${displayScaleTenths * 10}% " +
            "base=${resolutionDisplay.widthPixels}x${resolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (hevcEnabled) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$uiScalePercent " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(support.details)
        appendLog(effectiveSummary)
        return AirPlayConfig(
            deviceName = normalizedCarPlayName(),
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = display,
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            wirelessAudio = wirelessEnabled,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            getString(
                if (customBitmap != null) R.string.host_custom_icon_status
                else R.string.host_default_icon_status,
            )
    }

    private fun currentActivitySize(): DisplaySize? {
        return activeDisplaySize ?: currentCarPlayViewportSize()
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize()
            ?: return getString(R.string.host_safe_area_waiting)
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            getString(R.string.host_safe_area_full_screen, size.width, size.height)
        } else {
            getString(
                R.string.host_safe_area_dimensions,
                mapping.width,
                mapping.height,
                mapping.left,
                mapping.top,
                size.width,
                size.height,
            )
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun refreshDisplaySizeAfterLayout() {
        carPlayPane?.post { scheduleCurrentCarPlayViewportSize() }
    }

    private fun observeImeInsets() {
        val decor = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
            val wasImeVisible = imeVisible
            imeVisible = hasImeInsets(insets)
            if (imeVisible) {
                if (!imeResizeSuppressed) ignoreImeDisplayResize()
            } else if (wasImeVisible && !imeAnimationInProgress) {
                scheduleImeResizeCheck()
            }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            decor,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) == 0) return
                    imeAnimationInProgress = true
                    if (!imeResizeSuppressed) ignoreImeDisplayResize()
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    imeVisible = hasImeInsets(insets)
                    if (imeVisible || runningAnimations.any {
                            (it.typeMask and WindowInsetsCompat.Type.ime()) != 0
                        }
                    ) {
                        if (!imeResizeSuppressed) ignoreImeDisplayResize()
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) == 0) return
                    imeAnimationInProgress = false
                    imeVisible = ViewCompat.getRootWindowInsets(decor)?.let(::hasImeInsets) ?: imeVisible
                    if (imeVisible) {
                        if (!imeResizeSuppressed) ignoreImeDisplayResize()
                    } else {
                        scheduleImeResizeCheck()
                    }
                }
            },
        )
        ViewCompat.requestApplyInsets(decor)
    }

    private fun isImeResizeActive(): Boolean {
        if (imeAnimationInProgress || imeVisible || imeResizeSuppressed) return true
        return ViewCompat.getRootWindowInsets(window.decorView)?.let(::hasImeInsets) == true
    }

    private fun reconcileImeResizeState() {
        val insets = ViewCompat.getRootWindowInsets(window.decorView) ?: return
        imeVisible = hasImeInsets(insets)
        if (
            ImeResizeResumePolicy.shouldScheduleSettleCheck(
                imeVisible,
                imeAnimationInProgress,
                imeResizeSuppressed,
            )
        ) {
            if (imeAnimationInProgress && !imeResizeSuppressed) ignoreImeDisplayResize()
            imeAnimationInProgress = false
            scheduleImeResizeCheck()
        }
    }

    private fun hasImeInsets(insets: WindowInsetsCompat): Boolean =
        insets.isVisible(WindowInsetsCompat.Type.ime()) ||
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0

    private fun ignoreImeDisplayResize() {
        if (!imeResizeSuppressed) {
            imeResizeSuppressed = true
            window.decorView.removeCallbacks(waitForImeResizeToSettle)
            resetImeResizeTracking()
            pendingDisplaySize = null
            mainHandler.removeCallbacks(applyDisplaySize)
            activeDisplaySize?.let { stableSize ->
                displayResizeCoordinator.observe(stableSize)
                CarPlayBackgroundSession.observePendingRestartSize(this, stableSize)
            }
        }
    }

    private fun scheduleImeResizeCheck() {
        resetImeResizeTracking()
        val decor = window.decorView
        decor.removeCallbacks(waitForImeResizeToSettle)
        decor.postOnAnimation(waitForImeResizeToSettle)
    }

    private fun resetImeResizeTracking() {
        lastImeResizeSize = null
        stableImeResizeFrames = 0
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun normalizedCarPlayName(): String =
        AirPlayPersistence.normalizeCarPlayName(carPlayName)

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
        controllerConnectionEpoch: Long,
    ): AndroidMediaSink {
        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            dspConfigProvider = DspProfileRuntime.get(this).configProvider,
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            navigationAudioRoute = if (advancedAudioChannelMappingSupported && !advancedAudioChannelMapping) {
                NavigationAudioRoute.SYSTEM_NAVIGATION
            } else {
                navigationAudioRoute
            },
            transport = if (wirelessEnabled) "wireless" else "wired",
            audioManager = getSystemService(AudioManager::class.java),
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, controllerConnectionEpoch, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onVideoFrameSubmittedToSurface = { type ->
                onVideoFrameSubmittedToSurface(controllerGeneration, controllerConnectionEpoch, type)
            },
            onAudioDiagnostic = { message ->
                diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
            },
        )
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun isCurrentControllerCallback(controllerGeneration: Int, controllerConnectionEpoch: Long): Boolean =
        controllerGeneration == restartGeneration && controllerConnectionEpoch == connectionEpoch &&
            CarPlayBackgroundSession.isOwner(this)

    private fun createSessionListener(
        controllerGeneration: Int,
        controllerConnectionEpoch: Long,
    ): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (
                        !isCurrentControllerCallback(controllerGeneration, controllerConnectionEpoch) ||
                        session.isClosed ||
                        controller?.currentAirPlaySession() !== session
                    ) {
                        return@runOnUiThread
                    }
                    val previousSession = activeAirPlaySession
                    val sessionChanged = previousSession !== session
                    activeAirPlaySession = session
                    deviceInfoSession = session
                    connectedDeviceInfo = session.deviceInfo
                    CarPlayBackgroundSession.active = true
                    val recoveredRetry = if (CarPlayBackgroundSession.isOwner(this)) {
                        reconnectAfterLossGate.recovered(
                            restartGeneration = controllerGeneration,
                            connectionEpoch = connectionEpoch,
                            ownerEpoch = CarPlayBackgroundSession.ownerEpoch(),
                        )
                    } else {
                        null
                    }
                    if (recoveredRetry != null && scheduledReconnectTicket == recoveredRetry) {
                        mainHandler.removeCallbacks(restartAfterReconnectDelay)
                        scheduledReconnectTicket = null
                        scheduledReconnectReason = null
                    }
                    reconnectAttempts = 0
                    syncAirPlayDarkMode()
                    if (sessionChanged && hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
                        suspendYoutubeForProfileRevalidation(previousSession)
                        startYoutubeProfileResolution(resetTimeout = true)
                    } else {
                        resolveYoutubeProfile()
                    }
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    val currentControllerSession = controller?.currentAirPlaySession()
                    if (
                        !isCurrentControllerCallback(controllerGeneration, controllerConnectionEpoch) ||
                        activeAirPlaySession !== session ||
                        (currentControllerSession != null && currentControllerSession !== session)
                    ) {
                        return@runOnUiThread
                    }
                    finishCarPlayRestartTrace()
                    activeAirPlaySession = null
                    if (deviceInfoSession === session) {
                        deviceInfoSession = null
                        connectedDeviceInfo = null
                    }
                    CarPlayBackgroundSession.active = false
                    YoutubeDeviceProfileManager.clearSplitSelection()
                    YoutubeDeviceProfileManager.clearSessionCache(session)
                    if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
                        disconnectYoutubeProfile()
                    } else {
                        destroyYoutubeBrowser()
                        youtubeProfile = null
                    }
                    if (menuOpen) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    if (controller?.isWiredRecoveryManagedByController() == true) {
                        setConnectionStage("Wired recovery in progress")
                        appendLog("AirPlay session ended; wired recovery is controlled by the connection attempt")
                    } else {
                        setConnectionStage("CarPlay session ended; reconnecting")
                        appendLog("AirPlay session ended; reconnecting from scratch")
                        reconnectAfterLoss("AirPlay session ended")
                    }
                }
            }

            override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
                runOnUiThread {
                    if (!isCurrentControllerCallback(controllerGeneration, controllerConnectionEpoch) || session.isClosed) {
                        return@runOnUiThread
                    }
                    if (
                        activeAirPlaySession !== session ||
                        controller?.currentAirPlaySession() !== session
                    ) {
                        return@runOnUiThread
                    }
                    deviceInfoSession = session
                    connectedDeviceInfo = info
                    if (activeAirPlaySession === session) resolveYoutubeProfile()
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (menuOpen || !isCurrentControllerCallback(controllerGeneration, controllerConnectionEpoch)) {
                        return@runOnUiThread
                    }
                    if (controller?.isWiredRecoveryManagedByController() == true) {
                        setConnectionStage("Wired recovery in progress")
                        appendLog("Wired controller owns transport recovery; host retry skipped")
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage("Transport error; reconnecting")
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onHostUiRequested(session: AirPlaySession) {
                runOnUiThread {
                    if (
                        !isCurrentControllerCallback(controllerGeneration, controllerConnectionEpoch) ||
                        isFinishing ||
                        isDestroyed
                    ) {
                        return@runOnUiThread
                    }
                    controller?.sendTouch(emptyList())
                    appendLog("CarPlay requested host UI; opening DiPlay Menu")
                    startActivity(
                        Intent(this@CarPlayHostActivity, DiPlayMenuActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                }
            }

            override fun onDebugLog(message: String) {
                if (DiagnosticRedactor.redact(message) == null) return
                runOnUiThread {
                    if (!isCurrentControllerCallback(controllerGeneration, controllerConnectionEpoch)) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    if (menuOpen) return@runOnUiThread
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
        controllerConnectionEpoch: Long,
    ): (CarPlayStatus) -> Unit = { status ->
        if (!menuOpen && isCurrentControllerCallback(controllerGeneration, controllerConnectionEpoch)) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(description)
            if (status is CarPlayStatus.Failed) finishCarPlayRestartTrace()
            when (status) {
                is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                    wifiRecoveryButton?.visibility = View.VISIBLE
                } else {
                    wifiRecoveryButton?.visibility = View.GONE
                    if (!status.wiredRecoveryManaged) reconnectAfterLoss(description)
                }
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        if (!CarPlaySessionTransportPolicy.shouldAdoptBackgroundSession(
                existingWired = snapshot.controller.isUsingWiredTransport(),
                requestedWired = !wirelessEnabled,
            )
        ) {
            requestBackgroundTransportHandoff(snapshot)
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        sink = snapshot.sink
        CarPlayBackgroundSession.store(
            snapshot.controller,
            snapshot.sink,
            snapshot.width,
            snapshot.height,
            this,
            currentHostLaunchState(),
            backgroundStopAction(),
        )
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
            displayResizeCoordinator.observe(DisplaySize(snapshot.width, snapshot.height))
        }
        localViewportSize = currentCarPlayViewportSize()
        snapshot.controller.currentAirPlaySession()?.let { session ->
            activeAirPlaySession = session
            deviceInfoSession = session
            connectedDeviceInfo = session.deviceInfo
            CarPlayBackgroundSession.active = true
        }
        val generation = restartGeneration
        val controllerConnectionEpoch = connectionEpoch
        snapshot.controller.attachUi(
            createSessionListener(generation, controllerConnectionEpoch),
            createStatusReporter(generation, controllerConnectionEpoch),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(generation, controllerConnectionEpoch, type, active)
        }
        snapshot.sink.setVideoFrameSubmittedToSurfaceListener { type ->
            onVideoFrameSubmittedToSurface(generation, controllerConnectionEpoch, type)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                "CarPlay service already running"
            } else {
                "CarPlay session already running"
            },
        )
        updateDebugOverlays()
        resolveYoutubeProfile()
        tryApplyPendingLocalPaneViewport()
        return true
    }

    private fun requestBackgroundTransportHandoff(snapshot: CarPlayBackgroundSession.Snapshot) {
        if (backgroundTransportHandoffPending) return
        backgroundTransportHandoffPending = true
        val generation = ++backgroundTransportHandoffGeneration
        val previousWired = snapshot.controller.isUsingWiredTransport()
        val identity = autoUsbLaunchIdentity
        val handoffGeneration = identity?.let {
            IphoneUsbAutoLaunchDispatcher.beginHandoff(it, currentHostLaunchState())
        }
        CarPlayBackgroundSession.stop {
            runOnUiThread {
                if (generation != backgroundTransportHandoffGeneration) return@runOnUiThread
                backgroundTransportHandoffPending = false
                val handoffRequest = if (identity != null) {
                    IphoneUsbAutoLaunchDispatcher.completeHandoff(handoffGeneration)
                } else {
                    null
                }
                val latestIdentity = handoffRequest?.identity ?: autoUsbLaunchIdentity
                if (latestIdentity != null &&
                    IphoneUsbAutoLaunchSettings.currentCandidate(this, latestIdentity) == null
                ) {
                    IphoneUsbAutoLaunchDispatcher.failLaunch(latestIdentity)
                    autoUsbLaunchIdentity = null
                    autoUsbLaunchBlocked = false
                    wirelessEnabled = !previousWired
                    AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
                    wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
                    wirelessPermissionsRefused = false
                    if (microphonePermissionResolved) requestStartupPrerequisites()
                    return@runOnUiThread
                }
                if (latestIdentity != null) autoUsbLaunchIdentity = latestIdentity
                val current = CarPlayBackgroundSession.snapshot()
                if (current?.controller === snapshot.controller) {
                    autoUsbLaunchBlocked = true
                    appendLog("Transport handoff did not close the previous Controller")
                    return@runOnUiThread
                }
                if (current != null &&
                    !CarPlaySessionTransportPolicy.shouldAdoptBackgroundSession(
                        existingWired = current.controller.isUsingWiredTransport(),
                        requestedWired = !wirelessEnabled,
                    )
                ) {
                    requestBackgroundTransportHandoff(current)
                    return@runOnUiThread
                }
                maybeStartCarPlay()
                if (latestIdentity != null && !isFinishing && !isDestroyed) {
                    IphoneUsbAutoLaunchDispatcher.resumeDeferred(this)
                }
            }
        }
    }

    private fun currentHostLaunchState(): CarPlayHostLaunchState = CarPlayHostLaunchState(
        layoutMode = hostLayoutState.mode,
        carPlayFraction = hostLayoutState.carPlayFraction,
        pendingSplitMode = pendingSplitMode,
    )

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasPendingTeardown()) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || isFinishing || isDestroyed || menuOpen || handshakeResetInProgress || controller != null) return
        cancelPendingReconnectAfterLoss()
        connectionEpoch++
        val controllerGeneration = restartGeneration
        val controllerConnectionEpoch = connectionEpoch
        val config = createRuntimeConfig()
        val airPlayConfig = createAirPlayConfig(size)
        localViewportSize = currentCarPlayViewportSize() ?: size
        val locationProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) {
                AndroidCarPlayLocationProvider(this)
            } else {
                null
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
            controllerConnectionEpoch = controllerConnectionEpoch,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val traceAttemptId = ConnectionTraceStore.newAttemptId()
        val traceSequence = java.util.concurrent.atomic.AtomicLong()
        val traceLock = Any()
        val traceStore = ConnectionTraceStore.shared(applicationContext)
        val pairingContext = applicationContext
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration, controllerConnectionEpoch),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration, controllerConnectionEpoch),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(pairingContext) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(pairingContext, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(pairingContext) },
            locationProvider = locationProvider,
            connectionTraceEnabled = AirPlayPersistence.loadDebugLogsEnabled(this),
            onConnectionTrace = { event ->
                synchronized(traceLock) {
                    traceStore.append(traceAttemptId, traceSequence.incrementAndGet(), event)
                }
            },
            onWiredPathReport = traceStore::appendWiredPathReport,
            onWiredStartupTiming = traceStore::appendWiredStartupTiming,
        )
        controller = next
        CarPlayBackgroundSession.store(
            next,
            renderer,
            size.width,
            size.height,
            this,
            currentHostLaunchState(),
            backgroundStopAction(),
        )
        localViewportSize?.takeIf { canScaleCarPlayLocally() }?.let(::applyLocalCarPlayBounds)
        try {
            startForegroundService(Intent(this, DiPlaySessionService::class.java))
            next.start()
            IphoneUsbAutoLaunchDispatcher.resumeDeferred(this)
            tryApplyPendingLocalPaneViewport()
            SplitPerformanceTracer.increment(SplitPerformanceCounter.CARPLAY_CONTROLLER_STARTS)
            if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
                logYoutubeStartup("carplay-controller-start")
            }
            if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
                splitCarPlayReadyForYoutube = true
                resolveYoutubeProfile()
                youtubeProfile?.let(::attachYoutubeBrowser)
            }
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage("Could not start CarPlay. Return to DiPlay and check app permissions.")
        }
    }

    private fun backgroundStopAction(): ((() -> Unit) -> Unit) = { completion ->
        runOnUiThread {
            shutdown(terminateProcess = false, reason = "DiPlay disconnect") { result ->
                if (result == CarPlayHostShutdownResult.CLOSED) completion()
            }
            finish()
        }
    }

    private fun syncAirPlayDarkMode() {
        val session = activeAirPlaySession ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update error=${error.javaClass.simpleName}")
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun currentCarPlayViewportSize(): DisplaySize? {
        val pane = carPlayPane
        if (pane != null && pane.width > 0 && pane.height > 0) return DisplaySize(pane.width, pane.height)
        val video = videoView ?: return null
        return DisplaySize(video.width, video.height).takeIf { it.width > 0 && it.height > 0 }
    }

    private fun currentHostDisplaySize(): DisplaySize? = hostRoot?.let { root ->
        DisplaySize(root.width, root.height).takeIf { it.width > 0 && it.height > 0 }
    }

    private fun scheduleCurrentHostDisplaySize() {
        currentHostDisplaySize()?.let { size ->
            scheduleDisplaySize(size.width, size.height, HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED)
        }
    }

    private fun scheduleDisplaySizeForConfigurationChange() {
        if (hostLayoutState.mode == HostLayoutMode.CARPLAY_FULL) {
            scheduleCurrentHostDisplaySize()
        } else {
            scheduleCurrentCarPlayViewportSize(cause = HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED)
        }
    }

    private fun scheduleCurrentCarPlayViewportSize(
        fallbackWidth: Int = 0,
        fallbackHeight: Int = 0,
        cause: HostDisplaySizeCause = viewportCauseForCurrentLayout(),
    ) {
        val viewport = currentCarPlayViewportSize()
            ?: DisplaySize(fallbackWidth, fallbackHeight).takeIf { it.width > 0 && it.height > 0 }
            ?: return
        scheduleDisplaySize(viewport.width, viewport.height, cause)
    }

    private fun viewportCauseForCurrentLayout(): HostDisplaySizeCause {
        val currentMode = hostLayoutState.mode
        val modeChanged = currentMode != lastViewportLayoutMode
        lastViewportLayoutMode = currentMode
        return when {
            isImeResizeActive() -> HostDisplaySizeCause.IME_OR_INSETS_CHANGED
            modeChanged || localPaneLayoutPending || currentMode != HostLayoutMode.CARPLAY_FULL ->
                HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED
            else -> HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED
        }
    }

    private fun canScaleCarPlayLocally(): Boolean = LocalCarPlayScalePolicy.shouldKeepSession(
        hasController = controller != null,
        ownsController = CarPlayBackgroundSession.isOwner(this),
        hasUsableSurface = currentSurface?.isValid == true,
        hasRemoteSize = activeDisplaySize?.let { it.width > 0 && it.height > 0 } == true,
        handshakeReset = handshakeResetInProgress,
        restartPending = restartHandoffToken != null || restartReadyToStart ||
            CarPlayBackgroundSession.hasPendingRestart(),
        shuttingDown = shuttingDown.get() || isFinishing || isDestroyed,
    )

    private fun applyLocalCarPlayBounds(viewport: DisplaySize): Boolean {
        val remote = activeDisplaySize ?: return false
        val bounds = LocalCarPlayScalePolicy.fitBounds(remote, viewport) ?: return false
        val content = carPlayContent ?: return false
        val params = content.layoutParams as? FrameLayout.LayoutParams ?: return false
        if (params.width != bounds.width || params.height != bounds.height || params.gravity != Gravity.CENTER) {
            params.width = bounds.width
            params.height = bounds.height
            params.gravity = Gravity.CENTER
            content.layoutParams = params
        }
        localViewportSize = viewport
        pendingLocalPaneViewport = null
        localPaneLayoutPending = false
        return true
    }

    private fun tryApplyPendingLocalPaneViewport() {
        val targetMode = pendingSplitMode
        if (targetMode != null) {
            if (!canScaleCarPlayLocally()) return
            pendingSplitMode = null
            localPaneLayoutPending = true
            if (targetMode == HostLayoutMode.CARPLAY_DSP_SPLIT) ensureDspComposeView()
            updateHostLayout()
            carPlayPane?.post {
                scheduleCurrentCarPlayViewportSize(cause = HostDisplaySizeCause.LOCAL_PANE_LAYOUT_CHANGED)
                if (targetMode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
                    scheduleYoutubePreparationAfterLayout(resetTimeout = true)
                }
            }
            return
        }
        val viewport = pendingLocalPaneViewport ?: return
        if (canScaleCarPlayLocally()) applyLocalCarPlayBounds(viewport)
    }

    private fun scheduleYoutubeStableAfterLocalLayout() {
        if (hostLayoutState.mode != HostLayoutMode.CARPLAY_YOUTUBE_SPLIT || controller == null) return
        mainHandler.removeCallbacks(finishSplitYoutubeStartup)
        mainHandler.postDelayed(finishSplitYoutubeStartup, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun resetLocalCarPlayBounds() {
        val content = carPlayContent ?: return
        val params = content.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != ViewGroup.LayoutParams.MATCH_PARENT ||
            params.height != ViewGroup.LayoutParams.MATCH_PARENT || params.gravity != Gravity.CENTER
        ) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.CENTER
            content.layoutParams = params
        }
    }

    private fun scheduleDisplaySize(
        width: Int,
        height: Int,
        cause: HostDisplaySizeCause = HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED,
    ) {
        if (width <= 0 || height <= 0 || shuttingDown.get() || isFinishing || isDestroyed) return
        val size = DisplaySize(width, height)
        if (cause == HostDisplaySizeCause.IME_OR_INSETS_CHANGED || isImeResizeActive()) {
            ignoreImeDisplayResize()
            return
        }
        val waitingForInitialDisplay = activeDisplaySize == null && controller == null &&
            !CarPlayBackgroundSession.hasSession()
        if (cause != HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED && waitingForInitialDisplay) {
            currentHostDisplaySize()?.let { hostSize ->
                scheduleDisplaySize(
                    hostSize.width,
                    hostSize.height,
                    HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED,
                )
                return
            }
        }
        if (cause != HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED && !waitingForInitialDisplay) {
            pendingLocalPaneViewport = size
            localPaneLayoutPending = true
            if (canScaleCarPlayLocally()) {
                applyLocalCarPlayBounds(size)
                scheduleYoutubeStableAfterLocalLayout()
            }
            return
        }
        if (size == pendingDisplaySize) return
        val scaleLocally = canScaleCarPlayLocally()
        if (!scaleLocally) {
            CarPlayBackgroundSession.observePendingRestartSize(this, size)
            displayResizeCoordinator.observe(size)
        }
        val currentSize = if (scaleLocally) localViewportSize else activeDisplaySize
        if (size == currentSize) {
            if (!scaleLocally && controller == null) {
                localViewportSize = size
                resetLocalCarPlayBounds()
            }
            pendingDisplaySize = null
            mainHandler.removeCallbacks(applyDisplaySize)
            if (!scaleLocally && restartReadyToStart) {
                mainHandler.removeCallbacks(finishRestartAfterResize)
                mainHandler.postDelayed(finishRestartAfterResize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
            }
            if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT && controller != null) {
                mainHandler.removeCallbacks(finishSplitYoutubeStartup)
                mainHandler.postDelayed(finishSplitYoutubeStartup, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
            }
            return
        }
        pendingDisplaySize = size
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(finishRestartAfterResize)
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun applyDisplaySize(size: DisplaySize) {
        SplitPerformanceTracer.section("diplay.split.carplay_resize") {
            applyDisplaySizeNow(size)
        }
    }

    private fun applyDisplaySizeNow(size: DisplaySize) =
        applyDisplaySizeNow(size, HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED)

    private fun applyDisplaySizeNow(size: DisplaySize, cause: HostDisplaySizeCause) {
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        if (isImeResizeActive()) {
            ignoreImeDisplayResize()
            return
        }
        if (cause != HostDisplaySizeCause.PHYSICAL_DISPLAY_CHANGED) {
            pendingLocalPaneViewport = size
            localPaneLayoutPending = true
            if (canScaleCarPlayLocally()) {
                applyLocalCarPlayBounds(size)
                scheduleYoutubeStableAfterLocalLayout()
            }
            return
        }
        if (canScaleCarPlayLocally() && applyLocalCarPlayBounds(size)) return
        CarPlayBackgroundSession.markPendingRestartLayoutReady(this, size)
        if (size == activeDisplaySize) {
            localViewportSize = size
            if (controller == null) resetLocalCarPlayBounds()
            if (restartReadyToStart) finishRestartAtLatestSize(size)
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        localViewportSize = size
        resetLocalCarPlayBounds()
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
                displayResize = true,
            )
        }
        if (restartReadyToStart) finishRestartAtLatestSize(size)
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        if (backgroundTransportHandoffPending) return
        if (CarPlayBackgroundSession.hasPendingTeardown()) return
        if (CarPlayBackgroundSession.isRestartSuppressed()) return
        if (CarPlayBackgroundSession.hasPendingRestart()) {
            if (restartHandoffToken != null) return
            if (pendingDisplaySize != null) return
            val settledSize = activeDisplaySize ?: return
            CarPlayBackgroundSession.markPendingRestartLayoutReady(this, settledSize)
            val handedOffSize = CarPlayBackgroundSession.claimPendingRestart(this, backgroundStopAction())
            if (handedOffSize == null) {
                scheduleRestartHandoffRetry()
                return
            }
            restartHandoffRetryScheduled = false
            mainHandler.removeCallbacks(retryRestartHandoff)
            activeDisplaySize = handedOffSize
            localViewportSize = handedOffSize
            resetLocalCarPlayBounds()
            displayResizeCoordinator.observe(handedOffSize)
        }
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) scheduleRestartHandoffRetry()
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            controller != null
        ) {
            return
        }
        if (autoUsbLaunchBlocked) return
        autoUsbLaunchIdentity?.let { identity ->
            if (IphoneUsbAutoLaunchSettings.currentCandidate(this, identity) == null) {
                IphoneUsbAutoLaunchDispatcher.failLaunch(identity)
                autoUsbLaunchBlocked = true
                appendLog("iPhone USB auto-launch canceled because the setting or device is no longer valid")
                return
            }
        }
        startCarPlayForTest?.invoke(size) ?: startCarPlay(size)
    }

    private fun scheduleRestartHandoffRetry() {
        if (restartHandoffRetryScheduled || isFinishing || isDestroyed) return
        restartHandoffRetryScheduled = true
        mainHandler.postDelayed(retryRestartHandoff, RESTART_HANDOFF_RETRY_MILLIS)
    }

    private fun reconnectAfterLoss(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val controllerManaged = controller?.isWiredRecoveryManagedByController() == true
        if (controllerManaged) {
            appendLog("Wired controller owns recovery; host retry skipped")
            return
        }
        val previousTicket = scheduledReconnectTicket
        val ticket = reconnectAfterLossGate.schedule(
            restartGeneration = restartGeneration,
            connectionEpoch = connectionEpoch,
            ownerEpoch = CarPlayBackgroundSession.ownerEpoch(),
            controllerManaged = controllerManaged,
        ) ?: return
        if (previousTicket != null) mainHandler.removeCallbacks(restartAfterReconnectDelay)
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms")
        scheduledReconnectReason = reason
        scheduledReconnectTicket = ticket
        mainHandler.postDelayed(restartAfterReconnectDelay, delayMillis)
    }

    private fun cancelPendingReconnectAfterLoss() {
        reconnectAfterLossGate.cancel(scheduledReconnectTicket)
        scheduledReconnectTicket = null
        scheduledReconnectReason = null
        mainHandler.removeCallbacks(restartAfterReconnectDelay)
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String, displayResize: Boolean = false) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || isFinishing || isDestroyed || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        if (!displayResizeCoordinator.beginRestart()) return
        cancelPendingReconnectAfterLoss()
        val oldController = controller
        val restartToken = CarPlayBackgroundSession.beginRestart(oldController, this, size)
        if (restartToken == null) {
            displayResizeCoordinator.completeRestart(size)
            return
        }
        if (displayResize) SplitPerformanceTracer.increment(SplitPerformanceCounter.CARPLAY_DISPLAY_RESTARTS)
        finishCarPlayRestartTrace()
        restartHandoffToken = restartToken
        carPlayTeardownTraceCookie = SplitPerformanceTracer.beginAsync("diplay.split.carplay_teardown")
        carPlayRestartTraceCookie = SplitPerformanceTracer.beginAsync("diplay.split.carplay_restart")
        splitCarPlayReadyForYoutube = false
        if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
            logYoutubeStartup("carplay-restart-start")
        }
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldSink = sink
        val teardownToken = CarPlayBackgroundSession.beginTeardown(oldController)
        try {
            oldSink?.setVideoFrameSubmittedToSurfaceListener(null)
        } catch (error: RuntimeException) {
            Log.e(TAG, "restart listener detach failed error=${error.javaClass.simpleName}")
        }
        controller = null
        sink = null
        teardownExecutor.execute {
            if (oldController != null) {
                SplitPerformanceTracer.increment(SplitPerformanceCounter.CARPLAY_CONTROLLER_CLOSES)
                requestControllerCloseForShutdown(oldController)
            }
            val clean = oldController?.let(::awaitControllerClosedForShutdown) ?: true
            if (!clean || !closeSinkForShutdown(oldSink)) {
                Log.e(TAG, "Controller restart cancelled because shutdown is still pending")
                reportTeardownBlocked("CarPlay restart is blocked until the previous Controller and media resources stop")
                if (shutdownTerminatesProcess.get()) Process.killProcess(Process.myPid())
                return@execute
            }
            runOnUiThread {
                CarPlayBackgroundSession.completeTeardown(teardownToken)
                SplitPerformanceTracer.endAsync("diplay.split.carplay_teardown", carPlayTeardownTraceCookie)
                carPlayTeardownTraceCookie = null
                val latestSize = displayResizeCoordinator.latestDesiredSize(size)
                CarPlayBackgroundSession.completePendingRestartTeardown(restartToken, latestSize)
                if (restartHandoffToken != restartToken) return@runOnUiThread
                if (shuttingDown.get() || isFinishing || isDestroyed) {
                    CarPlayBackgroundSession.releaseRestartOwner(this)
                    restartHandoffToken = null
                    return@runOnUiThread
                }
                if (generation != restartGeneration) return@runOnUiThread
                restartFallbackSize = latestSize
                restartReadyToStart = true
                if (pendingDisplaySize == null) {
                    mainHandler.removeCallbacks(finishRestartAfterResize)
                    mainHandler.postDelayed(finishRestartAfterResize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
                }
            }
        }
    }

    private fun finishRestartAtLatestSize(fallback: DisplaySize) {
        if (!restartReadyToStart || pendingDisplaySize != null || shuttingDown.get() || isFinishing || isDestroyed) return
        if (restartHandoffToken == null) return
        val latestSize = displayResizeCoordinator.latestDesiredSize(fallback)
        CarPlayBackgroundSession.markPendingRestartLayoutReady(this, latestSize)
        val handedOffSize = CarPlayBackgroundSession.claimPendingRestart(this, backgroundStopAction()) ?: return
        restartHandoffToken = null
        mainHandler.removeCallbacks(retryRestartHandoff)
        restartHandoffRetryScheduled = false
        val size = displayResizeCoordinator.completeRestart(handedOffSize)
        activeDisplaySize = size
        localViewportSize = currentCarPlayViewportSize() ?: size
        resetLocalCarPlayBounds()
        restartReadyToStart = false
        restartFallbackSize = null
        handshakeResetInProgress = false
        if (hostLayoutState.mode == HostLayoutMode.CARPLAY_YOUTUBE_SPLIT) {
            logYoutubeStartup("resize-settled")
        }
        startCarPlay(size)
    }

    private fun showDiPlayHome(page: String = "home") {
        controller?.sendTouch(emptyList())
        startActivity(Intent(this, DiPlayActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun openSettingsMenu() = showDiPlayHome("settings")

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        if (!persistMenuSettings()) return
        settingsBaseline = null
        finishSettingsMenu("Settings saved")
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded")
    }

    private fun finishSettingsMenu(prefix: String) {
        if (!menuOpen) return
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateDebugOverlays()
        logLines.clear()
        appendLog(
            "$prefix; starting a fresh handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) {
            shutdown(terminateProcess = true, reason = "settings exit application")
            return
        }
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(
        terminateProcess: Boolean,
        reason: String,
        completion: (CarPlayHostShutdownResult) -> Unit = {},
    ) {
        if (terminateProcess) shutdownTerminatesProcess.set(true)
        enqueueShutdownCompletion(completion)
        if (!shuttingDown.compareAndSet(false, true)) return
        finishCarPlayRestartTrace()
        destroyYoutubeBrowser()
        youtubeProfile = null
        YoutubeDeviceProfileManager.clearSplitSelection()
        YoutubeDeviceProfileManager.clearSessionCache()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(finishRestartAfterResize)
        mainHandler.removeCallbacks(finishSplitYoutubeStartup)
        mainHandler.removeCallbacks(retryRestartHandoff)
        cancelPendingReconnectAfterLoss()
        window.decorView.removeCallbacks(waitForImeResizeToSettle)
        videoView?.removeCallbacks(deferYoutubePreparationOneFrame)
        videoView?.removeCallbacks(prepareYoutubeAfterLayout)
        restartHandoffRetryScheduled = false
        val backgroundSnapshot = CarPlayBackgroundSession.snapshot()
        val closeBackgroundSession = controller == null && backgroundSnapshot != null &&
            (terminateProcess || CarPlayBackgroundSession.isOwner(this))
        val oldController = controller ?: backgroundSnapshot?.controller?.takeIf { closeBackgroundSession }
        val oldSink = sink ?: backgroundSnapshot?.takeIf { it.controller === oldController }?.sink
        val clearUnmatchedSession = oldController == null &&
            (terminateProcess || CarPlayBackgroundSession.isOwner(this))
        val teardownToken = CarPlayBackgroundSession.beginTeardown(
            oldController,
            clearUnmatchedSession = clearUnmatchedSession,
        )
        try {
            oldSink?.setVideoFrameSubmittedToSurfaceListener(null)
        } catch (error: RuntimeException) {
            Log.e(TAG, "shutdown listener detach failed error=${error.javaClass.simpleName}")
            notifyShutdownCallbacks(CarPlayHostShutdownResult.FAILED)
        }
        controller = null
        sink = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            if (oldController != null) {
                SplitPerformanceTracer.increment(SplitPerformanceCounter.CARPLAY_CONTROLLER_CLOSES)
                requestControllerCloseForShutdown(oldController)
            }
            val clean = oldController?.let(::awaitControllerClosedForShutdown) ?: true
            if (!clean) {
                Log.e(TAG, "Controller teardown still pending during process shutdown")
                if (shutdownTerminatesProcess.get()) {
                    runCatching { applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java)) }
                    runCatching { applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java)) }
                    teardownExecutor.shutdown()
                    Process.killProcess(Process.myPid())
                } else {
                    reportTeardownBlocked("Controller teardown is still pending; the existing session barrier remains active")
                }
                return@execute
            }
            if (!closeSinkForShutdown(oldSink)) {
                if (shutdownTerminatesProcess.get()) {
                    runCatching { applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java)) }
                    runCatching { applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java)) }
                    teardownExecutor.shutdown()
                    Process.killProcess(Process.myPid())
                } else {
                    reportTeardownBlocked("Media resources are still stopping; the existing session barrier remains active")
                }
                return@execute
            }
            airPlayCommandExecutor.shutdown()
            if (shutdownTerminatesProcess.get()) {
                runCatching { applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java)) }
            }
            Log.i(TAG, "shutdown complete result=closed")
            runCatching { applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java)) }
            teardownExecutor.shutdown()
            mainHandler.post {
                CarPlayBackgroundSession.completeTeardown(teardownToken)
                completeShutdownCallbacks(CarPlayHostShutdownResult.CLOSED)
                if (!shutdownTerminatesProcess.get()) {
                    IphoneUsbAutoLaunchDispatcher.resumeDeferred(this)
                }
            }
            if (shutdownTerminatesProcess.get()) Process.killProcess(Process.myPid())
        }
    }

    private fun awaitControllerClosedForShutdown(controller: CarPlayController): Boolean {
        var notified = false
        repeat(MAX_TEARDOWN_ATTEMPTS) { attempt ->
            val closed = try {
                awaitControllerClosedForTest?.invoke(controller, CONTROLLER_CLOSE_TIMEOUT_MILLIS)
                    ?: controller.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            } catch (error: RuntimeException) {
                if (!notified) {
                    Log.e(TAG, "Controller close wait failed; retaining teardown barrier " +
                        "error=${error.javaClass.simpleName}")
                    notifyShutdownCallbacks(CarPlayHostShutdownResult.FAILED)
                    notified = true
                }
                false
            }
            if (closed) return true
            if (!notified) {
                Log.e(TAG, "Controller close timed out; retaining teardown barrier and retrying")
                notifyShutdownCallbacks(CarPlayHostShutdownResult.TIMED_OUT)
                notified = true
            }
            if (Thread.interrupted()) {
                Log.w(TAG, "Controller close wait interrupted; continuing fail-closed teardown")
            }
            if (attempt + 1 < MAX_TEARDOWN_ATTEMPTS && !shutdownTerminatesProcess.get()) pauseShutdownRetry()
        }
        Log.e(TAG, "Controller teardown did not finish after $MAX_TEARDOWN_ATTEMPTS attempts")
        return false
    }

    private fun requestControllerCloseForShutdown(controller: CarPlayController) {
        var notified = false
        repeat(MAX_TEARDOWN_ATTEMPTS) { attempt ->
            try {
                controller.close()
                return
            } catch (error: RuntimeException) {
                if (!notified) {
                    Log.e(TAG, "Controller close failed; blocking next start error=${error.javaClass.simpleName}")
                    notifyShutdownCallbacks(CarPlayHostShutdownResult.FAILED)
                    notified = true
                }
                if (controller.isClosed()) return
                if (attempt + 1 < MAX_TEARDOWN_ATTEMPTS && !shutdownTerminatesProcess.get()) pauseShutdownRetry()
            }
        }
        Log.e(TAG, "Controller close request did not succeed after $MAX_TEARDOWN_ATTEMPTS attempts")
    }

    private fun pauseShutdownRetry() {
        try {
            Thread.sleep(SHUTDOWN_RETRY_DELAY_MILLIS)
        } catch (_: InterruptedException) {
            Thread.interrupted()
        }
    }

    private fun closeSinkForShutdown(oldSink: AndroidMediaSink?): Boolean {
        var notified = false
        repeat(MAX_TEARDOWN_ATTEMPTS) { attempt ->
            try {
                if (oldSink?.closeAndAwaitResources(CONTROLLER_CLOSE_TIMEOUT_MILLIS) != false) return true
                if (!notified) {
                    Log.e(TAG, "Media sink resources are still active; retaining teardown barrier")
                    notifyShutdownCallbacks(CarPlayHostShutdownResult.TIMED_OUT)
                    notified = true
                }
            } catch (error: RuntimeException) {
                if (!notified) {
                    Log.e(TAG, "Media sink close failed; blocking next start " +
                        "error=${error.javaClass.simpleName}")
                    notifyShutdownCallbacks(CarPlayHostShutdownResult.FAILED)
                    notified = true
                }
            }
            if (attempt + 1 < MAX_TEARDOWN_ATTEMPTS && !shutdownTerminatesProcess.get()) pauseShutdownRetry()
        }
        Log.e(TAG, "Media sink teardown did not finish after $MAX_TEARDOWN_ATTEMPTS attempts")
        return false
    }

    private fun reportTeardownBlocked(message: String) {
        mainHandler.post {
            if (isDestroyed) return@post
            appendLog(message)
            setConnectionStage(message)
        }
    }

    private fun enqueueShutdownCompletion(completion: (CarPlayHostShutdownResult) -> Unit) {
        val runImmediately = synchronized(shutdownCompletionLock) {
            if (shutdownCompleted) true else {
                shutdownCompletionCallbacks += completion
                false
            }
        }
        if (runImmediately) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runShutdownCompletion(completion, CarPlayHostShutdownResult.CLOSED)
            } else {
                mainHandler.post { runShutdownCompletion(completion, CarPlayHostShutdownResult.CLOSED) }
            }
        }
    }

    private fun notifyShutdownCallbacks(result: CarPlayHostShutdownResult) {
        if (result == CarPlayHostShutdownResult.CLOSED) {
            val callbacks = synchronized(shutdownCompletionLock) {
                shutdownCompleted = true
                shutdownCompletionCallbacks.toList().also { shutdownCompletionCallbacks.clear() }
            }
            callbacks.forEach { runShutdownCompletion(it, result) }
            return
        }
        val callbacks = synchronized(shutdownCompletionLock) { shutdownCompletionCallbacks.toList() }
        callbacks.forEach { callback -> mainHandler.post { runShutdownCompletion(callback, result) } }
    }

    private fun completeShutdownCallbacks(result: CarPlayHostShutdownResult) = notifyShutdownCallbacks(result)

    private fun runShutdownCompletion(
        completion: (CarPlayHostShutdownResult) -> Unit,
        result: CarPlayHostShutdownResult,
    ) {
        try {
            completion(result)
        } catch (error: RuntimeException) {
            Log.e(TAG, "shutdown completion failed error=${error.javaClass.simpleName}")
        }
    }

    private fun retireTextureSurface(
        surface: Surface,
        texture: SurfaceTexture?,
        releaseTexture: Boolean = true,
    ) {
        if (texture != null) {
            val shouldSchedule = synchronized(surfaceRetirementLock) {
                if (releasedSurfaceTextures.contains(texture)) false
                else surfaceRetirementGate.begin(texture, surface, releaseTexture)
            }
            if (!shouldSchedule) return
        }

        val currentSink = sink
        if (currentSink == null) {
            completeTextureSurfaceRetirement(surface, texture)
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(2)
        val detached = {
            if (remaining.decrementAndGet() == 0) completeTextureSurfaceRetirement(surface, texture)
        }
        currentSink.clearSurface(SCREEN_TYPE_MAIN, surface, detached)
        currentSink.clearSurface(SCREEN_TYPE_ALT, surface, detached)
    }

    private fun completeTextureSurfaceRetirement(surface: Surface, texture: SurfaceTexture?) {
        if (texture == null) {
            runCatching { surface.release() }
            return
        }
        val retirement = synchronized(surfaceRetirementLock) {
            surfaceRetirementGate.complete(texture, surface)?.also { batch ->
                if (batch.releaseTexture) releasedSurfaceTextures.add(texture)
            }
        } ?: return
        retirement.surfaces.forEach { retired -> runCatching { retired.release() } }
        if (retirement.releaseTexture) runCatching { texture.release() }
        mainHandler.post(::resumeDeferredSurfaceIfReady)
    }

    private fun canAcceptSurfaceGeneration(): Boolean = synchronized(surfaceRetirementLock) {
        surfaceRetirementGate.canAcceptSurface(activeSurface = currentSurface != null)
    }

    private fun resumeDeferredSurfaceIfReady() {
        val texture = deferredSurfaceTexture ?: return
        if (videoView?.surfaceTexture !== texture) {
            deferredSurfaceTexture = null
            return
        }
        if (!canAcceptSurfaceGeneration() || deferredSurfaceTexture !== texture) return
        val existing = currentSurface
        val existingTexture = currentSurfaceTexture
        val surface = Surface(texture)
        deferredSurfaceTexture = null
        currentSurface = surface
        currentSurfaceTexture = texture
        existing?.let { oldSurface ->
            retireTextureSurface(
                oldSurface,
                existingTexture,
                releaseTexture = existingTexture !== texture,
            )
        }
        attachSurface(surface)
        surfaceGenerationBackpressureLogged = false
        appendLog("Deferred texture surface attached after retirement")
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        sink?.setSurface(SCREEN_TYPE_ALT, surface)
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) carPlayTouchSequenceCancelled = false
        if (carPlayTouchSequenceCancelled) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                carPlayTouchSequenceCancelled = false
            }
            return true
        }
        val contentBounds = LocalCarPlayContentBounds(0, 0, view.width, view.height)
        val pointerOutsideContent = (0 until event.pointerCount).any { index ->
            !contentBounds.contains(event.getX(index), event.getY(index))
        }
        if (pointerOutsideContent) {
            controller?.sendTouch(emptyList())
            gestureSequenceActive = false
            gestureTracking = false
            carPlayTouchSequenceCancelled = event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_CANCEL
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_COUNT && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Three-finger swipe tracking started")
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (!gestureTracking || event.pointerCount != THREE_FINGER_COUNT) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(THREE_FINGER_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * THREE_FINGER_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        val contacts = CarPlayTouchMapper.contacts(event, view.width, view.height)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(
        generation: Int,
        controllerConnectionEpoch: Long,
        type: Int,
        active: Boolean,
    ) {
        runOnUiThread {
            if (shuttingDown.get() || !isCurrentControllerCallback(generation, controllerConnectionEpoch)) {
                return@runOnUiThread
            }
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            updateDebugOverlays()
        }
    }

    private fun onVideoFrameSubmittedToSurface(generation: Int, controllerConnectionEpoch: Long, type: Int) {
        if (type != SCREEN_TYPE_MAIN) return
        runOnUiThread {
            if (shuttingDown.get() || !isCurrentControllerCallback(generation, controllerConnectionEpoch)) {
                return@runOnUiThread
            }
            finishCarPlayRestartTrace()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        stageStatusView?.text = friendlyStage(message)
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        statusScrollView?.visibility = View.GONE
        connectionPanel?.visibility = if (mainScreenStreamIsActive(activeScreenStreamTypes, SCREEN_TYPE_MAIN)) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun friendlyStage(message: String): String = when {
        message.contains("Turn on Wi-Fi", true) -> getString(R.string.host_stage_turn_on_wifi)
        message.contains("Allow precise Location", true) -> getString(R.string.host_stage_precise_location)
        message.contains("Allow Nearby devices", true) -> getString(R.string.host_stage_nearby_devices)
        message.contains("createGroup failed", true) -> getString(R.string.host_stage_wifi_start_failed)
        message.contains("needs a reset", true) -> getString(R.string.host_stage_wifi_reset_needed)
        message.contains("socket", true) || message.contains("RFCOMM", true) ->
            getString(R.string.host_stage_iphone_unavailable)
        message.contains("unsupported", true) || message.contains("not supported", true) ->
            getString(R.string.host_stage_wireless_unsupported)
        message.contains("denied", true) || message.contains("permission", true) ->
            getString(R.string.host_stage_permission_denied)
        message.contains("Could not start CarPlay", true) ->
            getString(R.string.host_stage_could_not_start)
        message.contains("Failed", true) || message.contains("error", true) ->
            getString(R.string.host_stage_connection_interrupted)
        message.contains("Waiting for iPhone", true) || message.contains("Discovering iPhone", true) ->
            getString(R.string.host_stage_connect_iphone_usb)
        message.contains("paired", true) -> getString(R.string.host_stage_looking_for_paired_iphone)
        message.contains("Bluetooth", true) -> getString(R.string.host_stage_connecting_iphone)
        message.contains("reconnect", true) || message.contains("ended", true) ->
            getString(R.string.host_stage_reconnecting_iphone)
        message.contains("active", true) || message.contains("running", true) ->
            getString(R.string.host_stage_opening_carplay)
        else -> getString(R.string.host_stage_ready)
    }

    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()))
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val hideTop = hideTopBar
        val hideBottom = hideBottomBar
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> "Preparing MFi authentication"
        CarPlayStatus.WaitingForMfi -> "Waiting for MFi coprocessor"
        CarPlayStatus.RequestingMfiPermission -> "Requesting MFi USB permission"
        CarPlayStatus.MfiReady -> "MFi authentication ready"
        CarPlayStatus.StartingHotspot -> "Starting wireless hotspot"
        is CarPlayStatus.HotspotReady ->
            "Hotspot ready: $backend, $ssid, $band, " +
                "channel ${if (channel == 0) "auto" else channel}"
        CarPlayStatus.WaitingForPairedIphone -> "Waiting for paired iPhone"
        CarPlayStatus.ConnectingBluetooth -> "Connecting Bluetooth"
        CarPlayStatus.RunningWireless -> "Wireless CarPlay control running"
        CarPlayStatus.WirelessActive -> "Wireless CarPlay active"
        CarPlayStatus.WirelessActiveBootstrapControl -> "Wireless CarPlay active"
        CarPlayStatus.DiscoveringIphone -> "Discovering iPhone"
        CarPlayStatus.WaitingForIphone -> "Waiting for iPhone over USB"
        CarPlayStatus.RequestingIphonePermission -> "Requesting iPhone USB permission"
        CarPlayStatus.WaitingForReenumeration -> "Waiting for iPhone re-enumeration"
        CarPlayStatus.SelectingConfiguration -> "Selecting CarPlay configuration"
        CarPlayStatus.OpeningDataPaths -> "Opening USB data paths"
        CarPlayStatus.Pairing -> "Pairing with iPhone"
        CarPlayStatus.ConnectingControl -> "Connecting iAP2 control"
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) "Starting AirPlay service" else "Attaching NCM/AirPlay network"
        CarPlayStatus.RunningControl -> "CarPlay control running"
        CarPlayStatus.ControlEnded -> "CarPlay control window ended"
        is CarPlayStatus.Failed -> "Failed: ${message}"
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        private const val STATE_HOST_LAYOUT_MODE = "host.layout_mode"
        private const val STATE_PENDING_SPLIT_MODE = "host.pending_split_mode"
        private const val STATE_YOUTUBE_SPLIT = "host.youtube_split"
        private const val STATE_CARPLAY_FRACTION = "host.carplay_fraction"
        private const val STATE_AUTO_USB_DEVICE_ID = "host.auto_usb_device_id"
        private const val STATE_AUTO_USB_DEVICE_NAME = "host.auto_usb_device_name"
        private const val STATE_AUTO_USB_VENDOR_ID = "host.auto_usb_vendor_id"
        private const val STATE_AUTO_USB_PRODUCT_ID = "host.auto_usb_product_id"
        private const val STATE_AUTO_USB_LAUNCH_BLOCKED = "host.auto_usb_launch_blocked"
        private const val PROFILE_IDENTITY_TIMEOUT_MILLIS = 5_000L
        private const val PROFILE_IDENTITY_RETRY_INTERVAL_MILLIS = 250L
        private const val RESTART_HANDOFF_RETRY_MILLIS = 500L
        private const val IME_RESIZE_STABLE_FRAME_COUNT = 2
        private const val PROFILE_LOG_PREFIX_LENGTH = 12
        private const val YOUTUBE_TOOLBAR_HEIGHT_DP = 56
        private const val YOUTUBE_BUTTON_MIN_HEIGHT_DP = 48
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val SHUTDOWN_RETRY_DELAY_MILLIS = 250L
        const val MAX_TEARDOWN_ATTEMPTS = 3
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE_EVENT "
        const val THREE_FINGER_COUNT = 3
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 72
        const val THREE_FINGER_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        val MENU_BACKGROUND = Color.rgb(12, 16, 19)
        val MENU_SECONDARY = Color.rgb(170, 180, 190)
        val MENU_ACCENT = Color.rgb(127, 205, 154)
        val MENU_ACCENT_TRACK = Color.rgb(78, 143, 102)
        val MENU_TRACK_OFF = Color.rgb(64, 74, 80)
        val MENU_BUTTON_TEXT = Color.rgb(8, 17, 11)
        val MENU_DANGER = Color.rgb(190, 45, 45)
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class LogEntry(val timestampMillis: Long, val text: String)
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

internal fun mainScreenStreamIsActive(activeScreenStreamTypes: Set<Int>, mainScreenType: Int): Boolean =
    mainScreenType in activeScreenStreamTypes

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopActionOwner: Any? = null
    private var stopping = false
    private var stopWaitingForTeardown = false
    private var owner: Any? = null
    private var ownershipEpoch = 0L
    private var restartSuppressed = false
    private val restartHandoff = CarPlayRestartHandoff()
    private var nextTeardownToken = 0L
    private val pendingTeardowns = mutableSetOf<Long>()
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun ownerEpoch(): Long = ownershipEpoch
    @Synchronized fun hasPendingTeardown(): Boolean = pendingTeardowns.isNotEmpty()
    @Synchronized fun hasSession(): Boolean =
        stopAction != null || stopping || restartHandoff.hasPending() || pendingTeardowns.isNotEmpty()
    @Synchronized fun isRestartSuppressed(): Boolean = restartSuppressed

    @Synchronized
    fun clearRestartSuppression() {
        restartSuppressed = false
    }

    private fun setOwner(next: Any?) {
        if (owner !== next) {
            owner = next
            ownershipEpoch++
        }
    }

    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) {
                stopping = true
                stopWaiters.add(completion)
            } else if (pendingTeardowns.isNotEmpty()) {
                stopping = true
                stopWaitingForTeardown = true
                stopWaiters.add(completion)
                return
            } else if (restartHandoff.hasPending()) {
                restartHandoff.clear()
                restartSuppressed = true
                setOwner(null)
                stopActionOwner = null
                active = false
                width = 0
                height = 0
            }
        }
        if (action == null) { completion(); return }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaitingForTeardown = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val hostLaunchState: CarPlayHostLaunchState?,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var hostLaunchState: CarPlayHostLaunchState? = null

    @Synchronized
    fun beginTeardown(
        expectedController: CarPlayController?,
        clearUnmatchedSession: Boolean = false,
    ): Long {
        val token = ++nextTeardownToken
        pendingTeardowns += token
        if (expectedController != null && controller === expectedController ||
            expectedController == null && clearUnmatchedSession
        ) {
            clearCurrentSession(keepOwner = false)
        }
        return token
    }

    fun completeTeardown(token: Long) {
        val callbacks = synchronized(this) {
            pendingTeardowns.remove(token)
            if (pendingTeardowns.isEmpty() && stopWaitingForTeardown) {
                stopping = false
                stopWaitingForTeardown = false
                stopWaiters.toList().also { stopWaiters.clear() }
            } else {
                emptyList()
            }
        }
        callbacks.forEach { it() }
    }

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        return Snapshot(currentController, currentSink, width, height, hostLaunchState)
    }

    @Synchronized
    fun store(
        controller: CarPlayController,
        sink: AndroidMediaSink,
        width: Int,
        height: Int,
        owner: Any,
        hostLaunchState: CarPlayHostLaunchState? = null,
        stop: ((() -> Unit)) -> Unit,
    ) {
        restartHandoff.clear()
        restartSuppressed = false
        this.stopAction = stop
        this.stopActionOwner = owner
        setOwner(owner)
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.hostLaunchState = hostLaunchState
    }

    @Synchronized
    fun updateHostLaunchState(owner: Any, hostLaunchState: CarPlayHostLaunchState) {
        if (this.owner === owner) this.hostLaunchState = hostLaunchState
    }

    @Synchronized
    fun beginRestart(expected: CarPlayController?, owner: Any, size: DisplaySize): Long? {
        if (this.owner !== owner || controller !== expected) return null
        val token = restartHandoff.begin(owner, size) ?: return null
        controller = null
        sink = null
        active = false
        width = 0
        height = 0
        return token
    }

    @Synchronized
    fun hasPendingRestart(): Boolean = restartHandoff.hasPending()

    @Synchronized
    fun isClaimedRestartOwner(owner: Any): Boolean = restartHandoff.isClaimedBy(owner)

    @Synchronized
    fun observePendingRestartSize(owner: Any, size: DisplaySize) {
        restartHandoff.observeSize(owner, size)
    }

    @Synchronized
    fun markPendingRestartLayoutReady(owner: Any, size: DisplaySize) {
        restartHandoff.markLayoutReady(owner, size)
    }

    @Synchronized
    fun completePendingRestartTeardown(token: Long, size: DisplaySize) {
        restartHandoff.completeTeardown(token, size)
    }

    @Synchronized
    fun claimPendingRestart(owner: Any, stop: ((() -> Unit)) -> Unit): DisplaySize? {
        val size = restartHandoff.claim(owner) ?: return null
        setOwner(owner)
        this.stopAction = stop
        this.stopActionOwner = owner
        return size
    }

    @Synchronized
    fun releaseRestartOwner(owner: Any) {
        restartHandoff.releaseOwner(owner)
        if (stopActionOwner === owner) {
            stopAction = null
            stopActionOwner = null
        }
        if (this.owner === owner && controller == null) setOwner(null)
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        if (expected == null) pendingTeardowns.clear()
        clearCurrentSession(keepOwner)
    }

    private fun clearCurrentSession(keepOwner: Boolean) {
        controller = null
        sink = null
        if (!keepOwner) {
            restartHandoff.clear()
            restartSuppressed = false
            stopAction = null
            stopActionOwner = null
            setOwner(null)
            hostLaunchState = null
        }
        active = false
        width = 0
        height = 0
    }
}
