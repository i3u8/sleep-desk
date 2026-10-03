package com.i3u8.sleepdesk.audio

import org.junit.Assert.*
import org.junit.Test

class SoundEventProcessorTest {
    private val audio = CandidateAudio(0, 32000, -60f, ShortArray(32000) { 1000 },
        ShortArray(32000) { 1000 }, 0, 32000)
    private fun event(id: String = "one") = NightEvent(
        id, NightEventType.UNKNOWN, 10_000, 12_000, 0f,
        detectionConfidence = .8f, classificationStatus = ClassificationStatus.PENDING,
        clipStatus = ClipStatus.PENDING
    )
    private class FixedModel(val scores: Map<String, Float> = emptyMap()) : SoundModel {
        override val version = "fake-model-v1"
        var closed = false
        override fun close() { closed = true }
        override fun infer(pcm: ShortArray, sampleRate: Int) = ModelEvidence(version,
            if (pcm.size < 23280) listOf(ModelFrame(0, minOf(pcm.size, 15600), scores))
            else listOf(ModelFrame(0, 15600, scores), ModelFrame(7680, 23280, scores)))
    }

    @Test fun failureStillRetainsDetectedEventAndSavedAudio() {
        val emitted = mutableListOf<NightEvent>()
        val processor = SoundEventProcessor(AudioAlgoConfig(),
            { throw IllegalStateException("unavailable model") },
            { _, _ -> "audio_clips/s/one.m4a" }, emitted::add)
        processor.process(event(), audio)
        val result = emitted.last()
        assertEquals("one", result.id)
        assertEquals(.8f, result.detectionConfidence, 0f)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertEquals(ClassificationStatus.FAILED, result.classificationStatus)
        assertEquals(ClipStatus.SAVED, result.clipStatus)
        assertNotNull(result.clipRelativePath)
    }

    @Test fun weakModelEvidenceCannotRemoveOrRenameTheDetectedEvent() {
        val emitted = mutableListOf<NightEvent>()
        val model = FixedModel()
        val processor = SoundEventProcessor(AudioAlgoConfig(), { model },
            { _, _ -> "unknown.m4a" }, emitted::add)
        processor.process(event(), audio)
        assertEquals(ClassificationStatus.PENDING, emitted.first().classificationStatus)
        assertEquals(ClassificationStatus.UNCERTAIN, emitted.last().classificationStatus)
        assertEquals(NightEventType.UNKNOWN, emitted.last().type)
        assertEquals(setOf("one"), emitted.map { it.id }.toSet())
        assertEquals(ClipStatus.SAVED, emitted.last().clipStatus)
        processor.close()
        assertTrue(model.closed)
    }

    @Test fun storageQuotaDoesNotSuppressDetectionOrClassification() {
        val emitted = mutableListOf<NightEvent>()
        val processor = SoundEventProcessor(AudioAlgoConfig(maxClipsPerSession = 0),
            { FixedModel() }, { _, _ -> error("quota must prevent encoding") }, emitted::add)
        processor.process(event(), audio)
        assertEquals("one", emitted.last().id)
        assertEquals(ClipStatus.QUOTA_REACHED, emitted.last().clipStatus)
        assertEquals(ClassificationStatus.UNCERTAIN, emitted.last().classificationStatus)
    }

    @Test fun encoderFailureDoesNotEraseSuccessfulModelEvidence() {
        val emitted = mutableListOf<NightEvent>()
        val processor = SoundEventProcessor(AudioAlgoConfig(),
            { FixedModel(mapOf("Snoring" to .95f)) },
            { _, _ -> throw java.io.IOException("encoder failure") }, emitted::add)
        processor.process(event(), audio)
        assertEquals(NightEventType.SNORE, emitted.last().type)
        assertEquals(ClassificationStatus.SUGGESTED, emitted.last().classificationStatus)
        assertEquals(ClipStatus.FAILED, emitted.last().clipStatus)
        assertTrue(emitted.last().classScores.isNotEmpty())
    }

