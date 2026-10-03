package com.i3u8.sleepdesk.audio

enum class NightEventType {
    SNORE, COUGH, SPEECH, NIGHT_WAKE_SOUND, ENV_NOISE, ABNORMAL, FALSE_TRIGGER,
    UNKNOWN, BREATHING, BED_MOVEMENT, CONTACT_SOUND
}

enum class ClassificationStatus { LEGACY, PENDING, SUGGESTED, UNCERTAIN, FAILED }

enum class ClipStatus { LEGACY, PENDING, SAVED, QUOTA_REACHED, DISABLED, FAILED }

object AudioPipelineVersion {
    const val CURRENT = "audio-v2.0"
    const val PREPROCESSING = "pcm16-16k-fixed-gain-v1"
}

data class NightEvent(
    val id: String,
    val type: NightEventType,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val clipRelativePath: String? = null,
    val features: Map<String, Float> = emptyMap(),
    val algoVersion: String = AudioPipelineVersion.CURRENT,
    /** Detection support, separate from uncalibrated classification support [confidence]. */
    val detectionConfidence: Float = 0f,
    val classificationStatus: ClassificationStatus = ClassificationStatus.LEGACY,
    val classScores: Map<String, Float> = emptyMap(),
    val suggestedTypes: List<String> = emptyList(),
    val modelVersion: String? = null,
    val classificationReason: String? = null,
    val clipStatus: ClipStatus = ClipStatus.LEGACY,
    val reviewFlags: Set<String> = emptySet(),
    val userLabel: String? = null,
    /** Pins async updates to their originating session, even after tracking stops. */
    val sessionId: String? = null,
    /** Monotone within an event: detected=0, context/clip=1, final analysis=2. */
    val revision: Long = 0
)

/**
 * The energy gate favors far-desk / nightstand placement. Classification
 * separately rejects weak evidence instead of interpreting every burst as waking.
 */
data class AudioAlgoConfig(
    val sampleRate: Int = 16_000,
    val windowMs: Int = 30,
    val hopMs: Int = 15,
    /** Base margin above noise floor; divided by [sensitivity]. Default ~6 dB @ sens 1.25 → ~4.8 dB. */
    val marginDb: Float = 6f,
    /** >1.0 = more sensitive (lower effective margin). Default high. */
    val sensitivity: Float = 1.25f,
    val preRollMs: Int = 2_000,
    val postRollMs: Int = 2_000,
    val maxClipMs: Int = 12_000,
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
    /** Fixed software gain applied before energy/features/clip encoding; system AGC remains unchanged. */
    val digitalGainDb: Float = 6f,
    val aacBitrate: Int = 40_000,
    val sessionWarmupMs: Long = 12_000L,
    val maxAnalysisQueue: Int = 8,
    val maxClipsPerHour: Int = 120,
    val maxClipsPerSession: Int = 360
) {
    fun effectiveMarginDb(): Float = (marginDb / sensitivity.coerceAtLeast(0.5f)).coerceIn(3f, 18f)

    companion object {
        /** Far-field/high-sensitivity preset used by the v0.2.3 follow-up. */
        fun farField() = AudioAlgoConfig()
    }
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
