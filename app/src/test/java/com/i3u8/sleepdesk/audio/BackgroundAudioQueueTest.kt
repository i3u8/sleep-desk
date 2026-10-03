package com.i3u8.sleepdesk.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BackgroundAudioQueueTest {
    private val audio = CandidateAudio(0, 2400, -60f, ShortArray(2400), ShortArray(2400), 0, 2400)
    private fun event(id: String) = NightEvent(id, NightEventType.UNKNOWN, 0, 150, 0f)

    @Test fun slowAnalysisCannotBlockCaptureAndOverflowIsExplicit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CopyOnWriteArrayList<String>()
        val rejected = CopyOnWriteArrayList<String>()
        val closed = AtomicInteger()
        val queue = BackgroundAudioQueue(1, { e, _ ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            done.add(e.id)
        }, { e, reason -> rejected.add("${e.id}:$reason") }, { closed.incrementAndGet() })
        try {
            assertTrue(queue.submit(event("one"), audio))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(queue.submit(event("two"), audio))
            assertFalse(queue.submit(event("three"), audio))
            assertEquals(listOf("three:analysis_queue_full"), rejected.toList())
        } finally {
            release.countDown()
            assertTrue(queue.finish(2000))
        }
        assertEquals(listOf("one", "two"), done.toList())
        assertEquals(1, closed.get())
    }

    @Test fun failedJobDoesNotKillSubsequentEvents() {
        val done = CopyOnWriteArrayList<String>()
        val rejected = CopyOnWriteArrayList<String>()
        val queue = BackgroundAudioQueue(3, { e, _ ->
            if (e.id == "bad") throw IllegalStateException("test")
            done.add(e.id)
        }, { e, _ -> rejected.add(e.id) }, {})
        queue.submit(event("bad"), audio)
        queue.submit(event("good"), audio)
        assertTrue(queue.finish(2000))
        assertEquals(listOf("bad"), rejected.toList())
        assertEquals(listOf("good"), done.toList())
        assertFalse(queue.submit(event("late"), audio))
        assertTrue("late" in rejected)
    }
}
