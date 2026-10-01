package com.i3u8.sleepdesk.data

import android.content.Context
import com.i3u8.sleepdesk.audio.AudioClipStore
import com.i3u8.sleepdesk.audio.NightEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * M1 local persistence: sessions.json with audio event index + clip relative paths.
 * Clips live under filesDir/audio_clips/ (written by AudioClipStore).
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "sessions.json")
    private val clipStore = AudioClipStore(appContext)
    private val lock = Any()

    fun loadCurrent(): SleepSession? = synchronized(lock) {
        val root = readRoot() ?: return null
        if (!root.optBoolean("running", false)) return null
        val cur = root.optJSONObject("current") ?: return null
        parseSession(cur)
    }

    fun loadHistory(): List<SleepSession> = synchronized(lock) {
        val root = readRoot() ?: return emptyList()
        val arr = root.optJSONArray("history") ?: JSONArray()
        val list = mutableListOf<SleepSession>()
        for (i in 0 until arr.length()) list.add(parseSession(arr.getJSONObject(i)))
        list.sortedByDescending { it.startMs }
    }

    fun loadLastFinished(): SleepSession? = loadHistory().firstOrNull()

    fun startNew(): SleepSession = synchronized(lock) {
        val now = System.currentTimeMillis()
        val session = SleepSession(id = now.toString(16), startMs = now)
        writeRoot(running = true, current = session, history = loadHistoryUnlocked())
        session
    }

    fun appendEvent(event: SleepEvent) = synchronized(lock) {
        val root = readRoot() ?: return
        if (!root.optBoolean("running", false)) return
        val current = parseSession(root.getJSONObject("current"))
        current.events.add(event)
        writeRoot(running = true, current = current, history = loadHistoryUnlocked())
    }

    fun appendNightEvent(event: NightEvent) = appendEvent(SleepEvent.fromNightEvent(event))

    fun stop(): SleepSession? = synchronized(lock) {
        val root = readRoot() ?: return null
        if (!root.optBoolean("running", false)) return null
        val current = parseSession(root.getJSONObject("current"))
        current.endMs = System.currentTimeMillis()
        current.materializeSegments()
        val history = loadHistoryUnlocked().toMutableList()
        history.add(0, current)
        while (history.size > MAX_HISTORY) {
            val dropped = history.removeAt(history.lastIndex)
            clipStore.deleteSessionClips(dropped.id)
        }
        writeRoot(running = false, current = null, history = history)
        current
    }

    /**
     * Delete one finished session from history and cascade-delete its audio clips.
     * Does not delete a currently running session.
     */
    fun deleteOne(sessionId: String): Boolean = synchronized(lock) {
        val root = readRoot() ?: return false
        val running = root.optBoolean("running", false)
        val current = if (running) {
            root.optJSONObject("current")?.let { parseSession(it) }
        } else null
        if (current?.id == sessionId) return false

        val history = loadHistoryUnlocked().toMutableList()
        val idx = history.indexOfFirst { it.id == sessionId }
        if (idx < 0) return false
        history.removeAt(idx)
        writeRoot(running = running, current = current, history = history)
        clipStore.deleteSessionClips(sessionId)
        true
    }

    /**
     * Clear all finished history sessions and their clips.
     * Leaves a running session (if any) untouched.
     * @return number of sessions removed
     */
    fun clearAll(): Int = synchronized(lock) {
        val root = readRoot()
        val running = root?.optBoolean("running", false) == true
        val current = if (running) {
            root?.optJSONObject("current")?.let { parseSession(it) }
        } else null
        val history = loadHistoryUnlocked()
        for (s in history) {
            clipStore.deleteSessionClips(s.id)
        }
        writeRoot(running = running, current = current, history = emptyList())
        history.size
    }

    private fun loadHistoryUnlocked(): List<SleepSession> {
        val root = readRoot() ?: return emptyList()
        val arr = root.optJSONArray("history") ?: JSONArray()
        val list = mutableListOf<SleepSession>()
        for (i in 0 until arr.length()) list.add(parseSession(arr.getJSONObject(i)))
        return list
    }

    private fun readRoot(): JSONObject? {
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun writeRoot(running: Boolean, current: SleepSession?, history: List<SleepSession>) {
        val root = JSONObject()
        root.put("running", running)
        root.put("version", 3)
        if (current != null) root.put("current", toJson(current)) else root.put("current", JSONObject.NULL)
        val arr = JSONArray()
        for (s in history) arr.put(toJson(s))
        root.put("history", arr)
        file.writeText(root.toString())
    }

    private fun toJson(s: SleepSession): JSONObject {
        val o = JSONObject()
        o.put("id", s.id)
        o.put("startMs", s.startMs)
        if (s.endMs != null) o.put("endMs", s.endMs) else o.put("endMs", JSONObject.NULL)
        val arr = JSONArray()
        for (e in s.events) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("timeMs", e.timeMs)
                    .put("endMs", e.endMs)
                    .put("type", e.type)
                    .put("peakLevel", e.peakLevel)
                    .put("confidence", e.confidence.toDouble())
                    .put("clipRelativePath", e.clipRelativePath ?: JSONObject.NULL)
                    .put("note", e.note ?: JSONObject.NULL)
                    .put("algoVersion", e.algoVersion ?: JSONObject.NULL)
            )
        }
        o.put("events", arr)
        o.put("audioEvents", arr) // alias per audio-algo.md
        val segArr = JSONArray()
        for (seg in s.segments) segArr.put(segmentToJson(seg))
        o.put("segments", segArr)
        return o
    }

    private fun segmentToJson(seg: NightSegment): JSONObject {
        val o = JSONObject()
        o.put("id", seg.id)
        o.put("sessionId", seg.sessionId)
        o.put("startMs", seg.startMs)
        o.put("endMs", seg.endMs)
        o.put("primaryLabel", seg.primaryLabel)
        val labels = JSONObject()
        for ((k, v) in seg.labels) labels.put(k, v)
        o.put("labels", labels)
        val eventIds = JSONArray()
        for (id in seg.eventIds) eventIds.put(id)
        o.put("eventIds", eventIds)
        val clips = JSONArray()
        for (p in seg.representativeClipPaths) clips.put(p)
        o.put("representativeClipPaths", clips)
        o.put("peakConfidence", seg.peakConfidence.toDouble())
        o.put("peakDb", seg.peakDb)
        o.put("snoreMinutes", seg.snoreMinutes.toDouble())
        val aux = JSONArray()
        for (a in seg.auxFlags) aux.put(a)
        o.put("auxFlags", aux)
        o.put("algoVersion", seg.algoVersion ?: JSONObject.NULL)
        o.put("segmentVersion", seg.segmentVersion)
        return o
    }

    private fun parseSegment(o: JSONObject): NightSegment {
        val labels = mutableMapOf<String, Int>()
        val labelsObj = o.optJSONObject("labels")
        if (labelsObj != null) {
            val keys = labelsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                labels[k] = labelsObj.optInt(k, 0)
            }
        }
        fun stringList(key: String): List<String> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { arr.getString(it) }
        }
        return NightSegment(
            id = o.optString("id", ""),
            sessionId = o.optString("sessionId", ""),
            startMs = o.getLong("startMs"),
            endMs = o.getLong("endMs"),
            primaryLabel = o.optString("primaryLabel", "MIXED"),
            labels = labels,
            eventIds = stringList("eventIds"),
            representativeClipPaths = stringList("representativeClipPaths"),
            peakConfidence = o.optDouble("peakConfidence", 0.0).toFloat(),
            peakDb = o.optDouble("peakDb", 0.0),
            snoreMinutes = o.optDouble("snoreMinutes", 0.0).toFloat(),
            auxFlags = stringList("auxFlags"),
            algoVersion = if (o.isNull("algoVersion")) null else o.optString("algoVersion"),
            segmentVersion = o.optString("segmentVersion", "seg-v1")
        )
    }

    private fun parseSession(o: JSONObject): SleepSession {
        val end = if (o.isNull("endMs")) null else o.getLong("endMs")
        val events = mutableListOf<SleepEvent>()
        val arr = o.optJSONArray("events") ?: o.optJSONArray("audioEvents") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            events.add(
                SleepEvent(
                    id = e.optString("id", UUID.randomUUID().toString()),
                    timeMs = e.getLong("timeMs"),
                    endMs = e.optLong("endMs", e.getLong("timeMs")),
                    type = e.getString("type"),
                    peakLevel = e.optDouble("peakLevel", 0.0),
                    confidence = e.optDouble("confidence", 1.0).toFloat(),
                    clipRelativePath = if (e.isNull("clipRelativePath")) null else e.optString("clipRelativePath"),
                    note = if (e.isNull("note")) null else e.optString("note"),
                    algoVersion = if (e.isNull("algoVersion")) null else e.optString("algoVersion")
                )
            )
        }
        val segments = mutableListOf<NightSegment>()
        val segArr = o.optJSONArray("segments")
        if (segArr != null) {
            for (i in 0 until segArr.length()) {
                segments.add(parseSegment(segArr.getJSONObject(i)))
            }
        }
        return SleepSession(
            id = o.optString("id", UUID.randomUUID().toString()),
            startMs = o.getLong("startMs"),
            endMs = end,
            events = events,
            segments = segments
        )
    }

    companion object {
        const val MAX_HISTORY = 90
    }
}
