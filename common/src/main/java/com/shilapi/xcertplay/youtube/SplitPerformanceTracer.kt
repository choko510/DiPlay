package com.shilapi.xcertplay.youtube

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

internal enum class SplitPerformanceCounter(val label: String) {
    CARPLAY_CONTROLLER_STARTS("carplay_controller_starts"),
    CARPLAY_CONTROLLER_CLOSES("carplay_controller_closes"),
    SPLIT_ENTRIES("split_entries"),
    SPLIT_EXITS("split_exits"),
    GECKO_RUNTIME_CREATIONS("gecko_runtime_creations"),
    GECKO_WARMUPS("gecko_warmups"),
    GECKO_SESSION_CREATIONS("gecko_session_creations"),
    GECKO_SESSION_CLOSES("gecko_session_closes"),
    YOUTUBE_LOAD_URI_CALLS("youtube_load_uri_calls"),
    CARPLAY_DISPLAY_RESTARTS("carplay_display_restarts"),
    PROFILE_RESOLUTIONS("profile_resolutions"),
    SHARED_PREFERENCES_WRITES("youtube_profile_preferences_writes"),
    WARM_SESSION_REOPENS("warm_session_reopens"),
}

internal object SplitPerformanceTracer {
    private const val NO_COOKIE = -1

    private val nextCookie = AtomicInteger()
    private val counters = AtomicLongArray(SplitPerformanceCounter.entries.size)

    @PublishedApi
    @Volatile
    internal var enabled = false

    fun configure(context: Context) {
        enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    fun increment(counter: SplitPerformanceCounter) {
        if (enabled) counters.incrementAndGet(counter.ordinal)
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
}
