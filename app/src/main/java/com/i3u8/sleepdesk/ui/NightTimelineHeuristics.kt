package com.i3u8.sleepdesk.ui

import com.i3u8.sleepdesk.audio.NightEventType
import com.i3u8.sleepdesk.data.SleepEvent
import com.i3u8.sleepdesk.data.SleepSession
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/**
 * Sliding-window activity + coarse experimental cycle bands.
 * Labels must stay honest: activity ≠ sleep staging; cycles = experimental heuristic.
 */
object NightTimelineHeuristics {

    enum class CycleKind {
        /** High acoustic / interrupt activity — wake-ish proxy */
        WAKE_ISH,
        /** Quieter stretches — not deep sleep */
        QUIETER_NREM_ISH,
        /** Mid activity / speech clusters after quieter — rem-ish proxy */
        REM_ISH
    }

    data class ActivityBin(
        val startMs: Long,
        val endMs: Long,
        /** 0..1 normalized event density in window */
        val level: Float
    )

    data class CycleBand(
        val startMs: Long,
        val endMs: Long,
        val kind: CycleKind
    )

    private val AUDIO_TYPES = NightEventType.entries
        .filter { it != NightEventType.FALSE_TRIGGER }
        .map { it.name }
        .toSet()

    private val INTERRUPT_TYPES = setOf(
        SleepEvent.TYPE_SCREEN_ON,
        SleepEvent.TYPE_SCREEN_OFF,
        SleepEvent.TYPE_CHARGING_ON,
        SleepEvent.TYPE_CHARGING_OFF,
        SleepEvent.TYPE_LIGHT_SPIKE
    )

    private val WAKE_WEIGHTED = setOf(
        NightEventType.NIGHT_WAKE_SOUND.name,
        NightEventType.COUGH.name,
        NightEventType.SPEECH.name,
        SleepEvent.TYPE_SCREEN_ON
    )

    fun isAudioEvent(type: String): Boolean = type in AUDIO_TYPES
    fun isInterrupt(type: String): Boolean = type in INTERRUPT_TYPES

    fun activityBins(session: SleepSession, binMs: Long = 10 * 60_000L): List<ActivityBin> {
        val start = session.startMs
        val end = session.endMs ?: System.currentTimeMillis()
        val audio = session.events.filter { isAudioEvent(it.type) }
        val bins = mutableListOf<ActivityBin>()
        var t = start
        var maxCount = 1f
        val raw = mutableListOf<Pair<Long, Float>>()
        while (t < end) {
            val tEnd = min(t + binMs, end)
            var score = 0f
            for (e in audio) {
                if (e.timeMs in t until tEnd) {
                    score += when (e.type) {
                        NightEventType.SNORE.name -> 0.7f
                        NightEventType.ENV_NOISE.name -> 0.5f
                        NightEventType.NIGHT_WAKE_SOUND.name,
                        NightEventType.COUGH.name -> 1.2f
                        NightEventType.SPEECH.name -> 1.0f
                        else -> 0.8f
                    }
                }
            }
            // Secondary interrupts bump activity slightly
            for (e in session.events) {
                if (isInterrupt(e.type) && e.timeMs in t until tEnd) {
                    score += 0.6f
                }
            }
            raw.add(t to score)
            maxCount = max(maxCount, score)
            t = tEnd
        }
        for ((i, pair) in raw.withIndex()) {
            val (binStart, score) = pair
            val binEnd = if (i + 1 < raw.size) raw[i + 1].first else end
            bins.add(ActivityBin(binStart, binEnd, (score / maxCount).coerceIn(0f, 1f)))
        }
        // Smooth lightly
        if (bins.size >= 3) {
            val smoothed = bins.mapIndexed { i, b ->
                val a = bins[max(0, i - 1)].level
                val c = bins[min(bins.lastIndex, i + 1)].level
                b.copy(level = (a * 0.25f + b.level * 0.5f + c * 0.25f))
            }
            return smoothed
        }
        return bins
    }

    /**
     * Coarse experimental bands from activity + event priors + ~90 min ultradian hint.
     * NOT a medical hypnogram — no N1/N2/N3 labels.
     */
    fun experimentalCycles(session: SleepSession, binMs: Long = 15 * 60_000L): List<CycleBand> {
        val start = session.startMs
        val end = session.endMs ?: System.currentTimeMillis()
        val activity = activityBins(session, binMs)
        if (activity.isEmpty()) return emptyList()

        val ultradianMs = 90 * 60_000L
        val bands = mutableListOf<CycleBand>()
        for (bin in activity) {
            val mid = (bin.startMs + bin.endMs) / 2
            val phase = ((mid - start).toDouble() % ultradianMs) / ultradianMs // 0..1
            // Cosine: early phase quieter-ish, mid rem-ish tendency, late rising wake-ish — weak prior only
            val phaseHint = (-cos(phase * 2 * PI)).toFloat() // -1..1

            var wakeScore = bin.level
            var remScore = 0.35f + 0.25f * phaseHint.coerceAtLeast(0f)
            var quietScore = 1f - bin.level

            val windowEvents = session.events.filter { it.timeMs in bin.startMs until bin.endMs }
            for (e in windowEvents) {
                when {
                    e.type in WAKE_WEIGHTED || e.type == SleepEvent.TYPE_SCREEN_ON -> {
                        wakeScore += 0.45f
                        quietScore -= 0.3f
                    }
                    e.type == NightEventType.SPEECH.name -> {
                        remScore += 0.25f
                        wakeScore += 0.15f
                    }
                    e.type == NightEventType.SNORE.name -> {
                        // Sustained snore more common in quieter NREM-ish stretches (not "deep sleep")
                        quietScore += 0.2f
                        remScore -= 0.1f
                    }
                }
            }
            // First ~20 min bias wake-ish (bedtime settling)
            if (mid - start < 20 * 60_000L) wakeScore += 0.35f
            // Last ~15 min bias wake-ish
            if (end - mid < 15 * 60_000L) wakeScore += 0.3f

            val kind = when {
                wakeScore >= quietScore && wakeScore >= remScore -> CycleKind.WAKE_ISH
                remScore >= quietScore && remScore > wakeScore * 0.85f -> CycleKind.REM_ISH
                else -> CycleKind.QUIETER_NREM_ISH
            }
            bands.add(CycleBand(bin.startMs, bin.endMs, kind))
        }
        return mergeAdjacent(bands)
    }

    private fun mergeAdjacent(bands: List<CycleBand>): List<CycleBand> {
        if (bands.isEmpty()) return bands
        val out = mutableListOf<CycleBand>()
        var cur = bands.first()
        for (i in 1 until bands.size) {
            val n = bands[i]
            if (n.kind == cur.kind) {
                cur = cur.copy(endMs = n.endMs)
            } else {
                out.add(cur)
                cur = n
            }
        }
        out.add(cur)
        return out
    }
}
