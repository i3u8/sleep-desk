package com.i3u8.sleepdesk.data

import com.i3u8.sleepdesk.audio.NightEvent
import com.i3u8.sleepdesk.audio.NightEventType

/**
 * Session + indexed night events (clip paths only — no full-night audio).
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
    val algoVersion: String? = null
) {
    companion object {
        fun fromNightEvent(e: NightEvent): SleepEvent = SleepEvent(
            id = e.id,
            timeMs = e.startMs,
            endMs = e.endMs,
            type = e.type.name,
            peakLevel = (e.features["peakDb"] ?: e.features["rmsDb"] ?: 0f).toDouble(),
            confidence = e.confidence,
            clipRelativePath = e.clipRelativePath,
            note = e.features["periodSec"]?.let { "period=${"%.2f".format(it)}s" },
            algoVersion = e.algoVersion
        )

        // Secondary (non-audio) types
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
    val events: MutableList<SleepEvent> = mutableListOf()
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
}
