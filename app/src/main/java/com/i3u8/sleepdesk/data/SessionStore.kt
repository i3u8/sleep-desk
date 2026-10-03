package com.i3u8.sleepdesk.data

import android.content.Context
import android.util.AtomicFile
import com.i3u8.sleepdesk.audio.AudioClipStore
import com.i3u8.sleepdesk.audio.NightEvent
import com.i3u8.sleepdesk.audio.ClassificationStatus
import com.i3u8.sleepdesk.audio.ClipStatus
import com.i3u8.sleepdesk.audio.NightEventType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    private val atomicFile = AtomicFile(file)
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
            if ((!hadSegs || s.segments.any { it.segmentVersion != SegmentBuilder.VERSION }) && s.audioEventCount() > 0) {
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
        val needsRebuild = target.segments.isEmpty() || target.segments.any { it.segmentVersion != SegmentBuilder.VERSION }
        target.ensureSegments()
        if (needsRebuild && target.segments.isNotEmpty() && !target.isRunning) {
            writeRoot(running = root.running, current = current, history = history)
        }
        target
    }

    fun startNew(): SleepSession = synchronized(lock) {
        val now = System.currentTimeMillis()
        val session = SleepSession(id = UUID.randomUUID().toString(), startMs = now)
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

    fun findSessionForEvent(eventId: String): SleepSession? = synchronized(lock) {
        val root = cachedRoot() ?: return null
        (listOfNotNull(root.current) + root.history)
            .singleOrNull { s -> s.events.any { it.id == eventId } }
    }

    /** Explicit ownership survives stop; a deleted owner's callbacks must never create another night. */
    fun appendNightEvent(event: NightEvent, sessionId: String? = null) = synchronized(lock) {
        val root = cachedRoot() ?: return
        if (event.sessionId != null && sessionId != null && event.sessionId != sessionId) return
        val ownerId = event.sessionId ?: sessionId
        val target = if (ownerId != null) {
            (listOfNotNull(root.current) + root.history).firstOrNull { it.id == ownerId }
        } else {
            root.current?.takeIf { root.running }
        } ?: return
        val incoming = SleepEvent.fromNightEvent(event)
        val index = target.events.indexOfFirst { it.id == event.id }
        val old = target.events.getOrNull(index)
        if (old != null) {
            if (incoming.revision < old.revision) return
            if (incoming.revision == old.revision && incoming.classificationStatus == ClassificationStatus.PENDING &&
                old.classificationStatus in setOf(ClassificationStatus.SUGGESTED,
                    ClassificationStatus.UNCERTAIN, ClassificationStatus.FAILED)) return
        }
        val merged = if (old == null) incoming else {
            val reviewed = SleepEvent.REVIEW_EDITED in old.reviewFlags
            incoming.copy(
                clipRelativePath = incoming.clipRelativePath ?: old.clipRelativePath,
                clipStatus = if (old.clipStatus == ClipStatus.SAVED &&
                    (incoming.clipRelativePath == null ||
                        incoming.clipStatus in setOf(ClipStatus.LEGACY, ClipStatus.PENDING))) old.clipStatus else incoming.clipStatus,
                userLabel = if (reviewed || old.userLabel != null) old.userLabel else incoming.userLabel,
                reviewFlags = if (reviewed) {
                    (incoming.reviewFlags - SleepEvent.REVIEW_IMPORTANT) + old.reviewFlags
                } else incoming.reviewFlags + old.reviewFlags
            )
        }
        val settled = if (target.isRunning) merged else recoverPending(merged)
        if (index < 0) target.events.add(settled) else {
            target.events[index] = settled
            target.events.removeAll { it.id == event.id && it !== settled }
        }
        target.segments.clear()
        writeRoot(root.running, root.current, root.history)
    }

    fun updateEventReview(sessionId: String, eventId: String, userLabel: String?, important: Boolean): Boolean =
        synchronized(lock) {
            if (userLabel != null && NightEventType.entries.none { it.name == userLabel }) return false
            val root = cachedRoot() ?: return false
            val target = (listOfNotNull(root.current) + root.history).firstOrNull { it.id == sessionId }
                ?: return false
            val index = target.events.indexOfFirst { it.id == eventId }
            if (index < 0) return false
            val old = target.events[index]
            val flags = (old.reviewFlags - SleepEvent.REVIEW_IMPORTANT) + SleepEvent.REVIEW_EDITED
            target.events[index] = old.copy(
                userLabel = userLabel,
                reviewFlags = if (important) flags + SleepEvent.REVIEW_IMPORTANT else flags
            )
            target.segments.clear()
            writeRoot(root.running, root.current, root.history)
            true
        }

    fun stop(sessionId: String? = null): SleepSession? = synchronized(lock) {
        val root = cachedRoot() ?: return null
        if (!root.running || root.current == null) return null
        val current = root.current
        if (sessionId != null && current.id != sessionId) return null
        current.endMs = System.currentTimeMillis()
        current.events.replaceAll(::recoverPending)
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

    private fun recoverPending(event: SleepEvent): SleepEvent =
        if (event.classificationStatus != ClassificationStatus.PENDING) event else event.copy(
            type = NightEventType.UNKNOWN.name,
            classificationStatus = ClassificationStatus.FAILED,
            confidence = 0f,
            classScores = emptyMap(),
            suggestedTypes = emptyList(),
            classificationReason = "stopped_before_analysis",
            revision = event.revision.coerceAtLeast(2),
            clipStatus = when {
                !event.clipRelativePath.isNullOrEmpty() -> ClipStatus.SAVED
                event.clipStatus == ClipStatus.DISABLED -> ClipStatus.DISABLED
                else -> ClipStatus.FAILED
            }
        )

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
        return try {
            val text = atomicFile.openRead().bufferedReader().use { it.readText() }
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
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let { atomicFile.failWrite(it) }
            cacheRef.set(null)
            throw error
        }
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
                    .put("peakLevel", e.peakLevel.takeIf { it.isFinite() } ?: 0.0)
                    .put("confidence", e.confidence.takeIf { it.isFinite() }?.toDouble() ?: 0.0)
                    .put("clipRelativePath", e.clipRelativePath ?: JSONObject.NULL)
                    .put("note", e.note ?: JSONObject.NULL)
                    .put("algoVersion", e.algoVersion ?: JSONObject.NULL)
                    .put("features", JSONObject(e.features.filterValues { it.isFinite() }))
                    .put("detectionConfidence", e.detectionConfidence.takeIf { it.isFinite() }?.toDouble() ?: 0.0)
                    .put("classificationStatus", e.classificationStatus.name)
                    .put("classScores", JSONObject(e.classScores.filterValues { it.isFinite() }))
                    .put("suggestedTypes", JSONArray(e.suggestedTypes))
                    .put("modelVersion", e.modelVersion ?: JSONObject.NULL)
                    .put("classificationReason", e.classificationReason ?: JSONObject.NULL)
                    .put("clipStatus", e.clipStatus.name)
                    .put("reviewFlags", JSONArray(e.reviewFlags.toList()))
                    .put("userLabel", e.userLabel ?: JSONObject.NULL)
                    .put("revision", e.revision)
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
        o.put("peakConfidence", seg.peakConfidence.takeIf { it.isFinite() }?.toDouble() ?: 0.0)
        o.put("peakDb", seg.peakDb.takeIf { it.isFinite() } ?: 0.0)
        o.put("snoreMinutes", seg.snoreMinutes.takeIf { it.isFinite() }?.toDouble() ?: 0.0)
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
            peakConfidence = o.optDouble("peakConfidence", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
            peakDb = o.optDouble("peakDb", 0.0).takeIf { it.isFinite() } ?: 0.0,
            snoreMinutes = o.optDouble("snoreMinutes", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
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
            val featureJson = e.optJSONObject("features")
            val features = mutableMapOf<String, Float>()
            if (featureJson != null) {
                for (key in featureJson.keys()) {
                    val value = featureJson.optDouble(key, Double.NaN).toFloat()
                    if (value.isFinite()) features[key] = value
                }
            }
            events.add(
                SleepEvent(
                    id = e.optString("id", UUID.randomUUID().toString()),
                    timeMs = e.getLong("timeMs"),
                    endMs = e.optLong("endMs", e.getLong("timeMs")),
                    type = e.getString("type"),
                    peakLevel = e.optDouble("peakLevel", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    confidence = e.optDouble("confidence", 1.0).toFloat().takeIf { it.isFinite() } ?: 0f,
                    clipRelativePath = if (e.isNull("clipRelativePath")) null else e.optString("clipRelativePath"),
                    note = if (e.isNull("note")) null else e.optString("note"),
                    algoVersion = if (e.isNull("algoVersion")) null else e.optString("algoVersion"),
                    features = features,
                    detectionConfidence = e.optDouble("detectionConfidence", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
                    classificationStatus = ClassificationStatus.entries.firstOrNull {
                        it.name == e.optString("classificationStatus")
                    } ?: ClassificationStatus.LEGACY,
                    classScores = e.optJSONObject("classScores")?.let { scores ->
                        scores.keys().asSequence().mapNotNull { key ->
                            scores.optDouble(key).toFloat().takeIf { it.isFinite() }?.let { key to it }
                        }.toMap()
                    } ?: emptyMap(),
                    suggestedTypes = stringList(e, "suggestedTypes"),
                    modelVersion = nullableString(e, "modelVersion"),
                    classificationReason = nullableString(e, "classificationReason"),
                    clipStatus = ClipStatus.entries.firstOrNull {
                        it.name == e.optString("clipStatus")
                    } ?: ClipStatus.LEGACY,
                    reviewFlags = stringList(e, "reviewFlags").toSet(),
                    userLabel = nullableString(e, "userLabel"),
                    revision = e.optLong("revision", 0L)
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

    private fun nullableString(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key)

    private fun stringList(o: JSONObject, key: String): List<String> =
        o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

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
