package com.i3u8.sleepdesk.audio

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Writes short AAC clips under filesDir/audio_clips/{sessionId}/.
 * Index JSON is owned by SessionStore; this class only handles files.
 */
class AudioClipStore(context: Context) {
    private val filesDir = context.applicationContext.filesDir
    private val root = File(filesDir, "audio_clips").also { if (!it.exists()) it.mkdirs() }

    fun clipsRoot(): File = root

    fun relativePath(sessionId: String, type: NightEventType, eventId: String, timeMs: Long): String {
        val ts = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.getDefault()).format(Date(timeMs))
        val shortId = eventId.take(6)
        return "audio_clips/$sessionId/${ts}_${type.name}_$shortId.m4a"
    }

    fun fileForRelative(relativePath: String): File = File(filesDir, relativePath)

    /**
     * @return (eventId, relativePath) or null if encode failed
     */
    fun encodeAac(
        sessionId: String,
        type: NightEventType,
        pcm: ShortArray,
        sampleRate: Int,
        bitrate: Int,
        timeMs: Long = System.currentTimeMillis(),
        eventId: String = UUID.randomUUID().toString().replace("-", "").take(8)
    ): Pair<String, String>? {
        val rel = relativePath(sessionId, type, eventId, timeMs)
        val file = fileForRelative(rel)
        file.parentFile?.mkdirs()
        val ok = AacEncoder.encodeMonoPcm16(pcm, sampleRate, bitrate, file)
        return if (ok) eventId to rel else null
    }

    fun deleteSessionClips(sessionId: String) {
        File(root, sessionId).deleteRecursively()
    }
}
