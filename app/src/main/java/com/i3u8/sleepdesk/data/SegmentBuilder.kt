package com.i3u8.sleepdesk.data

import com.i3u8.sleepdesk.audio.NightEventType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bout-first segment builder per docs/segments.md:
 * filter audio → same-type bout merge → split if > maxSegmentMs →
 * merge overlapping/near heterogenous bouts to MIXED → pick representative clips (strategy A).
 */
object SegmentBuilder {
    const val VERSION = "seg-v3"

    private val SEGMENT_TYPES = setOf(
        NightEventType.SNORE.name,
        NightEventType.COUGH.name,
        NightEventType.SPEECH.name,
        NightEventType.NIGHT_WAKE_SOUND.name,
        NightEventType.ABNORMAL.name
    )

    private val AUX_TYPES = setOf(
        SleepEvent.TYPE_SCREEN_ON,
        SleepEvent.TYPE_SCREEN_OFF,
        SleepEvent.TYPE_CHARGING_ON,
        SleepEvent.TYPE_CHARGING_OFF,
        SleepEvent.TYPE_LIGHT_SPIKE
    )

    private val TYPE_WEIGHT = mapOf(
        NightEventType.ABNORMAL.name to 10f,
        NightEventType.SNORE.name to 3f,
        NightEventType.NIGHT_WAKE_SOUND.name to 2.5f,
        NightEventType.COUGH.name to 2.5f,
        NightEventType.SPEECH.name to 2f
    )

    private data class Bout(
        val type: String,
        val events: MutableList<SleepEvent>
    ) {
        val startMs: Long get() = events.minOf { it.timeMs }
        val endMs: Long get() = events.maxOf { max(it.endMs, it.timeMs) }
    }

    fun build(session: SleepSession, config: SegmentConfig = SegmentConfig()): List<NightSegment> {
        val end = (session.endMs ?: System.currentTimeMillis()).coerceAtLeast(session.startMs + 1_000L)
        return build(session.id, session.startMs, end, session.events, config)
    }

    fun build(
        sessionId: String,
        sessionStartMs: Long,
        sessionEndMs: Long,
        events: List<SleepEvent>,
        config: SegmentConfig = SegmentConfig()
    ): List<NightSegment> {
        val clippedEnd = sessionEndMs.coerceAtLeast(sessionStartMs + 1_000L)
        val audio = events
            .filter { it.effectiveType in SEGMENT_TYPES || (it.effectiveType !in AUX_TYPES &&
                (it.effectiveType != NightEventType.FALSE_TRIGGER.name ||
                    it.classificationStatus != com.i3u8.sleepdesk.audio.ClassificationStatus.LEGACY)) }
            .sortedBy { it.timeMs }
        val aux = events.filter { it.effectiveType in AUX_TYPES }

        if (audio.isEmpty()) return emptyList()

        // 2) same-type bout merge (can cross bucket boundaries)
        val bouts = mergeBouts(audio, config)
        // 3) split if > maxSegmentMs
        val split = bouts.flatMap { splitLongBout(it, config.maxSegmentMs) }
        // merge overlapping / near heterogenous → MIXED
        val merged = mergeNearbyHeterogenous(split, config.burstMergeGapMs)

        return merged.mapIndexed { index, boutEvents ->
            materialize(
                sessionId = sessionId,
                sessionStartMs = sessionStartMs,
                sessionEndMs = clippedEnd,
                events = boutEvents,
                aux = aux,
                index = index,
                config = config
            )
        }.filter { segment ->
            segment.eventIds.size >= config.minEventsToShow ||
                audio.any { e -> e.id in segment.eventIds && (e.effectiveType == NightEventType.UNKNOWN.name ||
                    e.classificationStatus in setOf(com.i3u8.sleepdesk.audio.ClassificationStatus.PENDING,
                        com.i3u8.sleepdesk.audio.ClassificationStatus.FAILED)) }
        }
    }

    private fun mergeGapFor(type: String, config: SegmentConfig): Long = when (type) {
        NightEventType.SNORE.name -> config.snoreMergeGapMs
        NightEventType.ABNORMAL.name -> config.abnormalMergeGapMs
        else -> config.burstMergeGapMs
    }

