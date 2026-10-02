package com.i3u8.sleepdesk.audio

import java.util.ArrayDeque

internal data class CandidateAudio(
    val startSample: Long,
    val endSample: Long,
    val noiseFloorDb: Float,
    val pcm: ShortArray,
    val clipPcm: ShortArray,
    val clipStartSample: Long,
    val clipEndSample: Long
) {
    fun startMs(originMs: Long, sampleRate: Int) = originMs + startSample * 1000 / sampleRate
    fun endMs(originMs: Long, sampleRate: Int) = originMs + endSample * 1000 / sampleRate
    /** Source PCM timing; does not claim to measure AAC encoder padding. */
    fun clipFeatures(sampleRate: Int): Map<String, Float> = mapOf(
        "candidateOffsetMs" to ((startSample - clipStartSample) * 1000f / sampleRate),
        "clipDurationMs" to ((clipEndSample - clipStartSample) * 1000f / sampleRate)
    )
}

/** Sample-clock gate and PCM ownership, independent of Android and wall-clock changes. */
internal class CandidateDetector(private val config: AudioAlgoConfig) {
    private fun samples(ms: Long) = ms * config.sampleRate / 1000
    private val hopSamples = samples(config.hopMs.toLong()).coerceAtLeast(1).toInt()
    private val minSamples = samples(config.minCandidateMs.toLong()).coerceAtLeast(1)
    private val maxSamples = samples(config.maxCandidateMs.toLong()).coerceAtLeast(minSamples)
    private val preSamples = samples(config.preRollMs.toLong()).coerceAtLeast(0)
    private val ring = PcmRingBuffer((preSamples + maxSamples + hopSamples).toInt())
    private val floorWindow = ArrayDeque<Float>()
    private var smoothedDb = -60f
    private var initialized = false
    private var openingStart: Long? = null
    private var rawStart: Long? = null
    private var rawEnd = 0L
    private var rawActiveSamples = 0L
    private val onsetLookback = samples(config.energySmoothMs.coerceAtLeast(100).toLong())
        .coerceAtMost(maxSamples)
    private var candidateStart: Long? = null
    private var silenceStart: Long? = null
    private var candidateFloor = -58f
    var noiseFloorDb = -58f
        private set
    val inCandidate: Boolean get() = candidateStart != null
    val totalSamples: Long get() = ring.totalSamples()

    fun resetNoiseFloor() {
        floorWindow.clear()
        initialized = false
        openingStart = null
        rawStart = null
        rawActiveSamples = 0
    }

    fun process(frame: ShortArray, count: Int): CandidateAudio? {
        require(count in 1..minOf(frame.size, hopSamples))
        val before = totalSamples
        ring.write(frame, 0, count)
        val end = totalSamples
        val rms = FeatureExtractor.rmsDb(frame, count)
        if (!initialized) {
            noiseFloorDb = rms
            smoothedDb = rms
            initialized = true
        }
        val alpha = (count * 1000f / config.sampleRate /
            config.energySmoothMs.coerceAtLeast(100)).coerceIn(0f, 0.5f)
        smoothedDb += (rms - smoothedDb) * alpha
        val warmingUp = before < samples(config.sessionWarmupMs)
        val over = smoothedDb > noiseFloorDb + config.effectiveMarginDb()

        if (!inCandidate) {
            val rawOver = rms > noiseFloorDb + config.effectiveMarginDb()
            if (warmingUp || !rawOver) {
                updateNoiseFloor(rms)
                openingStart = null
                rawStart = null
                rawActiveSamples = 0
                return null
            }
            // Trace only this contiguous raw burst, never the full clip pre-roll.
            val onset = rawStart ?: before.also {
                candidateFloor = noiseFloorDb
            }
            rawStart = maxOf(onset, end - onsetLookback)
            rawEnd = end
            rawActiveSamples = end - rawStart!!
            if (!over) {
                openingStart = null
                return null
            }
            val start = openingStart ?: before.also {
                openingStart = it
            }
            if (end - start < (minSamples / 2).coerceAtLeast(1)) return null
            candidateStart = rawStart
        } else if (rms >= candidateFloor + config.effectiveMarginDb() * 0.45f) {
            rawEnd = end
            rawActiveSamples += count
        }

        val start = candidateStart ?: return null
        // Only the lower threshold closes an open gate; the upper threshold opens it.
        if (smoothedDb < candidateFloor + config.effectiveMarginDb() * 0.45f) {
            if (silenceStart == null) silenceStart = before
        } else {
            silenceStart = null
        }
        val quietStart = silenceStart
        val endedBySilence = quietStart != null && end - quietStart >= samples(100)
        val endedByMax = end - start >= maxSamples
        if (!endedBySilence && !endedByMax) return null

        // Neither EMA decay nor release debounce is raw event audio.
        val to = minOf(rawEnd, quietStart ?: end, start + maxSamples)
        val activeSamples = rawActiveSamples - (rawEnd - to).coerceAtLeast(0)
        candidateStart = null
        openingStart = null
        silenceStart = null
        rawStart = null
        rawActiveSamples = 0
        if (to - start < minSamples || activeSamples < minSamples) return null
        val pcm = ring.sliceSamples(start, to)
        check(pcm.size.toLong() == to - start) { "Candidate PCM was overwritten" }
        val clipLimit = samples(config.maxClipMs.toLong()).coerceAtLeast(1)
        // No future post-roll is captured. Saving it requires deferred clip finalization.
        // Prefer candidate audio over pre-roll when the configured clip limit is reached.
        val clipTo = minOf(to, start + clipLimit)
        val oldest = (totalSamples - ring.capacity).coerceAtLeast(0)
        val clipFrom = maxOf(oldest, start - preSamples, clipTo - clipLimit)
        return CandidateAudio(
            start, to, candidateFloor, pcm, ring.sliceSamples(clipFrom, clipTo), clipFrom, clipTo
        )
    }

    private fun updateNoiseFloor(rms: Float) {
        floorWindow.addLast(rms)
        val limit = (config.floorWindowMs / config.hopMs).coerceAtLeast(100)
        while (floorWindow.size > limit) floorWindow.removeFirst()
        if (floorWindow.size >= 30) {
            val sorted = floorWindow.sorted()
            val index = (sorted.size * config.floorPercentile.coerceIn(0.05f, 0.4f))
                .toInt().coerceIn(0, sorted.lastIndex)
            noiseFloorDb = sorted[index]
        }
    }
}
