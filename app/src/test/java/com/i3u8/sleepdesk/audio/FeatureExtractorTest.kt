package com.i3u8.sleepdesk.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {
    private val sampleRate = 16000

    private fun extract(pcm: ShortArray, hint: Float = 99999f): ClipFeatures =
        FeatureExtractor.extract(pcm, sampleRate, hint)

    private fun signal(seconds: Double, value: (Double) -> Double): ShortArray =
        ShortArray((seconds * sampleRate).toInt()) { i ->
            (value(i.toDouble() / sampleRate).coerceIn(-1.0, 1.0) * 28000).toInt().toShort()
        }

    private fun tone(seconds: Double, frequency: Double = 437.0): ShortArray =
        signal(seconds) { t -> 0.65 * sin(2 * PI * frequency * t) }

    private fun assertNoPeriod(features: ClipFeatures) {
        assertEquals(0f, features.periodSec, 0f)
        assertEquals(0f, features.periodicity, 0f)
    }

    @Test
    fun emptyAndSilenceHaveLowFiniteDbAndNoActivity() {
        for (pcm in listOf(ShortArray(0), ShortArray(sampleRate))) {
            val features = extract(pcm)
            assertEquals(-90f, features.rmsDb, 0f)
            assertEquals(-90f, features.peakDb, 0f)
            assertEquals(-90f, FeatureExtractor.rmsDb(pcm, pcm.size), 0f)
            assertEquals(pcm.size * 1000f / sampleRate, features.durationMs, 0.001f)
            assertNoPeriod(features)
            assertEquals(0f, features.bandLow + features.bandMid + features.bandHigh, 0f)
            assertEquals(0f, features.spectralCentroid, 0f)
            assertEquals(0f, features.spectralFlatness, 0f)
            assertEquals(0f, features.spectralFlux, 0f)
            assertEquals(0f, features.envelopeVariation, 0f)
            assertTrue(features.toMap().values.all { it.isFinite() })
        }
    }

    @Test
    fun actualPcmDurationOverridesHintIncludingAtNonRoundSampleRates() {
        val features = FeatureExtractor.extract(ShortArray(1103), 11025, 12345f)
        assertEquals((1103 * 1000.0 / 11025).toFloat(), features.durationMs, 0.001f)
        assertEquals(125f, extract(tone(0.125), Float.NaN).durationMs, 0.001f)
    }

    @Test
    fun signalAtEitherEndSurvivesSilentMidpoint() {
        for (atStart in listOf(true, false)) {
            val features = extract(signal(2.0) { t ->
                if ((atStart && t < 0.3) || (!atStart && t >= 1.7)) {
                    0.7 * sin(2 * PI * 200 * t)
                } else 0.0
            })
            assertTrue("atStart=$atStart: $features", features.bandLow > 0.9f)
            assertEquals(200f, features.spectralCentroid, 25f)
            assertEquals(1f, features.bandLow + features.bandMid + features.bandHigh, 0.0001f)
        }
    }

    @Test
    fun powerAggregationPreservesLouderStartAndQuieterEnd() {
        val pcm = signal(2.0) { t ->
            when {
                t < 0.4 -> 0.8 * sin(2 * PI * 200 * t)
                t >= 1.6 -> 0.4 * sin(2 * PI * 3000 * t)
                else -> 0.0
            }
        }
        val forward = extract(pcm)
        val reverse = extract(pcm.reversedArray())
        assertEquals(0.8f, forward.bandLow, 0.04f)
        assertEquals(0.2f, forward.bandHigh, 0.04f)
        assertEquals(forward.bandLow, reverse.bandLow, 0.015f)
        assertEquals(forward.spectralCentroid, reverse.spectralCentroid, 30f)
    }

    @Test
    fun singleSampleAtEitherEdgeIsNotLostToWindowZero() {
        for (index in listOf(0, 1023)) {
            val pcm = ShortArray(1024)
            pcm[index] = 20000
            val features = extract(pcm)
            assertEquals(1f, features.bandLow + features.bandMid + features.bandHigh, 0.0001f)
            assertTrue(features.spectralCentroid > 1000f)
            assertTrue(features.toMap().values.all { it.isFinite() })
        }
        assertTrue(extract(shortArrayOf(20000)).spectralCentroid > 1000f)
    }

    @Test
    fun stableToneHasLowVariationFlatnessAndFluxButNoEnvelopePeriod() {
        val features = extract(tone(3.0))
        assertTrue(features.bandMid > 0.95f)
        assertTrue(features.envelopeVariation < 0.04f)
        assertTrue(features.spectralFlatness < 0.03f)
        assertTrue(features.spectralFlux < 0.05f)
        assertNoPeriod(features)
    }

    @Test
    fun stableWhiteNoiseIsFlatAndHasNoRespiratoryPeriod() {
        val random = Random(3481L)
        val pcm = ShortArray(sampleRate * 3) { (random.nextInt(24001) - 12000).toShort() }
        val features = extract(pcm)
        assertTrue("noise flatness: $features", features.spectralFlatness > 0.35f)
        assertTrue(features.envelopeVariation < 0.12f)
        assertTrue(features.spectralFlux in 0f..1f)
        assertNoPeriod(features)
    }

    @Test
    fun repeatedBurstsHaveSupportedEnvelopePeriod() {
        val features = extract(signal(3.2) { t ->
            var envelope = 0.0
            for (i in 0..3) {
                val distance = (t - (0.2 + i * 0.8)) / 0.07
                envelope += exp(-0.5 * distance * distance)
            }
            0.7 * envelope * sin(2 * PI * 200 * t)
        })
        assertEquals(0.8f, features.periodSec, 0.04f)
        assertTrue("burst periodicity: $features", features.periodicity > 0.75f)
        assertTrue(features.envelopeVariation > 0.5f)
    }

    @Test
    fun trueLocalPeakAtLowerPeriodBoundaryIsObservable() {
        val features = extract(signal(1.5) { t ->
            var envelope = 0.0
            for (i in 0..4) {
                val distance = (t - (0.15 + i * 0.3)) / 0.035
                envelope += exp(-0.5 * distance * distance)
            }
            0.7 * envelope * sin(2 * PI * 200 * t)
        })
        assertEquals(0.3f, features.periodSec, 0.02f)
        assertTrue(features.periodicity > 0.7f)
    }

    @Test
    fun singleSmoothEnvelopeDoesNotBecomeLowerBoundaryPeriod() {
        val features = extract(signal(2.8) { t ->
            val distance = (t - 1.4) / 0.45
            0.7 * exp(-0.5 * distance * distance) * sin(2 * PI * 200 * t)
        })
        assertTrue(features.envelopeVariation > 0.1f)
        assertNoPeriod(features)
    }

    @Test
    fun smoothRampDoesNotBecomePeriod() {
        assertNoPeriod(extract(signal(2.0) { t ->
            0.4 * t * sin(2 * PI * 200 * t)
        }))
    }

    @Test
    fun twoBurstsOnlySupportOneIntervalNotTwoCycles() {
        assertNoPeriod(extract(signal(1.7) { t ->
            val a = (t - 0.3) / 0.08
            val b = (t - 1.1) / 0.08
            0.7 * (exp(-0.5 * a * a) + exp(-0.5 * b * b)) * sin(2 * PI * 200 * t)
        }))
    }

    @Test
    fun shortEventDoesNotInventUnobservableRespiratoryPeriod() {
        assertNoPeriod(extract(signal(0.45) { t ->
            0.7 * sin(PI * t / 0.45) * sin(2 * PI * 200 * t)
        }))
    }

    @Test
    fun speechLikeChangingFormantsHavePositiveFluxWithoutNoiseFlatness() {
        val frequencies = doubleArrayOf(350.0, 700.0, 1200.0, 1900.0, 500.0, 1000.0, 1600.0)
        var phase = 0.0
        val features = extract(signal(3.0) { t ->
            val frequency = frequencies[(t / 0.04).toInt() % frequencies.size]
            phase += 2 * PI * frequency / sampleRate
            val envelope = 0.5 + 0.25 * sin(2 * PI * 2.7 * t) +
                0.15 * sin(2 * PI * 4.1 * t)
            envelope * sin(phase)
        })
        val stable = extract(tone(3.0))
        assertTrue("speech-like mid band: $features", features.bandMid > 0.45f)
        assertTrue("speech-like flux: $features", features.spectralFlux > 0.12f)
        assertTrue(features.spectralFlux > stable.spectralFlux + 0.1f)
        assertTrue(features.envelopeVariation > 0.2f)
        assertTrue("speech-like flatness: $features", features.spectralFlatness < 0.2f)
    }

    @Test
    fun frameFlatnessDoesNotMistakeChangingPureTonesForBroadbandNoise() {
        val features = extract(signal(4.0) { t ->
            val frequency = 125.0 + (t / 0.08).toInt() * 100.0
            0.6 * sin(2 * PI * frequency * t)
        })
        assertTrue(features.spectralFlatness < 0.1f)
        assertTrue(features.spectralFlux > 0.05f)
    }

    @Test
    fun silencePaddingDoesNotDiluteActiveFrameFlatnessOrFlux() {
        val random = Random(12L)
        val active = ShortArray(sampleRate) { (random.nextInt(24001) - 12000).toShort() }
        val padded = ShortArray(sampleRate * 4)
        active.copyInto(padded, sampleRate)
        val short = extract(active)
        val long = extract(padded)
        assertEquals(short.spectralFlatness, long.spectralFlatness, 0.04f)
        assertEquals(short.spectralFlux, long.spectralFlux, 0.04f)
    }

    @Test
    fun normalizedSpectralFluxIsInsensitiveToOverallGain() {
        val pcm = signal(2.0) { t ->
            val frequency = if ((t / 0.05).toInt() % 2 == 0) 500.0 else 1300.0
            0.7 * sin(2 * PI * frequency * t)
        }
        val quiet = ShortArray(pcm.size) { (pcm[it].toInt() / 4).toShort() }
        assertEquals(extract(pcm).spectralFlux, extract(quiet).spectralFlux, 0.01f)
    }

    @Test
    fun addedFeaturesDefaultToZeroAndAreExported() {
        val legacy = ClipFeatures(-30f, -20f, 0f, 0f, 0f, 1f, 0f, 800f, 0f, 0f, 0f, 100f)
        assertEquals(0f, legacy.spectralFlatness, 0f)
        assertEquals(0f, legacy.envelopeVariation, 0f)
        assertEquals(0f, legacy.spectralFlux, 0f)
        for (features in listOf(legacy, extract(tone(1.0)))) {
            assertEquals(features.spectralFlatness, features.toMap().getValue("spectralFlatness"), 0f)
            assertEquals(features.envelopeVariation, features.toMap().getValue("envelopeVariation"), 0f)
            assertEquals(features.spectralFlux, features.toMap().getValue("spectralFlux"), 0f)
            assertTrue(features.spectralFlatness in 0f..1f)
            assertTrue(features.spectralFlux in 0f..1f)
            assertTrue(features.envelopeVariation >= 0f)
            assertTrue(abs(features.durationMs) < Float.POSITIVE_INFINITY)
        }
    }
}
