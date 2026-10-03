package com.shilapi.xcertplay.transport

import android.content.Context
import android.content.pm.ApplicationInfo

/** One-variable startup experiments. AUTO preserves the shipped NCM behavior. */
enum class NcmDiagnosticProfile(
    val statusPolling: Boolean = false,
    val preReadyOutTimeoutMillis: Int = 100,
    val synchronousBulkIn: Boolean = false,
    val forcedFunctionPair: Pair<Int, Int>? = null,
) {
    AUTO,
    STATUS_POLLING(statusPolling = true),
    OUT_TIMEOUT_250(preReadyOutTimeoutMillis = 250),
    OUT_TIMEOUT_500(preReadyOutTimeoutMillis = 500),
    OUT_TIMEOUT_1000(preReadyOutTimeoutMillis = 1_000),
    LEGACY_OUT_TIMEOUT(preReadyOutTimeoutMillis = 2_000),
    SYNC_BULK_IN(synchronousBulkIn = true),
    FORCE_3_4(forcedFunctionPair = 3 to 4),
    FORCE_5_6(forcedFunctionPair = 5 to 6),
}

object NcmDiagnosticProfileStore {
    private const val PREFERENCES = "ncm_diagnostics"
    private const val PROFILE_KEY = "profile"

    fun isDebuggable(context: Context): Boolean =
        (context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun load(context: Context): NcmDiagnosticProfile {
        val savedName = if (isDebuggable(context)) {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(PROFILE_KEY, null)
        } else {
            null
        }
        return resolveNcmDiagnosticProfile(isDebuggable(context), savedName)
    }

    fun save(context: Context, profile: NcmDiagnosticProfile) {
        if (!isDebuggable(context)) return
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(PROFILE_KEY, profile.name)
            .apply()
    }
}

internal fun resolveNcmDiagnosticProfile(
    debuggable: Boolean,
    savedName: String?,
): NcmDiagnosticProfile = if (debuggable) {
    NcmDiagnosticProfile.entries.firstOrNull { it.name == savedName } ?: NcmDiagnosticProfile.AUTO
} else {
    NcmDiagnosticProfile.AUTO
}
