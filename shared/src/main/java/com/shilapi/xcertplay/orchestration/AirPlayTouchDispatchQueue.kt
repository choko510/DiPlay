package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class AirPlayTouchDispatchQueue<S : Any>(
    capacity: Int = DEFAULT_CAPACITY,
    private val dispatch: (S, Long, List<AirPlayContact>) -> Unit,
    threadFactory: ThreadFactory = ThreadFactory { task ->
        Thread(task, "carplay-touch").apply { isDaemon = true }
    },
) : Closeable {
    private data class TouchTask<S : Any>(
        val session: S,
        val generation: Long,
        val contacts: List<AirPlayContact>,
        val pointerIds: Set<Int>,
        val movement: Boolean,
        val dispatch: (S, Long, List<AirPlayContact>) -> Unit,
    ) : Runnable {
        override fun run() = dispatch(session, generation, contacts)
    }

    private val lock = Any()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity.also { require(it > 0) }),
        threadFactory,
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var closed = false
    private var lastSession: S? = null
    private var lastGeneration = Long.MIN_VALUE
    private var lastPointerIds = emptySet<Int>()

    fun submit(session: S, generation: Long, contacts: List<AirPlayContact>): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (lastSession !== session || lastGeneration != generation) {
            lastSession = session
            lastGeneration = generation
            lastPointerIds = emptySet()
        }
        val snapshot = contacts.toList()
        val pointerIds = snapshot.filter(AirPlayContact::down).mapTo(linkedSetOf(), AirPlayContact::id)
        val movement = pointerIds.isNotEmpty() && snapshot.all(AirPlayContact::down) && pointerIds == lastPointerIds
        val queue = executor.queue
        if (movement) {
            val superseded = queue.toList().asReversed().firstOrNull { task ->
                task is TouchTask<*> && task.session === session && task.generation == generation &&
                    task.movement && task.pointerIds == pointerIds
            }
            if (superseded != null) queue.remove(superseded)
        }
        if (queue.remainingCapacity() == 0 && !movement) {
            val staleMovement = queue.firstOrNull { task -> task is TouchTask<*> && task.movement }
            if (staleMovement != null) queue.remove(staleMovement)
        }
        if (queue.remainingCapacity() == 0) {
            // Dropping queued transitions could leave a contact held; cancel all held slots instead.
            queue.clear()
            lastPointerIds = emptySet()
            enqueue(session, generation, emptyList(), emptySet(), movement = false)
            return@synchronized false
        }
        val queued = enqueue(session, generation, snapshot, pointerIds, movement)
        if (queued) lastPointerIds = pointerIds
        queued
    }

    private fun enqueue(
        session: S,
        generation: Long,
        contacts: List<AirPlayContact>,
        pointerIds: Set<Int>,
        movement: Boolean,
    ): Boolean = try {
        executor.execute(TouchTask(session, generation, contacts, pointerIds, movement, dispatch))
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    internal val pendingCountForTest: Int
        get() = executor.queue.size

    internal fun pendingContactsForTest(): List<List<AirPlayContact>> =
        executor.queue.mapNotNull { (it as? TouchTask<*>)?.contacts }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        executor.shutdownNow()
        lastSession = null
        lastPointerIds = emptySet()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 24
    }
}
