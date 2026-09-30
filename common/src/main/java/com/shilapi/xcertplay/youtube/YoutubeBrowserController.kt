package com.shilapi.xcertplay.youtube

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

internal enum class YoutubeLoadState {
    LOADING,
    READY,
    ERROR,
}

internal class YoutubeBrowserController(
    context: Context,
    private val onLoadStateChanged: (YoutubeLoadState) -> Unit,
    private val onCanGoBackChanged: (Boolean) -> Unit,
    private val onFullscreenChanged: (Boolean) -> Unit,
) {
    val view = GeckoView(context)

    private var runtime: org.mozilla.geckoview.GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var profile: YoutubeDeviceProfile? = null
    private var canGoBack = false
    private var active = true
    private var crashed = false

    fun open(deviceProfile: YoutubeDeviceProfile) {
        if (profile?.youtubeContextId == deviceProfile.youtubeContextId && session != null && !crashed) {
            session?.setActive(active)
            return
        }

        closeSession()
        profile = deviceProfile
        crashed = false
        setCanGoBack(false)
        onLoadStateChanged(YoutubeLoadState.LOADING)
        try {
            val geckoRuntime = runtime ?: YoutubeGeckoRuntimeProvider.get(view.context).also {
                runtime = it
            }
            val settings = GeckoSessionSettings.Builder()
                .contextId(deviceProfile.youtubeContextId)
                .suspendMediaWhenInactive(true)
                .build()
            val newSession = GeckoSession(settings)
            session = newSession
            newSession.setContentDelegate(object : GeckoSession.ContentDelegate {
                override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                    if (this@YoutubeBrowserController.session === session) {
                        onFullscreenChanged(fullScreen)
                    }
                }

                override fun onCrash(session: GeckoSession) {
                    onSessionCrashed(session)
                }

                override fun onKill(session: GeckoSession) {
                    onSessionCrashed(session)
                }
            })
            newSession.setProgressDelegate(object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    if (this@YoutubeBrowserController.session !== session) return
                    val host = url.toUri().host ?: "unknown"
                    Log.i(TAG, "YouTube page load started host=$host")
                    onLoadStateChanged(YoutubeLoadState.LOADING)
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    if (this@YoutubeBrowserController.session !== session) return
                    onLoadStateChanged(if (success) YoutubeLoadState.READY else YoutubeLoadState.ERROR)
                }
            })
            newSession.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest,
                ): GeckoResult<AllowOrDeny>? {
                    val scheme = request.uri.toUri().scheme
                    if (!scheme.equals("https", ignoreCase = true) &&
                        !scheme.equals("http", ignoreCase = true)
                    ) {
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                        session.loadUri(request.uri)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    return null
                }

                override fun onLoadError(
                    session: GeckoSession,
                    uri: String?,
                    error: org.mozilla.geckoview.WebRequestError,
                ): GeckoResult<String>? {
                    if (this@YoutubeBrowserController.session === session) {
                        onLoadStateChanged(YoutubeLoadState.ERROR)
                    }
                    return null
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    if (this@YoutubeBrowserController.session === session) setCanGoBack(canGoBack)
                }

                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
            })
            newSession.open(geckoRuntime)
            view.setSession(newSession)
            newSession.setActive(active)
            newSession.loadUri(YOUTUBE_URL)
            Log.i(TAG, "Gecko session opened profile=${deviceProfile.profileKey.take(LOG_PROFILE_PREFIX_LENGTH)}")
        } catch (_: RuntimeException) {
            onOpenFailed()
        } catch (_: LinkageError) {
            onOpenFailed()
        }
    }

    fun setActive(isActive: Boolean) {
        active = isActive
        session?.setActive(isActive)
    }

    fun reload() {
        if (crashed || session == null) {
            profile?.let(::open)
        } else {
            session?.reload()
        }
    }

    fun goBack(): Boolean {
        if (!canGoBack) return false
        session?.goBack()
        return true
    }

    fun exitFullscreen() {
        session?.exitFullScreen()
    }

    fun destroy() {
        closeSession()
        profile = null
        onCanGoBackChanged(false)
    }

    private fun onSessionCrashed(crashedSession: GeckoSession) {
        if (session !== crashedSession) return
        crashed = true
        setCanGoBack(false)
        onFullscreenChanged(false)
        onLoadStateChanged(YoutubeLoadState.ERROR)
        Log.w(TAG, "Gecko content process stopped")
    }

    private fun setCanGoBack(value: Boolean) {
        if (canGoBack == value) return
        canGoBack = value
        onCanGoBackChanged(value)
    }

    private fun onOpenFailed() {
        closeSession()
        onLoadStateChanged(YoutubeLoadState.ERROR)
        Log.w(TAG, "Gecko session could not open")
    }

    private fun closeSession() {
        val oldSession = session ?: return
        session = null
        runCatching {
            oldSession.setContentDelegate(null)
            oldSession.setProgressDelegate(null)
            oldSession.setNavigationDelegate(null)
            if (view.session === oldSession) view.releaseSession()
            oldSession.close()
        }
        crashed = false
    }

    private companion object {
        const val YOUTUBE_URL = "https://m.youtube.com/"
        const val LOG_PROFILE_PREFIX_LENGTH = 12
        const val TAG = "DiPlayYouTube"
    }
}
