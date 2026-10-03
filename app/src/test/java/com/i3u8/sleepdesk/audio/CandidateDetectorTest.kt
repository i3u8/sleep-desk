package com.i3u8.sleepdesk.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CandidateDetectorTest {
    private val config = AudioAlgoConfig(
        sampleRate = 16_000, hopMs = 15, energySmoothMs = 100,
        sessionWarmupMs = 600, digitalGainDb = 0f
    )

    private class Stream(val config: AudioAlgoConfig) {
        val detector = CandidateDetector(config)
        val events = mutableListOf<CandidateAudio>()
        val samples = mutableListOf<Short>()
        fun feed(ms: Int, amplitude: Int, hz: Int = 200, readSize: Int = 240) {
            var remaining = ms * config.sampleRate / 1000
            while (remaining > 0) {
                val count = minOf(remaining, readSize)
                val offset = samples.size
                val frame = ShortArray(count) {
                    (sin(2 * PI * hz * (offset + it) / config.sampleRate) * amplitude).toInt().toShort()
                }
                samples.addAll(frame.toList())
                detector.process(frame, count)?.let(events::add)
                remaining -= count
            }
        }
        fun features(event: CandidateAudio) = FeatureExtractor.extract(
            event.pcm, config.sampleRate, event.pcm.size * 1000f / config.sampleRate
        )
    }

    @Test fun constantBackgroundDoesNotProduceCandidates() {
        for (amplitude in listOf(32, 2000, 12000)) {
            val stream = Stream(config)
            stream.feed(20_000, amplitude)
            assertTrue("constant amplitude=$amplitude", stream.events.isEmpty())
        }
    }

    @Test fun shortSpikeCannotBorrowReleaseDebounceToPassMinimum() {
        val stream = Stream(config)
        stream.feed(1500, 32)
        stream.feed(15, 8000)
        stream.feed(1500, 32)
        assertTrue(stream.events.isEmpty())
    }

    @Test fun preRollFromFirstEventDoesNotContaminateSecondEventFeatures() {
        val stream = Stream(config)
        stream.feed(1500, 32)
        stream.feed(450, 14000, hz = 3000)
        stream.feed(450, 32)
        stream.feed(450, 2500, hz = 200)
        stream.feed(1000, 32)
        assertEquals(2, stream.events.size)
        val first = stream.events[0]
        val second = stream.events[1]
        assertTrue(second.clipPcm.size > second.pcm.size)
        assertTrue(second.clipPcm.any { kotlin.math.abs(it.toInt()) > 10000 })
        assertTrue(second.pcm.all { kotlin.math.abs(it.toInt()) <= 2500 })
        val f = stream.features(second)
        assertEquals(second.pcm.size * 1000f / config.sampleRate, f.durationMs, 0.001f)
        assertTrue(f.bandLow > f.bandHigh)
        assertTrue(first.endSample < second.startSample)
    }

    @Test fun eightSecondCandidateRetainsEverySampleAndClipDoesNotTrimItsEdges() {
        val stream = Stream(config)
        stream.feed(3000, 32)
        stream.feed(8500, 8000)
        val event = stream.events.first()
        assertEquals(128000, event.pcm.size)
        assertEquals(128000L, event.endSample - event.startSample)
        assertArrayEquals(
            stream.samples.subList(event.startSample.toInt(), event.endSample.toInt()).toShortArray(),
            event.pcm
        )
        assertArrayEquals(event.pcm, event.clipPcm.copyOfRange(32000, event.clipPcm.size))
        assertEquals(8000f, stream.features(event).durationMs, 0.001f)
        assertEquals(2000f, event.clipFeatures(config.sampleRate).getValue("candidateOffsetMs"), 0f)
        assertEquals(10000f, event.clipFeatures(config.sampleRate).getValue("clipDurationMs"), 0f)
    }

    @Test fun preRollAndFullCandidateSurviveRingWrap() {
        val stream = Stream(config.copy(maxClipMs = 10000))
        stream.feed(12000, 32)
        stream.feed(8500, 8000)
        val event = stream.events.first()
        assertEquals(160000, event.clipPcm.size)
        assertEquals(2000f, event.clipFeatures(config.sampleRate).getValue("candidateOffsetMs"), 0f)
        assertEquals(10000f, event.clipFeatures(config.sampleRate).getValue("clipDurationMs"), 0f)
        assertArrayEquals(
            stream.samples.subList((event.startSample - 32000).toInt(), event.endSample.toInt()).toShortArray(),
            event.clipPcm
        )
    }

    @Test fun gateRemainsOpenBetweenUpperAndLowerThresholds() {
        val stream = Stream(config.copy(marginDb = 10f, sensitivity = 1f))
        stream.feed(1500, 1000)
        stream.feed(300, 8000)
        stream.feed(1200, 2000) // +6 dB: above +4.5 dB close, below +10 dB open.
        assertTrue(stream.detector.inCandidate)
        assertTrue(stream.events.isEmpty())
        val holdEnd = stream.detector.totalSamples
        stream.feed(900, 1000)
        assertEquals(1, stream.events.size)
        assertTrue(stream.events.single().endSample >= holdEnd)
        assertTrue(stream.features(stream.events.single()).durationMs >= 1200f)
    }

    @Test fun firstCrossingStartsAtCurrentHopNotOneHopEarlier() {
        val stream = Stream(config)
        stream.feed(1500, 32)
        val before = stream.detector.totalSamples
        stream.feed(450, 8000)
        stream.feed(900, 32)
        assertEquals(before, stream.events.single().startSample)
    }

    @Test fun partialReadsUseActualSampleCounts() {
        val stream = Stream(config)
        stream.feed(1500, 32, readSize = 80)
        stream.feed(8500, 8000, readSize = 80)
        val event = stream.events.first()
        assertEquals(128000, event.pcm.size)
        assertEquals(128000L, event.endSample - event.startSample)
        assertArrayEquals(
            stream.samples.subList(event.startSample.toInt(), event.endSample.toInt()).toShortArray(),
            event.pcm
        )
    }

    @Test fun wallClockJumpsCannotChangeWarmupCandidateOrFeatureDuration() {
        val origin = 1_700_000_000_000L
        fun replay(jump: Long): CandidateAudio {
            val stream = Stream(config)
            var wallClock = origin
            stream.feed(300, 32)
            wallClock += jump
            stream.feed(150, 8000)
            assertTrue(stream.events.isEmpty()) // Still in sample-clock warmup.
            stream.feed(1050, 32)
            wallClock -= jump * 2
            stream.feed(450, 8000)
            stream.feed(900, 32)
            assertNotEquals(origin, wallClock)
            return stream.events.single()
        }
        val forward = replay(3_600_000)
        val backward = replay(-3_600_000)
        assertEquals(forward.startSample, backward.startSample)
        assertEquals(forward.endSample, backward.endSample)
        assertArrayEquals(forward.pcm, backward.pcm)
        assertEquals(origin + forward.startSample * 1000 / config.sampleRate,
            forward.startMs(origin, config.sampleRate))
        assertEquals((forward.endSample - forward.startSample) * 1000 / config.sampleRate,
            forward.endMs(origin, config.sampleRate) - forward.startMs(origin, config.sampleRate))
    }

    @Test fun postRollSettingDoesNotPretendFutureSamplesWereCaptured() {
        val stream = Stream(config.copy(preRollMs = 0, postRollMs = 2000))
        stream.feed(1500, 32)
        stream.feed(450, 8000)
        stream.feed(900, 32)
        val event = stream.events.single()
        assertArrayEquals(event.pcm, event.clipPcm)
        assertTrue(event.endSample < stream.detector.totalSamples)
        assertEquals(FeatureExtractor.rmsDb(ShortArray(240) { 32 }, 240),
            event.noiseFloorDb, 4f)
    }

    @Test fun clipOffsetUsesAvailablePreRollInsteadOfConfiguredPreRoll() {
        val stream = Stream(config.copy(sessionWarmupMs = 0))
        stream.feed(15, 32)
        stream.feed(450, 8000)
        stream.feed(900, 32)
        val event = stream.events.single()
        assertEquals(0L, event.clipStartSample)
        assertEquals(240L, event.startSample)
        assertEquals(15f, event.clipFeatures(config.sampleRate).getValue("candidateOffsetMs"), 0f)
        assertEquals(event.clipPcm.size * 1000f / config.sampleRate,
            event.clipFeatures(config.sampleRate).getValue("clipDurationMs"), 0f)
        assertArrayEquals(
            event.clipPcm.copyOfRange(240, event.clipPcm.size),
            event.pcm
        )
    }

    @Test fun clipLimitDoesNotShortenClassificationPcm() {
        val stream = Stream(config.copy(maxClipMs = 500))
        stream.feed(1500, 32)
        stream.feed(1500, 8000)
        stream.feed(900, 32)
        val event = stream.events.single()
        assertEquals(8000, event.clipPcm.size)
        assertTrue(event.pcm.size > event.clipPcm.size)
        assertEquals(event.startSample, event.clipStartSample)
        assertEquals(500f, event.clipFeatures(config.sampleRate).getValue("clipDurationMs"), 0f)
        assertEquals(1500f, stream.features(event).durationMs, 0.001f)
    }

    @Test fun defaultSmoothingPreservesCoughOnsetPeakAndAttackWithoutPreRoll() {
        val stream = Stream(config.copy(energySmoothMs = 350))
        stream.feed(3000, 32)
        val onset = stream.samples.size
        stream.feed(15, 200, hz = 2400)
        stream.feed(15, 12000, hz = 2400)
        stream.feed(30, 6000, hz = 2400)
        stream.feed(90, 3000, hz = 2400)
        stream.feed(150, 1500, hz = 2400)
        val end = stream.samples.size
        val expectedPcm = stream.samples.subList(onset, end).toShortArray()
        val expected = FeatureExtractor.extract(expectedPcm, config.sampleRate, 300f)
        stream.feed(1800, 32)

        val event = stream.events.single()
        assertEquals(onset.toLong(), event.startSample)
        assertEquals(end.toLong(), event.endSample)
        assertArrayEquals(expectedPcm, event.pcm)
        val actual = stream.features(event)
        assertEquals(expected.peakDb, actual.peakDb, 0.001f)
        assertEquals(expected.attackMs, actual.attackMs, 0.001f)
        assertEquals(300f, actual.durationMs, 0.001f)
        assertTrue(event.clipPcm.size > event.pcm.size)
    }

    @Test fun defaultSmoothingTailCannotTurnRawImpulsesIntoMinimumLengthEvents() {
        for (duration in listOf(15, 30, 60, 90, 120, 135)) {
            val stream = Stream(config.copy(energySmoothMs = 350))
            stream.feed(3000, 32)
            stream.feed(duration, 24000, hz = 2400)
            stream.feed(1800, 32)
            assertTrue("raw impulse duration=$duration", stream.events.isEmpty())
        }
    }

    @Test fun separatedImpulsesCannotAccumulateAnOpeningFromSmoothedNoise() {
        val stream = Stream(config.copy(energySmoothMs = 350))
        stream.feed(3000, 32)
        repeat(20) {
            stream.feed(30, 24000, hz = 2400)
            stream.feed(30, 32)
        }
        stream.feed(1800, 32)
        assertTrue(stream.events.isEmpty())
    }

    @Test fun rawBurstExactlyAtMinimumStillPassesWithDefaultSmoothing() {
        val stream = Stream(config.copy(energySmoothMs = 350))
        stream.feed(3000, 32)
        val onset = stream.samples.size
        stream.feed(150, 24000, hz = 2400)
        stream.feed(1800, 32)
        val event = stream.events.single()
        assertEquals(onset.toLong(), event.startSample)
        assertEquals(2400, event.pcm.size)
        assertEquals(150f, stream.features(event).durationMs, 0.001f)
    }

    @Test fun zeroPreRollRetainsLongRawCandidateWhileWaitingForEmaRelease() {
        for (duration in listOf(7800, 7995, 8000)) {
            val stream = Stream(config.copy(preRollMs = 0, energySmoothMs = 350))
            stream.feed(3000, 32)
            val onset = stream.samples.size
            stream.feed(duration, 8000)
            val rawEnd = stream.samples.size
            stream.feed(1500, 32)
            val event = stream.events.single()
            assertEquals(onset.toLong(), event.startSample)
            assertEquals(rawEnd.toLong(), event.endSample)
            assertArrayEquals(stream.samples.subList(onset, rawEnd).toShortArray(), event.pcm)
            assertArrayEquals(event.pcm, event.clipPcm)
            assertEquals(event.startSample, event.clipStartSample)
            assertEquals(event.endSample, event.clipEndSample)
            assertEquals(0f, event.clipFeatures(config.sampleRate).getValue("candidateOffsetMs"), 0f)
            assertEquals(duration.toFloat(),
                event.clipFeatures(config.sampleRate).getValue("clipDurationMs"), 0f)
        }
    }

    @Test fun stopRetainsRawMinimumEvenBeforeSmoothedGateOpens() {
        val stream = Stream(config.copy(energySmoothMs = 2000))
        stream.feed(3000, 1000)
        val onset = stream.samples.size
        stream.feed(150, 2500)
        val event = stream.detector.flush()!!
        assertEquals(onset.toLong(), event.startSample)
        assertEquals(2400, event.pcm.size)
        assertTrue("STOPPED" in event.contextFlags)
        assertNull(stream.detector.flush())
    }

    @Test fun defaultGateFindsMultipleEventsInTwoAndHalfSecondsOfRepeatedSound() {
        val stream = Stream(AudioAlgoConfig())
        stream.feed(13000, 32)
        repeat(5) {
            stream.feed(300, 8000)
            stream.feed(200, 32)
        }
        stream.detector.flush()?.let(stream.events::add)
        assertEquals(5, stream.events.size)
        stream.events.zipWithNext().forEach { (a, b) ->
            assertTrue(a.endSample < b.startSample)
        }
        stream.events.forEach { assertEquals(4800, it.pcm.size) }
    }

    @Test fun stopDiscardsSubminimumRawBurstAndDoesNotDuplicateReleasedEvent() {
        val short = Stream(config)
        short.feed(1500, 32)
        short.feed(135, 8000)
        assertNull(short.detector.flush())
        val complete = Stream(config)
        complete.feed(1500, 32)
        complete.feed(300, 8000)
        complete.feed(1500, 32)
        assertEquals(1, complete.events.size)
        assertNull(complete.detector.flush())
    }
}
