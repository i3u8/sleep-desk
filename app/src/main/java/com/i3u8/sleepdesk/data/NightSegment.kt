package com.i3u8.sleepdesk.data

/**
 * Segment = view + index over SleepEvents (docs/segments.md).
 * Not a second detection layer; never deletes underlying events or clip files.
 */
data class NightSegment(
    val id: String,
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    /** SNORE / COUGH / SPEECH / NIGHT_WAKE_SOUND / ABNORMAL / MIXED */
    val primaryLabel: String,
    val labels: Map<String, Int> = emptyMap(),
    val eventIds: List<String> = emptyList(),
    val representativeClipPaths: List<String> = emptyList(),
    val peakConfidence: Float = 0f,
    val peakDb: Double = 0.0,
    val snoreMinutes: Float = 0f,
    val auxFlags: List<String> = emptyList(),
    val algoVersion: String? = null,
    val segmentVersion: String = "seg-v1"
) {
    fun durationMs(): Long = (endMs - startMs).coerceAtLeast(0L)
    fun eventCount(): Int = eventIds.size
    fun clipCount(): Int = representativeClipPaths.size
}

data class SegmentConfig(
    val segmentBucketMs: Long = 300_000L,
    val snoreMergeGapMs: Long = 90_000L,
    val burstMergeGapMs: Long = 45_000L,
    val abnormalMergeGapMs: Long = 30_000L,
    val maxSegmentMs: Long = 20 * 60_000L,
    val minEventsToShow: Int = 1,
    val clipsPerSegment: Int = 2,
    val clipsPerSnoreSegment: Int = 2,
    val clipsPerBurstSegment: Int = 2,
    val maxClipsPerSegment: Int = 3,
    val longSnoreForExtraClipMs: Long = 10 * 60_000L,
    val minClipSpacingMs: Long = 60_000L,
    val preferEventsWithClip: Boolean = true,
    val mixedSecondaryRatio: Float = 0.40f,
    val mixedMinTypeKinds: Int = 3
)