    @Test fun speechPreferenceIsCheckedBeforeWritingSpeech() {
        val emitted = mutableListOf<NightEvent>()
        val processor = SoundEventProcessor(AudioAlgoConfig(saveSpeechClips = false),
            { FixedModel(mapOf("Speech" to .95f)) },
            { _, _ -> error("speech recording disabled") }, emitted::add)
        processor.process(event(), audio)
        assertEquals(NightEventType.SPEECH, emitted.last().type)
        assertEquals(ClipStatus.DISABLED, emitted.last().clipStatus)
        assertNull(emitted.last().clipRelativePath)
    }

    @Test fun savedQuotasCountSuccessfulWritesOnly() {
        val emitted = mutableListOf<NightEvent>()
        var attempts = 0
        val processor = SoundEventProcessor(AudioAlgoConfig(maxClipsPerSession = 1),
            { FixedModel() }, { _, _ -> if (++attempts == 1) null else "saved.m4a" }, emitted::add)
        processor.process(event("one"), audio)
        processor.process(event("two"), audio)
        processor.process(event("three"), audio)
        assertEquals(2, attempts)
        assertEquals(ClipStatus.SAVED, emitted.last { it.id == "two" }.clipStatus)
        assertEquals(ClipStatus.QUOTA_REACHED, emitted.last().clipStatus)
    }

    @Test fun uncertainShortSpeechIsNotRecordedWhenSpeechSavingIsDisabled() {
        val emitted = mutableListOf<NightEvent>()
        val processor = SoundEventProcessor(AudioAlgoConfig(saveSpeechClips = false),
            { FixedModel(mapOf("Speech" to .95f)) },
            { _, _ -> error("Uncertain speech must not be saved") }, emitted::add)
        val short = audio.copy(endSample = 8000, pcm = ShortArray(8000),
            clipPcm = ShortArray(8000), clipEndSample = 8000)
        processor.process(event(), short)
        assertEquals(NightEventType.UNKNOWN, emitted.last().type)
        assertEquals(ClassificationStatus.UNCERTAIN, emitted.last().classificationStatus)
        assertTrue("SPEECH" in emitted.last().suggestedTypes)
        assertEquals(ClipStatus.DISABLED, emitted.last().clipStatus)
    }

    @Test fun modelFailureCannotBypassTheSpeechRecordingPreference() {
        val emitted = mutableListOf<NightEvent>()
        val processor = SoundEventProcessor(AudioAlgoConfig(saveSpeechClips = false),
            { throw IllegalStateException("model unavailable") },
            { _, _ -> error("Cannot rule out speech") }, emitted::add)
        processor.process(event(), audio)
        assertEquals(ClassificationStatus.FAILED, emitted.last().classificationStatus)
        assertEquals(ClipStatus.DISABLED, emitted.last().clipStatus)
        assertEquals("one", emitted.last().id)
    }

    @Test fun contextSpeechCannotBeIgnoredJustBecauseBodyLooksLikeSnore() {
        val emitted = mutableListOf<NightEvent>()
        val model = object : SoundModel {
            override val version = "context-test"
            override fun close() {}
            override fun infer(pcm: ShortArray, sampleRate: Int): ModelEvidence {
                val snore = mapOf("Snoring" to .95f)
                return ModelEvidence(version, if (pcm.size == 48000) listOf(
                    ModelFrame(0, 15600, mapOf("Speech" to .98f)),
                    ModelFrame(16000, 31600, snore), ModelFrame(23680, 39280, snore)
                ) else listOf(ModelFrame(0, 15600, snore), ModelFrame(7680, 23280, snore)))
            }
        }
        val processor = SoundEventProcessor(AudioAlgoConfig(saveSpeechClips = false),
            { model }, { _, _ -> error("Pre-roll speech must prevent saving") }, emitted::add)
        processor.process(event(), audio.copy(startSample = 16000, endSample = 48000,
            clipPcm = ShortArray(48000), clipEndSample = 48000))
        assertEquals(NightEventType.SNORE, emitted.last().type)
        assertEquals(ClipStatus.DISABLED, emitted.last().clipStatus)
        assertTrue(emitted.last().features.getValue("contextSpeechScore") > .9f)
    }
}
