package com.i3u8.sleepdesk.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export one night or all nights as a zip:
 * - sessions metadata JSON (session(s) + segments + events)
 * - representative AAC clips referenced by segments
 */
class SessionExporter(context: Context) {

    private val appContext = context.applicationContext
    private val store = SessionStore(appContext)
    private val clipStore = store.clipStore()
    private val exportDir = File(appContext.cacheDir, "exports").also { it.mkdirs() }

    data class Result(
        val zipFile: File,
        val sessionCount: Int,
        val clipCount: Int
    )

    fun exportOne(sessionId: String): Result {
        val session = store.ensureSegmentsPersisted(sessionId)
            ?: store.loadSession(sessionId)
            ?: throw IllegalArgumentException("session not found: $sessionId")
        session.ensureSegments()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date(session.startMs))
        val zip = File(exportDir, "sleep-desk-${session.id.take(8)}-$stamp.zip")
        val clipCount = writeZip(zip, listOf(session))
        return Result(zip, 1, clipCount)
    }

    fun exportAll(): Result {
        val history = store.loadHistory()
        for (s in history) {
            if (s.segments.isEmpty() && s.audioEventCount() > 0) {
                store.ensureSegmentsPersisted(s.id)
            }
        }
        val sessions = store.loadHistory()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zip = File(exportDir, "sleep-desk-all-$stamp.zip")
        val clipCount = writeZip(zip, sessions)
        return Result(zip, sessions.size, clipCount)
    }

    private fun writeZip(zipFile: File, sessions: List<SleepSession>): Int {
        if (zipFile.exists()) zipFile.delete()
        var clipCount = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            val meta = JSONObject()
            meta.put("exportVersion", 1)
            meta.put("appVersion", "0.3.2")
            meta.put("exportedAtMs", System.currentTimeMillis())
            val arr = JSONArray()
            for (s in sessions) {
                s.ensureSegments()
                arr.put(store.sessionToExportJson(s))
            }
            meta.put("sessions", arr)
            zos.putNextEntry(ZipEntry("sessions.json"))
            zos.write(meta.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val seen = HashSet<String>()
            for (s in sessions) {
                for (seg in s.segments) {
                    for (rel in seg.representativeClipPaths) {
                        if (!seen.add(rel)) continue
                        val src = clipStore.fileForRelative(rel)
                        if (!src.exists() || !src.isFile) continue
                        val entryName = "clips/" + rel.removePrefix("audio_clips/")
                        zos.putNextEntry(ZipEntry(entryName))
                        src.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        clipCount++
                    }
                }
            }
        }
        return clipCount
    }

    companion object {
        fun shareZip(context: Context, zipFile: File, chooserTitle: String): Intent {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, zipFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(send, chooserTitle)
        }
    }
}
