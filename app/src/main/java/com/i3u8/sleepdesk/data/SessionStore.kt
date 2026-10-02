package com.i3u8.sleepdesk.data

import android.content.Context
import com.i3u8.sleepdesk.audio.AudioClipStore
import com.i3u8.sleepdesk.audio.NightEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Local persistence: sessions.json with audio event index + clip relative paths.
 * Clips live under filesDir/audio_clips/ (written by AudioClipStore).
 *
 * v0.3.1: in-memory cache shared across instances, lightweight history summaries,
 * and one-shot persist of materialized segments for pre-v0.3 JSON (legacy nights
 * without a `segments` array). Legacy events are never deleted.
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "sessions.json")
    private val clipStore = AudioClipStore(appContext)

    fun loadCurrent(): SleepSession? = synchronized(lock) {
        val root = cachedRoot() ?: return null
        if (!root.running) return null
        root.current
    }

    fun loadHistory(): List<SleepSession> = synchronized(lock) {
        val root = cachedRoot() ?: return emptyList()
        root.history.sortedByDescending { it.startMs }
    }

    /**
     * Lightweight rows for History list — O(sessions) after first parse;
     * does not force SegmentBuilder on the UI thread for already-persisted segments.
     * Materializes + persists missing segments for finished nights with audio events.
     */
    fun loadHistorySummaries(): List<SessionSummary> = synchronized(lock) {
        val root = cachedRoot() ?: return emptyList()
        var dirty = false
        val out = mutableListOf<SessionSummary>()
        for (s in root.history) {
            val hadSegs = s.segments.isNotEmpty()
            if (!hadSegs && s.audioEventCount() > 0) {
                s.ensureSegments()
                dirty = true
            } else if (!hadSegs) {
                // quiet night / aux-only — leave empty
            }
            out.add(SessionSummary.from(s))
        }
        if (dirty) {
            writeRoot(running = root.running, current = root.current, history = root.history)
        }
        out.sortedByDescending { it.startMs }
    }

    fun loadLastFinished(): SleepSession? = loadHistory().firstOrNull()

    /** Single session by id without re-scanning for callers that only need one night. */
    fun loadSession(sessionId: String): SleepSession? = synchronized(lock) {
        val root = cachedRoot() ?: return null
        if (root.current?.id == sessionId) return root.current
        root.history.firstOrNull { it.id == sessionId }
    }

    /**
     * Prefer persisted segments; if missing/empty but audio events exist, run
     * SegmentBuilder and persist once so reopen stays cheap. Never deletes events.
     */
    fun ensureSegmentsPersisted(sessionId: String): SleepSession? = synchronized(lock) {
        val root = cachedRoot() ?: return null
        val current = root.current
        val history = root.history.toMutableList()
        val target: SleepSession = when {
            current?.id == sessionId -> current
            else -> history.firstOrNull { it.id == sessionId } ?: return null
        }
        val beforeEmpty = target.segments.isEmpty()
        target.ensureSegments()
        if (beforeEmpty && target.segments.isNotEmpty() && !target.isRunning) {
            writeRoot(running = root.running, current = current, history = history)
        }
        target
    }

    fun startNew(): SleepSession = synchronized(lock) {
        val now = System.currentTimeMillis()
        val session = SleepSession(id = now.toString(16), startMs = now)
        val history = cachedRoot()?.history ?: emptyList()
        writeRoot(running = true, current = session, history = history)
        session
    }

    fun appendEvent(event: SleepEvent) = synchronized(lock) {
        val root = cachedRoot() ?: return
        if (!root.running || root.current == null) return
        val current = root.current
        current.events.add(event)
        // Invalidate segments for in-progress night (rebuild on demand)
        current.segments.clear()
        writeRoot(running = true, current = current, history = root.history)
    }

    fun appendNightEvent(event: NightEvent) = appendEvent(SleepEvent.fromNightEvent(event))

    fun stop(): SleepSession? = synchronized(lock) {
        val root = cachedRoot() ?: return null
        if (!root.running || root.current == null) return null
        val current = root.current
        current.endMs = System.currentTimeMillis()
        current.materializeSegments()
        val history = root.history.toMutableList()
        history.add(0, current)
        while (history.size > MAX_HISTORY) {
            val dropped = history.removeAt(history.lastIndex)
            clipStore.deleteSessionClips(dropped.id)
        }
        writeRoot(running = false, current = null, history = history)
        current
    }

    fun deleteOne(sessionId: String): Boolean = synchronized(lock) {
        val root = cachedRoot() ?: return false
        if (root.current?.id == sessionId) return false
        val history = root.history.toMutableList()
        val idx = history.indexOfFirst { it.id == sessionId }
        if (idx < 0) return false
        history.removeAt(idx)
        writeRoot(running = root.running, current = root.current, history = history)
        clipStore.deleteSessionClips(sessionId)
        true
    }

    fun clearAll(): Int = synchronized(lock) {
        val root = cachedRoot()
        val running = root?.running == true
        val current = if (running) root?.current else null
        val history = root?.history ?: emptyList()
        for (s in history) {
            clipStore.deleteSessionClips(s.id)
        }
        writeRoot(running = running, current = current, history = emptyList())
        history.size
    }

    fun clipStore(): AudioClipStore = clipStore

    fun sessionsFile(): File = file

    // ── cache / IO ──────────────────────────────────────────────────────────

    private fun cachedRoot(): RootCache? {
        val cached = cacheRef.get()
        if (cached != null && cached.filePath == file.absolutePath &&
            cached.mtime == fileLastModified()
        ) {
            return cached
        }
        return readAndCache()
    }

    private fun fileLastModified(): Long = if (file.exists()) file.lastModified() else -1L

    private fun readAndCache(): RootCache? {
        if (!file.exists()) {
            cacheRef.set(null)
            return null
        }
        return try {
            val text = file.readText()
            val o = JSONObject(text)
            val running = o.optBoolean("running", false)
            val current = if (running) {
                o.optJSONObject("current")?.let { parseSession(it) }
            } else null
            val arr = o.optJSONArray("history") ?: JSONArray()
            val history = mutableListOf<SleepSession>()
            for (i in 0 until arr.length()) history.add(parseSession(arr.getJSONObject(i)))
            val root = RootCache(
                filePath = file.absolutePath,
                mtime = fileLastModified(),
                running = running,
                current = current,
                history = history
            )
            cacheRef.set(root)
            root
        } catch (_: Exception) {
            cacheRef.set(null)
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
        cacheRef.set(
            RootCache(
                filePath = file.absolutePath,
                mtime = fileLastModified(),
                running = running,
                current = current,
                history = history.toList()
            )
        )
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

    /**
     * Parses a session JSON. Pre-v0.3 nights omit `segments` — that yields an empty list;
     * events / clips stay intact. Callers use [ensureSegmentsPersisted] to materialize.
     */
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

    /** Public JSON helpers for export. */
    fun sessionToExportJson(s: SleepSession): JSONObject = toJson(s)

    companion object {
        const val MAX_HISTORY = 90

        private val lock = Any()
        private val cacheRef = AtomicReference<RootCache?>(null)

        fun invalidateCache() {
            cacheRef.set(null)
        }
    }

    private data class RootCache(
        val filePath: String,
        val mtime: Long,
        val running: Boolean,
        val current: SleepSession?,
        val history: List<SleepSession>
    )
}
