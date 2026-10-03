package com.i3u8.sleepdesk.audio

import java.util.UUID
import kotlin.math.abs

/**
 * Capture-thread stage: produces durable event identities before any model is consulted.
 * Callbacks must enqueue their work; they must not encode audio or run inference inline.
 */
internal class EventDetectionPipeline(
    private val config: AudioAlgoConfig,
    private var originMs: Long,
    private val onDetected: (NightEvent) -> Unit,
    private val onContextReady: (NightEvent, CandidateAudio) -> Unit
) {
    private var detector = CandidateDetector(config)
    private var context = ContextualAudioBuffer(config)
    private val pending = LinkedHashMap<String, NightEvent>()
    val inCandidate: Boolean get() = detector.inCandidate
    val noiseFloorDb: Float get() = detector.noiseFloorDb
    val totalSamples: Long get() = detector.totalSamples

    fun process(frame: ShortArray, count: Int) {
        context.write(frame, count)
        detector.process(frame, count)?.let(::detected)
        publishReady(context.ready())
    }

    fun flush() {
        detector.flush()?.let(::detected)
        publishReady(context.flush())
        check(pending.isEmpty()) { "Detected events lost their captured context" }
    }

    /** A source restart is a recording gap, not a silent interval or fake post-roll. */
    fun discontinuity(newOriginMs: Long) {
        flush()
        originMs = newOriginMs
        detector = CandidateDetector(config)
        context = ContextualAudioBuffer(config)
    }

    private fun detected(audio: CandidateAudio) {
        val rms = FeatureExtractor.rmsDb(audio.pcm, audio.pcm.size)
        val peak = audio.pcm.maxOfOrNull { abs(it.toInt()) } ?: 0
        val peakDb = if (peak == 0) -90f else
            (20 * kotlin.math.log10(peak / 32768.0)).toFloat().coerceAtLeast(-90f)
        val snr = rms - audio.noiseFloorDb
        val event = NightEvent(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            type = NightEventType.UNKNOWN,
            startMs = audio.startMs(originMs, config.sampleRate),
            endMs = audio.endMs(originMs, config.sampleRate),
            confidence = 0f,
            detectionConfidence = (0.5f + snr / 40f).coerceIn(0f, 1f),
            classificationStatus = ClassificationStatus.PENDING,
            clipStatus = ClipStatus.PENDING,
            features = mapOf(
                "rmsDb" to rms, "peakDb" to peakDb, "noiseFloorDb" to audio.noiseFloorDb,
                "snrDb" to snr, "durationMs" to audio.pcm.size * 1000f / config.sampleRate,
                "digitalGainDb" to config.digitalGainDb
            )
        )
        pending[event.id] = event
        onDetected(event)
        context.add(event.id, audio)
    }

    private fun publishReady(ready: List<ContextualCandidate>) {
        for (item in ready) {
            val event = checkNotNull(pending.remove(item.id)) { "Unknown context event" }
            onContextReady(event, item.audio)
        }
    }
}
