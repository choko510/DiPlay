package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoOutputGeometryTest {
    @Test fun cropCoordinatesAreInclusiveAndProduceVisibleDimensions() {
        val geometry = VideoOutputGeometry.create(
            codedWidth = 1280,
            codedHeight = 736,
            cropLeft = 0,
            cropTop = 8,
            cropRight = 703,
            cropBottom = 727,
        )!!

        assertEquals(1280, geometry.codedWidth)
        assertEquals(704, geometry.visibleWidth)
        assertEquals(720, geometry.visibleHeight)
        assertEquals(704, geometry.displayWidth)
        assertEquals(720, geometry.displayHeight)
    }

    @Test fun omittedCropUsesTheWholeFrameAndRotationSwapsDisplayedDimensions() {
        val geometry = VideoOutputGeometry.create(1280, 720, rotationDegrees = 90)!!

        assertEquals(1280, geometry.visibleWidth)
        assertEquals(720, geometry.visibleHeight)
        assertEquals(720, geometry.displayWidth)
        assertEquals(1280, geometry.displayHeight)
        assertEquals(270, VideoOutputGeometry.create(1280, 720, rotationDegrees = -90)?.rotationDegrees)
    }

    @Test fun malformedCropAndRotationAreRejected() {
        assertNull(VideoOutputGeometry.create(0, 720))
        assertNull(VideoOutputGeometry.create(1280, 720, cropLeft = 704, cropRight = 703))
        assertNull(VideoOutputGeometry.create(1280, 720, cropBottom = 720))
        assertNull(VideoOutputGeometry.create(1280, 720, rotationDegrees = 45))
    }

    @Test fun adaptiveBoundsAreEnabledOnlyWhenCodecCanHandleTheNegotiatedCanvas() {
        assertEquals(
            AdaptivePlaybackBounds(1280, 720),
            boundedAdaptivePlaybackBounds(true, 1280, 720, 1920, 1080),
        )
        assertNull(boundedAdaptivePlaybackBounds(false, 1280, 720, 1920, 1080))
        assertNull(boundedAdaptivePlaybackBounds(true, 1280, 720, 1279, 1080))
        assertNull(boundedAdaptivePlaybackBounds(true, 1280, 720, 1920, 719))
    }

    @Test fun nonAdaptiveSizeChangesReconfigureOnlyTheDecoderWithinDeclaredBounds() {
        val geometry = VideoOutputGeometry.create(704, 720)!!
        assertEquals(
            VideoOutputSizeChange.Reconfigure(704, 720),
            VideoOutputSizePolicy.evaluate(geometry, false, 1280, 720, 1280, 720),
        )
        assertEquals(
            VideoOutputSizeChange.Adaptive,
            VideoOutputSizePolicy.evaluate(geometry, true, 1280, 720, 1280, 720),
        )
        val oversized = VideoOutputGeometry.create(1920, 1080)!!
        assertEquals(
            VideoOutputSizeChange.OutOfBounds,
            VideoOutputSizePolicy.evaluate(oversized, false, 1280, 720, 1280, 720),
        )
        assertEquals(
            VideoOutputSizeChange.Unchanged,
            VideoOutputSizePolicy.evaluate(
                VideoOutputGeometry.create(1280, 720)!!,
                adaptivePlaybackEnabled = false,
                configuredWidth = 1280,
                configuredHeight = 720,
                maximumWidth = 1280,
                maximumHeight = 720,
            ),
        )
    }
}
