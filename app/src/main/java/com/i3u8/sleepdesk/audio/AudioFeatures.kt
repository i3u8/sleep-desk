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
    val durationMs: Float,
    /** Active-frame energy-weighted flatness in [0, 1]; legacy default is unknown. */
    val spectralFlatness: Float = 0f,
    /** RMS-envelope standard deviation / mean, nonnegative and not capped at one. */
    val envelopeVariation: Float = 0f,
    /** Mean normalized-power L1/2 distance between adjacent active frames in [0, 1]. */
    val spectralFlux: Float = 0f
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
        "durationMs" to durationMs,
        "spectralFlatness" to spectralFlatness,
        "envelopeVariation" to envelopeVariation,
        "spectralFlux" to spectralFlux
    )
}

object FeatureExtractor {
    private val LN10 = ln(10.0).toFloat()
    private const val SILENCE_DB = -90f

    @Suppress("UNUSED_PARAMETER")
    fun extract(pcm: ShortArray, sampleRate: Int, durationMsHint: Float): ClipFeatures {
        require(sampleRate > 0) { "sampleRate must be positive" }
        if (pcm.isEmpty()) {
            return ClipFeatures(
                SILENCE_DB, SILENCE_DB, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 999f, 0f
            )
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
        val envHz = sampleRate.toFloat() / hop
        val attackMs = estimateAttackMs(env, envHz)
        val envelopeVariation = envelopeVariation(env)
        val (periodSec, periodicity) = estimatePeriodicity(env, envHz)

        // Aggregate power over the entire candidate, including its edges.
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
            durationMs = (n * 1000.0 / sampleRate).toFloat(),
            spectralFlatness = bands[4],
            envelopeVariation = envelopeVariation,
            spectralFlux = bands[5]
        )
    }

    fun rmsDb(frame: ShortArray, count: Int): Float {
        if (count <= 0) return SILENCE_DB
        var sum = 0.0
        for (i in 0 until count) {
            val v = frame[i].toDouble()
            sum += v * v
        }
        return toDb(sqrt(sum / count) / 32768.0)
    }

