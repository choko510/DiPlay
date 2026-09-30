package com.shilapi.xcertplay.youtube

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime

internal object YoutubeGeckoRuntimeProvider {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime = runtime ?: synchronized(this) {
        runtime ?: GeckoRuntime.create(context.applicationContext).also {
            runtime = it
            Log.i(TAG, "Gecko runtime initialized")
        }
    }

    private const val TAG = "DiPlayYouTube"
}
