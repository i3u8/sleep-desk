package com.i3u8.sleepdesk.data

import com.i3u8.sleepdesk.audio.ClassificationStatus
import com.i3u8.sleepdesk.audio.NightEvent
import com.i3u8.sleepdesk.audio.NightEventType
import com.i3u8.sleepdesk.ui.NightTimelineHeuristics
import org.junit.Assert.*
import org.junit.Test

class DetectionSegmentsTest {
    @Test fun manualLabelControlsCountsAndSegmentsWithoutChangingModelType() {
        val event = SleepEvent("e", 1000, 61000, "COUGH", -20.0, userLabel = "SNORE")
        val session = SleepSession("s", 0, 120000, mutableListOf(event))
        assertEquals(1, session.snoreCount())
        assertEquals(0, session.coughCount())
        val segment = session.materializeSegments().single()
        assertEquals("SNORE", segment.primaryLabel)
        assertEquals(mapOf("SNORE" to 1), segment.labels)
        assertEquals(1f, segment.snoreMinutes)
        assertEquals("COUGH", event.type)
    }

    @Test fun unresolvedEventsRemainVisibleEvenWithMinimumCount() {
        for (status in listOf(ClassificationStatus.PENDING, ClassificationStatus.FAILED)) {
            val event = SleepEvent("e", 1000, 2000, "UNKNOWN", -20.0, classificationStatus = status,
                clipRelativePath = "audio_clips/test.wav")
            val segment = SegmentBuilder.build("s", 0, 10_000, listOf(event),
                SegmentConfig(minEventsToShow = 3)).single()
            assertEquals(listOf("e"), segment.eventIds)
            assertEquals(listOf("audio_clips/test.wav"), segment.representativeClipPaths)
        }
    }

    @Test fun newCategoriesAndEnvironmentAllProduceSegments() {
        for (type in listOf("UNKNOWN", "BREATHING", "BED_MOVEMENT", "CONTACT_SOUND", "ENV_NOISE")) {
            val event = SleepEvent("e", 1000, 2000, type, -30.0)
            assertEquals(type, SegmentBuilder.build("s", 0, 10000, listOf(event)).single().primaryLabel)
        }
    }

    @Test fun reviewedClipsBypassRepresentativeQuota() {
        val events = (0..7).map { i ->
            SleepEvent("$i", i * 1000L, i * 1000L + 500, "SNORE", -30.0,
                clipRelativePath = "audio_clips/$i.wav", reviewFlags = setOf(SleepEvent.REVIEW_IMPORTANT))
        }
        val segment = SegmentBuilder.build("s", 0, 10000, events,
            SegmentConfig(maxClipsPerSegment = 1)).single()
        assertEquals(8, segment.representativeClipPaths.size)
    }

    @Test fun copyFiltersNonfiniteValuesAndPreservesClassificationMetadata() {
        val night = NightEvent("e", NightEventType.UNKNOWN, 0, 1000, Float.NaN,
            features = mapOf("bad" to Float.POSITIVE_INFINITY), detectionConfidence = Float.NaN,
            classScores = mapOf("bad" to Float.NaN, "COUGH" to .5f),
            classificationStatus = ClassificationStatus.UNCERTAIN, modelVersion = "test",
            suggestedTypes = listOf("COUGH"), classificationReason = "ambiguous",
            userLabel = "CONTACT_SOUND", reviewFlags = setOf(SleepEvent.REVIEW_IMPORTANT))
        val event = SleepEvent.fromNightEvent(night)
        assertEquals(0f, event.confidence)
        assertEquals(0f, event.detectionConfidence)
        assertTrue(event.features.isEmpty())
        assertEquals(mapOf("COUGH" to .5f), event.classScores)
        assertEquals(night.suggestedTypes, event.suggestedTypes)
        assertEquals(night.modelVersion, event.modelVersion)
        assertEquals(night.reviewFlags, event.reviewFlags)
        assertEquals(night.userLabel, event.userLabel)
    }

    @Test fun environmentIsNotWakeEvidence() {
        val quiet = SleepSession("s", 0, 7200000)
        val env = quiet.copy(events = (0..100).map { i ->
            SleepEvent("$i", i * 60000L, type = "ENV_NOISE", peakLevel = -10.0)
        }.toMutableList())
        assertEquals(NightTimelineHeuristics.experimentalCycles(quiet),
            NightTimelineHeuristics.experimentalCycles(env))
    }
}
