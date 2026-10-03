package com.i3u8.sleepdesk.audio

internal data class ContextualCandidate(val id: String, val audio: CandidateAudio)

/** Single-threaded; write each hop before processing it with the detector. */
internal class ContextualAudioBuffer(private val config: AudioAlgoConfig) {
    init { validateCandidateConfig(config) }

    private fun samples(ms: Int) = ms.toLong() * config.sampleRate / 1000
    private val pre = samples(config.preRollMs)
    private val post = samples(config.postRollMs)
    private val hop = samples(config.hopMs).toInt()
    private val clipLimit = samples(config.maxClipMs).coerceAtLeast(1)
    private val ring = PcmRingBuffer(checkedPcmCapacity(
        pre + samples(config.maxCandidateMs) + post + hop))
    private val pending = mutableListOf<Pending>()

    private class Pending(
        val id: String,
        val audio: CandidateAudio,
        val from: Long,
        val until: Long,
        val due: Long,
        val pcm: ShortArray,
        val flags: Set<String>,
        var captured: Int
    )

    fun write(frame: ShortArray, count: Int) {
        require(count in 1..minOf(frame.size, hop))
        val before = ring.totalSamples()
        require(before <= Long.MAX_VALUE - count - post) { "Sample clock overflow" }
        ring.write(frame, 0, count)
        val now = ring.totalSamples()
        for (item in pending) {
            val from = maxOf(before, item.from + item.captured)
            val to = minOf(now, item.until)
            if (to > from) {
                frame.copyInto(item.pcm, item.captured, (from - before).toInt(),
                    (to - before).toInt())
                item.captured += (to - from).toInt()
            }
        }
    }

    fun add(id: String, candidate: CandidateAudio) {
        val now = ring.totalSamples()
        require(candidate.startSample >= 0 && candidate.endSample > candidate.startSample)
        require(candidate.endSample <= now) { "Write PCM before adding its candidate" }
        require(candidate.endSample - candidate.startSample == candidate.pcm.size.toLong())
        require(candidate.pcm.size.toLong() <= samples(config.maxCandidateMs))
        require(candidate.endSample <= Long.MAX_VALUE - post)
        require(pending.none { it.id == id }) { "Duplicate pending candidate id" }
        val due = candidate.endSample + post
        // Preserve the body first; a smaller clip budget removes context before body.
        val wantedFrom = maxOf(0, candidate.startSample - pre,
            minOf(candidate.startSample, candidate.endSample - clipLimit))
        val until = wantedFrom + minOf(due - wantedFrom, clipLimit)
        val oldest = (now - ring.capacity).coerceAtLeast(0)
        require(oldest <= candidate.startSample) { "Candidate body has left the context ring" }
        val from = maxOf(wantedFrom, oldest)
        val prefix = ring.sliceSamples(from, minOf(now, until))
        val pcm = ShortArray(checkedPcmCapacity(until - from))
        prefix.copyInto(pcm)
        val flags = candidate.contextFlags.toMutableSet()
        if (from > candidate.startSample - pre) flags += "PRE_ROLL_SHORT"
        if (until < due) {
            flags += "POST_ROLL_SHORT"
            flags += "CLIP_LIMIT"
        }
        pending += Pending(id, candidate.copy(pcm = candidate.pcm.copyOf()), from,
            until, due, pcm, flags, prefix.size)
    }

    fun ready(): List<ContextualCandidate> = drain(stopped = false)

    /** No padding: each clip ends at the last captured sample, never at a future timestamp. */
    fun flush(): List<ContextualCandidate> = drain(stopped = true)

    private fun drain(stopped: Boolean): List<ContextualCandidate> {
        val now = ring.totalSamples()
        val result = mutableListOf<ContextualCandidate>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (!stopped && now < item.due) continue
            val flags = item.flags.toMutableSet()
            if (stopped) flags += "STOPPED"
            val end = item.from + item.captured
            if (end < item.due) flags += "POST_ROLL_SHORT"
            result += ContextualCandidate(item.id, item.audio.copy(
                clipPcm = item.pcm.copyOf(item.captured),
                clipStartSample = item.from,
                clipEndSample = end,
                contextFlags = flags
            ))
            iterator.remove()
        }
        return result
    }
}
