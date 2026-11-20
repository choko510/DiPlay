package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.media.NavigationAudioRoute
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File
import java.security.MessageDigest

/** SharedPreferences persistence for the accessory identity and paired controllers. */
object AirPlayPersistence {
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_IDENT_PRIVATE = "identity_private"
    private const val KEY_IDENT_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PAIRING_IDS = "pairing_ids"
    private const val KEY_LOCKDOWN_RECORD_IDS = "lockdown_record_ids"
    private const val MAX_LOCKDOWN_RECORDS = 8

    /** Serializes the read-modify-write of the id list with the records it names. */
    private val lockdownStoreLock = Any()
    private val LOCKDOWN_RECORD_ID_PATTERN = Regex("^[0-9a-f]{64}$")

    /** Stored per iPhone as `lockdown.<id>.<field>`; older builds kept one record as `lockdown_<field>`. */
    private val LOCKDOWN_RECORD_FIELDS = listOf(
        "host_id",
        "system_buid",
        "wifi_mac",
        "device_public",
        "device_cert",
        "host_private",
        "host_cert",
        "root_private",
        "root_cert",
    )
    private const val KEY_DISPLAY_SCALE_TENTHS = "display_scale_tenths"
    private const val KEY_UI_SCALE_PERCENT = "ui_scale_percent"
    private const val KEY_HEVC_ENABLED = "hevc_enabled"
    private const val KEY_HEVC_SOFTWARE_DECODER = "hevc_software_decoder"
    private const val KEY_ADVANCED_AUDIO_CHANNEL_MAPPING = "advanced_audio_channel_mapping"
    private const val KEY_NAVIGATION_AUDIO_ROUTE = "navigation_audio_route"
    private const val KEY_WIRELESS_ENABLED = "wireless_enabled"
    private const val KEY_WIRELESS_HOTSPOT_MODE = "wireless_hotspot_mode"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    private const val KEY_MANUAL_HOTSPOT_BAND = "manual_hotspot_band"
    private const val KEY_MANUAL_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    private const val KEY_MANUAL_HOTSPOT_SECURITY = "manual_hotspot_security"
    private const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    private const val KEY_CARPLAY_NAME = "carplay_name"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_OEM_LABEL = "oem_label"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MEDIA_BUFFER_MS = "media_buffer_ms"
    private const val KEY_WIDTH_PHYSICAL_MM = "display_width_physical_mm"
    private const val KEY_PHYSICAL_SIZE_BASIS = "display_physical_size_basis"
    private const val KEY_MAX_DETECTED_WIDTH = "display_max_detected_width"
    private const val KEY_MAX_DETECTED_HEIGHT = "display_max_detected_height"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_HIDE_TOP_BAR = "hide_top_bar"
    private const val KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar"
    private const val KEY_SAFE_AREA_DRAW_OUTSIDE = "safe_area_draw_outside"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_AUTO_LAUNCH_ON_IPHONE_USB_ATTACH = "auto_launch_on_iphone_usb_attach"
    private const val KEY_LOCATION_REPORTING_ENABLED = "location_reporting_enabled"
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_MFI_I2C_PATH = "mfi_i2c_path"
    private const val KEY_REMOTE_MFI_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_MFI_TOKEN = "remote_mfi_token"
    private const val SAFE_AREA_KEY_PREFIX = "safe_area_"
    private const val CUSTOM_ICON_FILE = "airplay-icon.png"

    const val DEFAULT_MANUFACTURER = "DiPlay"
    const val DEFAULT_MODEL = "DiPlay"
    const val DEFAULT_CARPLAY_NAME = "DiPlay"
    const val MAX_CARPLAY_NAME_UTF8_BYTES = 63
    const val DEFAULT_OEM_LABEL = "DiPlay"
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-1"

