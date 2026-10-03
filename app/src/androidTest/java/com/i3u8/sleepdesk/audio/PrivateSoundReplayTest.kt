package com.i3u8.sleepdesk.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in local emulator diagnostics. Private recordings are never packaged as test assets. */
@RunWith(AndroidJUnit4::class)
class PrivateSoundReplayTest {
    @Test fun replayExplicitlyProvidedLocalClips() {
        val arguments = InstrumentationRegistry.getArguments()
        val prepareOnly = arguments.getString("prepareReplay") == "true"
        assumeTrue(prepareOnly || arguments.getString("privateReplay") == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.filesDir, "private-replay")
        check(directory.isDirectory || directory.mkdirs())
        if (prepareOnly) return
        val manifest = JSONArray(File(directory, "manifest.json").readText())
        val results = JSONArray()
        YamNetSoundModel(context).use { model ->
            for (i in 0 until manifest.length()) {
                val row = manifest.getJSONObject(i)
                val id = row.getString("id")
                require(id.matches(Regex("[A-Z]")))
                val bytes = File(directory, "$id.pcm").readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                assertEquals(row.getString("pcmSha256"), hash)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val pcm = ShortArray(buffer.remaining())
                buffer.get(pcm)
                val start = row.getInt("estimatedCoreStartSample")
                require(start in 0 until pcm.size)
                val candidate = CandidateAudio(
                    start.toLong(), pcm.size.toLong(), -90f, pcm.copyOfRange(start, pcm.size),
                    pcm, 0, pcm.size.toLong(), setOf("EXPORTED_CONTEXT_APPROXIMATE")
                )
                // Samples are independent annotated exports, not adjacent live events.
                val result = SoundClassifier(model).classify(candidate, row.getLong("eventStartMs"))
                assertTrue(result.classScores.values.all { it.isFinite() })
                assertTrue(result.classScores.size <= 32)
                results.put(JSONObject()
                    .put("id", id)
                    .put("humanLabel", row.getString("humanLabel"))
                    .put("type", result.type.name)
                    .put("status", result.status.name)
                    .put("suggestedTypes", JSONArray(result.suggestedTypes))
                    .put("classScores", JSONObject(result.classScores))
                    .put("modelVersion", result.modelVersion)
                    .put("reason", result.reason)
                    .put("scope", "exploratory; estimated candidate boundaries; no detection accuracy"))
            }
        }
        assertEquals(manifest.length(), results.length())
        File(directory, "results.json").writeText(results.toString(2))
    }
}
