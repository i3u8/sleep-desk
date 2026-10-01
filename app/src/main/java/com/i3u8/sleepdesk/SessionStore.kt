package com.i3u8.sleepdesk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Minimal local persistence: one JSON file under app filesDir.
 * Tracks current (running) session and last finished session.
 */
class SessionStore(context: Context) {

    private val file = File(context.filesDir, "sleep_session.json")
    private val lock = Any()

    data class Bucket(val startMs: Long, val energy: Double)

    data class Session(
        val startMs: Long,
        var endMs: Long? = null,
        val buckets: MutableList<Bucket> = mutableListOf()
    ) {
        val isRunning: Boolean get() = endMs == null

        fun durationMs(): Long {
            val end = endMs ?: System.currentTimeMillis()
            return (end - startMs).coerceAtLeast(0L)
        }

        fun highMotionCount(threshold: Double = HIGH_MOTION_THRESHOLD): Int =
            buckets.count { it.energy >= threshold }
    }

    fun loadCurrent(): Session? = synchronized(lock) {
        val root = readRoot() ?: return null
        if (!root.optBoolean("running", false)) return null
        parseSession(root.getJSONObject("current"))
    }

    fun loadLast(): Session? = synchronized(lock) {
        val root = readRoot() ?: return null
        if (!root.has("last") || root.isNull("last")) return null
        parseSession(root.getJSONObject("last"))
    }

    fun startNew(): Session = synchronized(lock) {
        val session = Session(startMs = System.currentTimeMillis())
        writeRoot(running = true, current = session, last = loadLastUnlocked())
        session
    }

    fun appendBucket(startMs: Long, energy: Double) = synchronized(lock) {
        val root = readRoot() ?: return
        if (!root.optBoolean("running", false)) return
        val current = parseSession(root.getJSONObject("current"))
        current.buckets.add(Bucket(startMs, energy))
        // Keep file from growing forever during a long night (~1440 buckets/day is fine)
        writeRoot(running = true, current = current, last = loadLastUnlocked())
    }

    fun stop(): Session? = synchronized(lock) {
        val root = readRoot() ?: return null
        if (!root.optBoolean("running", false)) return null
        val current = parseSession(root.getJSONObject("current"))
        current.endMs = System.currentTimeMillis()
        writeRoot(running = false, current = null, last = current)
        current
    }

    private fun loadLastUnlocked(): Session? {
        val root = readRoot() ?: return null
        if (!root.has("last") || root.isNull("last")) return null
        return parseSession(root.getJSONObject("last"))
    }

    private fun readRoot(): JSONObject? {
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun writeRoot(running: Boolean, current: Session?, last: Session?) {
        val root = JSONObject()
        root.put("running", running)
        if (current != null) root.put("current", toJson(current)) else root.put("current", JSONObject.NULL)
        if (last != null) root.put("last", toJson(last)) else root.put("last", JSONObject.NULL)
        file.writeText(root.toString())
    }

    private fun toJson(s: Session): JSONObject {
        val o = JSONObject()
        o.put("startMs", s.startMs)
        if (s.endMs != null) o.put("endMs", s.endMs) else o.put("endMs", JSONObject.NULL)
        val arr = JSONArray()
        for (b in s.buckets) {
            arr.put(JSONObject().put("startMs", b.startMs).put("energy", b.energy))
        }
        o.put("buckets", arr)
        return o
    }

    private fun parseSession(o: JSONObject): Session {
        val end = if (o.isNull("endMs")) null else o.getLong("endMs")
        val buckets = mutableListOf<Bucket>()
        val arr = o.optJSONArray("buckets") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            buckets.add(Bucket(b.getLong("startMs"), b.getDouble("energy")))
        }
        return Session(startMs = o.getLong("startMs"), endMs = end, buckets = buckets)
    }

    companion object {
        /** Rough night-wake proxy: mean |Δa| over a 60s bucket above this. */
        const val HIGH_MOTION_THRESHOLD = 0.35
        const val BUCKET_MS = 60_000L
    }
}
