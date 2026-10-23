package com.shilapi.xcertplay.media.dsp

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal object DspPipelineReaper {
    private const val WORKER_COUNT = 2
    private val started = AtomicBoolean()
    private val nextWorker = AtomicInteger()
    private val pending = Array(WORKER_COUNT) { AtomicReference<DspPcmPipeline?>(null) }
    private val workers = arrayOfNulls<Thread>(WORKER_COUNT)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        repeat(WORKER_COUNT) { index ->
            val name = if (index == 0) "dsp-pipeline-reaper" else "dsp-pipeline-reaper-$index"
            val thread = Thread({ drain(index) }, name).apply { isDaemon = true }
            workers[index] = thread
            thread.start()
        }
    }

    fun enqueue(pipeline: DspPcmPipeline) {
        val workerIndex = nextWorker.getAndIncrement() and (WORKER_COUNT - 1)
        val queue = pending[workerIndex]
        var head: DspPcmPipeline?
        do {
            head = queue.get()
            pipeline.reaperNext = head
        } while (!queue.compareAndSet(head, pipeline))
        val reaper = workers[workerIndex]
        if (reaper != null) LockSupport.unpark(reaper)
    }

    private fun drain(workerIndex: Int) {
        val queue = pending[workerIndex]
        while (true) {
            var pipeline = queue.getAndSet(null)
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