    private fun toDb(norm: Double): Float {
        val x = max(norm, 1e-9)
        return (20.0 * ln(x) / LN10).toFloat().coerceAtLeast(SILENCE_DB)
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

    /** Coefficient of variation of the RMS envelope; zero for silence. */
    private fun envelopeVariation(env: FloatArray): Float {
        val mean = env.average()
        if (mean <= 1e-6) return 0f
        var variance = 0.0
        for (value in env) {
            val delta = value - mean
            variance += delta * delta
        }
        return (sqrt(variance / env.size) / mean).toFloat()
    }

    private fun estimatePeriodicity(env: FloatArray, envHz: Float): Pair<Float, Float> {
        val n = env.size
        val lagMin = max(1, kotlin.math.ceil(0.3 * envHz).toInt())
        val lagMax = min(n / 2, (3.0 * envHz).toInt())
        if (lagMax < lagMin) return 0f to 0f

        // Smooth carrier/frame beating, not the slower respiratory modulation.
        val radius = max(1, (0.02f * envHz).toInt())
        val smooth = FloatArray(n) { index ->
            val start = max(0, index - radius)
            val end = min(n - 1, index + radius)
            var sum = 0.0
            for (i in start..end) sum += env[i]
            (sum / (end - start + 1)).toFloat()
        }
        if (envelopeVariation(smooth) < 0.1f) return 0f to 0f

        // Hysteresis gives one peak per substantial envelope excursion.
        // Three peaks provide two observed intervals; one isolated event cannot.
        val floor = smooth.minOrNull() ?: return 0f to 0f
        val range = (smooth.maxOrNull() ?: floor) - floor
        if (range < 1e-3f) return 0f to 0f
        val high = floor + 0.6f * range
        val low = floor + 0.3f * range
        val peaks = ArrayList<Int>()
        var peakIndex = -1
        for (i in smooth.indices) {
            if (peakIndex < 0 && smooth[i] >= high) peakIndex = i
            if (peakIndex >= 0) {
                if (smooth[i] > smooth[peakIndex]) peakIndex = i
                if (smooth[i] <= low) {
                    peaks.add(peakIndex)
                    peakIndex = -1
                }
            }
        }
        if (peakIndex >= 0) peaks.add(peakIndex)
        if (peaks.size < 3) return 0f to 0f

        val mean = smooth.average()
        val centered = DoubleArray(n) { smooth[it] - mean }
        var energy = 0.0
        for (value in centered) energy += value * value
        if (energy < 1e-6) return 0f to 0f
        fun correlation(lag: Int): Double {
            var cross = 0.0
            var left = 0.0
            var right = 0.0
            for (i in 0 until n - lag) {
                val a = centered[i]
                val b = centered[i + lag]
                cross += a * b
                left += a * a
                right += b * b
            }
            // Do not amplify correlations supported only by quiet tails.
            if (min(left, right) < energy * 0.2) return 0.0
            return cross / sqrt(left * right)
        }
        val correlations = DoubleArray(lagMax + 2)
        for (lag in lagMin - 1..lagMax + 1) correlations[lag] = correlation(lag)
        var best = 0.0
        var bestLag = 0
        for (lag in lagMin..lagMax) {
            val corr = correlations[lag]
            // Inspect outside the search bounds too: 0.3 s is not itself a peak.
            if (corr < 0.35 || corr <= correlations[lag - 1] ||
                corr < correlations[lag + 1]
            ) continue
            val tolerance = max(2, (lag * 0.15).toInt())
            val supported = (0 until peaks.size - 2).any { i ->
                abs(peaks[i + 1] - peaks[i] - lag) <= tolerance &&
                    abs(peaks[i + 2] - peaks[i + 1] - lag) <= tolerance
            }
            // Prefer the shorter period when scores are practically tied.
            if (supported && corr > best + 0.03) {
                best = corr
                bestLag = lag
            }
        }
        // Zero means unobservable, not evidence that a short event is non-snore.
        return if (bestLag == 0) 0f to 0f
        else (bestLag / envHz) to best.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Returns [low, mid, high, centroidHz, flatness, flux].
     * Bands use all frames; flatness/flux use active 80-6000 Hz frame spectra.
     */
    private fun spectralBands(pcm: ShortArray, sampleRate: Int, fftN: Int): FloatArray {
        val re = FloatArray(fftN)
        val im = FloatArray(fftN)
        val half = fftN / 2
        val powers = DoubleArray(half + 1)
        val binHz = sampleRate.toFloat() / fftN
        val bins = (1..half).filter { it * binHz in 80f..6000f }
        if (bins.isEmpty()) return FloatArray(6)
        val framePowers = ArrayList<Double>()
        val frameFlatness = ArrayList<Double>()
        val frameFlux = ArrayList<Double>()
        var previous = DoubleArray(bins.size)
        val currentPowers = DoubleArray(half + 1)
        // Squared sqrt-Hann weights sum to one at 50% overlap. Padding both
        // edges keeps even the first/last sample from falling at a window zero.
        val window = FloatArray(fftN) {
            sqrt(0.5 - 0.5 * cos(2.0 * PI * it / fftN)).toFloat()
        }
        var start = -half
        while (start < pcm.size) {
            re.fill(0f)
            im.fill(0f)
            for (i in max(0, -start) until min(fftN, pcm.size - start)) {
                re[i] = pcm[start + i] / 32768f * window[i]
            }
            fftRadix2(re, im)
            for (k in 1..half) {
                val real = re[k].toDouble()
                val imaginary = im[k].toDouble()
                currentPowers[k] = (real * real + imaginary * imaginary) *
                    if (k == half) 1.0 else 2.0
                powers[k] += currentPowers[k]
            }
            var framePower = 0.0
            for (k in bins) framePower += currentPowers[k]
            framePowers.add(framePower)
            val normalized = DoubleArray(bins.size)
            var flatness = 0.0
            var flux = 0.0
            if (framePower > 1e-12) {
                val meanPower = framePower / bins.size
                var logPower = 0.0
                for ((index, k) in bins.withIndex()) {
                    logPower += ln(max(currentPowers[k], meanPower * 1e-12))
                    normalized[index] = currentPowers[k] / framePower
                    flux += abs(normalized[index] - previous[index])
                }
                flatness = kotlin.math.exp(logPower / bins.size) / meanPower
            }
            frameFlatness.add(flatness)
            frameFlux.add(flux / 2.0)
            previous = normalized
            start += half
        }
        var low = 0.0
        var midE = 0.0
        var high = 0.0
        var weighted = 0.0
        var total = 0.0
        for (k in 1..half) {
            val mag = powers[k]
            val hz = k * binHz
            if (hz in 80f..6000f) {
                when {
                    hz < 300f -> low += mag
                    hz < 1500f -> midE += mag
                    else -> high += mag
                }
                weighted += mag * hz
                total += mag
            }
        }
        if (total <= 1e-12) return FloatArray(6)
        // Parseval scaling: total window power is approximately N^2/2 * RMS^2.
        val activeFloor = max(
            (framePowers.maxOrNull() ?: 0.0) * 0.01,
            fftN.toDouble() * fftN / 2.0 * kotlin.math.exp(-7.5 * ln(10.0))
        )
        var activePower = 0.0
        var weightedFlatness = 0.0
        var fluxSum = 0.0
        var fluxCount = 0
        for (i in framePowers.indices) {
            if (framePowers[i] < activeFloor) continue
            activePower += framePowers[i]
            weightedFlatness += frameFlatness[i] * framePowers[i]
            if (i > 0 && framePowers[i - 1] >= activeFloor) {
                fluxSum += frameFlux[i]
                fluxCount++
            }
        }
        val flatness = if (activePower > 0.0) weightedFlatness / activePower else 0.0
        val flux = if (fluxCount > 0) fluxSum / fluxCount else 0.0
        return floatArrayOf(
            (low / total).toFloat(),
            (midE / total).toFloat(),
            (high / total).toFloat(),
            (weighted / total).toFloat(),
            flatness.toFloat().coerceIn(0f, 1f),
            flux.toFloat().coerceIn(0f, 1f)
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
