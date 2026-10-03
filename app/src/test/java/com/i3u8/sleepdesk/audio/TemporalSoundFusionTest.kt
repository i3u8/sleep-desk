package com.i3u8.sleepdesk.audio

import org.junit.Assert.*
import org.junit.Test

class TemporalSoundFusionTest {
    private fun decision(version: String = "v1") = SoundDecision(
        NightEventType.UNKNOWN, 0.85f, ClassificationStatus.UNCERTAIN,
        mapOf("Snoring" to 0.85f), listOf("SNORE"), version, "single"
    )
    private fun add(fusion: TemporalSoundFusion, start: Long, end: Long = start + 100) =
        fusion.fuse(decision(), start, end, NightEventType.SNORE)

    @Test fun repeatBoundsAreInclusive() {
        for (gap in listOf(400L, 8000L)) {
            val fusion = TemporalSoundFusion()
            add(fusion, 0)
            assertEquals(ClassificationStatus.SUGGESTED, add(fusion, gap).status)
        }
    }

    @Test fun outsideRepeatRangeDoesNotFuse() {
        for (gap in listOf(399L, 8001L, 31000L)) {
            val fusion = TemporalSoundFusion()
            add(fusion, 0)
            assertEquals(ClassificationStatus.UNCERTAIN, add(fusion, gap).status)
        }
    }

    @Test fun negativeGapAndDuplicateTimestampResetHistory() {
        for (next in listOf(999L, 1000L)) {
            val fusion = TemporalSoundFusion()
            add(fusion, 1000)
            assertEquals(1, add(fusion, next).fusionCount)
        }
    }

    @Test fun overlappingEventsAreNotIndependentSupport() {
        val fusion = TemporalSoundFusion()
        add(fusion, 0, 2000)
        assertEquals(1, add(fusion, 1000).fusionCount)
    }

    @Test fun currentEvidenceIsMandatory() {
        val fusion = TemporalSoundFusion()
        add(fusion, 0)
        val result = fusion.fuse(
            decision().copy(confidence = 0f, suggestedTypes = emptyList()), 1000, 1100, null
        )
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertEquals(1, result.fusionCount)
    }

    @Test fun modelVersionsCannotBorrowSupport() {
        val fusion = TemporalSoundFusion()
        add(fusion, 0)
        val result = fusion.fuse(decision("v2"), 1000, 1100, NightEventType.SNORE)
        assertEquals(1, result.fusionCount)
    }

    @Test fun unrelatedTypesCannotBorrowSupport() {
        val fusion = TemporalSoundFusion()
        add(fusion, 0)
        val result = fusion.fuse(
            decision().copy(suggestedTypes = listOf("COUGH")), 1000, 1100, NightEventType.COUGH
        )
        assertEquals(1, result.fusionCount)
    }

    @Test fun historyHasCountBoundAndExpires() {
        val fusion = TemporalSoundFusion()
        repeat(1000) { add(fusion, it.toLong(), it.toLong()) }
        assertTrue(add(fusion, 1500).fusionCount <= 65)
        assertEquals(1, add(fusion, 32000).fusionCount)
    }

    @Test fun failedDecisionCannotBePromoted() {
        val fusion = TemporalSoundFusion()
        add(fusion, 0)
        val result = fusion.fuse(
            decision().copy(status = ClassificationStatus.FAILED), 1000, 1100, NightEventType.SNORE
        )
        assertEquals(ClassificationStatus.FAILED, result.status)
    }
}
