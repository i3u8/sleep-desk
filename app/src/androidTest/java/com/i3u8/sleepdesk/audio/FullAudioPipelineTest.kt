package com.i3u8.sleepdesk.audio

import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.i3u8.sleepdesk.data.SessionExporter
import com.i3u8.sleepdesk.data.SessionStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated-emulator tests: real model, Android AAC encoder, persistence and ZIP export. */
@RunWith(AndroidJUnit4::class)
class FullAudioPipelineTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: SessionStore
    private val config = AudioAlgoConfig(sessionWarmupMs = 600, energySmoothMs = 100, digitalGainDb = 0f)

    @Before fun resetFixture() {
        store = SessionStore(context)
        store.stop()
        store.clearAll()
        SessionStore.invalidateCache()
    }

    @Test fun continuousPcmReachesModelRecordingStorageAndExportWithoutLosingDetection() {
        val session = store.startNew()
        val observed = CopyOnWriteArrayList<NightEvent>()
        fun publish(event: NightEvent) {
            observed.add(event)
            store.appendNightEvent(event.copy(sessionId = session.id))
        }
        val clipStore = AudioClipStore(context)
        val processor = SoundEventProcessor(config, { YamNetSoundModel(context) },
            { event, audio ->
                clipStore.encodeAac(session.id, NightEventType.UNKNOWN, audio.clipPcm,
                    16000, config.aacBitrate, event.startMs, event.id)?.second
            }, ::publish)
        val queue = BackgroundAudioQueue(8, processor::process, processor::cancel, processor::close)
        val pipeline = EventDetectionPipeline(config, session.startMs, ::publish) { event, audio ->
            queue.submit(event, audio)
        }
        var samples = 0
        fun feed(ms: Int, amplitude: Int) {
            var remaining = ms * 16
            while (remaining > 0) {
                val count = minOf(240, remaining)
                val frame = ShortArray(count) {
                    (amplitude * sin(2 * PI * 180 * (samples + it) / 16000)).toInt().toShort()
                }
                pipeline.process(frame, count)
                samples += count
                remaining -= count
            }
        }
        try {
            feed(2500, 32)
            feed(600, 6000)
            feed(300, 32)
            val pending = store.loadCurrent()!!.events.single()
            assertEquals(ClassificationStatus.PENDING, pending.classificationStatus)
            assertEquals("UNKNOWN", pending.type)
            assertTrue(pending.detectionConfidence > 0)
            assertNull(pending.clipRelativePath)
            feed(2000, 32)
            pipeline.flush()
        } finally {
            assertTrue("Native processing must drain", queue.finish(30_000))
        }
        val result = store.loadCurrent()!!.events.single()
        assertNotEquals(ClassificationStatus.PENDING, result.classificationStatus)
        assertNotEquals(ClassificationStatus.FAILED, result.classificationStatus)
        assertEquals(ClipStatus.SAVED, result.clipStatus)
        assertEquals(1, observed.map { it.id }.toSet().size)
        assertEquals(2000f, result.features.getValue("candidateOffsetMs"), 1f)
        assertFalse(result.features.containsKey("context.POST_ROLL_SHORT"))
        assertTrue(result.modelVersion!!.startsWith("yamnet-"))
        val file = clipStore.fileForRelative(result.clipRelativePath!!)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertEquals(result.features.getValue("clipDurationMs").toDouble(), duration.toDouble(), 200.0)
        } finally {
            retriever.release()
        }
        store.stop()
        assertTrue(store.updateEventReview(session.id, result.id, "SNORE", true))
        val exported = SessionExporter(context).exportOne(session.id)
        ZipFile(exported.zipFile).use { zip ->
            val json = zip.getInputStream(zip.getEntry("sessions.json")).bufferedReader().use { it.readText() }
            assertTrue(json.contains("USER_IMPORTANT"))
            assertTrue(json.contains("detectionConfidence"))
            assertTrue(json.contains("modelVersion"))
            assertTrue(zip.entries().asSequence().any { it.name.endsWith(".m4a") })
        }
    }

    @Test fun nativeRecordingSurvivesAnUnavailableModel() {
        val session = store.startNew()
        val pcm = ShortArray(16000) { (3000 * sin(2 * PI * 180 * it / 16000)).toInt().toShort() }
        val audio = CandidateAudio(0, pcm.size.toLong(), -60f, pcm, pcm, 0, pcm.size.toLong())
        val event = NightEvent("native-failure", NightEventType.UNKNOWN, session.startMs,
            session.startMs + 1000, 0f, detectionConfidence = .8f,
            classificationStatus = ClassificationStatus.PENDING, clipStatus = ClipStatus.PENDING,
            sessionId = session.id)
        store.appendNightEvent(event)
        val processor = SoundEventProcessor(config, { throw IllegalStateException("test missing model") },
            { e, a -> AudioClipStore(context).encodeAac(session.id, e.type, a.clipPcm,
                16000, 40000, e.startMs, e.id)?.second },
            { store.appendNightEvent(it) })
        processor.process(event, audio)
        processor.close()
        val result = store.loadCurrent()!!.events.single()
        assertEquals(ClassificationStatus.FAILED, result.classificationStatus)
        assertEquals(ClipStatus.SAVED, result.clipStatus)
        assertTrue(AudioClipStore(context).fileForRelative(result.clipRelativePath!!).length() > 0)
        assertTrue(store.loadCurrent()!!.ensureSegments().any { result.id in it.eventIds })
    }
}
