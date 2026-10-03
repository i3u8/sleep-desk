package com.i3u8.sleepdesk.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded PCM ownership; saturation produces an explicit event update, never a silent drop. */
internal class BackgroundAudioQueue(
    capacity: Int,
    private val process: (NightEvent, CandidateAudio) -> Unit,
    private val rejected: (NightEvent, String) -> Unit,
    private val onClose: () -> Unit
) {
    private val threadCreated = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity.coerceAtLeast(1)),
        { runnable ->
            threadCreated.set(true)
            Thread({
                try {
                    runnable.run()
                } finally {
                    onClose()
                }
            }, "night-analysis").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun submit(event: NightEvent, audio: CandidateAudio): Boolean {
        return try {
            executor.execute(Work(event, audio))
            true
        } catch (_: RejectedExecutionException) {
            rejected(event, if (closed.get()) "analysis_cancelled" else "analysis_queue_full")
            false
        }
    }

    /** Call off the main/capture loop after its last context has been submitted. */
    fun finish(timeoutMs: Long = 45_000L): Boolean {
        closed.set(true)
        executor.shutdown()
        if (!threadCreated.get()) onClose()
        return try {
            if (executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) true
            else {
                cancelQueued()
                executor.awaitTermination(minOf(timeoutMs, 15_000L), TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            cancelQueued()
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun cancelQueued() {
        for (task in executor.shutdownNow()) {
            (task as? Work)?.let { rejected(it.event, "analysis_cancelled") }
        }
    }

    private inner class Work(val event: NightEvent, val audio: CandidateAudio) : Runnable {
        override fun run() {
            try {
                process(event, audio)
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                rejected(event, "analysis_failure:${failure.javaClass.simpleName}")
            }
        }
    }
}
