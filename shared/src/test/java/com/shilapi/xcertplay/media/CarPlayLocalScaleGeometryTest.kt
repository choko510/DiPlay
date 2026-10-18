package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayLocalScaleGeometryTest {
    @Test
    fun wideCanvasIsCenteredWithVerticalLetterboxInPortraitViewport() {
        val geometry = CarPlayLocalScaleGeometry.fit(1920, 1080, 1056, 1080)

        assertNotNull(geometry)
        geometry ?: return
        assertEquals(1056.0, geometry.contentWidth, 0.001)
        assertEquals(594.0, geometry.contentHeight, 0.001)
        assertEquals(243.0, geometry.top, 0.001)
        assertEquals(0.5, geometry.mapViewportPoint(528.0, 540.0).x, 0.001)
        assertEquals(0.5, geometry.mapViewportPoint(528.0, 540.0).y, 0.001)
    }

    @Test
    fun sourceEdgesRoundTripAndLetterboxTouchesAreOutsideContent() {
        val geometry = CarPlayLocalScaleGeometry.fit(1920, 1080, 1056, 1080)
        assertNotNull(geometry)
        geometry ?: return

        val topLeft = geometry.mapViewportPoint(geometry.left, geometry.top)
        val bottomRight = geometry.mapViewportPoint(geometry.right, geometry.bottom)
        val letterboxTouch = geometry.mapViewportPoint(528.0, 100.0)

        assertTrue(topLeft.insideContent)
        assertEquals(0.0, topLeft.x, 0.001)
        assertEquals(0.0, topLeft.y, 0.001)
        assertTrue(bottomRight.insideContent)
        assertEquals(1.0, bottomRight.x, 0.001)
        assertEquals(1.0, bottomRight.y, 0.001)
        assertFalse(letterboxTouch.insideContent)
        assertEquals(0.0, letterboxTouch.y, 0.001)
    }

    @Test
    fun portraitCanvasUsesHorizontalLetterboxInLandscapeViewport() {
        val geometry = CarPlayLocalScaleGeometry.fit(1080, 1920, 1920, 1080)

        assertNotNull(geometry)
        geometry ?: return
        assertEquals(607.5, geometry.contentWidth, 0.001)
        assertEquals(1080.0, geometry.contentHeight, 0.001)
        assertEquals(656.25, geometry.left, 0.001)
        assertTrue(geometry.mapViewportPoint(960.0, 540.0).insideContent)
        assertFalse(geometry.mapViewportPoint(100.0, 540.0).insideContent)
    }

    @Test
    fun invalidDimensionsDoNotProduceTransformGeometry() {
        assertNull(CarPlayLocalScaleGeometry.fit(0, 1080, 1056, 1080))
        assertNull(CarPlayLocalScaleGeometry.fit(1920, 1080, 0, 1080))
    }
}
