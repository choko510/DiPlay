package com.shilapi.xcertplay.media.dsp

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal object DspPipelineReaper {
    private val started = AtomicBoolean()
    private val pending = AtomicReference<DspPcmPipeline?>(null)
    @Volatile private var worker: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val thread = Thread(::drain, "dsp-pipeline-reaper").apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    fun enqueue(pipeline: DspPcmPipeline) {
        var head: DspPcmPipeline?
        do {
            head = pending.get()
            pipeline.reaperNext = head
        } while (!pending.compareAndSet(head, pipeline))
        val reaper = worker
        if (reaper != null) LockSupport.unpark(reaper)
    }

    private fun drain() {
        while (true) {
            var pipeline = pending.getAndSet(null)
            if (pipeline == null) {
                LockSupport.park()
                continue
            }
            while (pipeline != null) {
                val next = pipeline.reaperNext
                pipeline.reaperNext = null
                runCatching { pipeline.close() }
                pipeline.decrementRetirementCount()
                pipeline = next
            }
        }
    }
}
