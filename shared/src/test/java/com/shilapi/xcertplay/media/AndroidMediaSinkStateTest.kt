package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioOwnerToken
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.VideoRecoveryDispatchResult
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class AndroidMediaSinkStateTest {
    @Test fun eachVideoDecoderOwnsItsOutputBufferInfo() {
        val sink = AndroidMediaSink()
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)

        try {
            val mainDecoder = checkNotNull(decoderFactory.invoke(sink, 110))
            val altDecoder = checkNotNull(decoderFactory.invoke(sink, 111))
            val field = mainDecoder.javaClass.getDeclaredField("outputBufferInfo").apply { isAccessible = true }
            val mainInfo = field.get(mainDecoder)
            val altInfo = field.get(altDecoder)

            assertTrue(mainInfo is MediaCodec.BufferInfo)
            assertTrue(altInfo is MediaCodec.BufferInfo)
            assertNotSame(mainInfo, altInfo)
        } finally {
            sink.close()
        }
    }

    @Test fun changingSurfaceResetsPendingOutputWaitBeforeRetirementAck() {
        val oldSurface = ReflectionHelpers.callConstructor(Surface::class.java)
        val newSurface = ReflectionHelpers.callConstructor(Surface::class.java)
        val sink = AndroidMediaSink(surface = oldSurface)
        val type = 110
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        sink.onScreenStreamActive(type, true)
        val decoder = checkNotNull(decoderFactory.invoke(sink, type))
        val pending = privateField(decoder, "pendingOutputState") as VideoPendingOutputState
        pending.onInputQueued(System.nanoTime())
        val detached = CountDownLatch(1)

        try {
            sink.setSurface(type, newSurface)
            sink.clearSurface(type, oldSurface) { detached.countDown() }

            assertTrue(detached.await(2, TimeUnit.SECONDS))
            assertEquals(0, pending.pendingOutputCount)
            assertSame(newSurface, privateField(decoder, "outputSurface"))
        } finally {
            sink.close()
            runCatching { oldSurface.release() }
            runCatching { newSurface.release() }
        }
    }

    @Test fun sinkSurfaceRecoveryDefaultsOffAndRequiresExplicitTestOptIn() {
        val disabledSink = AndroidMediaSink()
        val enabledSink = AndroidMediaSink().apply { surfaceRecoveryEnabledForTest = true }
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        disabledSink.onScreenStreamActive(110, true)
        enabledSink.onScreenStreamActive(110, true)

        try {
            val disabledDecoder = checkNotNull(decoderFactory.invoke(disabledSink, 110))
            val enabledDecoder = checkNotNull(decoderFactory.invoke(enabledSink, 110))
            val disabledPolicy = privateField(disabledDecoder, "surfaceRecoveryPolicy") as VideoSurfaceRecoveryPolicy
            val enabledPolicy = privateField(enabledDecoder, "surfaceRecoveryPolicy") as VideoSurfaceRecoveryPolicy

            assertFalse(disabledPolicy.enabled)
            assertTrue(enabledPolicy.enabled)
        } finally {
            disabledSink.close()
            enabledSink.close()
        }
    }

    @Test fun retiredVideoObserverCannotClaimAReplacementStreamFrame() {
        val generations = VideoStreamGenerations()
        val oldMain = generations.advance(110)
        val alt = generations.advance(111)
        val newMain = generations.advance(110)

        assertFalse(generations.isCurrent(110, oldMain))
        assertTrue(generations.isCurrent(110, newMain))
        assertTrue(generations.isCurrent(111, alt))
    }

    @Test fun recoveryRequestsForMainAndAltDoNotShareAnInFlightGate() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        val mainStarted = CountDownLatch(1)
        val allowMain = CountDownLatch(1)
        val altCompleted = CountDownLatch(1)
        val requestRecovery = AndroidMediaSink::class.java.getDeclaredMethod(
            "requestVideoRecovery",
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        sink.setVideoRecoveryHandler(110) {
            mainStarted.countDown()
            check(allowMain.await(5, TimeUnit.SECONDS))
        }
        sink.setVideoRecoveryHandler(111) { altCompleted.countDown() }

        try {
            requestRecovery.invoke(sink, 110, sink.videoStreamGenerationForTest(110))
            assertTrue(mainStarted.await(5, TimeUnit.SECONDS))
            requestRecovery.invoke(sink, 111, sink.videoStreamGenerationForTest(111))
            assertTrue(altCompleted.await(5, TimeUnit.SECONDS))
        } finally {
            allowMain.countDown()
            sink.close()
        }
    }

    @Test fun recoveryMetricsSeparateMainAltSendResultsAndStaleGenerationDrops() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        val mainCallback = CountDownLatch(1)
        val altCallback = CountDownLatch(1)
        val requestRecovery = AndroidMediaSink::class.java.getDeclaredMethod(
            "requestVideoRecovery",
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        sink.setVideoRecoveryResultHandler(110) {
            mainCallback.countDown()
            VideoRecoveryDispatchResult.SENT
        }
        sink.setVideoRecoveryResultHandler(111) {
            altCallback.countDown()
            VideoRecoveryDispatchResult.SEND_FAILED
        }

        try {
            requestRecovery.invoke(sink, 110, sink.videoStreamGenerationForTest(110))
            requestRecovery.invoke(sink, 111, sink.videoStreamGenerationForTest(111))
            assertTrue(mainCallback.await(2, TimeUnit.SECONDS))
            assertTrue(altCallback.await(2, TimeUnit.SECONDS))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var main = sink.videoRecoveryMetricsForTest(110)
            var alt = sink.videoRecoveryMetricsForTest(111)
            while (System.nanoTime() < deadline &&
                (main.queued == 0L || main.sent == 0L || alt.queued == 0L || alt.sendFailed == 0L)
            ) {
                Thread.yield()
                main = sink.videoRecoveryMetricsForTest(110)
                alt = sink.videoRecoveryMetricsForTest(111)
            }

            assertEquals(1L, main.attempts)
            assertEquals(1L, main.queued)
            assertEquals(1L, main.sent)
            assertEquals(0L, main.sendFailed)
            assertFalse(main.toDiagnosticString().contains("uuid"))
            assertEquals(1L, alt.attempts)
            assertEquals(1L, alt.queued)
            assertEquals(0L, alt.sent)
            assertEquals(1L, alt.sendFailed)

            val altSkipped = CountDownLatch(1)
            sink.setVideoRecoveryResultHandler(111) {
                altSkipped.countDown()
                VideoRecoveryDispatchResult.SKIPPED
            }
            requestRecovery.invoke(sink, 111, sink.videoStreamGenerationForTest(111))
            assertTrue(altSkipped.await(2, TimeUnit.SECONDS))
            val altDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var updatedAlt = sink.videoRecoveryMetricsForTest(111)
            while (System.nanoTime() < altDeadline && (updatedAlt.queued < 2L || updatedAlt.skipped == 0L)) {
                Thread.yield()
                updatedAlt = sink.videoRecoveryMetricsForTest(111)
            }
            assertEquals(2L, updatedAlt.attempts)
            assertEquals(1L, updatedAlt.skipped)

            val oldGeneration = sink.videoStreamGenerationForTest(110)
            sink.onScreenStreamActive(110, false)
            requestRecovery.invoke(sink, 110, oldGeneration)
            val stale = sink.videoRecoveryMetricsForTest(110)
            assertEquals(2L, stale.attempts)
            assertEquals(1L, stale.staleDrops)
            assertEquals(1L, stale.sent)
        } finally {
            sink.close()
        }
    }

    @Test fun videoRecoveryQueueHasAFixedCapacityWhenWorkersAreBlocked() {
        val sink = AndroidMediaSink()
        val workersStarted = CountDownLatch(2)
        val releaseWorkers = CountDownLatch(1)
        val requestRecovery = AndroidMediaSink::class.java.getDeclaredMethod(
            "requestVideoRecovery",
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }

        try {
            for (type in 1_000..1_001) {
                sink.onScreenStreamActive(type, true)
                sink.setVideoRecoveryHandler(type) {
                    workersStarted.countDown()
                    check(releaseWorkers.await(5, TimeUnit.SECONDS))
                }
                requestRecovery.invoke(sink, type, sink.videoStreamGenerationForTest(type))
            }
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS))

            for (type in 1_002..1_049) {
                sink.onScreenStreamActive(type, true)
                sink.setVideoRecoveryHandler(type) { }
                requestRecovery.invoke(sink, type, sink.videoStreamGenerationForTest(type))
            }

            assertTrue(sink.videoRecoveryQueueDepthForTest() <= 16)
            assertTrue(sink.videoRecoveryQueueDropsForTest() > 0)
            assertTrue(field(sink, "recoveryPending").size <= 18)
        } finally {
            releaseWorkers.countDown()
            sink.close()
        }
    }

    @Test fun aRetiredVideoDecoderCannotRequestAKeyframeThroughItsReplacementHandler() {
        val sink = AndroidMediaSink()
        val type = 110
        val staleRequests = AtomicInteger()
        val currentRequest = CountDownLatch(1)
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        fun recoveryCallback(decoder: Any): () -> Unit {
            val callback = decoder.javaClass.getDeclaredField("requestKeyFrame").apply { isAccessible = true }
                .get(decoder) as Function0<*>
            return { check(callback.invoke() == Unit) }
        }

        sink.onScreenStreamActive(type, true)
        sink.setVideoRecoveryHandler(type) { staleRequests.incrementAndGet() }
        val oldDecoder = checkNotNull(decoderFactory.invoke(sink, type))
        val oldRecovery = recoveryCallback(oldDecoder)

        try {
            sink.onScreenStreamActive(type, false)
            sink.onScreenStreamActive(type, true)
            sink.setVideoRecoveryHandler(type) { currentRequest.countDown() }
            oldRecovery()

            assertEquals(0, staleRequests.get())
            assertFalse(currentRequest.await(50, TimeUnit.MILLISECONDS))

            val newRecovery = recoveryCallback(checkNotNull(decoderFactory.invoke(sink, type)))
            newRecovery()
            assertTrue(currentRequest.await(5, TimeUnit.SECONDS))
        } finally {
            sink.close()
        }
    }

    @Test fun retiredVideoDecoderCannotRequestAKeyframeThroughTheReplacementHandler() {
        val sink = AndroidMediaSink()
        val oldRequests = AtomicInteger()
        val newRequests = CountDownLatch(1)
        val type = 110
        sink.onScreenStreamActive(type, true)
        sink.setVideoRecoveryHandler(type) { oldRequests.incrementAndGet() }
        val oldGeneration = sink.videoStreamGenerationForTest(type)
        val requestRecovery = AndroidMediaSink::class.java.getDeclaredMethod(
            "requestVideoRecovery",
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }

        try {
            sink.onScreenStreamActive(type, false)
            sink.onScreenStreamActive(type, true)
            sink.setVideoRecoveryHandler(type) { newRequests.countDown() }

            requestRecovery.invoke(sink, type, oldGeneration)
            assertEquals(0, oldRequests.get())
            assertFalse(newRequests.await(50, TimeUnit.MILLISECONDS))

            requestRecovery.invoke(sink, type, sink.videoStreamGenerationForTest(type))
            assertTrue(newRequests.await(5, TimeUnit.SECONDS))
        } finally {
            sink.close()
        }
    }

    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }

    @Test fun closeCallbackFailureDoesNotSkipVideoDecoderShutdown() {
        val type = 110
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val sink = AndroidMediaSink(onScreenStreamActiveChanged = { _, active ->
            if (!active) throw IllegalStateException("injected screen-close callback failure")
        })
        sink.onScreenStreamActive(type, true)
        sink.beforeVideoDecoderStopForTest = {
            stopEntered.countDown()
            var interrupted = false
            while (true) {
                try {
                    releaseStop.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        sink.onVideoCodec(type, VideoCodec.H264)
        sink.onVideoConfig(type, byteArrayOf(1))
        val decoder = checkNotNull(videoDecoder(sink, type))

        try {
            Thread {
                try {
                    sink.close()
                } catch (error: Throwable) {
                    closeFailure.set(error)
                } finally {
                    closeFinished.countDown()
                }
            }.apply { isDaemon = true; start() }

            assertTrue("close must continue after a screen callback throws", stopEntered.await(2, TimeUnit.SECONDS))
            assertFalse("close must not return while the video decoder worker is blocked", closeFinished.await(50, TimeUnit.MILLISECONDS))
            releaseStop.countDown()
            assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
            assertNull(closeFailure.get())
            assertTrue(awaitVideoDecoderStopped(decoder))
        } finally {
            releaseStop.countDown()
            sink.close()
        }
    }

    @Test fun sinkCloseReportsPendingUntilVideoDecoderReleaseIsSafe() {
        val type = 110
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(type, true)
        sink.beforeVideoDecoderStopForTest = {
            stopEntered.countDown()
            var interrupted = false
            while (true) {
                try {
                    releaseStop.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        sink.onVideoCodec(type, VideoCodec.H264)
        sink.onVideoConfig(type, byteArrayOf(1))
        val decoder = checkNotNull(videoDecoder(sink, type))

        try {
            assertFalse(sink.closeAndAwaitResources(timeoutMillis = 50))
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
            assertFalse("a repeated close cannot report success while codec release is blocked",
                sink.closeAndAwaitResources(timeoutMillis = 50))
            releaseStop.countDown()
            assertTrue(sink.closeAndAwaitResources(timeoutMillis = 2_000))
            assertTrue(awaitVideoDecoderStopped(decoder))
            assertTrue(privateField(decoder, "surfaceReleaseSafe") as Boolean)
        } finally {
            releaseStop.countDown()
            sink.close()
        }
    }

    @Test fun replacingTheScreenClearsTheOldDecoderAndCodecBeforeTheNextConfig() {
        val sink = AndroidMediaSink()
        val type = 110

        try {
            sink.onVideoCodec(type, VideoCodec.H265)
            sink.onVideoConfig(type, byteArrayOf())
            val oldDecoder = checkNotNull(videoDecoder(sink, type))

            sink.onScreenStreamActive(type, false)

            assertNull(videoDecoder(sink, type))
            assertNull(pendingVideoCodec(sink, type))

            sink.onScreenStreamActive(type, true)
            sink.onVideoCodec(type, VideoCodec.H264)
            assertTrue(awaitVideoDecoderStopped(oldDecoder))
            sink.onVideoConfig(type, byteArrayOf())

            assertNotNull(videoDecoder(sink, type))
            assertNotSame(oldDecoder, videoDecoder(sink, type))
            assertEquals(VideoCodec.H264, pendingVideoCodec(sink, type))
        } finally {
            sink.close()
        }
    }

    @Test fun replacementWaitsForTheOldVideoDecoderStopAckWithoutBlockingTheStreamOwner() {
        val sink = AndroidMediaSink()
        val type = 110
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        sink.onScreenStreamActive(type, true)
        sink.onVideoCodec(type, VideoCodec.H264)
        sink.onVideoConfig(type, byteArrayOf(1))
        val oldDecoder = checkNotNull(videoDecoder(sink, type))
        sink.beforeVideoDecoderStopForTest = {
            stopEntered.countDown()
            var interrupted = false
            while (true) {
                try {
                    releaseStop.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        try {
            sink.onScreenStreamActive(type, false)
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS))

            sink.onScreenStreamActive(type, true)
            sink.onVideoCodec(type, VideoCodec.H264)
            val configReturned = CountDownLatch(1)
            Thread {
                sink.onVideoConfig(type, byteArrayOf(2))
                configReturned.countDown()
            }.apply { isDaemon = true; start() }
            assertTrue(configReturned.await(1, TimeUnit.SECONDS))
            assertNull(videoDecoder(sink, type))

            releaseStop.countDown()
            assertTrue(awaitVideoDecoderStopped(oldDecoder))
            sink.onVideoConfig(type, byteArrayOf(2))
            val replacement = videoDecoder(sink, type)
            assertNotNull(replacement)
            assertNotSame(oldDecoder, replacement)
        } finally {
            releaseStop.countDown()
            sink.close()
        }
    }

    @Test fun codecReleaseFailureRetainsItsSurfaceAndBlocksDecoderReplacement() {
        val surface = ReflectionHelpers.callConstructor(Surface::class.java)
        val sink = AndroidMediaSink(surface = surface)
        val type = 110
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        val releaseAttempts = AtomicInteger()
        val diagnostics = CopyOnWriteArrayList<String>()
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        sink.beforeVideoDecoderStopForTest = {
            stopEntered.countDown()
            var interrupted = false
            while (true) {
                try {
                    check(allowStop.await(5, TimeUnit.SECONDS))
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        sink.videoCodecReleaseForTest = {
            releaseAttempts.incrementAndGet()
            throw IllegalStateException("injected MediaCodec release failure")
        }
        sink.setVideoDiagnosticHandler(type) { diagnostics += it }
        sink.onScreenStreamActive(type, true)
        val oldDecoder = checkNotNull(decoderFactory.invoke(sink, type))

        try {
            sink.onScreenStreamActive(type, false)
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS))

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            oldDecoder.javaClass.getDeclaredField("decoder").apply { isAccessible = true }
                .set(oldDecoder, codec)
            allowStop.countDown()

            assertTrue(awaitVideoDecoderStopped(oldDecoder))
            assertEquals(1, releaseAttempts.get())
            assertFalse(privateField(oldDecoder, "surfaceReleaseSafe") as Boolean)
            assertSame(surface, privateField(oldDecoder, "outputSurface"))
            assertSame(oldDecoder, field(sink, "retiringVideoDecoders")[type])
            assertTrue(diagnostics.any { it.contains("state=SESSION_RESTART_REQUIRED") })

            sink.onScreenStreamActive(type, true)
            assertNull(decoderFactory.invoke(sink, type))
            assertSame(oldDecoder, field(sink, "retiringVideoDecoders")[type])

            val retryRelease = oldDecoder.javaClass.getDeclaredMethod("releaseDecoder").apply { isAccessible = true }
            assertFalse(retryRelease.invoke(oldDecoder) as Boolean)
            assertEquals(2, releaseAttempts.get())
            assertFalse(retryRelease.invoke(oldDecoder) as Boolean)
            assertEquals(2, releaseAttempts.get())
        } finally {
            allowStop.countDown()
            sink.videoCodecReleaseForTest = null
            sink.close()
            runCatching { surface.release() }
        }
    }

    @Test fun transientCodecReleaseFailureRetriesOnItsWorkerAndCompletesSurfaceRetirement() {
        val surface = ReflectionHelpers.callConstructor(Surface::class.java)
        val sink = AndroidMediaSink(surface = surface)
        val type = 110
        val releaseAttempts = AtomicInteger()
        val detached = CountDownLatch(1)
        val diagnostics = CopyOnWriteArrayList<String>()
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        sink.setVideoDiagnosticHandler(type) { diagnostics += it }
        sink.videoCodecReleaseForTest = { codec ->
            if (releaseAttempts.incrementAndGet() == 1) {
                throw IllegalStateException("injected transient release failure")
            }
            codec.release()
        }
        sink.onScreenStreamActive(type, true)
        val decoder = checkNotNull(decoderFactory.invoke(sink, type))
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder.javaClass.getDeclaredField("decoder").apply { isAccessible = true }.set(decoder, codec)

        try {
            sink.clearSurface(type, surface) { detached.countDown() }

            assertTrue(detached.await(5, TimeUnit.SECONDS))
            assertEquals(2, releaseAttempts.get())
            assertTrue(diagnostics.any { it.contains("state=RETRYING_SAFE_RELEASE") })
            assertTrue(diagnostics.any { it.contains("state=RECOVERED") })
            assertFalse(diagnostics.any { it.contains("state=SESSION_RESTART_REQUIRED") })
        } finally {
            sink.videoCodecReleaseForTest = null
            sink.close()
            runCatching { surface.release() }
        }
    }

    @Test fun mainCodecReleaseFailureDoesNotBlockAltSurfaceAcknowledgement() {
        val mainSurface = ReflectionHelpers.callConstructor(Surface::class.java)
        val altSurface = ReflectionHelpers.callConstructor(Surface::class.java)
        val sink = AndroidMediaSink()
        val mainType = 110
        val altType = 111
        val mainReleaseAttempts = AtomicInteger()
        val altReleaseAttempts = AtomicInteger()
        val mainDetached = CountDownLatch(1)
        val altDetached = CountDownLatch(1)
        val mainRestartRequired = CountDownLatch(1)
        val mainDiagnostics = CopyOnWriteArrayList<String>()
        val altDiagnostics = CopyOnWriteArrayList<String>()
        sink.setSurface(mainType, mainSurface)
        sink.setSurface(altType, altSurface)
        sink.setVideoDiagnosticHandler(mainType) {
            mainDiagnostics += it
            if (it.contains("state=SESSION_RESTART_REQUIRED")) mainRestartRequired.countDown()
        }
        sink.setVideoDiagnosticHandler(altType) { altDiagnostics += it }
        sink.onScreenStreamActive(mainType, true)
        sink.onScreenStreamActive(altType, true)
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val mainDecoder = checkNotNull(decoderFactory.invoke(sink, mainType))
        val altDecoder = checkNotNull(decoderFactory.invoke(sink, altType))
        val mainCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val altCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mainDecoder.javaClass.getDeclaredField("decoder").apply { isAccessible = true }.set(mainDecoder, mainCodec)
        altDecoder.javaClass.getDeclaredField("decoder").apply { isAccessible = true }.set(altDecoder, altCodec)
        sink.videoCodecReleaseForTest = { codec ->
            if (codec === mainCodec) {
                mainReleaseAttempts.incrementAndGet()
                throw IllegalStateException("injected MAIN release failure")
            }
            altReleaseAttempts.incrementAndGet()
            codec.release()
        }

        try {
            sink.clearSurface(mainType, mainSurface) { mainDetached.countDown() }
            sink.clearSurface(altType, altSurface) { altDetached.countDown() }

            assertTrue(altDetached.await(5, TimeUnit.SECONDS))
            assertTrue(mainRestartRequired.await(5, TimeUnit.SECONDS))
            assertFalse(mainDetached.await(100, TimeUnit.MILLISECONDS))
            assertEquals(2, mainReleaseAttempts.get())
            assertEquals(1, altReleaseAttempts.get())

            sink.onScreenStreamActive(mainType, false)
            sink.onScreenStreamActive(altType, false)
            assertTrue(awaitVideoDecoderStopped(mainDecoder))
            assertTrue(awaitVideoDecoderStopped(altDecoder))
            assertFalse(privateField(mainDecoder, "surfaceReleaseSafe") as Boolean)
            assertTrue(privateField(altDecoder, "surfaceReleaseSafe") as Boolean)
            assertSame(mainDecoder, field(sink, "retiringVideoDecoders")[mainType])
            assertNull(field(sink, "retiringVideoDecoders")[altType])
            assertTrue(mainDiagnostics.any { it.contains("state=SESSION_RESTART_REQUIRED") })
        } finally {
            sink.videoCodecReleaseForTest = null
            sink.close()
            runCatching { mainSurface.release() }
            runCatching { altSurface.release() }
        }
    }

    @Test fun stoppedVideoDecoderReleasesSurfaceConfigAndQueuedFrameReferences() {
        val sink = AndroidMediaSink()
        val type = 110
        sink.onScreenStreamActive(type, true)
        sink.onVideoCodec(type, VideoCodec.H264)
        sink.onVideoConfig(type, byteArrayOf(1, 2, 3))
        val retired = checkNotNull(videoDecoder(sink, type))

        try {
            sink.onScreenStreamActive(type, false)
            assertTrue(awaitVideoDecoderStopped(retired))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (System.nanoTime() < deadline && field(sink, "retiringVideoDecoders")[type] != null) {
                Thread.yield()
            }

            assertNull(field(sink, "retiringVideoDecoders")[type])
            assertNull(privateField(retired, "outputSurface"))
            assertNull(privateField(retired, "lastConfig"))
            assertEquals(0, (privateField(retired, "queue") as VideoDecodeQueue).queuedJobsForTest)
        } finally {
            sink.close()
        }
    }

    @Test fun clearSurfaceCallbackWaitsForTheDecoderWorkerToDetach() {
        val surface = ReflectionHelpers.callConstructor(Surface::class.java)
        val sink = AndroidMediaSink(surface = surface)
        val type = 110
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val decoder = checkNotNull(decoderFactory.invoke(sink, type))
        val detached = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable?>()
        sink.setSurface(type, surface)

        try {
            sink.clearSurface(type, surface) {
                try {
                    assertNull(privateField(decoder, "outputSurface"))
                } catch (error: Throwable) {
                    callbackFailure.set(error)
                } finally {
                    detached.countDown()
                }
            }

            assertTrue(detached.await(2, TimeUnit.SECONDS))
            assertNull(callbackFailure.get())
        } finally {
            sink.close()
            runCatching { surface.release() }
        }
    }

    @Test fun retiredVideoDecoderKeepsItsCapturedDiagnosticHandler() {
        val sink = AndroidMediaSink()
        val oldMessages = mutableListOf<String>()
        val currentMessages = mutableListOf<String>()
        sink.setVideoDiagnosticHandler(110) { oldMessages += it }
        val decoderFactory = AndroidMediaSink::class.java.getDeclaredMethod(
            "videoDecoder",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val oldDecoder = decoderFactory.invoke(sink, 110)
        sink.setVideoDiagnosticHandler(110) { currentMessages += it }
        @Suppress("UNCHECKED_CAST")
        val report = oldDecoder.javaClass.getDeclaredField("report").apply { isAccessible = true }
            .get(oldDecoder) as (String) -> Unit

        try {
            report("first frame submitted to surface")

            assertEquals(listOf("first frame submitted to surface"), oldMessages)
            assertTrue(currentMessages.isEmpty())
        } finally {
            sink.close()
        }
    }

    @Test fun staleAudioCallbacksCannotReplaceOrRemoveTheCurrentRenderer() {
        val sink = AndroidMediaSink()
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media")
        val ownerA = AudioOwnerToken(100, 1)
        val ownerB = AudioOwnerToken(100, 2)

        try {
            sink.claimAudioOwner(ownerA)
            sink.onAudioStarted(ownerA, format, 0)
            assertEquals(ownerA, rendererToken(sink, 100))

            sink.claimAudioOwner(ownerB)
            sink.onAudioRtp(ownerA, format, ByteArray(12), 0)
            sink.onAudioStarted(ownerA, format, 0)
            assertEquals(ownerA, rendererToken(sink, 100))
            assertNull(sink.audioPlaybackClock(ownerA))

            sink.onAudioStopped(ownerA)
            assertNull(rendererToken(sink, 100))
            sink.onAudioStarted(ownerB, format, 0)
            assertEquals(ownerB, rendererToken(sink, 100))
            sink.onAudioStarted(ownerA, format, 0)
            assertEquals(ownerB, rendererToken(sink, 100))

            sink.onAudioStopped(ownerA)
            sink.releaseAudioOwner(ownerA)
            assertEquals(ownerB, rendererToken(sink, 100))
            assertNull(sink.audioPlaybackClock(ownerA))
        } finally {
            sink.close()
        }
    }

    @Test fun rendererThreadStartFailurePropagatesAndAllowsARetry() {
        val sink = AndroidMediaSink()
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media")
        val token = AudioOwnerToken(100, 1)
        val threadFactoryCalls = AtomicInteger()
        sink.audioRendererThreadFactoryForTest = { task, name ->
            if (threadFactoryCalls.getAndIncrement() == 0) {
                object : Thread(task, name) {
                    override fun start() {
                        throw IllegalStateException("injected renderer worker start failure")
                    }
                }
            } else {
                Thread(task, name)
            }
        }
        sink.claimAudioOwner(token)

        try {
            try {
                sink.onAudioStarted(token, format, 0)
                fail("renderer worker start failure should be returned to the engine")
            } catch (_: IllegalStateException) {
                // Expected injected start failure.
            }
            assertNull(rendererToken(sink, 100))

            sink.audioRendererThreadFactoryForTest = null
            sink.onAudioStarted(token, format, 0)
            assertEquals(token, rendererToken(sink, 100))
        } finally {
            sink.close()
        }
    }

    @Test fun staleMicrophoneStopCannotRemoveTheCurrentUplink() {
        val sink = AndroidMediaSink()
        val ownerA = AudioOwnerToken(100, 1)
        val ownerB = AudioOwnerToken(100, 2)
        val uplink = MicrophoneUplink(
            MicrophoneConfig(
                audioType = "telephony",
                sampleRate = 16_000,
                channels = 1,
                payloadType = 100,
                frameMillis = 20,
                host = InetAddress.getLoopbackAddress(),
                port = 1,
                key = ByteArray(32),
            ),
        )
        val entryType = AndroidMediaSink::class.java.declaredClasses.single {
            it.simpleName == "MicrophoneEntry"
        }
        val entry = entryType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(ownerB, uplink)
        val uplinksField = AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val uplinks = uplinksField.get(sink) as MutableMap<Int, Any>
        val ownersField = AndroidMediaSink::class.java.getDeclaredField("activeAudioOwners").apply {
            isAccessible = true
        }
        val owners = ownersField.get(sink) as Map<*, *>
        uplinks[100] = entry
        sink.claimAudioOwner(ownerB)

        try {
            sink.onMicrophoneStopped(ownerA)
            assertSame(entry, uplinks[100])
            sink.releaseAudioOwner(ownerA)
            assertEquals(ownerB, owners[100])
            sink.onMicrophoneStopped(ownerB)
            assertNull(uplinks[100])
        } finally {
            sink.close()
        }
    }

    @Test fun sameTokenInactiveMicrophoneUplinkIsRecreated() {
        val sink = AndroidMediaSink()
        val token = AudioOwnerToken(100, 1)
        val inactive = FakeMicrophoneUplink()
        val replacement = FakeMicrophoneUplink()
        val creations = AtomicInteger()
        sink.microphoneUplinkFactoryForTest = { _, _ ->
            creations.incrementAndGet()
            replacement
        }
        val config = microphoneConfig()
        val entryType = AndroidMediaSink::class.java.declaredClasses.single {
            it.simpleName == "MicrophoneEntry"
        }
        val entry = entryType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(token, inactive)
        val uplinksField = AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val uplinks = uplinksField.get(sink) as MutableMap<Int, Any>
        sink.claimAudioOwner(token)
        uplinks[100] = entry

        try {
            assertTrue(sink.onMicrophoneStarted(token, config))

            assertEquals(1, creations.get())
            assertTrue(inactive.closed)
            assertSame(replacement, microphoneUplink(sink, 100))
            assertTrue(replacement.isActive)
        } finally {
            sink.close()
        }
    }

    @Test fun sameTokenActiveMicrophoneUplinkIsNotStartedTwice() {
        val sink = AndroidMediaSink()
        val token = AudioOwnerToken(100, 1)
        val active = FakeMicrophoneUplink().apply { activate() }
        val creations = AtomicInteger()
        sink.microphoneUplinkFactoryForTest = { _, _ ->
            creations.incrementAndGet()
            FakeMicrophoneUplink()
        }
        val entryType = AndroidMediaSink::class.java.declaredClasses.single {
            it.simpleName == "MicrophoneEntry"
        }
        val entry = entryType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(token, active)
        val uplinksField = AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val uplinks = uplinksField.get(sink) as MutableMap<Int, Any>
        sink.claimAudioOwner(token)
        uplinks[100] = entry

        try {
            assertTrue(sink.onMicrophoneStarted(token, microphoneConfig()))

            assertEquals(0, creations.get())
            assertSame(active, microphoneUplink(sink, 100))
            assertFalse(active.closed)
        } finally {
            sink.close()
        }
    }

    @Test fun sinkCloseWaitsForMicrophoneUplinkToFinish() {
        val sink = AndroidMediaSink()
        val token = AudioOwnerToken(100, 1)
        val uplink = DelayedMicrophoneUplink()
        sink.microphoneUplinkFactoryForTest = { _, _ -> uplink }
        sink.claimAudioOwner(token)

        assertTrue(sink.onMicrophoneStarted(token, microphoneConfig()))
        assertFalse(sink.closeAndAwaitResources(timeoutMillis = 50))
        assertTrue(uplink.closed)

        uplink.stopped = true
        assertTrue(sink.closeAndAwaitResources(timeoutMillis = 500))
    }

    private fun microphoneConfig() = MicrophoneConfig(
        audioType = "telephony",
        sampleRate = 16_000,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 1,
        key = ByteArray(32),
    )

    private fun microphoneUplink(sink: AndroidMediaSink, type: Int): MicrophoneUplinkController? {
        val field = AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply {
            isAccessible = true
        }
        val entry = (field.get(sink) as Map<*, *>)[type] ?: return null
        val uplinkField = entry.javaClass.getDeclaredField("uplink").apply { isAccessible = true }
        return uplinkField.get(entry) as MicrophoneUplinkController
    }

    private class FakeMicrophoneUplink : MicrophoneUplinkController {
        private var active = false
        var closed = false
            private set

        override val isActive: Boolean
            get() = active && !closed

        override fun start(): Boolean = true

        override fun activate() {
            active = true
        }

        override fun close() {
            active = false
            closed = true
        }
    }

    private class DelayedMicrophoneUplink : MicrophoneUplinkController {
        override val isActive: Boolean = true
        var closed = false
            private set
        var stopped = false

        override fun start(): Boolean = true

        override fun activate() = Unit

        override fun close() {
            closed = true
        }

        override fun awaitStopped(timeoutMillis: Long): Boolean = stopped
    }

    private fun rendererToken(sink: AndroidMediaSink, type: Int): AudioOwnerToken? {
        val field = AndroidMediaSink::class.java.getDeclaredField("audioRenderers").apply {
            isAccessible = true
        }
        val renderers = field.get(sink) as Map<*, *>
        val entry = renderers[type] ?: return null
        val token = entry.javaClass.getDeclaredField("token").apply { isAccessible = true }
        return token.get(entry) as AudioOwnerToken
    }

    private fun videoDecoder(sink: AndroidMediaSink, type: Int): Any? =
        (field(sink, "videoDecoders")[type])

    private fun awaitVideoDecoderStopped(decoder: Any, timeoutMillis: Long = 2_000): Boolean {
        val awaitStopped = decoder.javaClass.getDeclaredMethod(
            "awaitStopped",
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return awaitStopped.invoke(decoder, timeoutMillis) as Boolean
    }

    private fun pendingVideoCodec(sink: AndroidMediaSink, type: Int): VideoCodec? =
        (field(sink, "pendingVideoCodec")[type]) as? VideoCodec

    private fun field(sink: AndroidMediaSink, name: String): Map<*, *> =
        sink.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(sink) as Map<*, *>

    private fun privateField(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
}
