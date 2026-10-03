package com.i3u8.sleepdesk.audio

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Frozen CPU model, no per-clip gain normalization or learned application-specific head. */
class YamNetSoundModel(context: Context) : SoundModel {
    override val version = "yamnet-$MODEL_SHA256-pcm16-window15600-hop7680-v1"
    private val labels: List<String>
    private val modelBuffer: ByteBuffer
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(WINDOW_SAMPLES * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(CLASS_COUNT * 4).order(ByteOrder.nativeOrder())
    private var closed = false

    init {
        try {
            labels = context.assets.open(LABEL_ASSET).bufferedReader(Charsets.UTF_8).use {
                it.readLines()
            }
            require(labels.size == CLASS_COUNT && labels.toSet().size == CLASS_COUNT &&
                labels.none { it.isBlank() } && labels[0] == "Speech" && "Snoring" in labels) {
                "Invalid YAMNet labels"
            }
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            require(bytes.size == MODEL_BYTES) { "Unexpected YAMNet model size" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(digest == MODEL_SHA256) { "Unexpected YAMNet SHA-256" }
            modelBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            modelBuffer.put(bytes)
            modelBuffer.rewind()
            val loaded = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(2))
            try {
                require(loaded.inputTensorCount == 1 && loaded.outputTensorCount == 3)
                require(loaded.getInputTensor(0).dataType() == DataType.FLOAT32)
                loaded.resizeInput(0, intArrayOf(WINDOW_SAMPLES))
                loaded.allocateTensors()
                interpreter = loaded
            } catch (failure: Exception) {
                loaded.close()
                throw failure
            } catch (failure: LinkageError) {
                loaded.close()
                throw IllegalStateException("YAMNet native initialization failed", failure)
            }
        } catch (failure: LinkageError) {
            // The pipeline catches Exception and must retain the candidate as FAILED.
            throw IllegalStateException("YAMNet native runtime unavailable", failure)
        }
    }

    @Synchronized
    override fun infer(pcm: ShortArray, sampleRate: Int): ModelEvidence {
        check(!closed) { "YAMNet model is closed" }
        require(sampleRate == SAMPLE_RATE) { "YAMNet requires 16000 Hz mono PCM" }
        require(pcm.isNotEmpty()) { "Empty YAMNet input" }
        try {
            val frames = mutableListOf<ModelFrame>()
            var start = 0
            while (start < pcm.size) {
                checkInterrupted()
                val validEnd = minOf(start.toLong() + WINDOW_SAMPLES, pcm.size.toLong()).toInt()
                input.clear()
                for (i in 0 until WINDOW_SAMPLES) {
                    input.putFloat(if (i < validEnd - start) pcm[start + i] / 32768f else 0f)
                }
                input.rewind()
                output.clear()
                // 2.17 Java API permits selecting only scores; embeddings/logmel stay native.
                interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), mutableMapOf<Int, Any>(0 to output))
                // Native invocation is synchronous; cancellation takes effect at window boundaries.
                checkInterrupted()
                val tensor = interpreter.getOutputTensor(0)
                check(tensor.dataType() == DataType.FLOAT32 &&
                    tensor.shape().contentEquals(intArrayOf(1, CLASS_COUNT))) {
                    "Unexpected YAMNet scores shape"
                }
                output.rewind()
                val scores = labels.associateWith {
                    output.float.also { score ->
                        check(score.isFinite() && score in 0f..1f) { "Invalid YAMNet output" }
                    }
                }
                frames.add(ModelFrame(start, validEnd, scores))
                if (validEnd == pcm.size) break
                start += HOP_SAMPLES
            }
            return ModelEvidence(version, frames)
        } catch (failure: LinkageError) {
            throw IllegalStateException("YAMNet native inference failed", failure)
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("YAMNet analysis cancelled")
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            try {
                interpreter.close()
            } catch (failure: LinkageError) {
                throw IllegalStateException("YAMNet native close failed", failure)
            }
        }
    }

    companion object {
        const val MODEL_ASSET = "models/yamnet.tflite"
        const val LABEL_ASSET = "models/yamnet-labels.txt"
        const val MODEL_SHA256 = "141fba1cdaae842c816f28edc4937e8b4f0af4c8df21862ccc6b52dc567993c3"
        const val MODEL_BYTES = 16_096_668
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SAMPLES = 15_600
        const val HOP_SAMPLES = 7_680
        const val CLASS_COUNT = 521
    }
}