    fun loadDisplayScaleTenths(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarPlayDisplayScale.sanitize(
            prefs.getInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.DEFAULT_TENTHS),
        )
    }

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.sanitize(tenths))
            .apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_ENABLED, false)

    fun loadUiScalePercent(context: Context): Int = CarPlayUiScale.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.DEFAULT),
    )

    fun saveUiScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.sanitize(percent)).apply()
    }

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_ENABLED, enabled)
            .apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled)
            .apply()
    }

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, false)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, enabled)
            .apply()
    }

    fun loadNavigationAudioRoute(context: Context): NavigationAudioRoute {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAVIGATION_AUDIO_ROUTE, null)
        return NavigationAudioRoute.entries.firstOrNull { it.name == stored }
            ?: NavigationAudioRoute.FULL_BAND
    }

    fun saveNavigationAudioRoute(context: Context, route: NavigationAudioRoute) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_NAVIGATION_AUDIO_ROUTE, route.name)
        }
    }

    fun loadWirelessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIRELESS_ENABLED, true)

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_ENABLED, enabled)
            .apply()
    }

    fun loadMfiTarget(context: Context): MfiTarget {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_TARGET, null)
        return MfiTarget.entries.firstOrNull { it.name == stored } ?: MfiTarget.LOCAL
    }

    fun saveMfiTarget(context: Context, target: MfiTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_TARGET, target.name)
            .apply()
    }

    fun loadMfiI2cPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_I2C_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MFI_I2C_PATH

    fun saveMfiI2cPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_I2C_PATH, path.trim())
            .apply()
    }

    fun loadRemoteMfiServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_SERVER, null)
            .orEmpty()

    fun saveRemoteMfiServer(context: Context, server: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_SERVER, server)
            .apply()
    }

    fun loadRemoteMfiToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_TOKEN, null)
            .orEmpty()

    fun saveRemoteMfiToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_TOKEN, token)
            .apply()
    }

    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WIRELESS_HOTSPOT_MODE, null)
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored }
            ?: WirelessHotspotMode.WIFI_P2P
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            mode == WirelessHotspotMode.WIFI_P2P
        ) {
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
        } else {
            mode
        }
    }

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIRELESS_HOTSPOT_MODE, mode.name)
            .apply()
    }

    fun loadManualHotspotSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_SSID, null)
            .orEmpty()

    fun saveManualHotspotSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, ssid)
            .apply()
    }

    fun loadManualHotspotPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_PASSPHRASE, null)
            .orEmpty()

    fun saveManualHotspotPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadManualHotspotBand(context: Context): ManualHotspotBand {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_BAND, null)
        return ManualHotspotBand.entries.firstOrNull { it.name == stored }
            ?: ManualHotspotBand.AUTO
    }

    fun saveManualHotspotBand(context: Context, band: ManualHotspotBand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_BAND, band.name)
            .apply()
    }

    fun loadManualHotspotChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MANUAL_HOTSPOT_CHANNEL, 0)
            .coerceIn(0, 196)

    fun saveManualHotspotChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MANUAL_HOTSPOT_CHANNEL, channel.coerceIn(0, 196))
            .apply()
    }

    fun loadManualHotspotSecurity(context: Context): ManualHotspotSecurity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MANUAL_HOTSPOT_SECURITY, null)
        return ManualHotspotSecurity.entries.firstOrNull { it.name == stored }
            ?: if (loadManualHotspotPassphrase(context).isEmpty()) {
                ManualHotspotSecurity.OPEN
            } else {
                ManualHotspotSecurity.WPA2
            }
    }

    fun saveManualHotspotSecurity(context: Context, security: ManualHotspotSecurity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SECURITY, security.name)
            .apply()
    }

    fun loadDebugLogsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGS_ENABLED, false)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEBUG_LOGS_ENABLED, enabled)
            .apply()
    }

    fun loadAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_ON_BOOT, false)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }

    fun loadAutoLaunchOnIphoneUsbAttach(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_LAUNCH_ON_IPHONE_USB_ATTACH, false)

    fun saveAutoLaunchOnIphoneUsbAttach(context: Context, enabled: Boolean): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_LAUNCH_ON_IPHONE_USB_ATTACH, enabled)
            .commit()

    fun loadLocationReportingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCATION_REPORTING_ENABLED, false)

    fun saveLocationReportingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCATION_REPORTING_ENABLED, enabled)
            .apply()
    }

    fun normalizeCarPlayName(name: String): String =
        name.replace('\u0000', ' ').trim().ifBlank { DEFAULT_CARPLAY_NAME }

    fun isCarPlayNameValid(name: String): Boolean =
        normalizeCarPlayName(name).toByteArray(Charsets.UTF_8).size <= MAX_CARPLAY_NAME_UTF8_BYTES

    fun loadCarPlayName(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CARPLAY_NAME, DEFAULT_CARPLAY_NAME)
        val normalized = normalizeCarPlayName(stored ?: DEFAULT_CARPLAY_NAME)
        return normalized.takeIf(::isCarPlayNameValid) ?: DEFAULT_CARPLAY_NAME
    }

    fun saveCarPlayName(context: Context, name: String): Boolean {
        val normalized = normalizeCarPlayName(name)
        if (!isCarPlayNameValid(normalized)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARPLAY_NAME, normalized)
            .apply()
        return true
    }

    fun loadManufacturer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUFACTURER, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANUFACTURER

    fun saveManufacturer(context: Context, manufacturer: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUFACTURER, manufacturer)
            .apply()
    }

    fun loadModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun saveModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    fun loadOemLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)
            // iOS hides the car icon without a label.
            .orEmpty().ifBlank { DEFAULT_OEM_LABEL }

    fun saveOemLabel(context: Context, oemLabel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OEM_LABEL, oemLabel)
            .apply()
    }

    fun loadFps(context: Context): Int = AirPlayDisplaySettings.sanitizeFps(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, 30),
    )

    fun loadMediaBufferMillis(context: Context): Int = com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS),
    )

    fun saveMediaBufferMillis(context: Context, millis: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(millis)).apply()
    }

    fun saveFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, AirPlayDisplaySettings.sanitizeFps(fps))
            .apply()
    }

    fun loadWidthPhysicalMm(context: Context): Int =
        AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                com.shilapi.xcertplay.airplay.CarPlaySize.DEFAULT.widthMillimeters,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return AirPlayPhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHYSICAL_SIZE_BASIS, basis.name)
            .apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_DETECTED_WIDTH, 0) to
            prefs.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive)
            .apply()
    }

    fun loadHideTopBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_TOP_BAR, true)

    fun saveHideTopBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_TOP_BAR, hide)
            .apply()
    }

    fun loadHideBottomBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BOTTOM_BAR, true)

    fun saveHideBottomBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_BOTTOM_BAR, hide)
            .apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside)
            .apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    fun loadCustomAirPlayIconFile(context: Context): File? =
        File(context.filesDir, CUSTOM_ICON_FILE).takeIf { it.isFile }

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) {
        require(encodedImage.isNotEmpty()) { "AirPlay icon data must not be empty" }
        File(context.filesDir, CUSTOM_ICON_FILE).outputStream().use { output ->
            output.write(encodedImage)
        }
    }

    fun clearCustomAirPlayIcon(context: Context) {
        File(context.filesDir, CUSTOM_ICON_FILE).delete()
    }

    fun loadIdentity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privateKey = prefs.getString(KEY_IDENT_PRIVATE, null)
        val publicKey = prefs.getString(KEY_IDENT_PUBLIC, null)
        val pairingId = prefs.getString(KEY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            return AirPlayIdentity(privateKey.decodeHex(), publicKey.decodeHex(), pairingId)
        }
        return AirPlayIdentity.generate().also { identity ->
            prefs.edit()
                .putString(KEY_IDENT_PRIVATE, identity.privateKey.toHex())
                .putString(KEY_IDENT_PUBLIC, identity.publicKey.toHex())
                .putString(KEY_PAIRING_ID, identity.pairingId)
                .apply()
        }
    }

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = PairingStore(onSave)
        for (identifier in prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            prefs.getString("pairing.$identifier", null)?.let { store.save(identifier, it.decodeHex()) }
        }
        return store
    }

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identifiers = prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString("pairing.$identifier", longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    /**
     * Wired Lockdown pair records, one per iPhone under a fingerprint of its DevicePublicKey, so that
     * two drivers' iPhones never replace each other's trust.
     *
     * [KEY_LOCKDOWN_RECORD_IDS] lists the ids most recently used first, and at most
     * [MAX_LOCKDOWN_RECORDS] records are kept. Callers save a new record and call
     * [markLockdownRecordUsed] only once a CarKit connection opened, so a failed attempt neither
     * evicts another iPhone's record nor counts as recent use.
     */
    fun loadLockdownRecord(context: Context, devicePublicKeyPem: ByteArray): LockdownPairRecord? =
        synchronized(lockdownStoreLock) {
            val prefs = lockdownPrefs(context)
            val id = lockdownRecordId(devicePublicKeyPem)
            if (id !in lockdownRecordIds(prefs)) return@synchronized null
            readLockdownRecordById(prefs, id)
        }

    /** The most recently used record: what releases up to 0.2.22 offered every iPhone. */
    fun loadNewestLockdownRecord(context: Context): LockdownPairRecord? =
        synchronized(lockdownStoreLock) {
            val prefs = lockdownPrefs(context)
            lockdownRecordIds(prefs).firstNotNullOfOrNull { id -> readLockdownRecordById(prefs, id) }
        }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        synchronized(lockdownStoreLock) { saveLockdownRecord(lockdownPrefs(context), record) }
    }
    /**
     * Moves a stored record to the front of the list without touching its keys or certificates.
     * A record that is not stored, or no longer usable, is left alone. Callers use this only for a
     * connection that opened, so recency never follows a rejected or failed record.
     */
    fun markLockdownRecordUsed(context: Context, devicePublicKeyPem: ByteArray) {
        synchronized(lockdownStoreLock) {
            val prefs = lockdownPrefs(context)
            val id = lockdownRecordId(devicePublicKeyPem)
            val ids = lockdownRecordIds(prefs)
            if (id !in ids || readLockdownRecordById(prefs, id) == null) return
            val reordered = listOf(id) + (ids - id)
            if (reordered == ids) return
            writeLockdownRecordIds(prefs, reordered)
        }
    }

    fun clearLockdownRecord(context: Context, devicePublicKeyPem: ByteArray) {
        synchronized(lockdownStoreLock) {
            val prefs = lockdownPrefs(context)
            val id = lockdownRecordId(devicePublicKeyPem)
            val editor = prefs.edit()
            removeLockdownRecord(editor, id)
            editor.putString(KEY_LOCKDOWN_RECORD_IDS, (lockdownRecordIds(prefs) - id).joinToString(",")).apply()
        }
    }

    /**
     * Moves the single record of older builds under its iPhone's id and converts the store to the
     * per-iPhone layout.
     *
     * The per-iPhone copy must be readable and carry the legacy record's own fingerprint before the
     * flat keys go, and every write here is a [SharedPreferences.Editor.commit]. An interrupted,
     * repeated, or concurrent move therefore repeats itself, a damaged per-iPhone copy is rewritten
     * from the legacy record instead of being trusted, and a failed write keeps the flat keys for
     * the next start. Only a legacy record that cannot be restored at all is dropped.
     */
    private fun lockdownPrefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (LOCKDOWN_RECORD_FIELDS.none { prefs.contains("lockdown_$it") }) return prefs
        val legacy = readLockdownRecord(prefs) { field -> "lockdown_$field" }
        if (legacy != null) {
            val id = lockdownRecordId(legacy.devicePublicKeyPem)
            val migrated = if (readLockdownRecordById(prefs, id) != null) {
                writeLockdownRecordIds(prefs, listOf(id) + (lockdownRecordIds(prefs) - id), commit = true)
            } else {
                saveLockdownRecord(prefs, legacy, commit = true) && readLockdownRecordById(prefs, id) != null
            }
            if (!migrated) return prefs
        }
        val editor = prefs.edit()
        LOCKDOWN_RECORD_FIELDS.forEach { editor.remove("lockdown_$it") }
        editor.commit()
        return prefs
    }

    /** Returns false only when a requested [commit] failed, which leaves the store as it was. */
    private fun saveLockdownRecord(
        prefs: SharedPreferences,
        record: LockdownPairRecord,
        commit: Boolean = false,
    ): Boolean {
        val id = lockdownRecordId(record.devicePublicKeyPem)
        val ids = listOf(id) + lockdownRecordIds(prefs)
            .filter { it != id && readLockdownRecordById(prefs, it) != null }
        val kept = ids.take(MAX_LOCKDOWN_RECORDS)
        val editor = prefs.edit()
        ids.drop(MAX_LOCKDOWN_RECORDS).forEach { removeLockdownRecord(editor, it) }
        fun key(field: String) = "lockdown.$id.$field"
        editor
            .putString(key("host_id"), record.hostId)
            .putString(key("system_buid"), record.systemBuid)
            .putString(key("wifi_mac"), record.wifiMacAddress)
            .putString(key("device_public"), record.devicePublicKeyPem.toHex())
            .putString(key("device_cert"), record.deviceCertificatePem.toHex())
            .putString(key("host_private"), record.hostPrivateKeyPem.toHex())
            .putString(key("host_cert"), record.hostCertificatePem.toHex())
            .putString(key("root_private"), record.rootPrivateKeyPem.toHex())
            .putString(key("root_cert"), record.rootCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_RECORD_IDS, kept.joinToString(","))
        return if (commit) {
            editor.commit()
        } else {
            editor.apply()
            true
        }
    }

    /**
     * The record stored under [id], and only if it still belongs to that id: a copy whose
     * `device_public` no longer hashes to [id] is treated as absent rather than used.
     */
    private fun readLockdownRecordById(prefs: SharedPreferences, id: String): LockdownPairRecord? {
        if (!LOCKDOWN_RECORD_ID_PATTERN.matches(id)) return null
        val record = readLockdownRecord(prefs) { field -> "lockdown.$id.$field" } ?: return null
        return record.takeIf { lockdownRecordId(it.devicePublicKeyPem) == id }
    }

    private fun readLockdownRecord(prefs: SharedPreferences, key: (String) -> String): LockdownPairRecord? =
        try {
            val values = LOCKDOWN_RECORD_FIELDS.associateWith { field ->
                prefs.getString(key(field), null) ?: return null
            }
            LockdownPairRecord.restore(
                hostId = values.getValue("host_id"),
                systemBuid = values.getValue("system_buid"),
                wifiMacAddress = values.getValue("wifi_mac"),
                devicePublicKeyPem = values.getValue("device_public").decodeHex(),
                deviceCertificatePem = values.getValue("device_cert").decodeHex(),
                hostPrivateKeyPem = values.getValue("host_private").decodeHex(),
                hostCertificatePem = values.getValue("host_cert").decodeHex(),
                rootPrivateKeyPem = values.getValue("root_private").decodeHex(),
                rootCertificatePem = values.getValue("root_cert").decodeHex(),
            )
        } catch (_: Exception) {
            // Includes a value stored under an unexpected type, which SharedPreferences reports as a
            // ClassCastException from getString().
            null
        }

    private fun removeLockdownRecord(editor: SharedPreferences.Editor, id: String) {
        if (!LOCKDOWN_RECORD_ID_PATTERN.matches(id)) return
        LOCKDOWN_RECORD_FIELDS.forEach { editor.remove("lockdown.$id.$it") }
    }

    /** Most recently used first; unknown or duplicated ids in the stored string are ignored. */
    private fun lockdownRecordIds(prefs: SharedPreferences): List<String> =
        prefs.getString(KEY_LOCKDOWN_RECORD_IDS, null)
            ?.split(',')
            ?.filter { LOCKDOWN_RECORD_ID_PATTERN.matches(it) }
            ?.distinct()
            ?.take(MAX_LOCKDOWN_RECORDS)
            ?: emptyList()

    /** Returns false only when a requested [commit] failed. */
    private fun writeLockdownRecordIds(
        prefs: SharedPreferences,
        ids: List<String>,
        commit: Boolean = false,
    ): Boolean {
        val editor = prefs.edit()
            .putString(KEY_LOCKDOWN_RECORD_IDS, ids.take(MAX_LOCKDOWN_RECORDS).joinToString(","))
        return if (commit) {
            editor.commit()
        } else {
            editor.apply()
            true
        }
    }

    private fun lockdownRecordId(devicePublicKeyPem: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(devicePublicKeyPem).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"
}