    private fun mergeBouts(audio: List<SleepEvent>, config: SegmentConfig): List<Bout> {
        if (audio.isEmpty()) return emptyList()
        val out = mutableListOf<Bout>()
        var cur = Bout(audio.first().effectiveType, mutableListOf(audio.first()))
        for (i in 1 until audio.size) {
            val e = audio[i]
            val prev = cur.events.last()
            val prevEnd = max(prev.endMs, prev.timeMs)
            val gap = e.timeMs - prevEnd
            if (e.effectiveType == cur.type && gap <= mergeGapFor(e.effectiveType, config)) {
                cur.events.add(e)
            } else {
                out.add(cur)
                cur = Bout(e.effectiveType, mutableListOf(e))
            }
        }
        out.add(cur)
        return out
    }

    private fun splitLongBout(bout: Bout, maxMs: Long): List<List<SleepEvent>> {
        val dur = bout.endMs - bout.startMs
        if (dur <= maxMs || bout.events.size <= 1) {
            return listOf(bout.events.toList())
        }
        val chunks = mutableListOf<MutableList<SleepEvent>>()
        var chunk = mutableListOf(bout.events.first())
        var chunkStart = bout.events.first().timeMs
        for (i in 1 until bout.events.size) {
            val e = bout.events[i]
            if (e.timeMs - chunkStart > maxMs && chunk.isNotEmpty()) {
                chunks.add(chunk)
                chunk = mutableListOf(e)
                chunkStart = e.timeMs
            } else {
                chunk.add(e)
            }
        }
        if (chunk.isNotEmpty()) chunks.add(chunk)
        return chunks
    }

