package com.shilapi.xcertplay.youtube

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

internal object YoutubeGeckoRuntimeProvider {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        SplitPerformanceTracer.configure(context)
        return runtime ?: synchronized(this) {
            runtime ?: SplitPerformanceTracer.section("diplay.gecko.runtime_create") {
                GeckoRuntime.create(
                    context.applicationContext,
                    GeckoRuntimeSettings.Builder().debugLogging(false).build(),
                ).also {
                    runtime = it
                    SplitPerformanceTracer.increment(SplitPerformanceCounter.GECKO_RUNTIME_CREATIONS)
                    Log.i(TAG, "Gecko runtime initialized")
                }
            }
        }
    }

    fun warmUp(context: Context) {
        SplitPerformanceTracer.configure(context)
        SplitPerformanceTracer.increment(SplitPerformanceCounter.GECKO_WARMUPS)
        try {
            SplitPerformanceTracer.section("diplay.gecko.warmup") {
                get(context).warmUp()
            }
        } catch (_: RuntimeException) {
            Log.w(TAG, "Gecko runtime warm-up failed")
        } catch (_: LinkageError) {
            Log.w(TAG, "Gecko runtime warm-up failed")
        }
    }

    fun configurationChanged(configuration: Configuration) {
        try {
            runtime?.configurationChanged(configuration)
        } catch (_: RuntimeException) {
            Log.w(TAG, "Gecko configuration update failed")
        } catch (_: LinkageError) {
            Log.w(TAG, "Gecko configuration update failed")
        }
        try {
            runtime?.orientationChanged(configuration.orientation)
        } catch (_: RuntimeException) {
            Log.w(TAG, "Gecko orientation update failed")
        } catch (_: LinkageError) {
            Log.w(TAG, "Gecko orientation update failed")
        }
    }

    private const val TAG = "DiPlayYouTube"
}
