package com.i3u8.sleepdesk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleClassifierTest {
    private val ambient = ClipFeatures(
        rmsDb = -40f, peakDb = -28f, zcrMean = 0.1f, zcrStd = 0.02f,
        bandLow = 0.2f, bandMid = 0.25f, bandHigh = 0.55f,
        spectralCentroid = 1800f, periodSec = 0f, periodicity = 0f,
        attackMs = 200f, durationMs = 1000f,
        spectralFlatness = 0.6f, spectralFlux = 0.15f, envelopeVariation = 0.4f
    )

    private fun type(f: ClipFeatures, screen: Boolean = false, floor: Float? = null) =
        RuleClassifier.classify(f, screen, floor).first

    @Test fun ambiguousSoundDoesNotMeanWaking() {
        assertEquals(NightEventType.ENV_NOISE, type(ambient))
        assertEquals(NightEventType.NIGHT_WAKE_SOUND, type(ambient, screen = true))
    }

    @Test fun shortTriggersAreRejectedBeforeAnySemanticRule() {
        for (duration in listOf(0f, 77f, 149f)) {
            assertEquals(NightEventType.FALSE_TRIGGER, type(ambient.copy(
                durationMs = duration, peakDb = -5f, spectralCentroid = 3000f
            ), screen = true, floor = -60f))
        }
    }

    @Test fun stationaryMidBandDoesNotMeanSpeechEvenWithScreenOn() {
        val fan = ambient.copy(
            bandMid = 0.7f, bandHigh = 0.1f, spectralCentroid = 700f,
            spectralFlux = 0.02f, envelopeVariation = 0.05f
        )
        assertEquals(NightEventType.ENV_NOISE, type(fan, screen = true))
    }

    @Test fun broadBandNoiseDoesNotMeanSpeech() {
        assertEquals(NightEventType.ENV_NOISE, type(ambient.copy(
            bandMid = 0.4f, bandHigh = 0.4f, spectralFlatness = 0.8f
        )))
    }

    @Test fun speechNeedsTemporalAndSpectralEvidence() {
        val speech = ambient.copy(
            bandLow = 0.15f, bandMid = 0.65f, bandHigh = 0.2f,
            spectralCentroid = 950f, spectralFlatness = 0.15f
        )
        assertEquals(NightEventType.SPEECH, type(speech))
        assertEquals(NightEventType.ENV_NOISE, type(speech.copy(spectralFlux = 0.01f)))
        assertEquals(NightEventType.ENV_NOISE, type(speech.copy(envelopeVariation = 0.05f)))
        assertEquals(NightEventType.ENV_NOISE, type(speech.copy(durationMs = 350f)))
    }

    @Test fun snoreNeedsObservableRepeatedCycles() {
        val snore = ambient.copy(
            durationMs = 4000f, periodicity = 0.75f, periodSec = 1f,
            bandLow = 0.65f, bandMid = 0.25f, bandHigh = 0.1f,
            spectralCentroid = 300f, spectralFlatness = 0.1f
        )
        assertEquals(NightEventType.SNORE, type(snore))
        assertEquals(NightEventType.ENV_NOISE, type(snore.copy(durationMs = 900f)))
        assertEquals(NightEventType.ENV_NOISE, type(snore.copy(periodSec = 0.3f)))
        assertEquals(NightEventType.ENV_NOISE, type(snore.copy(envelopeVariation = 0.05f)))
    }

    @Test fun coughNeedsAnImpulsiveBroadbandEnvelope() {
        val cough = ambient.copy(
            durationMs = 400f, attackMs = 40f, spectralFlatness = 0.3f
        )
        assertEquals(NightEventType.COUGH, type(cough))
        assertEquals(NightEventType.ENV_NOISE, type(cough.copy(peakDb = -36f)))
        assertEquals(NightEventType.ENV_NOISE, type(cough.copy(spectralFlatness = 0.01f)))
    }

    @Test fun loudPeakAloneDoesNotMeanAbnormal() {
        val loud = ambient.copy(
            peakDb = -5f, rmsDb = -22f, spectralCentroid = 3000f
        )
        assertEquals(NightEventType.ENV_NOISE, type(loud))
        assertEquals(NightEventType.ENV_NOISE, type(loud, floor = -32f))
        assertEquals(NightEventType.ABNORMAL, type(loud, floor = -50f))
    }

    @Test fun lowSnrAndInvalidFeaturesAreRejected() {
        assertEquals(NightEventType.FALSE_TRIGGER, type(ambient, floor = -42f))
        assertEquals(NightEventType.FALSE_TRIGGER, type(ambient, floor = Float.NaN))
        assertEquals(NightEventType.FALSE_TRIGGER, type(ambient.copy(spectralFlux = Float.NaN)))
        assertEquals(NightEventType.FALSE_TRIGGER, type(ambient.copy(rmsDb = -100f)))
    }

    @Test fun scoresAreFiniteAndBounded() {
        for (screen in listOf(false, true)) {
            for (duration in listOf(0f, 150f, 450f, 3000f, 8000f)) {
                val score = RuleClassifier.classify(ambient.copy(durationMs = duration), screen).second
                assertTrue(score.isFinite() && score in 0f..1f)
            }
        }
    }
}
