package com.i3u8.sleepdesk.data

import android.content.Context
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
        val history = loadHistoryUnlocked().toMutableList()
        history.add(0, current)
        while (history.size > MAX_HISTORY) {
            history.removeAt(history.lastIndex)
        }
        writeRoot(running = false, current = null, history = history)
        current
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
        root.put("version", 2)
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
        return o
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
        return SleepSession(
            id = o.optString("id", UUID.randomUUID().toString()),
            startMs = o.getLong("startMs"),
            endMs = end,
            events = events
        )
    }

    companion object {
        const val MAX_HISTORY = 90
    }
}