    /**
     * Overlapping or gap < burstMergeGapMs heterogenous bouts → one MIXED group.
     */
    private fun mergeNearbyHeterogenous(
        bouts: List<List<SleepEvent>>,
        gapMs: Long
    ): List<List<SleepEvent>> {
        if (bouts.isEmpty()) return emptyList()
        val sorted = bouts.sortedBy { it.minOf { e -> e.timeMs } }
        val out = mutableListOf<MutableList<SleepEvent>>()
        var cur = sorted.first().toMutableList()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val curEnd = cur.maxOf { max(it.endMs, it.timeMs) }
            val nextStart = next.minOf { it.timeMs }
            val gap = nextStart - curEnd
            val overlap = nextStart <= curEnd
            if (overlap || gap < gapMs) {
                cur.addAll(next)
            } else {
                out.add(cur)
                cur = next.toMutableList()
            }
        }
        out.add(cur)
        return out
    }

    private fun materialize(
        sessionId: String,
        sessionStartMs: Long,
        sessionEndMs: Long,
        events: List<SleepEvent>,
        aux: List<SleepEvent>,
        index: Int,
        config: SegmentConfig
    ): NightSegment {
        val deduped = events.distinctBy { it.id }.sortedBy { it.timeMs }
        val start = deduped.minOf { it.timeMs }.coerceIn(sessionStartMs, sessionEndMs)
        val end = deduped.maxOf { max(it.endMs, it.timeMs) }.coerceIn(sessionStartMs, sessionEndMs)
            .coerceAtLeast(start)

        val labels = linkedMapOf<String, Int>()
        for (e in deduped) {
            labels[e.effectiveType] = (labels[e.effectiveType] ?: 0) + 1
        }
        val primary = pickPrimaryLabel(labels, deduped, config)

        val auxFlags = aux
            .filter { it.timeMs in start..end }
            .map { it.effectiveType }
            .distinct()

        val snoreMinutes = deduped
            .filter { it.effectiveType == NightEventType.SNORE.name }
            .sumOf { max(it.endMs - it.timeMs, 0L).toDouble() }
            .toFloat() / 60_000f

        val peakConf = deduped.maxOfOrNull { it.confidence } ?: 0f
        val peakDb = deduped.maxOfOrNull { it.peakLevel } ?: 0.0
        val clips = pickRepresentativeClips(deduped, primary, start, end, config)

        return NightSegment(
            id = "${sessionId}_seg_$index",
            sessionId = sessionId,
            startMs = start,
            endMs = end,
            primaryLabel = primary,
            labels = labels,
            eventIds = deduped.map { it.id },
            representativeClipPaths = clips,
            peakConfidence = peakConf,
            peakDb = peakDb,
            snoreMinutes = snoreMinutes,
            auxFlags = auxFlags,
            algoVersion = deduped.firstOrNull()?.algoVersion,
            segmentVersion = VERSION
        )
    }

    private fun pickPrimaryLabel(
        labels: Map<String, Int>,
        events: List<SleepEvent>,
        config: SegmentConfig
    ): String {
        if (labels.isEmpty()) return "MIXED"
        // ABNORMAL priority
        if ((labels[NightEventType.ABNORMAL.name] ?: 0) >= 1) {
            return NightEventType.ABNORMAL.name
        }
        data class Cand(val type: String, val count: Int, val score: Float, val peakConf: Float)
        val cands = labels.map { (type, count) ->
            val w = TYPE_WEIGHT[type] ?: 1f
            val peakConf = events.filter { it.effectiveType == type }.maxOfOrNull { it.confidence } ?: 0f
            Cand(type, count, count * w, peakConf)
        }.sortedWith(
            compareByDescending<Cand> { it.score }
                .thenByDescending { it.count }
                .thenByDescending { it.peakConf }
        )
        val top = cands.first()
        val kinds = labels.keys.size
        val secondaryCount = cands.drop(1).sumOf { it.count }
        val mixed = secondaryCount >= top.count * config.mixedSecondaryRatio ||
            kinds >= config.mixedMinTypeKinds
        return if (mixed) "MIXED" else top.type
    }

    /**
     * Strategy A: reference existing clip paths only; never delete files.
     */
    private fun pickRepresentativeClips(
        events: List<SleepEvent>,
        primaryLabel: String,
        startMs: Long,
        endMs: Long,
        config: SegmentConfig
    ): List<String> {
        val withClip = events
            .filter { !it.clipRelativePath.isNullOrEmpty() }
            .distinctBy { it.clipRelativePath }
        if (withClip.isEmpty()) return emptyList()

        val duration = endMs - startMs
        val isSnore = primaryLabel == NightEventType.SNORE.name
        val isBurst = primaryLabel == NightEventType.COUGH.name ||
            primaryLabel == NightEventType.SPEECH.name ||
            primaryLabel == NightEventType.NIGHT_WAKE_SOUND.name

        var quota = when {
            isSnore -> config.clipsPerSnoreSegment
            isBurst -> config.clipsPerBurstSegment
            else -> config.clipsPerSegment
        }
        if (isSnore && duration >= config.longSnoreForExtraClipMs) {
            quota = min(config.maxClipsPerSegment, quota + 1)
        }
        quota = min(quota, config.maxClipsPerSegment)

        val minDb = withClip.minOf { it.peakLevel }
        val maxDb = withClip.maxOf { it.peakLevel }
        val mid = (startMs + endMs) / 2.0
        fun score(e: SleepEvent): Double {
            val norm = if (maxDb > minDb) {
                ((e.peakLevel - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
            } else 1.0
            var s = e.confidence * 0.6 + norm * 0.4
            if (isSnore) {
                val dist = abs(e.timeMs - mid)
                val span = max(duration.toDouble(), 1.0)
                s += 0.15 * (1.0 - (dist / span).coerceIn(0.0, 1.0))
            }
            return s
        }

        val selected = mutableListOf<SleepEvent>()
        if (isSnore && quota >= 2) {
            val earliest = withClip.minByOrNull { it.timeMs }
            if (earliest != null) selected.add(earliest)
        }

        val spacings = listOf(config.minClipSpacingMs, config.minClipSpacingMs / 2, 0L)
        for (spacing in spacings) {
            if (selected.size >= quota) break
            val ranked = withClip.sortedByDescending { score(it) }
            for (e in ranked) {
                if (selected.size >= quota) break
                if (selected.any { it.id == e.id || it.clipRelativePath == e.clipRelativePath }) continue
                if (selected.any { abs(it.timeMs - e.timeMs) < spacing }) continue
                selected.add(e)
            }
        }

        return selected
            .sortedBy { it.timeMs }
            .mapNotNull { it.clipRelativePath }
            .distinct()
            .take(quota)
            .plus(withClip.filter { it.userLabel != null || it.reviewFlags.isNotEmpty() }
                .mapNotNull { it.clipRelativePath })
            .distinct()
    }

    fun eventsForSegment(session: SleepSession, segment: NightSegment): List<SleepEvent> {
        val ids = segment.eventIds.toSet()
        return session.events.filter { it.id in ids }.sortedBy { it.timeMs }
    }

    fun eventsForClipPaths(session: SleepSession, paths: List<String>): List<SleepEvent> {
        val set = paths.toSet()
        return session.events
            .filter { it.clipRelativePath != null && it.clipRelativePath in set }
            .sortedBy { it.timeMs }
    }
}
