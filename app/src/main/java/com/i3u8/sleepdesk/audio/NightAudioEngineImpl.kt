package com.i3u8.sleepdesk.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Doc-aligned night ambient engine: relative energy gate → candidate → rule classify → AAC clip.
 * Defaults prioritize recall (far desk / soft events) over precision.
 * @see docs/audio-algo.md
 */
class NightAudioEngineImpl(
    context: Context,
    private val clipStore: AudioClipStore = AudioClipStore(context)
) : NightAudioEngine {

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var recordThread: Thread? = null
    @Volatile private var listener: NightAudioListener? = null
    @Volatile private var config = AudioAlgoConfig()
    @Volatile private var sessionId: String = ""
    @Volatile private var screenOnNearby = false
    @Volatile private var lastScreenChangeMs: Long? = null

    private var lastSnoreClipMs: Long? = null
    private var lastSnoreEventMs: Long? = null
    private var sessionStartMs = 0L
    private val hourlyClipCounts = HashMap<NightEventType, Int>()
    private var hourBucket = 0L

    private lateinit var detector: CandidateDetector

    override fun setListener(listener: NightAudioListener?) {
        this.listener = listener
    }

    override fun isRunning(): Boolean = running.get()

    override fun onAuxScreenChanged(isOn: Boolean) {
        screenOnNearby = isOn
        lastScreenChangeMs = SystemClock.elapsedRealtime()
    }

    override fun start(sessionId: String, config: AudioAlgoConfig) {
        if (!running.compareAndSet(false, true)) return
        this.sessionId = sessionId
        this.config = config
        this.sessionStartMs = System.currentTimeMillis()
        detector = CandidateDetector(config)
        lastSnoreClipMs = null
        lastSnoreEventMs = null
        hourlyClipCounts.clear()
        hourBucket = 0L

        recordThread = Thread({
            try {
                loop()
            } catch (t: Throwable) {
                Log.e(TAG, "engine crashed", t)
                listener?.onEngineError(t)
            } finally {
                running.set(false)
            }
        }, "night-audio").also {
            it.priority = Thread.NORM_PRIORITY - 1
            it.start()
        }
    }

    override fun stop() {
        running.set(false)
        recordThread?.join(1500)
        recordThread = null
    }

    private fun loop() {
        val sr = config.sampleRate
        val hopSamples = (sr * config.hopMs / 1000).coerceAtLeast(1)
        var source = initialAudioSource()
        var recorder = openRecorder(source, sr) ?: return

        val hop = ShortArray(hopSamples)
        val probeLevels = ArrayDeque<Float>()
        var probed = !config.fallbackMicIfLowGain || source == MediaRecorder.AudioSource.MIC
        val probeHops = (config.lowGainProbeMs / config.hopMs).coerceAtLeast(40)

        try {
            recorder.startRecording()
            sessionStartMs = System.currentTimeMillis()
            Log.i(TAG, "recording source=$source margin=${config.effectiveMarginDb()} sens=${config.sensitivity}")
            while (running.get()) {
                val n = recorder.read(hop, 0, hopSamples)
                if (n <= 0) continue

                if (!probed && !detector.inCandidate) {
                    val rms = FeatureExtractor.rmsDb(hop, n)
                    probeLevels.addLast(rms)
                    if (probeLevels.size >= probeHops) {
                        val med = probeLevels.sorted()[probeLevels.size / 2]
                        if (med < config.lowGainDbThreshold && source != MediaRecorder.AudioSource.MIC) {
                            Log.i(TAG, "low gain on UNPROCESSED (median=$med dB) → fallback MIC")
                            try {
                                recorder.stop()
                            } catch (_: Exception) {
                            }
                            recorder.release()
                            source = MediaRecorder.AudioSource.MIC
                            recorder = openRecorder(source, sr) ?: return
                            recorder.startRecording()
                            detector.resetNoiseFloor()
                            probeLevels.clear()
                        }
                        probed = true
                    }
                }

                processHop(hop, n)
            }
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
        }
    }

    private fun openRecorder(source: Int, sr: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            listener?.onEngineError(IllegalStateException("AudioRecord minBuf=$minBuf"))
            return null
        }
        val bufSize = maxOf(minBuf, sr)
        val recorder = try {
            AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        } catch (e: SecurityException) {
            listener?.onEngineError(e)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            if (source != MediaRecorder.AudioSource.MIC) {
                Log.w(TAG, "source $source failed init, trying MIC")
                return openRecorder(MediaRecorder.AudioSource.MIC, sr)
            }
            listener?.onEngineError(IllegalStateException("AudioRecord not initialized"))
            return null
        }
        return recorder
    }

    private fun initialAudioSource(): Int {
        if (config.preferUnprocessedSource) {
            return try {
                MediaRecorder.AudioSource.UNPROCESSED
            } catch (_: Exception) {
                MediaRecorder.AudioSource.MIC
            }
        }
        return MediaRecorder.AudioSource.MIC
    }

    private fun processHop(frame: ShortArray, count: Int) {
        applyDigitalGain(frame, count, config.digitalGainDb)
        val candidate = detector.process(frame, count)
        listener?.onNoiseFloor(detector.noiseFloorDb)
        if (candidate != null) finishCandidate(candidate)
    }

    /** Apply fixed software gain while softly saturating peaks; no system AGC is enabled. */
    private fun applyDigitalGain(frame: ShortArray, count: Int, gainDb: Float) {
        if (count <= 0 || gainDb == 0f) return
        val linearGain = 10.0.pow((gainDb / 20.0).toDouble()).toFloat()
        if (!linearGain.isFinite() || linearGain <= 0f) return
        val tanhGain = tanh(linearGain.toDouble()).toFloat().coerceAtLeast(1e-6f)
        for (i in 0 until count) {
            val normalized = frame[i] / 32768f
            val gained = normalized * linearGain
            val saturated = tanh(gained.toDouble()).toFloat() / tanhGain
            frame[i] = (saturated.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    private fun finishCandidate(candidate: CandidateAudio) {
        val sr = config.sampleRate
        val startMs = candidate.startMs(sessionStartMs, sr)
        val endMs = candidate.endMs(sessionStartMs, sr)
        val durationMs = candidate.pcm.size * 1000f / sr
        val feats = FeatureExtractor.extract(candidate.pcm, sr, durationMs)
        val nearScreen = screenOnNearby || (lastScreenChangeMs?.let {
            SystemClock.elapsedRealtime() - it < 30_000L
        } ?: false)
        val (type, conf) = RuleClassifier.classify(feats, nearScreen, candidate.noiseFloorDb)

        if (type == NightEventType.FALSE_TRIGGER || conf < config.confidenceFloor) return

        if (type == NightEventType.SNORE) {
            val now = candidate.endSample * 1000 / sr
            if (lastSnoreEventMs?.let { now - it < config.snoreMergeMs } == true) {
                if (lastSnoreClipMs?.let { now - it < config.snoreClipIntervalMs } == true) return
            }
            lastSnoreEventMs = now
        }

        val saveClip = shouldSaveClip(type)
        var eventId = UUID.randomUUID().toString().replace("-", "").take(8)
        var clipPath: String? = null

        if (saveClip && allowClipThisHour(type, candidate.endSample)) {
            val encoded = clipStore.encodeAac(
                sessionId = sessionId,
                type = type,
                pcm = candidate.clipPcm,
                sampleRate = sr,
                bitrate = config.aacBitrate,
                timeMs = startMs,
                eventId = eventId
            )
            if (encoded != null) {
                eventId = encoded.first
                clipPath = encoded.second
                if (type == NightEventType.SNORE) lastSnoreClipMs = candidate.endSample * 1000 / sr
                bumpHourly(type)
            }
        }

        val event = NightEvent(
            id = eventId,
            type = type,
            startMs = startMs,
            endMs = endMs,
            confidence = conf,
            clipRelativePath = clipPath,
            features = feats.toMap() + mapOf(
                "noiseFloorDb" to candidate.noiseFloorDb,
                "snrDb" to (feats.rmsDb - candidate.noiseFloorDb)
            ) + if (clipPath != null) candidate.clipFeatures(sr) else emptyMap(),
            algoVersion = RuleClassifier.VERSION
        )
        listener?.onEvent(event)
    }

    private fun shouldSaveClip(type: NightEventType): Boolean = when (type) {
        NightEventType.SNORE,
        NightEventType.COUGH,
        NightEventType.NIGHT_WAKE_SOUND,
        NightEventType.ABNORMAL -> true
        NightEventType.SPEECH -> config.saveSpeechClips
        NightEventType.ENV_NOISE,
        NightEventType.FALSE_TRIGGER -> false
    }

    private fun allowClipThisHour(type: NightEventType, endSample: Long): Boolean {
        val hour = endSample / (config.sampleRate * 3_600L)
        if (hour != hourBucket) {
            hourBucket = hour
            hourlyClipCounts.clear()
        }
        val n = hourlyClipCounts[type] ?: 0
        return n < 120
    }

    private fun bumpHourly(type: NightEventType) {
        hourlyClipCounts[type] = (hourlyClipCounts[type] ?: 0) + 1
    }

    companion object {
        private const val TAG = "NightAudioEngine"
    }
}
