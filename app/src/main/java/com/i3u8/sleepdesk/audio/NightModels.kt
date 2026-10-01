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
    val algoVersion: String = "audio-v1"
)

data class AudioAlgoConfig(
    val sampleRate: Int = 16_000,
    val windowMs: Int = 30,
    val hopMs: Int = 15,
    val marginDb: Float = 10f,
    val preRollMs: Int = 1_500,
    val postRollMs: Int = 1_500,
    val maxClipMs: Int = 8_000,
    val minCandidateMs: Int = 250,
    val maxCandidateMs: Int = 8_000,
    val saveSpeechClips: Boolean = true,
    val snoreClipIntervalMs: Long = 5 * 60_000L,
    val snoreMergeMs: Long = 8_000L,
    val preferUnprocessedSource: Boolean = true,
    val confidenceFloor: Float = 0.55f,
    val aacBitrate: Int = 40_000
)

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
