package com.i3u8.sleepdesk.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Production PCM -> features -> rules; complements tests of individual feature fields. */
class AudioPipelineRegressionTest {
    private val sr = 16000
    private fun classify(seconds: Double, wave: (Double) -> Double): NightEventType {
        val pcm = ShortArray((seconds * sr).toInt()) { i ->
            (wave(i.toDouble() / sr).coerceIn(-1.0, 1.0) * 24000).toInt().toShort()
        }
        return RuleClassifier.classify(FeatureExtractor.extract(pcm, sr, 0f), false).first
    }

    @Test fun stationaryCarrierAndWhiteNoiseNeverBecomeSpeechOrSnore() {
        assertEquals(NightEventType.ENV_NOISE, classify(2.0) {
            sin(2 * PI * 700 * it)
        })
        val random = Random(2718)
        assertEquals(NightEventType.ENV_NOISE, classify(2.0) {
            random.nextDouble() * 2 - 1
        })
    }

    @Test fun repeatedLowFrequencyBurstsRetainSnorePositivePath() {
        assertEquals(NightEventType.SNORE, classify(4.0) { t ->
            val phase = (t % 1.0) - 0.45
            val envelope = exp(-phase * phase / 0.025)
            envelope * (0.7 * sin(2 * PI * 140 * t) + 0.2 * sin(2 * PI * 280 * t))
        })
    }

    @Test fun oneSmoothBurstCannotBecomeSnore() {
        assertNotEquals(NightEventType.SNORE, classify(2.0) { t ->
            exp(-(t - 1) * (t - 1) / 0.08) * sin(2 * PI * 140 * t)
        })
    }

    @Test fun silenceIsRejected() {
        assertEquals(NightEventType.FALSE_TRIGGER, classify(1.0) { 0.0 })
    }

    @Test fun changingFormantsRetainSpeechLikePositivePath() {
        val frequencies = doubleArrayOf(350.0, 700.0, 1200.0, 1900.0, 500.0, 1000.0, 1600.0)
        var phase = 0.0
        assertEquals(NightEventType.SPEECH, classify(3.0) { t ->
            phase += 2 * PI * frequencies[(t / 0.04).toInt() % frequencies.size] / sr
            val envelope = 0.5 + 0.25 * sin(2 * PI * 2.7 * t) +
                0.15 * sin(2 * PI * 4.1 * t)
            envelope * sin(phase)
        })
    }
}
