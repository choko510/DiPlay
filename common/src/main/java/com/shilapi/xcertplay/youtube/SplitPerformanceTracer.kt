package com.shilapi.xcertplay.youtube

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

internal enum class SplitPerformanceCounter(val label: String) {
    CARPLAY_CONTROLLER_STARTS("carplay_controller_starts"),
    CARPLAY_CONTROLLER_CLOSES("carplay_controller_closes"),
    AIRPLAY_SESSION_CHANGES("airplay_session_changes"),
    AIRPLAY_SETUP_COUNT("airplay_setup_count"),
    AIRPLAY_TEARDOWN_COUNT("airplay_teardown_count"),
    SCREEN_STREAM_STARTS("screen_stream_starts"),
    SCREEN_STREAM_ENDS("screen_stream_ends"),
    SPLIT_ENTRIES("split_entries"),
    SPLIT_EXITS("split_exits"),
    VIEWAREA_ADVERTISED_COUNT("viewarea_advertised_count"),
    VIEWAREA_COMMAND_ATTEMPTS("viewarea_command_attempts"),
    VIEWAREA_COMMAND_WRITE_OK("viewarea_command_write_ok"),
    VIEWAREA_TRANSITION_CONFIRMED("viewarea_transition_confirmed"),
    VIEWAREA_TRANSITION_TIMEOUT("viewarea_transition_timeout"),
    VIEWAREA_REQUEST_FROM_PHONE("viewarea_request_from_phone"),
    VIEWAREA_FALLBACK_COUNT("viewarea_fallback_count"),
    VIDEO_CONFIG_CHANGES("video_config_changes"),
    VIDEO_OUTPUT_FORMAT_CHANGES("video_output_format_changes"),
    VIDEO_DECODER_RECONFIGURES("video_decoder_reconfigure_count"),
    SURFACE_TEXTURE_CREATED("surface_texture_created"),
    SURFACE_TEXTURE_DESTROYED("surface_texture_destroyed"),
    SURFACE_OUTPUT_SUBMISSIONS("surface_output_submissions"),
    TEXTURE_VISIBLE_COMPOSITE("texture_visible_composite"),
    GECKO_RUNTIME_CREATIONS("gecko_runtime_creations"),
    GECKO_WARMUPS("gecko_warmups"),
    GECKO_SESSION_CREATIONS("gecko_session_creations"),
    GECKO_SESSION_CLOSES("gecko_session_closes"),
    YOUTUBE_LOAD_URI_CALLS("youtube_load_uri_calls"),
    CARPLAY_DISPLAY_RESTARTS("carplay_display_restarts"),
    PROFILE_RESOLUTIONS("profile_resolutions"),
    SHARED_PREFERENCES_WRITES("youtube_profile_preferences_writes"),
    WARM_SESSION_REOPENS("warm_session_reopens"),
    GECKO_PAINT_STATUS_RESETS("paint_status_reset_count"),
    GECKO_FIRST_COMPOSITES("first_composite_count"),
    GECKO_FIRST_CONTENTFUL_PAINTS("first_contentful_paint_count"),
    SPLIT_VISIBLE_PAINT_COMPLETIONS("visible_paint_completed_count"),
    SPLIT_VISIBLE_PAINT_TIMEOUTS("visible_paint_timeout_count"),
}

internal object SplitPerformanceTracer {
    private const val NO_COOKIE = -1

    private val nextCookie = AtomicInteger()
    private val counters = AtomicLongArray(SplitPerformanceCounter.entries.size)

    @Volatile
    private var configuredPackageName: String? = null

    @PublishedApi
    @Volatile
    internal var enabled = false

    fun configure(context: Context) {
        val packageName = context.packageName
        if (configuredPackageName == packageName) return
        synchronized(this) {
            if (configuredPackageName == packageName) return
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            val benchmarkOptIn = if (debuggable) {
                false
            } else {
                try {
                    context.packageManager
                        .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                        .metaData
                        ?.getBoolean(BENCHMARK_TRACE_METADATA, false) == true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
            enabled = shouldEnable(debuggable, benchmarkOptIn)
            configuredPackageName = packageName
        }
    }

    internal fun shouldEnable(debuggable: Boolean, benchmarkOptIn: Boolean): Boolean =
        debuggable || benchmarkOptIn

    fun increment(counter: SplitPerformanceCounter) {
        if (enabled) counters.incrementAndGet(counter.ordinal)
    }

    fun snapshot(): LongArray = LongArray(SplitPerformanceCounter.entries.size) { index ->
        counters.get(index)
    }

    fun summarySince(snapshot: LongArray): String? {
        if (!enabled) return null
        return deltaSummary(snapshot(), snapshot)
    }

    internal fun deltaSummary(current: LongArray, baseline: LongArray): String {
        require(current.size == SplitPerformanceCounter.entries.size)
        require(baseline.size == SplitPerformanceCounter.entries.size)
        return SplitPerformanceCounter.entries.joinToString(", ") { counter ->
            val index = counter.ordinal
            "${counter.label}=${current[index] - baseline[index]}"
        }
    }

    inline fun <T> section(name: String, action: () -> T): T {
        if (!enabled) return action()
        Trace.beginSection(name)
        return try {
            action()
        } finally {
            Trace.endSection()
        }
    }

    fun beginAsync(name: String): Int {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return NO_COOKIE
        val cookie = nextCookie.incrementAndGet()
        Trace.beginAsyncSection(name, cookie)
        return cookie
    }

    fun endAsync(name: String, cookie: Int?) {
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cookie != null && cookie != NO_COOKIE) {
            Trace.endAsyncSection(name, cookie)
        }
    }

    fun summary(): String? {
        if (!enabled) return null
        return SplitPerformanceCounter.entries.joinToString(", ") { counter ->
            "${counter.label}=${counters.get(counter.ordinal)}"
        }
    }

    private const val BENCHMARK_TRACE_METADATA =
        "com.shilapi.xcertplay.host.SPLIT_PERFORMANCE_TRACING"
}
