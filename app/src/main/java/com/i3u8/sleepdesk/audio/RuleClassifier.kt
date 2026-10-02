package com.i3u8.sleepdesk.audio

/**
 * Conservative acoustic rules, not sleep-state detection.
 * Scores express rule support, not calibrated probabilities.
 */
object RuleClassifier {
    const val VERSION = "audio-v1.3"

    fun classify(
        f: ClipFeatures,
        screenOnNearby: Boolean,
        noiseFloorDb: Float? = null
    ): Pair<NightEventType, Float> {
        if (f.toMap().values.any { !it.isFinite() } ||
            (noiseFloorDb != null && !noiseFloorDb.isFinite()) ||
            f.durationMs < 150f || f.rmsDb < -80f || f.peakDb < f.rmsDb
        ) {
            return NightEventType.FALSE_TRIGGER to 0.2f
        }
        val snrDb = noiseFloorDb?.let { f.rmsDb - it }
        if (snrDb != null && snrDb < 3f) {
            return NightEventType.FALSE_TRIGGER to 0.25f
        }
        val crestDb = f.peakDb - f.rmsDb

        // Stationary fans and tones are not speech merely because of their mid band.
        if (f.envelopeVariation < 0.12f && f.spectralFlux < 0.06f) {
            return NightEventType.ENV_NOISE to 0.65f
        }

        if (f.periodicity >= 0.55f &&
            f.periodSec in 0.45f..3f &&
            f.durationMs >= 2f * f.periodSec * 1000f &&
            f.bandHigh < 0.35f &&
            f.spectralFlatness < 0.45f &&
            f.envelopeVariation >= 0.18f
        ) {
            val score = 0.45f + 0.35f * f.periodicity + 0.1f * (1f - f.bandHigh)
            return NightEventType.SNORE to score.coerceIn(0.55f, 0.9f)
        }

        // A high-frequency click alone is insufficient evidence of a cough.
        if (f.durationMs in 150f..900f &&
            f.attackMs in 10f..100f &&
            crestDb >= 10f &&
            f.envelopeVariation >= 0.35f &&
            f.spectralFlux >= 0.08f &&
            f.spectralFlatness >= 0.08f &&
            f.bandHigh > 0.20f &&
            f.spectralCentroid > 1100f
        ) {
            return NightEventType.COUGH to
                (0.55f + 0.15f * (1f - f.attackMs / 120f)).coerceIn(0.55f, 0.75f)
        }

        // Require spectral AND temporal variation, not one mid-band FFT window.
        // Speech-like acoustics cannot establish a speaker's identity or dreaming.
        if (f.durationMs in 450f..8000f &&
            f.bandMid >= 0.35f &&
            f.bandHigh < 0.60f &&
            f.periodicity < 0.45f &&
            f.spectralCentroid in 250f..3500f &&
            f.zcrMean in 0.01f..0.30f &&
            f.spectralFlatness < 0.50f &&
            f.spectralFlux >= 0.06f &&
            f.envelopeVariation >= 0.12f
        ) {
            return NightEventType.SPEECH to
                (0.50f + 0.15f * f.bandMid + 0.10f * f.spectralFlux).coerceIn(0.55f, 0.8f)
        }

        // Absolute peaks depend on microphone sensitivity and digital gain.
        if (snrDb != null && snrDb >= 15f &&
            f.peakDb > -12f && f.rmsDb > -28f &&
            f.spectralCentroid > 2200f && f.bandHigh > 0.40f
        ) {
            return NightEventType.ABNORMAL to 0.65f
        }

        if (screenOnNearby && f.durationMs >= 250f &&
            f.envelopeVariation >= 0.20f && crestDb >= 6f
        ) {
            return NightEventType.NIGHT_WAKE_SOUND to 0.55f
        }

        // Unidentified ambient activity is not evidence that the sleeper woke up.
        return NightEventType.ENV_NOISE to 0.45f
    }
}
