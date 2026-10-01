package com.i3u8.sleepdesk.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class ClipFeatures(
    val rmsDb: Float,
    val peakDb: Float,
    val zcrMean: Float,
    val zcrStd: Float,
    val bandLow: Float,
    val bandMid: Float,
    val bandHigh: Float,
    val spectralCentroid: Float,
    val periodSec: Float,
    val periodicity: Float,
    val attackMs: Float,
    val durationMs: Float
) {
    fun toMap(): Map<String, Float> = mapOf(
        "rmsDb" to rmsDb,
        "peakDb" to peakDb,
        "zcrMean" to zcrMean,
        "zcrStd" to zcrStd,
        "bandLow" to bandLow,
        "bandMid" to bandMid,
        "bandHigh" to bandHigh,
        "spectralCentroid" to spectralCentroid,
        "periodSec" to periodSec,
        "periodicity" to periodicity,
        "attackMs" to attackMs,
        "durationMs" to durationMs
    )
}

object FeatureExtractor {
    private val LN10 = ln(10.0).toFloat()

    fun extract(pcm: ShortArray, sampleRate: Int, durationMsHint: Float): ClipFeatures {
        if (pcm.isEmpty()) {
            return ClipFeatures(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, durationMsHint)
        }
        var sumSq = 0.0
        var peak = 0
        val n = pcm.size
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
            val v = s.toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / n)
        val rmsDb = toDb(rms / 32768.0)
        val peakDb = toDb(peak / 32768.0)

        // ZCR over 20ms frames
        val frame = max(1, sampleRate / 50)
        val zcrs = ArrayList<Float>()
        var i = 0
        while (i + frame <= n) {
            var zc = 0
            for (j in i until i + frame - 1) {
                if ((pcm[j] >= 0) != (pcm[j + 1] >= 0)) zc++
            }
            zcrs.add(zc.toFloat() / frame)
            i += frame
        }
        val zcrMean = if (zcrs.isEmpty()) 0f else zcrs.average().toFloat()
        val zcrStd = if (zcrs.size < 2) 0f else {
            val m = zcrMean
            sqrt(zcrs.map { (it - m) * (it - m) }.average()).toFloat()
        }

        // Envelope for attack + autocorrelation (downsample to ~100 Hz)
        val hop = max(1, sampleRate / 100)
        val env = FloatArray((n + hop - 1) / hop)
        for (k in env.indices) {
            val start = k * hop
            val end = min(n, start + hop)
            var e = 0.0
            for (j in start until end) {
                val v = pcm[j].toDouble()
                e += v * v
            }
            env[k] = sqrt(e / (end - start)).toFloat()
        }
        val attackMs = estimateAttackMs(env, 100f)
        val (periodSec, periodicity) = estimatePeriodicity(env, 100f)

        // Spectral bands via small FFT on middle window
        val fftN = 512
        val bands = spectralBands(pcm, sampleRate, fftN)

        return ClipFeatures(
            rmsDb = rmsDb,
            peakDb = peakDb,
            zcrMean = zcrMean,
            zcrStd = zcrStd,
            bandLow = bands[0],
            bandMid = bands[1],
            bandHigh = bands[2],
            spectralCentroid = bands[3],
            periodSec = periodSec,
            periodicity = periodicity,
            attackMs = attackMs,
            durationMs = durationMsHint
        )
    }

    fun rmsDb(frame: ShortArray, count: Int): Float {
        if (count <= 0) return -90f
        var sum = 0.0
        for (i in 0 until count) {
            val v = frame[i].toDouble()
            sum += v * v
        }
        return toDb(sqrt(sum / count) / 32768.0)
    }

    private fun toDb(norm: Double): Float {
        val x = max(norm, 1e-9)
        return (20.0 * ln(x) / LN10).toFloat()
    }

    private fun estimateAttackMs(env: FloatArray, envHz: Float): Float {
        if (env.size < 4) return 999f
        val maxE = env.maxOrNull() ?: return 999f
        if (maxE < 1e-3f) return 999f
        val lo = maxE * 0.1f
        val hi = maxE * 0.9f
        var t10 = -1
        var t90 = -1
        for (i in env.indices) {
            if (t10 < 0 && env[i] >= lo) t10 = i
            if (t10 >= 0 && env[i] >= hi) {
                t90 = i
                break
            }
        }
        if (t10 < 0 || t90 < 0) return 999f
        return ((t90 - t10) / envHz) * 1000f
    }

    private fun estimatePeriodicity(env: FloatArray, envHz: Float): Pair<Float, Float> {
        val n = env.size
        if (n < 20) return 0f to 0f
        val mean = env.average().toFloat()
        var energy = 0.0
        for (v in env) {
            val d = v - mean
            energy += d * d
        }
        if (energy < 1e-6) return 0f to 0f
        // Search 0.3–3.0 s → lag in env frames
        val lagMin = max(1, (0.3f * envHz).toInt())
        val lagMax = min(n - 2, (3.0f * envHz).toInt())
        var best = 0.0
        var bestLag = lagMin
        for (lag in lagMin..lagMax) {
            var corr = 0.0
            val m = n - lag
            for (i in 0 until m) {
                corr += (env[i] - mean) * (env[i + lag] - mean)
            }
            corr /= energy
            if (corr > best) {
                best = corr
                bestLag = lag
            }
        }
        return (bestLag / envHz) to best.toFloat().coerceIn(0f, 1f)
    }

    /** Returns [low, mid, high, centroidHz] normalized band energies. */
    private fun spectralBands(pcm: ShortArray, sampleRate: Int, fftN: Int): FloatArray {
        val mid = pcm.size / 2
        val start = (mid - fftN / 2).coerceAtLeast(0)
        val re = FloatArray(fftN)
        val im = FloatArray(fftN)
        val copy = min(fftN, pcm.size - start)
        for (i in 0 until copy) {
            // Hann window
            val w = (0.5 - 0.5 * cos(2.0 * PI * i / (fftN - 1))).toFloat()
            re[i] = pcm[start + i] / 32768f * w
        }
        fftRadix2(re, im)
        val binHz = sampleRate.toFloat() / fftN
        var low = 0.0
        var midE = 0.0
        var high = 0.0
        var weighted = 0.0
        var total = 0.0
        val half = fftN / 2
        for (k in 1 until half) {
            val mag = re[k] * re[k] + im[k] * im[k]
            val hz = k * binHz
            when {
                hz in 80f..300f -> low += mag
                hz in 300f..1500f -> midE += mag
                hz in 1500f..6000f -> high += mag
            }
            if (hz in 80f..6000f) {
                weighted += mag * hz
                total += mag
            }
        }
        val sum = (low + midE + high).coerceAtLeast(1e-12)
        val centroid = if (total > 1e-12) (weighted / total).toFloat() else 0f
        return floatArrayOf(
            (low / sum).toFloat(),
            (midE / sum).toFloat(),
            (high / sum).toFloat(),
            centroid
        )
    }

    /** In-place radix-2 FFT; n must be power of 2. */
    private fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenRe = cos(ang).toFloat()
            val wlenIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                    val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
