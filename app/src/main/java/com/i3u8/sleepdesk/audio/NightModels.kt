package com.i3u8.sleepdesk.audio

enum class NightEventType {
    SNORE, COUGH, SPEECH, NIGHT_WAKE_SOUND, ENV_NOISE, ABNORMAL, FALSE_TRIGGER
}

data class NightEvent(
    val id: String,
    val type: NightEventType,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val clipRelativePath: String? = null,
    val features: Map<String, Float> = emptyMap(),
    val algoVersion: String = "audio-v1.1"
)

/**
 * Defaults lean **high sensitivity** (fewer misses, more false positives OK)
 * and favor far-desk / nightstand placement.
 */
data class AudioAlgoConfig(
    val sampleRate: Int = 16_000,
    val windowMs: Int = 30,
    val hopMs: Int = 15,
    /** Base margin above noise floor; divided by [sensitivity]. Default ~6 dB @ sens 1.25 → ~4.8 dB. */
    val marginDb: Float = 6f,
    /** >1.0 = more sensitive (lower effective margin). Default high. */
    val sensitivity: Float = 1.25f,
    val preRollMs: Int = 1_500,
    val postRollMs: Int = 1_500,
    val maxClipMs: Int = 8_000,
    val minCandidateMs: Int = 150,
    val maxCandidateMs: Int = 8_000,
    val saveSpeechClips: Boolean = true,
    val snoreClipIntervalMs: Long = 5 * 60_000L,
    val snoreMergeMs: Long = 6_000L,
    val preferUnprocessedSource: Boolean = true,
    /** If UNPROCESSED median idle level stays below this, reopen with MIC (AGC helps far desk). */
    val fallbackMicIfLowGain: Boolean = true,
    val lowGainDbThreshold: Float = -52f,
    val lowGainProbeMs: Int = 3_500,
    /** Longer EMA helps distant weak bursts rise above floor. */
    val energySmoothMs: Int = 350,
    val floorWindowMs: Int = 60_000,
    val floorPercentile: Float = 0.15f,
    val confidenceFloor: Float = 0.42f,
    val aacBitrate: Int = 40_000,
    val sessionWarmupMs: Long = 12_000L
) {
    fun effectiveMarginDb(): Float = (marginDb / sensitivity.coerceAtLeast(0.5f)).coerceIn(3f, 18f)
}

interface NightAudioListener {
    fun onEvent(event: NightEvent)
    fun onNoiseFloor(dbfs: Float) {}
    fun onEngineError(t: Throwable) {}
}

interface NightAudioEngine {
    fun start(sessionId: String, config: AudioAlgoConfig = AudioAlgoConfig())
    fun stop()
    fun isRunning(): Boolean
    fun setListener(listener: NightAudioListener?)
    fun onAuxScreenChanged(isOn: Boolean) {}
    fun onAuxMotionHigh(bucketStartMs: Long, energy: Double) {}
}
