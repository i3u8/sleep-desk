package com.i3u8.sleepdesk.audio

/**
 * v1 rule classifier per docs/audio-algo.md §3.4.
 */
object RuleClassifier {
    fun classify(f: ClipFeatures, screenOnNearby: Boolean): Pair<NightEventType, Float> {
        // Snore
        if (f.periodicity >= 0.45f &&
            f.periodSec in 0.4f..3.0f &&
            (f.bandHigh < 0.35f) &&
            f.durationMs >= 1200f
        ) {
            val conf = (0.45f + 0.4f * f.periodicity + 0.1f * (1f - f.bandHigh))
                .coerceIn(0.55f, 0.95f)
            return NightEventType.SNORE to conf
        }

        // Cough
        if (f.durationMs in 80f..600f &&
            f.attackMs < 80f &&
            (f.spectralCentroid > 1500f || f.bandHigh > 0.28f)
        ) {
            val conf = 0.6f + 0.2f * (1f - f.attackMs / 80f).coerceIn(0f, 1f)
            return NightEventType.COUGH to conf.coerceIn(0.55f, 0.92f)
        }

        // Speech (coarse)
        if (f.durationMs in 400f..8000f &&
            f.bandMid > 0.28f &&
            f.periodicity < 0.35f &&
            f.spectralCentroid in 400f..3500f
        ) {
            var conf = 0.58f + 0.15f * f.bandMid
            if (screenOnNearby) conf += 0.08f
            return NightEventType.SPEECH to conf.coerceIn(0.55f, 0.9f)
        }

        // ENV_NOISE: elevated but smooth / long / low periodicity
        if (f.durationMs >= 3000f &&
            f.attackMs > 200f &&
            f.periodicity < 0.3f &&
            f.zcrStd < 0.05f
        ) {
            return NightEventType.ENV_NOISE to 0.7f
        }

        // Abnormal: very loud peak + high centroid
        if (f.peakDb > -18f && f.spectralCentroid > 2500f) {
            return NightEventType.ABNORMAL to 0.75f
        }

        // Short junk
        if (f.durationMs < 150f) {
            return NightEventType.FALSE_TRIGGER to 0.2f
        }

        // Default night-wake-ish burst
        var conf = 0.58f
        if (screenOnNearby) conf += 0.1f
        if (f.peakDb > -28f) conf += 0.05f
        return NightEventType.NIGHT_WAKE_SOUND to conf.coerceIn(0.55f, 0.88f)
    }
}
