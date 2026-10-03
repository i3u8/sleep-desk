package com.i3u8.sleepdesk.audio

/**
 * Serial background stage. Model/encoder failure changes an event's status, never its existence.
 * No classifier result can undo detection or silently discard the captured event.
 */
internal class SoundEventProcessor(
    private val config: AudioAlgoConfig,
    private val modelFactory: () -> SoundModel,
    private val writeClip: (NightEvent, CandidateAudio) -> String?,
    private val publish: (NightEvent) -> Unit
) : AutoCloseable {
    private var model: SoundModel? = null
    private var classifier: SoundClassifier? = null
    private var initializationFailure: Throwable? = null
    private var savedInSession = 0
    private var savedInHour = 0
    private var hour = Long.MIN_VALUE

    fun process(detected: NightEvent, audio: CandidateAudio) {
        var event = detected.copy(
            revision = detected.revision + 1,
            features = detected.features + audio.clipFeatures(config.sampleRate) +
                audio.contextFlags.associate { "context.$it" to 1f }
        )
        // Respect the existing speech-recording preference before writing any known speech.
        if (config.saveSpeechClips) {
            event = save(event, audio)
            publish(event)
        }
        event = classify(event, audio)
        if (!config.saveSpeechClips) {
            val speechExcluded = event.classificationStatus == ClassificationStatus.SUGGESTED &&
                event.type != NightEventType.SPEECH &&
                NightEventType.SPEECH.name !in event.suggestedTypes &&
                (event.features["contextSpeechScore"]?.let { it < .15f } == true)
            event = if (speechExcluded) save(event, audio)
            else event.copy(clipStatus = ClipStatus.DISABLED)
        }
        publish(event.copy(revision = detected.revision + 2))
    }

    fun cancel(event: NightEvent, reason: String) {
        publish(event.copy(
            type = NightEventType.UNKNOWN, confidence = 0f,
            classificationStatus = ClassificationStatus.FAILED,
            classificationReason = reason, clipStatus = ClipStatus.FAILED,
            revision = event.revision + 2
        ))
    }

    private fun save(event: NightEvent, audio: CandidateAudio): NightEvent {
        val currentHour = event.startMs / 3_600_000L
        if (hour != currentHour) {
            hour = currentHour
            savedInHour = 0
        }
        if (savedInSession >= config.maxClipsPerSession || savedInHour >= config.maxClipsPerHour) {
            return event.copy(clipStatus = ClipStatus.QUOTA_REACHED)
        }
        return try {
            val path = writeClip(event, audio)
            if (path == null) event.copy(clipStatus = ClipStatus.FAILED)
            else {
                savedInHour++
                savedInSession++
                event.copy(clipRelativePath = path, clipStatus = ClipStatus.SAVED)
            }
        } catch (_: Exception) {
            event.copy(clipStatus = ClipStatus.FAILED)
        }
    }

    private fun classify(event: NightEvent, audio: CandidateAudio): NightEvent {
        return try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Analysis cancelled")
            initializationFailure?.let { throw it }
            if (classifier == null) {
                try {
                    val loaded = modelFactory()
                    model = loaded
                    classifier = SoundClassifier(loaded)
                } catch (failure: Throwable) {
                    initializationFailure = failure
                    throw failure
                }
            }
            val result = checkNotNull(classifier).classify(audio, event.startMs)
            event.copy(
                type = result.type, confidence = result.confidence,
                classificationStatus = result.status, classScores = result.classScores,
                suggestedTypes = result.suggestedTypes, modelVersion = result.modelVersion,
                classificationReason = result.reason,
                features = event.features + ("fusionEventCount" to result.fusionCount.toFloat()) +
                    (result.contextSpeechScore?.let { mapOf("contextSpeechScore" to it) } ?: emptyMap())
            )
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            event.copy(
                type = NightEventType.UNKNOWN, confidence = 0f,
                classificationStatus = ClassificationStatus.FAILED,
                classificationReason = if (failure is InterruptedException) "analysis_cancelled"
                else "model_failure:${failure.javaClass.simpleName}"
            )
        }
    }

    override fun close() {
        model?.close()
        model = null
        classifier = null
    }
}
