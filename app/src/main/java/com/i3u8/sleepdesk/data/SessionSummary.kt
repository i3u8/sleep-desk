package com.i3u8.sleepdesk.data

/**
 * Lightweight history-row fields — no event arrays.
 * Built once from a session (segments preferred; counts from events when needed).
 */
data class SessionSummary(
    val id: String,
    val startMs: Long,
    val endMs: Long?,
    val durationMs: Long,
    val segmentCount: Int,
    val audioEventCount: Int,
    val clipCount: Int,
    val snoreEventCount: Int,
    val snoreSegmentCount: Int,
    /** True when segments were rebuilt from legacy events (caller may persist). */
    val segmentsWereMaterialized: Boolean = false
) {
    companion object {
        fun from(session: SleepSession): SessionSummary {
            val hadSegments = session.segments.isNotEmpty()
            val segs = session.ensureSegments()
            return SessionSummary(
                id = session.id,
                startMs = session.startMs,
                endMs = session.endMs,
                durationMs = session.durationMs(),
                segmentCount = segs.size,
                audioEventCount = session.audioEventCount(),
                clipCount = session.clipCount(),
                snoreEventCount = session.snoreCount(),
                snoreSegmentCount = segs.count { it.primaryLabel == "SNORE" },
                segmentsWereMaterialized = !hadSegments && segs.isNotEmpty()
            )
        }
    }
}
