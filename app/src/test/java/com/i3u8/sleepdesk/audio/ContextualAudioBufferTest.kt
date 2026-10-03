package com.i3u8.sleepdesk.audio

import org.junit.Assert.*
import org.junit.Test

class ContextualAudioBufferTest {
    private val config = AudioAlgoConfig(sampleRate = 1000, hopMs = 15)

    private class Stream(val config: AudioAlgoConfig) {
        val buffer = ContextualAudioBuffer(config)
        val source = mutableListOf<Short>()
        fun feed(count: Int, readSize: Int = 15) {
            var remaining = count
            while (remaining > 0) {
                val n = minOf(readSize, remaining)
                val frame = ShortArray(readSize + 3) { 30000 }
                repeat(n) { frame[it] = ((source.size + it) % 20000).toShort() }
                source.addAll(frame.take(n))
                buffer.write(frame, n)
                remaining -= n
            }
        }
        fun add(id: String, start: Int, end: Int): CandidateAudio {
            val body = source.subList(start, end).toShortArray()
            val audio = CandidateAudio(start.toLong(), end.toLong(), -50f,
                body, body, start.toLong(), end.toLong())
            buffer.add(id, audio)
            return audio
        }
        fun assertClip(audio: CandidateAudio, start: Int, end: Int) {
            assertEquals(start.toLong(), audio.clipStartSample)
            assertEquals(end.toLong(), audio.clipEndSample)
            assertArrayEquals(source.subList(start, end).toShortArray(), audio.clipPcm)
            assertArrayEquals(source.subList(audio.startSample.toInt(),
                audio.endSample.toInt()).toShortArray(), audio.pcm)
        }
    }

    @Test fun returnsOwnedTwoSecondContextOnlyAfterPostRollArrives() {
        val stream = Stream(config)
        stream.feed(3450)
        val original = stream.add("a", 3000, 3450)
        stream.feed(1999)
        assertTrue(stream.buffer.ready().isEmpty())
        stream.feed(1)
        val result = stream.buffer.ready().single()
        assertEquals("a", result.id)
        stream.assertClip(result.audio, 1000, 5450)
        assertEquals(450, original.clipPcm.size)
        assertTrue(result.audio.contextFlags.isEmpty())
        assertTrue(stream.buffer.ready().isEmpty())
        stream.feed(20000)
        stream.assertClip(result.audio, 1000, 5450)
    }

    @Test fun adjacentEventsShareContextWithoutMergingBodies() {
        val stream = Stream(config)
        stream.feed(3300)
        stream.add("first", 3000, 3300)
        stream.feed(500)
        stream.add("second", 3500, 3800)
        stream.feed(1500)
        val first = stream.buffer.ready().single()
        assertEquals("first", first.id)
        stream.assertClip(first.audio, 1000, 5300)
        stream.feed(500)
        val second = stream.buffer.ready().single()
        assertEquals("second", second.id)
        stream.assertClip(second.audio, 1500, 5800)
        assertEquals(300, first.audio.pcm.size)
        assertEquals(300, second.audio.pcm.size)
    }

    @Test fun eightSecondBodyKeepsBothContextEdgesAcrossRingWrap() {
        val stream = Stream(config)
        stream.feed(38000)
        stream.add("long", 30000, 38000)
        stream.feed(2000)
        val audio = stream.buffer.ready().single().audio
        stream.assertClip(audio, 28000, 40000)
        assertEquals(12000, audio.clipPcm.size)
        assertEquals(8000, audio.pcm.size)
        assertTrue(audio.contextFlags.isEmpty())
    }

    @Test fun stopUsesAvailableContextWithoutInventingFuturePcm() {
        val stream = Stream(config)
        stream.feed(450)
        stream.add("early", 150, 450)
        stream.feed(137)
        val audio = stream.buffer.flush().single().audio
        stream.assertClip(audio, 0, 587)
        assertEquals(setOf("PRE_ROLL_SHORT", "POST_ROLL_SHORT", "STOPPED"), audio.contextFlags)
        assertTrue(stream.buffer.flush().isEmpty())
        assertTrue(stream.buffer.ready().isEmpty())
    }

    @Test fun partialReadsIgnoreUnusedFrameTailAndUseActualSampleClock() {
        val stream = Stream(config)
        stream.feed(3300, readSize = 7)
        stream.add("partial", 3000, 3300)
        stream.feed(1999, readSize = 7)
        assertTrue(stream.buffer.ready().isEmpty())
        stream.feed(1, readSize = 7)
        stream.assertClip(stream.buffer.ready().single().audio, 1000, 5300)
    }

    @Test fun completedClipSurvivesWrapBeforeReadyIsCalled() {
        val stream = Stream(config)
        stream.feed(3300)
        stream.add("held", 3000, 3300)
        stream.feed(30000)
        stream.assertClip(stream.buffer.ready().single().audio, 1000, 5300)
    }

