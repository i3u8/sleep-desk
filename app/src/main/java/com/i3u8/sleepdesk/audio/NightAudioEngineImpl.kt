package com.i3u8.sleepdesk.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Doc-aligned night ambient engine: relative energy gate → candidate → rule classify → AAC clip.
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

    // Noise floor: p20 over ~45s of idle hop dBFS
    private val floorWindow = ArrayDeque<Float>()
    private var noiseFloor = -50f
    private var smoothedDb = -60f

    // Candidate state
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
        noiseFloor = -50f
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
        val source = pickAudioSource()
        val minBuf = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            listener?.onEngineError(IllegalStateException("AudioRecord minBuf=$minBuf"))
            return
        }
        val bufSize = maxOf(minBuf, sr) // ≥1s
        val recorder = try {
            AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        } catch (e: SecurityException) {
            listener?.onEngineError(e)
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            listener?.onEngineError(IllegalStateException("AudioRecord not initialized"))
            recorder.release()
            return
        }

        val hop = ShortArray(hopSamples)
        try {
            recorder.startRecording()
            while (running.get()) {
                val n = recorder.read(hop, 0, hopSamples)
                if (n <= 0) continue
                processHop(hop, n)
            }
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
            // Drop open candidate on stop (user ending session)
            inCandidate = false
        }
    }

    private fun pickAudioSource(): Int {
        if (config.preferUnprocessedSource) {
            // UNPROCESSED = 9 (API 24+)
            return try {
                MediaRecorder.AudioSource.UNPROCESSED
            } catch (_: Exception) {
                MediaRecorder.AudioSource.MIC
            }
        }
        return MediaRecorder.AudioSource.MIC
    }

    private fun processHop(frame: ShortArray, count: Int) {
        val before = ring.totalSamples()
        ring.write(frame, 0, count)
        val hopStartSample = before
        val now = System.currentTimeMillis()

        val rms = FeatureExtractor.rmsDb(frame, count)
        // EMA ~200ms: alpha ≈ hopMs/200
        val alpha = (config.hopMs / 200f).coerceIn(0.05f, 0.5f)
        smoothedDb = smoothedDb * (1 - alpha) + rms * alpha

        val margin = config.marginDb
        val over = smoothedDb > noiseFloor + margin

        if (!inCandidate) {
            updateNoiseFloor(rms)
            if (over) {
                overCount++
                val need = (config.minCandidateMs / config.hopMs).coerceAtLeast(1)
                if (overCount >= need) {
                    // Session head silence: skip first 30s
                    if (now - sessionStartMs < 30_000L) {
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
            if (!over || smoothedDb < noiseFloor + margin / 2f) {
                underCount++
            } else {
                underCount = 0
            }
            val elapsedMs = now - candidateStartMs
            val endBySilence = underCount >= (150 / config.hopMs).coerceAtLeast(1)
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

    private fun updateNoiseFloor(rmsDb: Float) {
        // Only idle hops
        floorWindow.addLast(rmsDb)
        val maxN = (45_000 / config.hopMs).coerceAtLeast(100)
        while (floorWindow.size > maxN) floorWindow.removeFirst()
        if (floorWindow.size >= 40) {
            val sorted = floorWindow.sorted()
            val idx = (sorted.size * 0.20).toInt().coerceIn(0, sorted.lastIndex)
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
            // Prefer keeping event center
            val mid = (startSample + endSample) / 2
            from = mid - maxSamples / 2
            to = from + maxSamples
        }
        val pcm = ring.sliceSamples(from, to)
        if (pcm.isEmpty()) return

        val durMs = (endMs - startMs).toFloat().coerceAtLeast(config.minCandidateMs.toFloat())
        val feats = FeatureExtractor.extract(pcm, sr, durMs)
        val nearScreen = screenOnNearby || (System.currentTimeMillis() - lastScreenChangeMs < 30_000L)
        val (type, conf) = RuleClassifier.classify(feats, nearScreen)

        if (type == NightEventType.FALSE_TRIGGER || conf < config.confidenceFloor) return

        // Snore merge cooldown
        if (type == NightEventType.SNORE) {
            val now = System.currentTimeMillis()
            if (now - lastSnoreEventMs < config.snoreMergeMs) {
                // Same bout — maybe skip indexing entirely or only re-clip periodically
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
            algoVersion = "audio-v1"
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
