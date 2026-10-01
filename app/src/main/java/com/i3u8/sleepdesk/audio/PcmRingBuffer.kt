package com.i3u8.sleepdesk.audio

/**
 * Circular PCM buffer with sample-index clock for pre/post roll slicing.
 */
class PcmRingBuffer(capacitySamples: Int) {
    private val buf = ShortArray(capacitySamples)
    private var writePos = 0
    private var totalWritten = 0L
    private val lock = Any()

    val capacity: Int get() = buf.size

    fun clear() = synchronized(lock) {
        writePos = 0
        totalWritten = 0L
    }

    fun write(samples: ShortArray, offset: Int, count: Int) = synchronized(lock) {
        var o = offset
        var n = count
        while (n > 0) {
            buf[writePos] = samples[o]
            writePos = (writePos + 1) % buf.size
            o++; n--; totalWritten++
        }
    }

    fun totalSamples(): Long = synchronized(lock) { totalWritten }

    /**
     * Slice by absolute sample indices [fromSample, toSample) relative to stream start.
     * Clamps to what is still in the ring.
     */
    fun sliceSamples(fromSample: Long, toSample: Long): ShortArray = synchronized(lock) {
        if (toSample <= fromSample || totalWritten == 0L) return ShortArray(0)
        val oldest = (totalWritten - buf.size).coerceAtLeast(0L)
        val from = fromSample.coerceIn(oldest, totalWritten)
        val to = toSample.coerceIn(from, totalWritten)
        val len = (to - from).toInt()
        val out = ShortArray(len)
        for (i in 0 until len) {
            val abs = from + i
            val age = totalWritten - abs // 1..capacity
            val idx = (writePos - age.toInt() + buf.size) % buf.size
            out[i] = buf[idx]
        }
        return out
    }
}
