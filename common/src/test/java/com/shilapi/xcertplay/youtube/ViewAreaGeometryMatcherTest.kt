package com.shilapi.xcertplay.youtube

import com.shilapi.xcertplay.airplay.CarPlayRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewAreaGeometryMatcherTest {
    private val areas = listOf(
        CarPlayRect(0, 0, 1280, 720),
        CarPlayRect(0, 0, 704, 720),
    )

    @Test fun exactAndProportionallyScaledFrameShapesMapToOneDeclaredArea() {
        assertEquals(ViewAreaGeometryMatch(0, exactDimensions = true), ViewAreaGeometryMatcher.match(areas, 1280, 720))
        assertEquals(ViewAreaGeometryMatch(1, exactDimensions = true), ViewAreaGeometryMatcher.match(areas, 704, 720))
        assertEquals(ViewAreaGeometryMatch(1, exactDimensions = false), ViewAreaGeometryMatcher.match(areas, 352, 360))
    }

    @Test fun ambiguousAspectRatiosAndUnrelatedFramesDoNotSelectAnArea() {
        assertNull(ViewAreaGeometryMatcher.match(areas, 640, 480))
        assertNull(
            ViewAreaGeometryMatcher.match(
                listOf(CarPlayRect(0, 0, 1280, 720), CarPlayRect(0, 0, 640, 360)),
                640,
                360,
            ),
        )
    }
}
