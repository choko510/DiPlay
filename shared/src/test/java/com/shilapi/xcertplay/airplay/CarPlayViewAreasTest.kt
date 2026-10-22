package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayViewAreasTest {
    @Test
    fun fullAndSplitAreasStayOnTheSameMainDisplayAndSerializeInOrder() {
        val display = testDisplay().copy(
            safeArea = AirPlayInsets(top = 60, bottom = 30, left = 30, right = 30),
            safeAreaDrawOutside = false,
        )
        val config = testConfig(display.copy(viewAreas = CarPlayViewAreas.fullAndSplit(display)), rightHandDrive = true)
        val info = AirPlayInfoPlist.build(config)
        val displayEntry = (info["displays"] as List<*>).single() as Map<*, *>
        val areas = displayEntry["viewAreas"] as List<*>
        val full = areas[0] as Map<*, *>
        val split = areas[1] as Map<*, *>
        val splitSafe = split["safeArea"] as Map<*, *>

        assertEquals(AirPlayInfoPlist.MAIN_UUID, displayEntry["uuid"])
        assertEquals(110, displayEntry["type"])
        assertEquals(0, displayEntry["initialViewArea"])
        assertEquals(2, areas.size)
        assertEquals(1920, full["widthPixels"])
        assertEquals(1080, full["heightPixels"])
        assertEquals(1056, split["widthPixels"])
        assertEquals(1080, split["heightPixels"])
        assertEquals(30, splitSafe["originXPixels"])
        assertEquals(1026, splitSafe["widthPixels"])
        assertEquals(false, splitSafe["drawUIOutsideSafeArea"])
        assertEquals(200, displayEntry["widthPhysical"])
        assertEquals(true, info["rightHandDrive"])
    }

    @Test
    fun omittedMultiAreaModelKeepsLegacySingleAreaDeclaration() {
        val display = testDisplay().copy(
            viewArea = AirPlayInsets(top = 6, bottom = 12, left = 18, right = 24),
            safeArea = AirPlayInsets(top = 30, bottom = 60, left = 90, right = 120),
        )
        assertNull(display.viewAreas)
        val info = AirPlayInfoPlist.build(testConfig(display))
        val displayEntry = (info["displays"] as List<*>).single() as Map<*, *>
        val area = (displayEntry["viewAreas"] as List<*>).single() as Map<*, *>

        assertEquals(1, (displayEntry["viewAreas"] as List<*>).size)
        assertEquals(0, displayEntry["initialViewArea"])
        assertEquals(1878, area["widthPixels"])
        assertEquals(1062, area["heightPixels"])
    }

    @Test
    fun splitAreaKeepsItsOriginInTheFullDisplayCoordinateSpace() {
        val display = testDisplay().copy(
            viewArea = AirPlayInsets(top = 6, bottom = 12, left = 18, right = 24),
            safeArea = AirPlayInsets(top = 30, bottom = 60, left = 90, right = 120),
        )
        val model = CarPlayViewAreas.fullAndSplit(display)

        assertTrue(model.isValidFor(display.widthPixels, display.heightPixels))
        assertEquals(AirPlayInsets(top = 6, bottom = 12, left = 18, right = 24), model.areas[0].viewArea)
        assertEquals(AirPlayInsets(top = 6, bottom = 12, left = 18, right = 869), model.areas[1].viewArea)
        assertEquals(AirPlayInsets(top = 30, bottom = 60, left = 90, right = 869), model.areas[1].safeArea)
    }

    @Test
    fun invalidGeometryAndInitialAreaAreRejected() {
        val width = 1920
        val height = 1080
        val invalidAreas = listOf(
            CarPlayViewAreas(emptyList()),
            CarPlayViewAreas(listOf(CarPlayViewAreaDefinition()), initialIndex = 1),
            CarPlayViewAreas(listOf(CarPlayViewAreaDefinition(viewArea = AirPlayInsets(left = -1)))),
            CarPlayViewAreas(listOf(CarPlayViewAreaDefinition(viewArea = AirPlayInsets(left = width)))),
            CarPlayViewAreas(listOf(CarPlayViewAreaDefinition(safeArea = AirPlayInsets(bottom = height)))),
            CarPlayViewAreas(
                listOf(
                    CarPlayViewAreaDefinition(
                        viewArea = AirPlayInsets(top = 100, bottom = 100, left = 100, right = 100),
                        safeArea = AirPlayInsets(top = 100, bottom = 100, left = 50, right = 100),
                    ),
                ),
            ),
            CarPlayViewAreas(
                listOf(
                    CarPlayViewAreaDefinition(
                        viewArea = AirPlayInsets(top = 100, bottom = 100, left = 100, right = 100),
                        safeArea = AirPlayInsets(top = 100, bottom = 100, left = 100, right = 50),
                    ),
                ),
            ),
            CarPlayViewAreas(
                listOf(
                    CarPlayViewAreaDefinition(
                        viewArea = AirPlayInsets(top = 100, bottom = 100, left = 100, right = 100),
                        safeArea = AirPlayInsets(top = 50, bottom = 100, left = 100, right = 100),
                    ),
                ),
            ),
            CarPlayViewAreas(
                listOf(
                    CarPlayViewAreaDefinition(
                        viewArea = AirPlayInsets(top = 100, bottom = 100, left = 100, right = 100),
                        safeArea = AirPlayInsets(top = 100, bottom = 50, left = 100, right = 100),
                    ),
                ),
            ),
        )

        invalidAreas.forEach { assertFalse(it.isValidFor(width, height)) }
        assertTrue(runCatching {
            AirPlayInfoPlist.build(testConfig(testDisplay().copy(viewAreas = invalidAreas.first())))
        }.isFailure)
        assertTrue(
            runCatching {
                AirPlayInfoPlist.build(
                    testConfig(
                        testDisplay().copy(
                            viewArea = AirPlayInsets(left = 100),
                            safeArea = AirPlayInsets(),
                        ),
                    ),
                )
            }.isFailure,
        )
        assertTrue(CarPlayViewAreas.fullAndSplit(testDisplay(), initialIndex = 1).isValidFor(width, height))
        assertTrue(runCatching { CarPlayViewAreas.fullAndSplit(testDisplay(), splitFraction = 0.49) }.isFailure)
        assertTrue(runCatching { CarPlayViewAreas.fullAndSplit(testDisplay(), splitFraction = 0.61) }.isFailure)
        assertTrue(
            runCatching {
                CarPlayViewAreas.fullAndSplit(
                    testDisplay().copy(safeArea = AirPlayInsets(left = 1_100, right = 100)),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                CarPlayViewAreas.fullAndSplit(
                    testDisplay().copy(
                        viewArea = AirPlayInsets(left = 100),
                        safeArea = AirPlayInsets(left = 50),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun displayAndUiScalingKeepBothAreaCoordinateSpacesAligned() {
        val display = testDisplay().copy(viewAreas = CarPlayViewAreas.fullAndSplit(testDisplay()))
        val reduced = CarPlayDisplayScale.apply(display, 5)
        val reducedSplit = reduced.viewAreas!!.areas[1].viewArea
        assertEquals(960, reduced.widthPixels)
        assertEquals(540, reduced.heightPixels)
        assertEquals(528, reduced.widthPixels - reducedSplit.left - reducedSplit.right)
        assertEquals(0, reducedSplit.left)
        assertTrue(reduced.viewAreas.isValidFor(reduced.widthPixels, reduced.heightPixels))

        val enlarged = CarPlayUiScale.apply(display, 75)
        val enlargedSplit = enlarged.viewAreas!!.areas[1].viewArea
        assertEquals(2560, enlarged.widthPixels)
        assertEquals(1440, enlarged.heightPixels)
        assertEquals(1408, enlarged.widthPixels - enlargedSplit.left - enlargedSplit.right)
        assertTrue(enlarged.viewAreas.isValidFor(enlarged.widthPixels, enlarged.heightPixels))
    }

    private fun testDisplay() = AirPlayDisplayConfig(
        widthPixels = 1920,
        heightPixels = 1080,
        widthPhysicalMm = 200,
        heightPhysicalMm = 113,
    )

    private fun testConfig(display: AirPlayDisplayConfig, rightHandDrive: Boolean = false) = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "950.7.1",
        main = display,
        rightHandDrive = rightHandDrive,
    )
}
