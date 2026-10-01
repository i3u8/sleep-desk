package com.i3u8.sleepdesk.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    @Volatile private var lastScreenChangeMs = 0L

    private val floorWindow = ArrayDeque<Float>()
    private var noiseFloor = -58f
    private var smoothedDb = -60f

    private var inCandidate = false
    private var candidateStartSample = 0L
    private var candidateStartMs = 0L
    private var overCount = 0
    private var underCount = 0
    private var lastSnoreClipMs = 0L
    private var lastSnoreEventMs = 0L
    private var sessionStartMs = 0L
    private val hourlyClipCounts = HashMap<NightEventType, Int>()
    private var hourBucket = 0L

    private lateinit var ring: PcmRingBuffer

    override fun setListener(listener: NightAudioListener?) {
        this.listener = listener
    }

    override fun isRunning(): Boolean = running.get()

    override fun onAuxScreenChanged(isOn: Boolean) {
        screenOnNearby = isOn
        lastScreenChangeMs = System.currentTimeMillis()
    }

    override fun start(sessionId: String, config: AudioAlgoConfig) {
        if (!running.compareAndSet(false, true)) return
        this.sessionId = sessionId
        this.config = config
        this.sessionStartMs = System.currentTimeMillis()
        ring = PcmRingBuffer(config.sampleRate * 6)
        floorWindow.clear()
        noiseFloor = -58f
        smoothedDb = -60f
        inCandidate = false
        overCount = 0
        underCount = 0
        lastSnoreClipMs = 0L
        lastSnoreEventMs = 0L
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
            Log.i(TAG, "recording source=$source margin=${config.effectiveMarginDb()} sens=${config.sensitivity}")
            while (running.get()) {
                val n = recorder.read(hop, 0, hopSamples)
                if (n <= 0) continue

                if (!probed && !inCandidate) {
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
                            floorWindow.clear()
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
            inCandidate = false
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
        val before = ring.totalSamples()
        ring.write(frame, 0, count)
        val hopStartSample = before
        val now = System.currentTimeMillis()

        val rms = FeatureExtractor.rmsDb(frame, count)
        val smoothMs = config.energySmoothMs.coerceAtLeast(100).toFloat()
        val alpha = (config.hopMs / smoothMs).coerceIn(0.03f, 0.5f)
        smoothedDb = smoothedDb * (1 - alpha) + rms * alpha

        val margin = config.effectiveMarginDb()
        val over = smoothedDb > noiseFloor + margin

        if (!inCandidate) {
            updateNoiseFloor(rms)
            if (over) {
                overCount++
                // Enter sooner: ~half of minCandidate for gate open (recall)
                val need = ((config.minCandidateMs / 2) / config.hopMs).coerceAtLeast(1)
                if (overCount >= need) {
                    if (now - sessionStartMs < config.sessionWarmupMs) {
                        overCount = 0
                    } else {
                        inCandidate = true
                        candidateStartSample = hopStartSample - overCount.toLong() * count
                        candidateStartMs = now - overCount.toLong() * config.hopMs
                        underCount = 0
                    }
                }
            } else {
                overCount = 0
            }
        } else {
            if (!over || smoothedDb < noiseFloor + margin * 0.45f) {
                underCount++
            } else {
                underCount = 0
            }
            val elapsedMs = now - candidateStartMs
            val endBySilence = underCount >= (100 / config.hopMs).coerceAtLeast(1)
            val endByMax = elapsedMs >= config.maxCandidateMs
            if (endBySilence || endByMax) {
                val endSample = ring.totalSamples()
                val endMs = now
                finishCandidate(candidateStartSample, endSample, candidateStartMs, endMs)
                inCandidate = false
                overCount = 0
                underCount = 0
            }
        }
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

    private fun updateNoiseFloor(rmsDb: Float) {
        floorWindow.addLast(rmsDb)
        val maxN = (config.floorWindowMs / config.hopMs).coerceAtLeast(100)
        while (floorWindow.size > maxN) floorWindow.removeFirst()
        if (floorWindow.size >= 30) {
            val sorted = floorWindow.sorted()
            val pct = config.floorPercentile.coerceIn(0.05f, 0.4f)
            val idx = (sorted.size * pct).toInt().coerceIn(0, sorted.lastIndex)
            noiseFloor = sorted[idx]
            listener?.onNoiseFloor(noiseFloor)
        }
    }

    private fun finishCandidate(startSample: Long, endSample: Long, startMs: Long, endMs: Long) {
        val sr = config.sampleRate
        val pre = (config.preRollMs / 1000.0 * sr).toLong()
        val post = (config.postRollMs / 1000.0 * sr).toLong()
        val maxSamples = (config.maxClipMs / 1000.0 * sr).toLong()
        var from = startSample - pre
        var to = endSample + post
        if (to - from > maxSamples) {
            val mid = (startSample + endSample) / 2
            from = mid - maxSamples / 2
            to = from + maxSamples
        }
        val pcm = ring.sliceSamples(from, to)
        if (pcm.isEmpty()) return

        val durMs = (endMs - startMs).toFloat().coerceAtLeast(config.minCandidateMs.toFloat())
        // Drop only ultra-short after silence merge
        if (durMs < config.minCandidateMs * 0.6f) return

        val feats = FeatureExtractor.extract(pcm, sr, durMs)
        val nearScreen = screenOnNearby || (System.currentTimeMillis() - lastScreenChangeMs < 30_000L)
        val (type, conf) = RuleClassifier.classify(feats, nearScreen)

        if (type == NightEventType.FALSE_TRIGGER || conf < config.confidenceFloor) return

        if (type == NightEventType.SNORE) {
            val now = System.currentTimeMillis()
            if (now - lastSnoreEventMs < config.snoreMergeMs) {
                if (now - lastSnoreClipMs < config.snoreClipIntervalMs) return
            }
            lastSnoreEventMs = now
        }

        val saveClip = shouldSaveClip(type)
        var eventId = UUID.randomUUID().toString().replace("-", "").take(8)
        var clipPath: String? = null

        if (saveClip && allowClipThisHour(type)) {
            val encoded = clipStore.encodeAac(
                sessionId = sessionId,
                type = type,
                pcm = pcm,
                sampleRate = sr,
                bitrate = config.aacBitrate,
                timeMs = startMs,
                eventId = eventId
            )
            if (encoded != null) {
                eventId = encoded.first
                clipPath = encoded.second
                if (type == NightEventType.SNORE) lastSnoreClipMs = System.currentTimeMillis()
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
            features = feats.toMap(),
            algoVersion = "audio-v1.2"
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

    private fun allowClipThisHour(type: NightEventType): Boolean {
        val hour = System.currentTimeMillis() / 3_600_000L
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
