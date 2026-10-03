package com.i3u8.sleepdesk.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class EventUpdatePublisherTest {
    private fun event(id: String) = NightEvent(
        id, NightEventType.UNKNOWN, 0, 500, 0f, classificationStatus = ClassificationStatus.PENDING
    )

    @Test fun boundsDistinctEventsAndCoalescesToNewestRevisionWithoutBlocking() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<NightEvent>()
        val publisher = EventUpdatePublisher(2, { e ->
            if (e.id == "busy") {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            delivered.add(e)
        }, { throw AssertionError(it) })
        try {
            assertTrue(publisher.publish(event("busy")))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(publisher.publish(event("second")))
            assertTrue(publisher.publish(event("third")))
            assertFalse(publisher.publish(event("overflow")))
            assertTrue(publisher.publish(event("overflow").copy(
                revision = 2, classificationStatus = ClassificationStatus.FAILED,
                classificationReason = "analysis_queue_full")))
            assertTrue(publisher.publish(event("second").copy(
                revision = 2, type = NightEventType.SNORE,
                classificationStatus = ClassificationStatus.SUGGESTED)))
            assertTrue(publisher.publish(event("second"))) // stale update is ignored
        } finally {
            release.countDown()
            assertTrue(publisher.finish(2000))
        }
        assertEquals(listOf("busy", "second", "third", "overflow"), delivered.map { it.id })
        assertEquals(2L, delivered[1].revision)
        assertEquals(ClassificationStatus.SUGGESTED, delivered[1].classificationStatus)
        assertEquals(ClassificationStatus.FAILED, delivered.last().classificationStatus)
        assertFalse(publisher.publish(event("late")))
    }

    @Test fun persistenceFailureIsReportedRatherThanSilentlySwallowed() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val publisher = EventUpdatePublisher(2, { throw java.io.IOException("disk full") }, failures::add)
        assertTrue(publisher.publish(event("one")))
        assertTrue(publisher.finish(2000))
        assertEquals(1, failures.size)
        assertTrue(failures.single() is EventPublicationException)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCapacityBeforeStartingAWorker() {
        EventUpdatePublisher(0, {}, {})
    }

    @Test fun stoppingTheSingleCaptureProducerStillDeliversTheOverflowDetection() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val detected = mutableListOf<String>()
        val delivered = CopyOnWriteArrayList<String>()
        val publisher = EventUpdatePublisher(1, {
            if (it.id == "slow-storage") {
                blocked.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            delivered.add(it.id)
        }, { stop.set(true) })
        val pipeline = EventDetectionPipeline(
            AudioAlgoConfig(sessionWarmupMs = 600, energySmoothMs = 100), 100_000,
            {
                detected.add(it.id)
                if (!publisher.publish(it)) stop.set(true)
            }, { _, _ -> }
        )
        var offset = 0
        fun feed(ms: Int, amplitude: Int) {
            var remaining = ms * 16
            while (remaining > 0 && !stop.get()) {
                val count = minOf(remaining, 240)
                val frame = ShortArray(count) {
                    (amplitude * sin(2 * PI * 180 * (offset + it) / 16000)).toInt().toShort()
                }
                pipeline.process(frame, count)
                offset += count
                remaining -= count
            }
        }
        try {
            assertTrue(publisher.publish(event("slow-storage")))
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            feed(2500, 32)
            repeat(4) {
                feed(450, 6000)
                feed(2050, 32)
            }
            pipeline.flush()
            assertTrue(stop.get())
            assertEquals(2, detected.size)
        } finally {
            release.countDown()
            assertTrue(publisher.finish(2000))
        }
        assertTrue(delivered.containsAll(detected))
        assertEquals(3, delivered.size)
    }
}
