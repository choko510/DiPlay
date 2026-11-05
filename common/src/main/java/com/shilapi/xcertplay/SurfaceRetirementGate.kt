package com.shilapi.xcertplay

import java.util.IdentityHashMap

// A small burst is tolerated while keeping native Surface/SurfaceTexture retention finite.
internal const val MAX_RETAINED_SURFACE_GENERATIONS = 8

internal data class SurfaceRetirementBatch<S : Any>(
    val surfaces: List<S>,
    val releaseTexture: Boolean,
)

internal class SurfaceRetirementGate<T : Any, S : Any>(
    private val maximumOutstandingSurfaces: Int,
) {
    private val pending = IdentityHashMap<T, IdentityHashMap<S, Boolean>>()
    private val releaseTextureOnCompletion = IdentityHashMap<T, Boolean>()

    init {
        require(maximumOutstandingSurfaces > 0)
    }

    @Synchronized
    fun canAcceptSurface(activeSurface: Boolean): Boolean =
        outstandingSurfaceCount + (if (activeSurface) 1 else 0) < maximumOutstandingSurfaces

    @Synchronized
    fun begin(texture: T, surface: S, releaseTexture: Boolean = true): Boolean {
        val surfaces = pending.getOrPut(texture) { IdentityHashMap() }
        if (releaseTexture) releaseTextureOnCompletion[texture] = true
        if (surfaces.containsKey(surface)) return false
        surfaces[surface] = false
        return true
    }

    @Synchronized
    fun complete(texture: T, surface: S): SurfaceRetirementBatch<S>? {
        val surfaces = pending[texture] ?: return null
        if (surfaces[surface] != false) return null
        surfaces[surface] = true
        if (surfaces.values.any { !it }) return null
        pending.remove(texture)
        return SurfaceRetirementBatch(
            surfaces = surfaces.keys.toList(),
            releaseTexture = releaseTextureOnCompletion.remove(texture) == true,
        )
    }

    @Synchronized
    fun hasPendingTexture(texture: T): Boolean = pending.containsKey(texture)

    @get:Synchronized
    val outstandingSurfaceCount: Int
        get() = pending.values.sumOf { it.size }
}
