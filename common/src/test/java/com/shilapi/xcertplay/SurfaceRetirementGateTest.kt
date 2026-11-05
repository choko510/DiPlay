package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceRetirementGateTest {
    @Test
    fun surfaceGenerationChurnBoundsRetirementsAndKeepsOnlyTheLatestDeferredGeneration() {
        listOf(1, 100, 1_000, 10_000).forEach { generationCount ->
            val gate = SurfaceRetirementGate<Any, Any>(
                maximumOutstandingSurfaces = MAX_RETAINED_SURFACE_GENERATIONS,
            )
            val retirements = mutableListOf<Pair<Any, Any>>()
            var currentTexture: Any? = null
            var currentSurface: Any? = null
            var deferredTexture: Any? = null
            var latestTexture: Any? = null

            repeat(generationCount) {
                val texture = Any()
                latestTexture = texture
                if (gate.canAcceptSurface(activeSurface = currentSurface != null)) {
                    val retiringTexture = currentTexture
                    val retiringSurface = currentSurface
                    if (retiringTexture != null && retiringSurface != null) {
                        assertTrue(gate.begin(retiringTexture, retiringSurface))
                        retirements += retiringTexture to retiringSurface
                    }
                    currentTexture = texture
                    currentSurface = Any()
                    deferredTexture = null
                } else {
                    deferredTexture = texture
                }
                assertTrue(
                    gate.outstandingSurfaceCount + (if (currentSurface != null) 1 else 0) <=
                        MAX_RETAINED_SURFACE_GENERATIONS,
                )
            }

            assertTrue(retirements.size <= MAX_RETAINED_SURFACE_GENERATIONS - 1)
            assertEquals(retirements.size, gate.outstandingSurfaceCount)
            assertEquals(generationCount > MAX_RETAINED_SURFACE_GENERATIONS, deferredTexture != null)
            if (deferredTexture != null) assertSame(latestTexture, deferredTexture)
            assertEquals(
                generationCount < MAX_RETAINED_SURFACE_GENERATIONS,
                gate.canAcceptSurface(activeSurface = currentSurface != null),
            )

            var acknowledged = 0
            if (deferredTexture != null) {
                val (oldTexture, oldSurface) = retirements.removeAt(0)
                assertEquals(listOf(oldSurface), gate.complete(oldTexture, oldSurface)?.surfaces)
                assertNull(gate.complete(oldTexture, oldSurface))
                acknowledged++

                val resumedTexture = deferredTexture
                if (gate.canAcceptSurface(activeSurface = currentSurface != null)) {
                    val retiringTexture = checkNotNull(currentTexture)
                    val retiringSurface = checkNotNull(currentSurface)
                    assertTrue(gate.begin(retiringTexture, retiringSurface))
                    retirements += retiringTexture to retiringSurface
                    currentTexture = resumedTexture
                    currentSurface = Any()
                    deferredTexture = null
                }
            }

            retirements.forEach { (texture, surface) ->
                assertEquals(listOf(surface), gate.complete(texture, surface)?.surfaces)
                assertNull(gate.complete(texture, surface))
                acknowledged++
            }
            assertEquals(
                acknowledged,
                (generationCount - 1).coerceAtMost(MAX_RETAINED_SURFACE_GENERATIONS),
            )
            assertEquals(0, gate.outstandingSurfaceCount)
        }
    }

    @Test
    fun duplicateDestructionDoesNotScheduleAnotherRetirement() {
        val gate = SurfaceRetirementGate<Any, Any>(
            maximumOutstandingSurfaces = MAX_RETAINED_SURFACE_GENERATIONS,
        )
        val texture = Any()
        val surface = Any()

        assertTrue(gate.begin(texture, surface))
        assertFalse(gate.begin(texture, surface))
        assertEquals(1, gate.outstandingSurfaceCount)
        assertEquals(listOf(surface), gate.complete(texture, surface)?.surfaces)
        assertNull(gate.complete(texture, surface))
        assertEquals(0, gate.outstandingSurfaceCount)
    }

    @Test
    fun textureReleaseWaitsForEverySurfaceOwnerAndReturnsTheBatchOnlyOnce() {
        val gate = SurfaceRetirementGate<Any, Any>(
            maximumOutstandingSurfaces = MAX_RETAINED_SURFACE_GENERATIONS,
        )
        val texture = Any()
        val mainSurface = Any()
        val altSurface = Any()

        assertTrue(gate.begin(texture, mainSurface))
        assertTrue(gate.begin(texture, altSurface))
        assertNull(gate.complete(texture, mainSurface))
        assertEquals(2, gate.outstandingSurfaceCount)
        val batch = checkNotNull(gate.complete(texture, altSurface))
        assertEquals(setOf(mainSurface, altSurface), batch.surfaces.toSet())
        assertTrue(batch.releaseTexture)
        assertNull(gate.complete(texture, mainSurface))
        assertEquals(0, gate.outstandingSurfaceCount)
    }

    @Test
    fun sameTextureSurfaceReplacementWaitsForAckWithoutReleasingTheLiveTexture() {
        val gate = SurfaceRetirementGate<Any, Any>(
            maximumOutstandingSurfaces = MAX_RETAINED_SURFACE_GENERATIONS,
        )
        val texture = Any()
        val oldSurface = Any()
        val currentSurface = Any()

        assertTrue(gate.begin(texture, oldSurface, releaseTexture = false))
        assertTrue(gate.begin(texture, currentSurface))
        assertNull(gate.complete(texture, oldSurface))
        val batch = checkNotNull(gate.complete(texture, currentSurface))

        assertEquals(setOf(oldSurface, currentSurface), batch.surfaces.toSet())
        assertTrue(batch.releaseTexture)
    }
}
