package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CarPlayRenderGeometryTest {
    @Test fun fitCenterMapsVisibleImageAndRejectsLetterboxTouches() {
        val geometry = CarPlayRenderGeometry.create(
            canvasWidth = 1280,
            canvasHeight = 720,
            sourceWidth = 1280,
            sourceHeight = 720,
            viewWidth = 704,
            viewHeight = 720,
        )!!

        assertNull(geometry.mapViewPoint(352.0, 10.0))
        assertPoint(0.5, 0.5, geometry.mapViewPoint(352.0, 360.0))
        assertPoint(0.0, 0.0, geometry.mapViewPoint(0.0, 162.0))
        assertPoint(1.0, 1.0, geometry.mapViewPoint(704.0, 558.0))
    }

    @Test fun declaredViewportMapsToItsCanvasCoordinates() {
        val geometry = CarPlayRenderGeometry.create(
            canvasWidth = 1280,
            canvasHeight = 720,
            sourceWidth = 704,
            sourceHeight = 720,
            viewWidth = 704,
            viewHeight = 720,
            viewArea = CarPlayRenderRect(0, 0, 704, 720),
        )!!

        assertPoint(704.0 / 1280.0, 1.0, geometry.mapViewPoint(704.0, 720.0))
    }

    @Test fun rotatedGeometryProducesMatchingTouchAndTextureTransforms() {
        val geometry = CarPlayRenderGeometry.create(
            canvasWidth = 720,
            canvasHeight = 1280,
            sourceWidth = 1280,
            sourceHeight = 720,
            viewWidth = 720,
            viewHeight = 1280,
            rotationDegrees = 90,
        )!!

        assertPoint(0.5, 0.5, geometry.mapViewPoint(360.0, 640.0))
        assertArrayEquals(floatArrayOf(0f, -1f, 720f, 1f, 0f, 0f, 0f, 0f, 1f), geometry.textureMatrixValues(), 0.0001f)
    }

    @Test fun invalidOrOutOfCanvasGeometryIsRejected() {
        assertNull(CarPlayRenderGeometry.create(0, 720, 1280, 720, 704, 720))
        assertNull(
            CarPlayRenderGeometry.create(
                canvasWidth = 1280,
                canvasHeight = 720,
                sourceWidth = 1280,
                sourceHeight = 720,
                viewWidth = 704,
                viewHeight = 720,
                viewArea = CarPlayRenderRect(0, 0, 1400, 720),
            ),
        )
        assertNotNull(CarPlayRenderGeometry.create(1280, 720, 1280, 720, 704, 720))
    }

    private fun assertPoint(x: Double, y: Double, actual: CarPlayRenderPoint?) {
        assertNotNull(actual)
        assertEquals(x, actual!!.x, 0.00000001)
        assertEquals(y, actual.y, 0.00000001)
    }
}
