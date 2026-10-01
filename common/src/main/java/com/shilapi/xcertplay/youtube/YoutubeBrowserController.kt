package com.shilapi.xcertplay.youtube

import android.content.Context
import android.graphics.Color
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

internal object YoutubePopupSessionFactory {
    fun create(settings: GeckoSessionSettings): GeckoSession =
        GeckoSession(settings).also { popup ->
            check(!popup.isOpen) { "Gecko must open the returned popup session" }
        }
}

internal class YoutubeBrowserController(
    context: Context,
    private val onLoadStateChanged: (YoutubeLoadState) -> Unit,
    private val onCanGoBackChanged: (Boolean) -> Unit,
    private val onFullscreenChanged: (Boolean) -> Unit,
    private val onFirstVisiblePaint: () -> Unit,
    private val onVisiblePaintTimeout: () -> Unit,
) {
    val view = GeckoView(context)

    private var runtime: org.mozilla.geckoview.GeckoRuntime? = null
    private var primarySession: GeckoSession? = null
    private var popupSession: GeckoSession? = null
    private var session: GeckoSession? = null
    private var profile: YoutubeDeviceProfile? = null
    private val uiStatePublisher = YoutubeBrowserUiStatePublisher(
        onLoadStateChanged = onLoadStateChanged,
        onCanGoBackChanged = onCanGoBackChanged,
    )
    private val canGoBack: Boolean
        get() = uiStatePublisher.canGoBack
    private var primaryCanGoBack = false
    private var popupCanGoBack = false
    private var primaryLoadState = YoutubeLoadState.LOADING
    private val popupGate = YoutubePopupGate()
    private var active = true
    private var crashed = false
    private var lifecycle = YoutubeBrowserLifecycle.ACTIVE
    private var pageReadyTraceCookie: Int? = null
    private var warmSessionReopenPending = false
    private var awaitingVisiblePaint = false
    private var paintStatusResetObserved = false
    private var resumedFromSuspendedSession = false

    val isSuspendedForReuse: Boolean
        get() = lifecycle == YoutubeBrowserLifecycle.SUSPENDED

    fun open(deviceProfile: YoutubeDeviceProfile) {
        when (
            YoutubeBrowserSessionReusePolicy.decide(
                lifecycle = lifecycle,
                currentContextId = profile?.youtubeContextId,
                requestedContextId = deviceProfile.youtubeContextId,
                primarySessionOpen = primarySession?.isOpen == true,
                crashed = crashed,
            )
        ) {
            YoutubeBrowserOpenDecision.PRESERVE_ACTIVE_SESSION -> {
                setActive(active)
                return
            }
            YoutubeBrowserOpenDecision.REACTIVATE_SUSPENDED_SESSION -> {
                setLifecycle(YoutubeBrowserLifecycle.ACTIVE)
                warmSessionReopenPending = true
                awaitingVisiblePaint = true
                return
            }
            YoutubeBrowserOpenDecision.CREATE_SESSION -> Unit
            YoutubeBrowserOpenDecision.IGNORE_DESTROYED_CONTROLLER -> return
        }

        closeSession()
        setLifecycle(YoutubeBrowserLifecycle.ACTIVE)
        uiStatePublisher.invalidatePublishedState()
        warmSessionReopenPending = false
        profile = deviceProfile
        crashed = false
        awaitingVisiblePaint = true
        paintStatusResetObserved = false
        resumedFromSuspendedSession = false
        setCanGoBack(false)
        primaryLoadState = YoutubeLoadState.LOADING
        uiStatePublisher.updateLoadState(YoutubeLoadState.LOADING)
        try {
            val geckoRuntime = runtime ?: YoutubeGeckoRuntimeProvider.get(view.context).also {
                runtime = it
            }
            val newSession = createSession(deviceProfile)
            primarySession = newSession
            session = newSession
            configureDelegates(newSession)
            SplitPerformanceTracer.section("diplay.gecko.session_open") {
                newSession.open(geckoRuntime)
            }
            view.setSession(newSession)
            view.coverUntilFirstPaint(Color.BLACK)
            newSession.setActive(active)
            pageReadyTraceCookie = SplitPerformanceTracer.beginAsync("diplay.gecko.page_ready")
            SplitPerformanceTracer.increment(SplitPerformanceCounter.YOUTUBE_LOAD_URI_CALLS)
            newSession.loadUri(YOUTUBE_URL)
            Log.i(TAG, "Gecko session opened profile=${deviceProfile.profileKey.take(LOG_PROFILE_PREFIX_LENGTH)}")
        } catch (_: RuntimeException) {
            onOpenFailed()
        } catch (_: LinkageError) {
            onOpenFailed()
        }
    }

    fun suspendForReuse() {
        if (lifecycle == YoutubeBrowserLifecycle.DESTROYED) return
        setLifecycle(YoutubeBrowserLifecycle.SUSPENDED)
        warmSessionReopenPending = false
        resumedFromSuspendedSession = true
        active = false
        popupSession?.let(::closePopupSession)
        primarySession?.let { primary ->
            cleanup("primary fullscreen exit") {
                if (primary.isOpen) primary.exitFullScreen()
            }
        }
        setActive(false)
    }

    fun setActive(isActive: Boolean) {
        if (lifecycle == YoutubeBrowserLifecycle.DESTROYED) return
        if (!isActive && active && primarySession != null) {
            awaitingVisiblePaint = true
            resumedFromSuspendedSession = true
        }
        if (isActive && !active && primarySession != null) awaitingVisiblePaint = true
        val traceWarmReopen = isActive && warmSessionReopenPending
        val wasActive = active
        if (!isActive) {
            active = false
            uiStatePublisher.setActive(false)
        }
        val activateSessions = {
            primarySession?.setActive(isActive && popupSession == null)
            popupSession?.setActive(isActive)
        }
        try {
            if (traceWarmReopen) {
                SplitPerformanceTracer.section("diplay.split.warm_reopen", activateSessions)
            } else {
                activateSessions()
            }
        } catch (_: RuntimeException) {
            Log.w(TAG, "Gecko session activation failed")
            return
        } catch (_: LinkageError) {
            Log.w(TAG, "Gecko session activation failed")
            return
        }
        if (isActive) {
            active = true
            uiStatePublisher.setActive(true)
            if (traceWarmReopen) {
                warmSessionReopenPending = false
                SplitPerformanceTracer.increment(SplitPerformanceCounter.WARM_SESSION_REOPENS)
            }
            if (!wasActive || awaitingVisiblePaint) scheduleVisiblePaintTimeout()
        } else {
            cancelVisiblePaintTimeout()
        }
    }

    fun reload() {
        if (crashed || primarySession == null) {
            profile?.let(::open)
        } else {
            session?.reload()
        }
    }

    fun goBack(): Boolean {
        val popup = popupSession
        if (popup != null && !popupCanGoBack) {
            closePopupSession(popup)
            return true
        }
        if (!canGoBack) return false
        session?.goBack()
        return true
    }

    fun exitFullscreen() {
        session?.exitFullScreen()
    }

    fun destroy() {
        if (lifecycle == YoutubeBrowserLifecycle.DESTROYED) return
        setLifecycle(YoutubeBrowserLifecycle.DESTROYED)
        warmSessionReopenPending = false
        active = false
        uiStatePublisher.setActive(false)
        cancelVisiblePaintTimeout()
        closeSession()
        profile = null
    }

    private fun createSession(deviceProfile: YoutubeDeviceProfile): GeckoSession {
        SplitPerformanceTracer.increment(SplitPerformanceCounter.GECKO_SESSION_CREATIONS)
        return GeckoSession(createSessionSettings(deviceProfile))
    }

    private fun createSessionSettings(deviceProfile: YoutubeDeviceProfile): GeckoSessionSettings =
        GeckoSessionSettings.Builder()
            .contextId(deviceProfile.youtubeContextId)
            .suspendMediaWhenInactive(true)
            .build()

    private fun configureDelegates(geckoSession: GeckoSession) {
        geckoSession.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onFirstComposite(session: GeckoSession) {
                SplitPerformanceTracer.increment(SplitPerformanceCounter.GECKO_FIRST_COMPOSITES)
                if (
                    session === primarySession &&
                    primaryLoadState == YoutubeLoadState.READY &&
                    paintStatusResetObserved
                ) {
                    completeVisiblePaint(session)
                }
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                SplitPerformanceTracer.increment(SplitPerformanceCounter.GECKO_FIRST_CONTENTFUL_PAINTS)
                completeVisiblePaint(session)
            }

            override fun onPaintStatusReset(session: GeckoSession) {
                SplitPerformanceTracer.increment(SplitPerformanceCounter.GECKO_PAINT_STATUS_RESETS)
                if (session === primarySession) paintStatusResetObserved = true
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (isCurrentSession(session) && canPublishUiState(isCurrentSession(session))) {
                    onFullscreenChanged(fullScreen)
                }
            }

            override fun onCrash(session: GeckoSession) {
                onSessionCrashed(session)
            }

            override fun onKill(session: GeckoSession) {
                onSessionCrashed(session)
            }

            override fun onCloseRequest(session: GeckoSession) {
                if (popupSession === session) closePopupSession(session)
            }
        })
        geckoSession.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (!isKnownSession(session)) return
                if (isCurrentSession(session)) {
                    val host = url.toUri().host ?: "unknown"
                    Log.i(TAG, "YouTube page load started host=$host")
                }
                setSessionLoadState(session, YoutubeLoadState.LOADING)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!isKnownSession(session)) return
                if (session === primarySession) finishPageReadyTrace()
                setSessionLoadState(
                    session,
                    if (success) YoutubeLoadState.READY else YoutubeLoadState.ERROR,
                )
            }
        })
        geckoSession.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                if (!isKnownSession(session)) return GeckoResult.fromValue(AllowOrDeny.DENY)
                val newWindow = request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
                return when (
                    YoutubeNavigationPolicy.decide(
                        request.uri,
                        newWindow,
                        isPopup = session === popupSession,
                    )
                ) {
                    YoutubeNavigationDecision.ALLOW_CURRENT -> null
                    YoutubeNavigationDecision.OPEN_POPUP -> {
                        if (session !== primarySession || popupSession != null || !popupGate.canOpen(request.uri)) {
                            GeckoResult.fromValue(AllowOrDeny.DENY)
                        } else {
                            null
                        }
                    }
                    YoutubeNavigationDecision.DENY -> GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: org.mozilla.geckoview.WebRequestError,
            ): GeckoResult<String>? {
                if (isKnownSession(session)) {
                    if (session === primarySession) finishPageReadyTrace()
                    setSessionLoadState(session, YoutubeLoadState.ERROR)
                }
                return null
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                when (session) {
                    primarySession -> primaryCanGoBack = canGoBack
                    popupSession -> popupCanGoBack = canGoBack
                }
                if (isCurrentSession(session)) setCanGoBack(canGoBack, session)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (session !== primarySession || popupSession != null || !popupGate.canOpen(uri)) return null
                if (!popupGate.markOpened()) return null
                val popup = createPopupSession() ?: run {
                    popupGate.close()
                    return null
                }
                if (popup.isOpen) {
                    closePopupSession(popup)
                    return null
                }
                return GeckoResult.fromValue(popup)
            }
        })
    }

    private fun createPopupSession(): GeckoSession? {
        val deviceProfile = profile ?: return null
        val popup = try {
            YoutubePopupSessionFactory.create(createSessionSettings(deviceProfile)).also {
                SplitPerformanceTracer.increment(SplitPerformanceCounter.GECKO_SESSION_CREATIONS)
            }
        } catch (_: RuntimeException) {
            return null
        } catch (_: LinkageError) {
            return null
        }
        popupSession = popup
        popupCanGoBack = false
        return try {
            configureDelegates(popup)
            check(!popup.isOpen) { "Gecko must open the returned popup session" }
            val previous = session
            if (previous != null && view.session === previous) view.releaseSession()
            session = popup
            view.setSession(popup)
            view.coverUntilFirstPaint(Color.BLACK)
            popup.setActive(active)
            primarySession?.setActive(false)
            setCanGoBack(false)
            popup
        } catch (_: RuntimeException) {
            popupGate.close()
            closePopupSession(popup)
            null
        } catch (_: LinkageError) {
            popupGate.close()
            closePopupSession(popup)
            null
        }
    }

    private fun closePopupSession(closingSession: GeckoSession, restoreLoadState: Boolean = true) {
        if (popupSession !== closingSession) return
        popupSession = null
        popupCanGoBack = false
        popupGate.close()
        val wasCurrent = session === closingSession
        if (wasCurrent) session = primarySession
        cleanup("popup content delegate") { closingSession.setContentDelegate(null) }
        cleanup("popup progress delegate") { closingSession.setProgressDelegate(null) }
        cleanup("popup navigation delegate") { closingSession.setNavigationDelegate(null) }
        cleanup("popup fullscreen exit") {
            if (closingSession.isOpen) closingSession.exitFullScreen()
        }
        cleanup("popup view release") {
            if (view.session === closingSession) view.releaseSession()
        }
        cleanup("popup close") {
            if (closingSession.isOpen) {
                SplitPerformanceTracer.increment(SplitPerformanceCounter.GECKO_SESSION_CLOSES)
                closingSession.close()
            }
        }
        if (wasCurrent) {
            primarySession?.let { parent ->
                cleanup("primary view attach") { view.setSession(parent) }
                cleanup("primary activation") { parent.setActive(active) }
                cleanup("primary fullscreen exit") {
                    if (parent.isOpen) parent.exitFullScreen()
                }
            }
            setCanGoBack(primaryCanGoBack)
            if (canPublishUiState(isCurrentSession = true)) onFullscreenChanged(false)
            if (restoreLoadState) {
                uiStatePublisher.updateLoadState(
                    if (primarySession != null) primaryLoadState else YoutubeLoadState.ERROR,
                    isCurrentSession = true,
                )
            }
        }
    }

    private fun onSessionCrashed(crashedSession: GeckoSession) {
        val wasCurrent = isCurrentSession(crashedSession)
        when {
            popupSession === crashedSession -> {
                closePopupSession(crashedSession, restoreLoadState = false)
                uiStatePublisher.updateLoadState(
                    YoutubeCrashLoadStatePolicy.afterPopupCrash(primaryLoadState),
                    isCurrentSession = wasCurrent,
                )
                Log.w(TAG, "Gecko popup content process stopped")
            }
            primarySession === crashedSession -> {
                val presentationActive = canPublishUiState(isCurrentSession = true)
                closeSession()
                crashed = true
                primaryLoadState = YoutubeCrashLoadStatePolicy.afterPrimaryCrash()
                setCanGoBack(false)
                if (presentationActive) onFullscreenChanged(false)
                uiStatePublisher.updateLoadState(
                    primaryLoadState,
                    isCurrentSession = wasCurrent || presentationActive,
                )
                Log.w(TAG, "Gecko content process stopped")
            }
        }
    }

    private fun isCurrentSession(candidate: GeckoSession): Boolean = session === candidate && !crashed

    private fun isKnownSession(candidate: GeckoSession): Boolean =
        primarySession === candidate || popupSession === candidate

    private fun setCanGoBack(value: Boolean, owner: GeckoSession? = null) {
        if (owner != null && !isKnownSession(owner)) return
        uiStatePublisher.updateCanGoBack(
            value,
            isCurrentSession = owner == null || isCurrentSession(owner),
        )
    }

    private fun setSessionLoadState(owner: GeckoSession, state: YoutubeLoadState) {
        when (owner) {
            primarySession -> {
                primaryLoadState = state
                if (state == YoutubeLoadState.ERROR) cancelVisiblePaintTimeout()
            }
            popupSession -> Unit
            else -> return
        }
        uiStatePublisher.updateLoadState(state, isCurrentSession(owner))
    }

    private fun onOpenFailed() {
        closeSession()
        primaryLoadState = YoutubeLoadState.ERROR
        uiStatePublisher.updateCanGoBack(false)
        uiStatePublisher.updateLoadState(YoutubeLoadState.ERROR)
        Log.w(TAG, "Gecko session could not open")
    }

    private fun closeSession() {
        cancelVisiblePaintTimeout()
        finishPageReadyTrace()
        val sessions = listOfNotNull(primarySession, popupSession).distinct()
        primarySession = null
        popupSession = null
        session = null
        popupGate.close()
        primaryCanGoBack = false
        popupCanGoBack = false
        primaryLoadState = YoutubeLoadState.LOADING
        sessions.forEach(::closeGeckoSession)
        crashed = false
    }

    private fun closeGeckoSession(closingSession: GeckoSession) {
        cleanup("content delegate") { closingSession.setContentDelegate(null) }
        cleanup("progress delegate") { closingSession.setProgressDelegate(null) }
        cleanup("navigation delegate") { closingSession.setNavigationDelegate(null) }
        cleanup("view release") {
            if (view.session === closingSession) view.releaseSession()
        }
        cleanup("session close") {
            if (closingSession.isOpen) {
                SplitPerformanceTracer.increment(SplitPerformanceCounter.GECKO_SESSION_CLOSES)
                closingSession.close()
            }
        }
    }

    private fun finishPageReadyTrace() {
        SplitPerformanceTracer.endAsync("diplay.gecko.page_ready", pageReadyTraceCookie)
        pageReadyTraceCookie = null
    }

    private fun completeVisiblePaint(paintedSession: GeckoSession) {
        if (
            !awaitingVisiblePaint ||
            paintedSession !== session ||
            !isCurrentSession(paintedSession) ||
            !active ||
            (resumedFromSuspendedSession && paintedSession === primarySession && !paintStatusResetObserved) ||
            view.visibility != android.view.View.VISIBLE ||
            !view.isAttachedToWindow
        ) {
            return
        }
        cancelVisiblePaintTimeout()
        awaitingVisiblePaint = false
        paintStatusResetObserved = false
        resumedFromSuspendedSession = false
        SplitPerformanceTracer.increment(SplitPerformanceCounter.SPLIT_VISIBLE_PAINT_COMPLETIONS)
        onFirstVisiblePaint()
    }

    private fun canPublishUiState(isCurrentSession: Boolean): Boolean =
        uiStatePublisher.shouldPublish(isCurrentSession)

    private fun setLifecycle(value: YoutubeBrowserLifecycle) {
        lifecycle = value
        uiStatePublisher.setLifecycle(value)
    }

    private fun scheduleVisiblePaintTimeout() {
        cancelVisiblePaintTimeout()
        if (
            !SplitPerformanceTracer.enabled || !awaitingVisiblePaint ||
            lifecycle != YoutubeBrowserLifecycle.ACTIVE || !active || session == null
        ) {
            return
        }
        view.postDelayed(visiblePaintTimeout, VISIBLE_PAINT_TIMEOUT_MILLIS)
    }

    fun cancelVisiblePaintTimeout() {
        view.removeCallbacks(visiblePaintTimeout)
    }

    private val visiblePaintTimeout = Runnable {
        if (!awaitingVisiblePaint || lifecycle != YoutubeBrowserLifecycle.ACTIVE || !active || session == null) return@Runnable
        SplitPerformanceTracer.increment(SplitPerformanceCounter.SPLIT_VISIBLE_PAINT_TIMEOUTS)
        SplitPerformanceTracer.section("diplay.split.total.paint_missing") {}
        onVisiblePaintTimeout()
    }

    private inline fun cleanup(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Gecko cleanup failed: $operation", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Gecko cleanup failed: $operation", error)
        }
    }

    private companion object {
        const val YOUTUBE_URL = "https://m.youtube.com/"
        const val LOG_PROFILE_PREFIX_LENGTH = 12
        const val VISIBLE_PAINT_TIMEOUT_MILLIS = 10_000L
        const val TAG = "DiPlayYouTube"
    }
}
