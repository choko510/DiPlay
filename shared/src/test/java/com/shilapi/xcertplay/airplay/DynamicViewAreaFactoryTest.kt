package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicViewAreaFactoryTest {
    @Test fun defaultSplitUsesTwoEvenAreasAndClipsSafeAreaToEachViewport() {
        val display = AirPlayDisplayConfig(
            widthPixels = 1280,
            heightPixels = 720,
            safeArea = AirPlayInsets(top = 16, bottom = 10, left = 8, right = 12),
            safeAreaDrawOutside = false,
        )

        val config = DynamicViewAreaFactory.twoAreas(display)!!
        val full = config.areas[0]
        val split = config.areas[1]

        assertEquals(0, config.initialIndex)
        assertEquals(CarPlayRect(0, 0, 1280, 720), full.viewport)
        assertEquals(CarPlayRect(0, 0, 704, 720), split.viewport)
        assertEquals(CarPlayRect(8, 16, 1260, 694), full.safeArea)
        assertEquals(CarPlayRect(8, 16, 696, 694), split.safeArea)
        assertEquals(false, split.drawUIOutsideSafeArea)
    }

    @Test fun oddCanvasDimensionsAreClippedWithoutExceedingTheDisplay() {
        val config = DynamicViewAreaFactory.twoAreas(AirPlayDisplayConfig(1281, 721))!!

        assertEquals(CarPlayRect(0, 0, 1280, 720), config.areas[0].viewport)
        assertEquals(CarPlayRect(0, 0, 704, 720), config.areas[1].viewport)
        config.areas.forEach { area ->
            assertTrue(area.viewport.x + area.viewport.width <= 1281)
            assertTrue(area.viewport.y + area.viewport.height <= 721)
        }
    }

    @Test fun commonAndSpecialCanvasSizesKeepBothAreasInBoundsAndAligned() {
        listOf(640 to 480, 1280 to 720, 1920 to 1080, 1024 to 768, 768 to 800, 3440 to 1440).forEach { (width, height) ->
            val areas = DynamicViewAreaFactory.twoAreas(AirPlayDisplayConfig(width, height))!!.areas
            assertEquals(2, areas.size)
            areas.forEach { area ->
                assertEquals(0, area.viewport.x % 2)
                assertEquals(0, area.viewport.y % 2)
                assertEquals(0, area.viewport.width % 2)
                assertEquals(0, area.viewport.height % 2)
                assertTrue(area.viewport.x + area.viewport.width <= width)
                assertTrue(area.viewport.y + area.viewport.height <= height)
                assertTrue(area.safeArea.x >= area.viewport.x)
                assertTrue(area.safeArea.y >= area.viewport.y)
                assertTrue(area.safeArea.x + area.safeArea.width <= area.viewport.x + area.viewport.width)
                assertTrue(area.safeArea.y + area.safeArea.height <= area.viewport.y + area.viewport.height)
            }
        }
    }

    @Test fun invalidFractionsOrUnrepresentableSafeAreasDisableDynamicDeclaration() {
        val display = AirPlayDisplayConfig(1280, 720)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 1.0, -0.1, 1.1).forEach { fraction ->
            assertNull(DynamicViewAreaFactory.twoAreas(display, fraction))
        }
        assertNull(
            DynamicViewAreaFactory.twoAreas(
                display.copy(safeArea = AirPlayInsets(left = 900, right = 900)),
            ),
        )
    }

    @Test fun dynamicAreaConfigRejectsOutOfCanvasOddAndDuplicateViewports() {
        val full = CarPlayViewArea(CarPlayRect(0, 0, 1280, 720), CarPlayRect(0, 0, 1280, 720))
        assertFails { DynamicViewAreaConfig(listOf(full, full)) }

        val outside = DynamicViewAreaConfig(
            listOf(CarPlayViewArea(CarPlayRect(0, 0, 1282, 720), CarPlayRect(0, 0, 1282, 720))),
        )
        assertFails { AirPlayDisplayConfig(1280, 720, dynamicViewAreas = outside) }

        val odd = DynamicViewAreaConfig(
            listOf(CarPlayViewArea(CarPlayRect(1, 0, 1278, 720), CarPlayRect(2, 0, 1276, 720))),
        )
        assertFails { AirPlayDisplayConfig(1280, 720, dynamicViewAreas = odd) }
    }

    @Test fun constructorDefensivelyCopiesAreaListAndValidatesInitialIndex() {
        val viewport = CarPlayViewArea(CarPlayRect(0, 0, 1280, 720), CarPlayRect(0, 0, 1280, 720))
        val source = mutableListOf(viewport)
        val config = DynamicViewAreaConfig(source)
        source.clear()
        assertEquals(listOf(viewport), config.areas)
        assertFails { DynamicViewAreaConfig(listOf(viewport), initialIndex = 1) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid dynamic ViewArea geometry to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
