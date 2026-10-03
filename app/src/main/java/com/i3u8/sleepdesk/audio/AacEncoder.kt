package com.i3u8.sleepdesk.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer

/**
 * Encode mono PCM16 → AAC-LC M4A via MediaCodec + MediaMuxer.
 */
object AacEncoder {
    private const val TAG = "AacEncoder"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val TIMEOUT_US = 10_000L

    fun encodeMonoPcm16(
        pcm: ShortArray,
        sampleRate: Int,
        bitrate: Int,
        outFile: File
    ): Boolean {
        if (pcm.isEmpty()) return false
        outFile.parentFile?.mkdirs()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var pcmIndex = 0
            var inputDone = false
            var outputDone = false
            var presentationUs = 0L
            val bytesPerSample = 2
            val deadline = SystemClock.elapsedRealtime() + 30_000L

            while (!outputDone) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("AAC cancelled")
                check(SystemClock.elapsedRealtime() < deadline) { "AAC encoder timeout" }
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIx >= 0) {
                        val inBuf = codec.getInputBuffer(inIx)!!
                        inBuf.clear()
                        val capacitySamples = inBuf.capacity() / bytesPerSample
                        val remaining = pcm.size - pcmIndex
                        if (remaining <= 0) {
                            codec.queueInputBuffer(
                                inIx, 0, 0, presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val n = minOf(capacitySamples, remaining)
                            for (i in 0 until n) {
                                val s = pcm[pcmIndex + i].toInt()
                                inBuf.put((s and 0xff).toByte())
                                inBuf.put(((s shr 8) and 0xff).toByte())
                            }
                            val size = n * bytesPerSample
                            codec.queueInputBuffer(inIx, 0, size, presentationUs, 0)
                            presentationUs += n * 1_000_000L / sampleRate
                            pcmIndex += n
                        }
                    }
                }

                val outIx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                    outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IllegalStateException("format changed twice")
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            return outFile.exists() && outFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "AAC encode failed", e)
            if (outFile.exists()) outFile.delete()
            return false
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }
}
