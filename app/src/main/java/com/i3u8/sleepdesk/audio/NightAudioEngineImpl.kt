package com.i3u8.sleepdesk.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.math.tanh

/** Detection owns the event; background inference only enriches it. */
class NightAudioEngineImpl(
    context: Context,
    private val clipStore: AudioClipStore = AudioClipStore(context)
) : NightAudioEngine {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    @Volatile private var recordThread: Thread? = null
    @Volatile private var activeRecorder: AudioRecord? = null
    @Volatile private var listener: NightAudioListener? = null
    @Volatile private var screenOnNearby = false
    @Volatile private var lastScreenChangeMs: Long? = null

    override fun setListener(listener: NightAudioListener?) { this.listener = listener }
    override fun isRunning(): Boolean = running.get() || recordThread?.isAlive == true
    override fun onAuxScreenChanged(isOn: Boolean) {
        screenOnNearby = isOn
        lastScreenChangeMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    override fun start(sessionId: String, config: AudioAlgoConfig) {
        if (recordThread?.isAlive == true || !running.compareAndSet(false, true)) return
        recordThread = Thread({ runSession(sessionId, config) }, "night-audio").also {
            it.priority = Thread.NORM_PRIORITY - 1
            it.start()
        }
    }

    /** Blocking drain; the service invokes this off the Android main thread. */
    override fun stop() {
        running.set(false)
        try { activeRecorder?.stop() } catch (_: Exception) { }
        val thread = recordThread
        if (thread != Thread.currentThread()) thread?.join()
        if (recordThread === thread) recordThread = null
    }

    private fun runSession(sessionId: String, config: AudioAlgoConfig) {
        val publicationFailed = AtomicBoolean(false)
        fun publicationFailure(failure: Throwable) {
            if (publicationFailed.compareAndSet(false, true)) {
                running.set(false)
                Log.e(TAG, "event persistence cannot keep up; stopping capture", failure)
                listener?.onEngineError(failure)
            }
        }
        val publisher = EventUpdatePublisher(256, { listener?.onEvent(it) }, ::publicationFailure)
        fun publish(event: NightEvent) {
            val pinned = event.copy(sessionId = sessionId)
            if (!publisher.publish(pinned)) {
                if (!publisher.isClosed) {
                    publicationFailure(EventPublicationException("Sound event storage queue is full"))
                } else {
                    // Never bypass the queue: that could let a late result overtake a
                    // previously queued PENDING update. Stop converts unfinished events to FAILED.
                    Log.w(TAG, "analysis update arrived after publication closed")
                }
            }
        }
        val processor = SoundEventProcessor(
            config, { YamNetSoundModel(appContext) },
            { event, audio ->
                clipStore.encodeAac(
                    sessionId, NightEventType.UNKNOWN, audio.clipPcm, config.sampleRate,
                    config.aacBitrate, event.startMs, event.id
                )?.second
            }, ::publish
        )
        val analysis = BackgroundAudioQueue(
            config.maxAnalysisQueue, processor::process, processor::cancel, processor::close
        )
        try {
            loop(config, ::publish) { event, audio -> analysis.submit(event, audio) }
        } catch (failure: Throwable) {
            if (running.get()) {
                Log.e(TAG, "capture failed", failure)
                listener?.onEngineError(failure)
            }
        } finally {
            running.set(false)
            if (!analysis.finish()) Log.w(TAG, "analysis shutdown deadline exceeded")
            if (!publisher.finish()) {
                Log.w(TAG, "event publisher still draining")
            }
        }
    }

    private fun loop(
        config: AudioAlgoConfig,
        onDetected: (NightEvent) -> Unit,
        onContext: (NightEvent, CandidateAudio) -> Unit
    ) {
        val sr = config.sampleRate
        val hopSamples = (sr * config.hopMs / 1000).coerceAtLeast(1)
        val initial = if (config.preferUnprocessedSource) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.MIC
        var handle = openRecorder(initial, sr) ?: return
        activeRecorder = handle.recorder
        val hop = ShortArray(hopSamples)
        val probeLevels = ArrayDeque<Float>()
        var probed = !config.fallbackMicIfLowGain || handle.source == MediaRecorder.AudioSource.MIC
        val probeHops = (config.lowGainProbeMs / config.hopMs).coerceAtLeast(40)
        var pipeline: EventDetectionPipeline? = null
        try {
            handle.recorder.startRecording()
            fun withCaptureInfo(event: NightEvent): NightEvent {
                val nearScreen = screenOnNearby || (lastScreenChangeMs?.let {
                    SystemClock.elapsedRealtime() - it < 30_000L
                } ?: false)
                return event.copy(features = event.features + mapOf(
                    "audioSource" to handle.source.toFloat(),
                    "screenOnNearby" to if (nearScreen) 1f else 0f
                ))
            }
            val stream = EventDetectionPipeline(
                config, System.currentTimeMillis(),
                { onDetected(withCaptureInfo(it)) },
                { event, audio -> onContext(withCaptureInfo(event), audio) }
            )
            pipeline = stream
            Log.i(TAG, "recording source=${handle.source} margin=${config.effectiveMarginDb()}")
            while (running.get()) {
                val n = handle.recorder.read(hop, 0, hopSamples)
                if (n < 0) {
                    if (!running.get()) break
                    throw IllegalStateException("AudioRecord read=$n")
                }
                if (n == 0) continue
                if (!probed && !stream.inCandidate) {
                    probeLevels.addLast(FeatureExtractor.rmsDb(hop, n))
                    if (probeLevels.size >= probeHops) {
                        val median = probeLevels.sorted()[probeLevels.size / 2]
                        if (median < config.lowGainDbThreshold && handle.source != MediaRecorder.AudioSource.MIC) {
                            applyDigitalGain(hop, n, config.digitalGainDb)
                            stream.process(hop, n)
                            stream.flush()
                            try { handle.recorder.stop() } catch (_: Exception) { }
                            handle.recorder.release()
                            activeRecorder = null
                            handle = openRecorder(MediaRecorder.AudioSource.MIC, sr) ?: return
                            activeRecorder = handle.recorder
                            handle.recorder.startRecording()
                            stream.discontinuity(System.currentTimeMillis())
                            probeLevels.clear()
                            probed = true
                            continue
                        }
                        probed = true
                    }
                }
                applyDigitalGain(hop, n, config.digitalGainDb)
                stream.process(hop, n)
                listener?.onNoiseFloor(stream.noiseFloorDb)
            }
        } finally {
            try {
                pipeline?.flush()
            } finally {
                try { handle.recorder.stop() } catch (_: Exception) { }
                handle.recorder.release()
                activeRecorder = null
            }
        }
    }

    private data class RecordingHandle(val recorder: AudioRecord, val source: Int)

    private fun openRecorder(source: Int, sr: Int): RecordingHandle? {
        val minimum = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) {
            listener?.onEngineError(IllegalStateException("AudioRecord minBuf=$minimum"))
            return null
        }
        val recorder = try {
            AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, sr) * 2)
        } catch (failure: Exception) {
            listener?.onEngineError(failure)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            if (source != MediaRecorder.AudioSource.MIC) return openRecorder(MediaRecorder.AudioSource.MIC, sr)
            listener?.onEngineError(IllegalStateException("AudioRecord not initialized"))
            return null
        }
        return RecordingHandle(recorder, source)
    }

    /** Keep the previously recorded gain contract; no per-clip loudness normalization. */
    private fun applyDigitalGain(frame: ShortArray, count: Int, gainDb: Float) {
        if (count <= 0 || gainDb == 0f) return
        val gain = 10.0.pow((gainDb / 20.0).toDouble()).toFloat()
        if (!gain.isFinite() || gain <= 0f) return
        val denominator = tanh(gain.toDouble()).toFloat().coerceAtLeast(1e-6f)
        for (i in 0 until count) {
            val value = tanh((frame[i] / 32768f * gain).toDouble()).toFloat() / denominator
            frame[i] = (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    companion object { private const val TAG = "NightAudioEngine" }
}