    @Test fun shortClipBudgetNeverTruncatesClassificationBody() {
        val stream = Stream(config.copy(maxClipMs = 500))
        stream.feed(4500)
        stream.add("limited", 3000, 4500)
        stream.feed(2000)
        val audio = stream.buffer.ready().single().audio
        stream.assertClip(audio, 3000, 3500)
        assertEquals(1500, audio.pcm.size)
        assertTrue(audio.contextFlags.containsAll(
            setOf("PRE_ROLL_SHORT", "POST_ROLL_SHORT", "CLIP_LIMIT")))
    }

    @Test fun rejectsNonFiniteAndOverflowingConfigurationsBeforeAllocation() {
        for (bad in listOf(
            config.copy(sensitivity = Float.NaN),
            config.copy(marginDb = Float.POSITIVE_INFINITY),
            config.copy(floorPercentile = Float.NaN),
            config.copy(sampleRate = 0),
            config.copy(hopMs = 0),
            config.copy(preRollMs = -1),
            config.copy(postRollMs = -1),
            config.copy(minCandidateMs = 9000),
            config.copy(sessionWarmupMs = Long.MAX_VALUE),
            config.copy(sampleRate = Int.MAX_VALUE, preRollMs = Int.MAX_VALUE)
        )) {
            assertThrows(IllegalArgumentException::class.java) { ContextualAudioBuffer(bad) }
            assertThrows(IllegalArgumentException::class.java) { CandidateDetector(bad) }
        }
    }

    @Test fun rejectsInvalidReadsAndCandidatesFromFutureClock() {
        val buffer = ContextualAudioBuffer(config)
        assertThrows(IllegalArgumentException::class.java) { buffer.write(ShortArray(20), 16) }
        assertThrows(IllegalArgumentException::class.java) { buffer.write(ShortArray(2), 3) }
        assertThrows(IllegalArgumentException::class.java) { buffer.write(ShortArray(2), 0) }
        val audio = CandidateAudio(0, 150, -50f, ShortArray(150), ShortArray(150), 0, 150)
        assertThrows(IllegalArgumentException::class.java) { buffer.add("future", audio) }
    }

    @Test fun detectorAndContextShareSampleClockWithEightSecondBodyAndPartialReads() {
        val detector = CandidateDetector(config.copy(sessionWarmupMs = 600))
        val buffer = ContextualAudioBuffer(config)
        val detected = mutableListOf<CandidateAudio>()
        val completed = mutableListOf<ContextualCandidate>()
        fun feed(samples: Int, amplitude: Short) {
            var left = samples
            while (left > 0) {
                val n = minOf(7, left)
                val frame = ShortArray(15) { if (it < n) amplitude else 30000 }
                buffer.write(frame, n)
                detector.process(frame, n)?.let {
                    detected += it
                    buffer.add("body-${detected.size}", it)
                }
                completed += buffer.ready()
                left -= n
            }
        }
        feed(30000, 32)
        feed(8000, 8000)
        assertEquals(1, detected.size)
        assertTrue(completed.isEmpty())
        feed(2000, 32)
        val audio = completed.single().audio
        assertEquals(40000L, detector.totalSamples)
        assertEquals(30000L, audio.startSample)
        assertEquals(38000L, audio.endSample)
        assertEquals(28000L, audio.clipStartSample)
        assertEquals(40000L, audio.clipEndSample)
        assertArrayEquals(ShortArray(8000) { 8000 }, audio.pcm)
        assertArrayEquals(ShortArray(12000) { if (it in 2000 until 10000) 8000 else 32 },
            audio.clipPcm)
        assertEquals(setOf("CANDIDATE_LIMIT"), audio.contextFlags)
        assertNull(detector.flush())
        assertTrue(buffer.flush().isEmpty())
    }

    @Test fun addCopiesBodyAndFlushPreservesUpstreamFlags() {
        val stream = Stream(config)
        stream.feed(3300)
        val body = stream.source.subList(3000, 3300).toShortArray()
        val audio = CandidateAudio(3000, 3300, -50f, body, body, 3000, 3300,
            setOf("CANDIDATE_LIMIT"))
        stream.buffer.add("owned", audio)
        body.fill(-1)
        val result = stream.buffer.flush().single().audio
        stream.assertClip(result, 1000, 3300)
        assertEquals(setOf("CANDIDATE_LIMIT", "STOPPED", "POST_ROLL_SHORT"), result.contextFlags)
    }

    @Test fun zeroContextIsReadyImmediatelyAndDuplicatePendingIdsAreRejected() {
        val stream = Stream(config.copy(preRollMs = 0, postRollMs = 0))
        stream.feed(450)
        val original = stream.add("zero", 150, 450)
        assertThrows(IllegalArgumentException::class.java) { stream.buffer.add("zero", original) }
        val audio = stream.buffer.ready().single().audio
        stream.assertClip(audio, 150, 450)
        assertTrue(audio.contextFlags.isEmpty())
    }
}
