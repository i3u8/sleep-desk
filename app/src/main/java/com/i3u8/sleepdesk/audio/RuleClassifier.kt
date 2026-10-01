package com.i3u8.sleepdesk.audio

/**
 * v1.1 rule classifier — relaxed thresholds (prefer recall over precision).
 * @see docs/audio-algo.md §3.4
 */
object RuleClassifier {
    fun classify(f: ClipFeatures, screenOnNearby: Boolean): Pair<NightEventType, Float> {
        // Snore — lower periodicity / shorter bout OK (distant, soft snore)
        if (f.periodicity >= 0.32f &&
            f.periodSec in 0.35f..3.5f &&
            f.bandHigh < 0.42f &&
            f.durationMs >= 800f
        ) {
            val conf = (0.40f + 0.4f * f.periodicity + 0.1f * (1f - f.bandHigh))
                .coerceIn(0.42f, 0.95f)
            return NightEventType.SNORE to conf
        }

        // Cough
        if (f.durationMs in 60f..700f &&
            f.attackMs < 120f &&
            (f.spectralCentroid > 1200f || f.bandHigh > 0.22f)
        ) {
            val conf = 0.52f + 0.2f * (1f - f.attackMs / 120f).coerceIn(0f, 1f)
            return NightEventType.COUGH to conf.coerceIn(0.42f, 0.92f)
        }

        // Speech (coarse)
        if (f.durationMs in 300f..8000f &&
            f.bandMid > 0.22f &&
            f.periodicity < 0.40f &&
            f.spectralCentroid in 350f..4000f
        ) {
            var conf = 0.50f + 0.18f * f.bandMid
            if (screenOnNearby) conf += 0.08f
            return NightEventType.SPEECH to conf.coerceIn(0.42f, 0.9f)
        }

        // ENV_NOISE: elevated but smooth / long / low periodicity
        if (f.durationMs >= 3500f &&
            f.attackMs > 250f &&
            f.periodicity < 0.28f &&
            f.zcrStd < 0.05f
        ) {
            return NightEventType.ENV_NOISE to 0.65f
        }

        // Abnormal: very loud peak + high centroid
        if (f.peakDb > -20f && f.spectralCentroid > 2200f) {
            return NightEventType.ABNORMAL to 0.72f
        }

        // Short junk only if truly tiny
        if (f.durationMs < 80f) {
            return NightEventType.FALSE_TRIGGER to 0.2f
        }

        // Default night-wake-ish burst (catch-all for distant rustle / sit-up)
        var conf = 0.50f
        if (screenOnNearby) conf += 0.1f
        if (f.peakDb > -32f) conf += 0.06f
        if (f.durationMs >= 200f) conf += 0.04f
        return NightEventType.NIGHT_WAKE_SOUND to conf.coerceIn(0.42f, 0.88f)
    }
}
