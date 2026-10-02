package com.i3u8.sleepdesk.data

import com.i3u8.sleepdesk.audio.NightEvent
import com.i3u8.sleepdesk.audio.NightEventType

/**
 * Session + indexed night events (clip paths only — no full-night audio).
 * Segments are a view/index over events (docs/segments.md); detection layer unchanged.
 */
data class SleepEvent(
    val id: String,
    val timeMs: Long,
    val endMs: Long = timeMs,
    val type: String,
    val peakLevel: Double,
    val confidence: Float = 1f,
    val clipRelativePath: String? = null,
    val note: String? = null,
    val algoVersion: String? = null,
    val features: Map<String, Float> = emptyMap()
) {
    companion object {
        fun fromNightEvent(e: NightEvent): SleepEvent = SleepEvent(
            id = e.id,
            timeMs = e.startMs,
            endMs = e.endMs,
            type = e.type.name,
            peakLevel = (e.features["peakDb"]?.takeIf { it.isFinite() }
                ?: e.features["rmsDb"]?.takeIf { it.isFinite() } ?: 0f).toDouble(),
            confidence = e.confidence,
            clipRelativePath = e.clipRelativePath,
            note = e.features["periodSec"]?.takeIf { it.isFinite() }
                ?.let { "period=${"%.2f".format(it)}s" },
            algoVersion = e.algoVersion,
            features = e.features.filterValues { it.isFinite() }
        )

        const val TYPE_SCREEN_ON = "SCREEN_ON"
        const val TYPE_SCREEN_OFF = "SCREEN_OFF"
        const val TYPE_CHARGING_ON = "CHARGING_ON"
        const val TYPE_CHARGING_OFF = "CHARGING_OFF"
        const val TYPE_LIGHT_SPIKE = "LIGHT_SPIKE"
    }
}

data class SleepSession(
    val id: String,
    val startMs: Long,
    var endMs: Long? = null,
    val events: MutableList<SleepEvent> = mutableListOf(),
    /** Materialized at session end; may be empty for running / legacy sessions. */
    var segments: MutableList<NightSegment> = mutableListOf()
) {
    val isRunning: Boolean get() = endMs == null

    fun durationMs(): Long {
        val end = endMs ?: System.currentTimeMillis()
        return (end - startMs).coerceAtLeast(0L)
    }

    fun countByType(type: String): Int = events.count { it.type == type }

    fun countByType(type: NightEventType): Int = countByType(type.name)

    fun audioEventCount(): Int = events.count { e ->
        NightEventType.entries.any { it.name == e.type && it != NightEventType.FALSE_TRIGGER }
    }

    fun clipCount(): Int = events.count { !it.clipRelativePath.isNullOrEmpty() }

    fun snoreCount(): Int = countByType(NightEventType.SNORE)
    fun coughCount(): Int = countByType(NightEventType.COUGH)
    fun wakeSoundCount(): Int = countByType(NightEventType.NIGHT_WAKE_SOUND)

    fun segmentCount(): Int = ensureSegments().size

    fun snoreSegmentCount(): Int =
        ensureSegments().count { it.primaryLabel == NightEventType.SNORE.name }

    /**
     * Prefer persisted segments; rebuild from events if missing (legacy / in-progress).
     */
    fun ensureSegments(config: SegmentConfig = SegmentConfig()): List<NightSegment> {
        if (segments.isNotEmpty()) return segments
        val built = SegmentBuilder.build(this, config)
        segments.clear()
        segments.addAll(built)
        return segments
    }

    fun materializeSegments(config: SegmentConfig = SegmentConfig()): List<NightSegment> {
        val built = SegmentBuilder.build(this, config)
        segments.clear()
        segments.addAll(built)
        return segments
    }
}
