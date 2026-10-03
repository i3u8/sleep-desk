package com.i3u8.sleepdesk.audio

import java.util.LinkedHashMap

class EventPublicationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Bounded, ordered metadata delivery. False tells the capture owner to stop immediately.
 * One extra slot retains the final detection that causes backpressure; analysis updates
 * can still coalesce into that slot while the already queued events drain.
 */
internal class EventUpdatePublisher(
    private val capacity: Int,
    private val deliver: (NightEvent) -> Unit,
    private val failure: (Throwable) -> Unit
) {
    init { require(capacity > 0) }
    private val lock = Object()
    private val pending = LinkedHashMap<String, NightEvent>()
    private var finalDetection: NightEvent? = null
    private var closed = false
    private val worker = Thread({ drain() }, "night-events").apply {
        isDaemon = true
        start()
    }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    fun publish(event: NightEvent): Boolean = synchronized(lock) {
        if (closed) return false
        val terminal = finalDetection
        if (terminal?.id == event.id) {
            if (event.revision >= terminal.revision) finalDetection = event
            return true
        }
        val old = pending[event.id]
        if (old != null && old.revision > event.revision) return true
        if (old == null && pending.size >= capacity) {
            if (event.revision == 0L && finalDetection == null) finalDetection = event
            return false
        }
        pending[event.id] = event
        lock.notifyAll()
        true
    }

    fun finish(timeoutMs: Long = 30_000L): Boolean {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        if (Thread.currentThread() != worker) worker.join(timeoutMs)
        return !worker.isAlive
    }

    private fun drain() {
        while (true) {
            val event = synchronized(lock) {
                while (pending.isEmpty() && !closed) lock.wait()
                if (pending.isEmpty()) return
                val first = pending.entries.iterator().next()
                pending.remove(first.key)
                finalDetection?.let {
                    pending[it.id] = it
                    finalDetection = null
                }
                first.value
            }
            try {
                deliver(event)
            } catch (error: Exception) {
                failure(EventPublicationException("Sound event could not be stored", error))
            }
        }
    }
}
