package com.i3u8.sleepdesk.audio

import java.util.ArrayDeque

/** Per-classifier/session bounded history. Never feeds fused confidence back as evidence. */
internal class TemporalSoundFusion {
    private data class Entry(
        val startMs: Long, val endMs: Long, val type: NightEventType?, val version: String?
    )
    private val history = ArrayDeque<Entry>()
    private var lastStart: Long? = null

    @Synchronized
    fun fuse(
        decision: SoundDecision,
        startMs: Long,
        endMs: Long,
        locallyStrongType: NightEventType?
    ): SoundDecision {
        require(endMs >= startMs)
        if (lastStart?.let { startMs <= it } == true) history.clear()
        lastStart = startMs
        while (history.isNotEmpty() && startMs - history.first.startMs > 30_000) {
            history.removeFirst()
        }
        val validType = locallyStrongType?.takeIf {
            decision.status in setOf(ClassificationStatus.UNCERTAIN, ClassificationStatus.SUGGESTED) &&
                decision.confidence >= 0.7f && it.name in decision.suggestedTypes
        }
        val matches = if (validType == null) 0 else history.count {
            it.type == validType && it.version == decision.modelVersion &&
                startMs - it.startMs in 400L..8_000L && startMs >= it.endMs
        }
        history.addLast(Entry(startMs, endMs, validType, decision.modelVersion))
        while (history.size > 64) history.removeFirst()
        return if (matches > 0) decision.copy(
            type = validType!!,
            status = ClassificationStatus.SUGGESTED,
            fusionCount = matches + 1,
            reason = "adjacent_events_with_current_strong_evidence;uncalibrated;non_medical"
        ) else decision
    }
}
