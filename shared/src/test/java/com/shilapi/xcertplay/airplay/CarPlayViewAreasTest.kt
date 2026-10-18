package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayViewAreasTest {
    @Test
    fun fullAndSplitAreasShareOneDisplayAndKeepSafeAreasInsideGeometry() {
        val display = AirPlayDisplayConfig(
            widthPixels = 1920,
            heightPixels = 1080,
            safeArea = AirPlayInsets(top = 20, bottom = 30, left = 40, right = 30),
            safeAreaDrawOutside = false,
        )
        val areas = CarPlayViewAreaPlanner.fullAndSplit(display)

        assertNotNull(areas)
        areas ?: return
        assertEquals(0, areas.initialIndex)
        assertTrue(areas.isValidFor(display.widthPixels, display.heightPixels))
        assertEquals(2, areas.areas.size)
        assertEquals(CarPlayViewAreaRect(1920, 1080), areas.areas[0].viewArea)
        assertEquals(CarPlayViewAreaRect(1850, 1030, 40, 20), areas.areas[0].safeArea)
        assertEquals(CarPlayViewAreaRect(1056, 1080), areas.areas[1].viewArea)
        assertEquals(CarPlayViewAreaRect(1016, 1030, 40, 20), areas.areas[1].safeArea)
        assertFalse(areas.areas[1].drawUiOutsideSafeArea)
    }

    @Test
    fun splitGeometryUsesTheScaledCanvasAndPreservesRightHandDriveOutsideTheAreaModel() {
        val scaled = CarPlayDisplayScale.apply(AirPlayDisplayConfig(1280, 720), 8)
        val areas = CarPlayViewAreaPlanner.fullAndSplit(scaled, splitFraction = 0.60f)
        val uiScaled = CarPlayUiScale.apply(scaled, 85)
        val uiScaledAreas = CarPlayViewAreaPlanner.fullAndSplit(uiScaled)

        assertEquals(1024, scaled.widthPixels)
        assertEquals(576, scaled.heightPixels)
        assertNotNull(areas)
        areas ?: return
        assertEquals(614, areas.areas[1].viewArea.widthPixels)
        assertTrue(areas.isValidFor(scaled.widthPixels, scaled.heightPixels))
        assertEquals(1204, uiScaled.widthPixels)
        assertNotNull(uiScaledAreas)
        uiScaledAreas ?: return
        assertEquals(662, uiScaledAreas.areas[1].viewArea.widthPixels)
        val config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = scaled.copy(
                widthPhysicalMm = 170,
                heightPhysicalMm = 95,
                advertisedViewAreas = areas,
            ),
            rightHandDrive = true,
        )

        val info = AirPlayInfoPlist.build(config)
        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val viewAreas = display["viewAreas"] as List<*>
        assertEquals(true, info["rightHandDrive"])
        assertEquals(2, viewAreas.size)
        assertEquals(170, display["widthPhysical"])
        assertEquals(95, display["heightPhysical"])
        assertEquals(614, ((viewAreas[1] as Map<*, *>)["widthPixels"]))
    }

    @Test
    fun invalidDimensionsOrInsetsDoNotProduceSplitAreaModel() {
        assertNull(CarPlayViewAreaPlanner.fullAndSplit(AirPlayDisplayConfig(0, 720)))
        assertNull(
            CarPlayViewAreaPlanner.fullAndSplit(
                AirPlayDisplayConfig(1280, 720, safeArea = AirPlayInsets(left = 700, right = 700)),
            ),
        )
        assertNull(
            CarPlayViewAreaPlanner.fullAndSplit(
                AirPlayDisplayConfig(1280, 720, viewArea = AirPlayInsets(left = 100, right = 100), safeArea = AirPlayInsets()),
            ),
        )
    }

    @Test
    fun splitFractionIsBoundedAndNonFiniteValuesUseTheDefault() {
        val display = AirPlayDisplayConfig(1920, 1080)

        assertEquals(960, CarPlayViewAreaPlanner.fullAndSplit(display, 0.1f)?.areas?.get(1)?.viewArea?.widthPixels)
        assertEquals(1152, CarPlayViewAreaPlanner.fullAndSplit(display, 0.9f)?.areas?.get(1)?.viewArea?.widthPixels)
        assertEquals(
            1056,
            CarPlayViewAreaPlanner.fullAndSplit(display, Float.NaN)?.areas?.get(1)?.viewArea?.widthPixels,
        )
        assertEquals(
            1056,
            CarPlayViewAreaPlanner.fullAndSplit(display, Float.POSITIVE_INFINITY)?.areas?.get(1)?.viewArea?.widthPixels,
        )
    }

    @Test
    fun invalidOptionalAreaSetFallsBackToLegacySingleArea() {
        val invalid = CarPlayViewAreas(
            areas = listOf(
                CarPlayViewArea(
                    viewArea = CarPlayViewAreaRect(1280, 720),
                    safeArea = CarPlayViewAreaRect(1280, 720),
                    drawUiOutsideSafeArea = true,
                ),
            ),
            initialIndex = 1,
        )
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(1280, 720, advertisedViewAreas = invalid),
            ),
        )
        val display = (info["displays"] as List<*>).single() as Map<*, *>

        assertEquals(0, display["initialViewArea"])
        assertEquals(1, (display["viewAreas"] as List<*>).size)
    }

    @Test
    fun multiAreaDeclarationIsOptInAndKeepsOneAreaByDefault() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(1280, 720),
        )
        val info = AirPlayInfoPlist.build(base)
        val display = (info["displays"] as List<*>).single() as Map<*, *>

        assertEquals(1, (display["viewAreas"] as List<*>).size)
        assertEquals(0, display["initialViewArea"])
        assertNull(base.main.advertisedViewAreas)
    }
}
