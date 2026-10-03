package com.i3u8.sleepdesk.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class EventDetectionPipelineTest {
    private class Stream {
        val events = mutableListOf<NightEvent>()
        val clips = mutableListOf<Pair<NightEvent, CandidateAudio>>()
        val samples = mutableListOf<Short>()
        val config = AudioAlgoConfig(sessionWarmupMs = 600, energySmoothMs = 100, digitalGainDb = 0f)
        val pipeline = EventDetectionPipeline(config, 100_000L, events::add) { e, a -> clips.add(e to a) }
        fun feed(ms: Int, amplitude: Int) {
            var remaining = ms * 16
            while (remaining > 0) {
                val count = minOf(240, remaining)
                val offset = samples.size
                val frame = ShortArray(count) {
                    (amplitude * sin(2 * PI * 180 * (offset + it) / 16000)).toInt().toShort()
                }
                samples.addAll(frame.toList())
                pipeline.process(frame, count)
                remaining -= count
            }
        }
    }

    @Test fun detectionIsPublishedBeforePostRollAndWithoutInvokingAnyClassifier() {
        val stream = Stream()
        stream.feed(2500, 32)
        stream.feed(600, 6000)
        stream.feed(300, 32)
        assertEquals(1, stream.events.size)
        assertTrue(stream.clips.isEmpty())
        val detected = stream.events.single()
        assertEquals(NightEventType.UNKNOWN, detected.type)
        assertEquals(ClassificationStatus.PENDING, detected.classificationStatus)
        assertEquals(0f, detected.confidence, 0f)
        assertTrue(detected.detectionConfidence > 0f)
        stream.feed(1800, 32)
        val (sameEvent, audio) = stream.clips.single()
        assertEquals(detected.id, sameEvent.id)
        assertEquals(32000L, audio.startSample - audio.clipStartSample)
        assertEquals(32000L, audio.clipEndSample - audio.endSample)
        assertArrayEquals(stream.samples.subList(audio.startSample.toInt(),
            audio.endSample.toInt()).toShortArray(), audio.pcm)
    }

    @Test fun stoppingDuringSoundRetainsItAndMarksMissingFutureContext() {
        val stream = Stream()
        stream.feed(2500, 32)
        stream.feed(300, 6000)
        stream.pipeline.flush()
        assertEquals(1, stream.events.size)
        val audio = stream.clips.single().second
        assertEquals(stream.samples.size.toLong(), audio.clipEndSample)
        assertTrue("POST_ROLL_SHORT" in audio.contextFlags)
        assertTrue("STOPPED" in audio.contextFlags)
        stream.pipeline.flush()
        assertEquals(1, stream.clips.size)
    }

    @Test fun sourceRestartCannotInsertInventedSilenceIntoTheOldClip() {
        val stream = Stream()
        stream.feed(2500, 32)
        stream.feed(300, 6000)
        stream.pipeline.discontinuity(200_000L)
        assertEquals(1, stream.events.size)
        assertTrue("POST_ROLL_SHORT" in stream.clips.single().second.contextFlags)
        stream.feed(2500, 32)
        stream.feed(400, 6000)
        stream.feed(2500, 32)
        assertEquals(2, stream.events.size)
        assertTrue(stream.events.first().startMs < 200_000L)
        assertTrue(stream.events.last().startMs >= 200_000L)
        assertNotEquals(stream.events.first().id, stream.events.last().id)
    }

    @Test fun separatedRepeatedSoundsAreNotRequiredToContainThreeInternalPeaks() {
        val stream = Stream()
        stream.feed(2500, 32)
        repeat(3) {
            stream.feed(450, 6000)
            stream.feed(2050, 32)
        }
        stream.pipeline.flush()
        assertEquals(3, stream.events.size)
        assertEquals(3, stream.clips.size)
        assertTrue(stream.events.all { it.classificationStatus == ClassificationStatus.PENDING })
    }
}
