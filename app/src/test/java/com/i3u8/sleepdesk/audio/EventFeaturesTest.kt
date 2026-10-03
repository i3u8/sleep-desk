package com.i3u8.sleepdesk.audio

import com.i3u8.sleepdesk.data.SleepEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EventFeaturesTest {
    @Test fun eventConversionRetainsReplayEvidence() {
        val event = NightEvent(
            id = "test", type = NightEventType.SPEECH, startMs = 100, endMs = 1100,
            confidence = 0.6f,
            features = mapOf("peakDb" to -25f, "noiseFloorDb" to -60f, "spectralFlux" to 0.2f)
        )
        val stored = SleepEvent.fromNightEvent(event)
        assertEquals(event.features, stored.features)
        assertEquals(AudioPipelineVersion.CURRENT, stored.algoVersion)
        assertEquals(-25.0, stored.peakLevel, 0.001)
    }

    @Test fun legacyEventsNeedNoFeaturesAndNonfiniteValuesAreNotPersisted() {
        val legacy = SleepEvent(id = "legacy", timeMs = 0, type = "SPEECH", peakLevel = -25.0)
        assertEquals(emptyMap<String, Float>(), legacy.features)
        val converted = SleepEvent.fromNightEvent(NightEvent(
            id = "invalid", type = NightEventType.ENV_NOISE, startMs = 0, endMs = 1000,
            confidence = 0.45f, features = mapOf(
                "invalid" to Float.NaN, "peakDb" to Float.NaN,
                "rmsDb" to -60f, "periodSec" to Float.POSITIVE_INFINITY
            )
        ))
        assertFalse(converted.features.containsKey("invalid"))
        assertEquals(-60.0, converted.peakLevel, 0.001)
        assertEquals(null, converted.note)
    }
}
