package com.shilapi.xcertplay.media

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class VideoStreamGenerations {
    private val current = ConcurrentHashMap<Int, AtomicLong>()

    fun advance(type: Int): Long = current.computeIfAbsent(type) { AtomicLong() }.incrementAndGet()

    fun isCurrent(type: Int, generation: Long): Boolean = current[type]?.get() == generation
}
