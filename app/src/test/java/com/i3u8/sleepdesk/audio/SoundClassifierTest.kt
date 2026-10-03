package com.i3u8.sleepdesk.audio

import org.junit.Assert.*
import org.junit.Test

class SoundClassifierTest {
    private class FakeModel(var frames: List<ModelFrame>) : SoundModel {
        override val version = "fake-v1"
        var bodyFrames: List<ModelFrame>? = null
        var calls = 0
        override fun infer(pcm: ShortArray, sampleRate: Int): ModelEvidence {
            assertEquals(16000, sampleRate)
            calls++
            return ModelEvidence(version, if (calls % 2 == 0) bodyFrames ?: frames else frames)
        }
        override fun close() = Unit
    }

    private fun candidate(size: Int = 24000, offset: Int = 0) = CandidateAudio(
        offset.toLong(), (offset + size).toLong(), -50f, ShortArray(size),
        ShortArray(offset + size), 0, (offset + size).toLong()
    )
    private fun frame(start: Int = 0, end: Int = 15600, vararg scores: Pair<String, Float>) =
        ModelFrame(start, end, mapOf(*scores))
    private fun strong(label: String = "Snoring") = listOf(
        frame(0, 15600, label to 0.85f),
        frame(7680, 23280, label to 0.8f)
    )

    @Test fun multipleStrongWindowsSuggestSoundWithoutMedicalClaim() {
        val result = SoundClassifier(FakeModel(strong())).classify(candidate(), 0)
        assertEquals(NightEventType.SNORE, result.type)
        assertEquals(ClassificationStatus.SUGGESTED, result.status)
        assertTrue(result.reason!!.contains("non_medical"))
        assertEquals("fake-v1", result.modelVersion)
    }

    @Test fun singlePoint66SnoreIsUnknownButKeepsSuggestion() {
        val result = SoundClassifier(FakeModel(listOf(frame(0, 8000, "Snoring" to 0.66f))))
            .classify(candidate(8000), 0)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertEquals(ClassificationStatus.UNCERTAIN, result.status)
        assertTrue("SNORE" in result.suggestedTypes)
    }

    @Test fun aSingleStrongShortSnoreNeedsNoThreePeakRuleButNeedsAdjacentSupport() {
        val classifier = SoundClassifier(FakeModel(listOf(frame(0, 3200, "Snoring" to 0.85f))))
        assertEquals(NightEventType.UNKNOWN, classifier.classify(candidate(3200), 1000).type)
        val result = classifier.classify(candidate(3200), 2000)
        assertEquals(NightEventType.SNORE, result.type)
        assertEquals(2, result.fusionCount)
        assertEquals(0.85f, result.confidence, 0.001f)
    }

    @Test fun explicitlyConfusableUnmappedClassPreventsSuggestion() {
        val frames = strong().map { it.copy(scores = it.scores + ("Purr" to 0.8f)) }
        val result = SoundClassifier(FakeModel(frames)).classify(candidate(), 0)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertEquals(0.8f, result.classScores.getValue("Purr"), 0.001f)
    }

    @Test fun specificSnoreWinsOverCompatibleBreathingAndBackground() {
        val frames = strong().map {
            it.copy(scores = mapOf(
                "Snoring" to 0.9f, "Breathing" to 0.95f,
                "Inside, small room" to 0.99f, "Noise" to 0.99f
            ))
        }
        val result = SoundClassifier(FakeModel(frames)).classify(candidate(), 0)
        assertEquals(NightEventType.SNORE, result.type)
        assertEquals(ClassificationStatus.SUGGESTED, result.status)
        assertEquals(0.9f, result.confidence, 0.0001f)
        assertEquals(0.95f, result.classScores.getValue("Breathing"), 0.0001f)
    }

    @Test fun compatibleLabelsAllowAdjacentStrongSnoreSupport() {
        val frames = listOf(frame(0, 3200,
            "Snoring" to 0.9f, "Breathing" to 0.95f, "Noise" to 0.99f))
        val classifier = SoundClassifier(FakeModel(frames))
        assertEquals(NightEventType.UNKNOWN, classifier.classify(candidate(3200), 0).type)
        val result = classifier.classify(candidate(3200), 1000)
        assertEquals(NightEventType.SNORE, result.type)
        assertEquals(2, result.fusionCount)
    }

    @Test fun speechAndSnoreRemainConflictingDespiteCompatibleBackground() {
        val frames = strong().map {
            it.copy(scores = mapOf(
                "Snoring" to 0.9f, "Speech" to 0.88f, "Breathing" to 0.95f,
                "Inside, small room" to 0.99f, "Noise" to 0.99f
            ))
        }
        val classifier = SoundClassifier(FakeModel(frames))
        repeat(3) {
            val result = classifier.classify(candidate(), it * 3000L)
            assertEquals(NightEventType.UNKNOWN, result.type)
            assertEquals(ClassificationStatus.UNCERTAIN, result.status)
            assertTrue(result.suggestedTypes.containsAll(listOf("SNORE", "SPEECH")))
        }
    }

    @Test fun sighMapsToNonMedicalBreathingSuggestion() {
        val result = SoundClassifier(FakeModel(strong("Sigh"))).classify(candidate(), 0)
        assertEquals(NightEventType.BREATHING, result.type)
        assertTrue(result.reason!!.contains("non_medical"))
        assertTrue("BREATHING" in result.suggestedTypes)
    }

    @Test fun backgroundCannotUpgradeWeakSnoreToEnvironment() {
        val frames = strong().map { it.copy(scores = mapOf("Snoring" to 0.66f, "Noise" to 0.99f)) }
        val result = SoundClassifier(FakeModel(frames)).classify(candidate(), 0)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertTrue("SNORE" in result.suggestedTypes)
    }

    @Test fun decisionScoresAreBoundedWithoutTruncatingInferenceEvidence() {
        val semantic = listOf(
            "Snoring", "Cough", "Throat clearing", "Speech", "Conversation", "Whispering",
            "Breathing", "Wheeze", "Sigh", "Rustle", "Creak", "Tap", "Thump, thud",
            "Knock", "Surface contact", "Noise", "White noise", "Pink noise", "Wind",
            "Rain", "Fan", "Air conditioning", "Vehicle", "Traffic noise, roadway noise", "Music"
        ).associateWith { 0.01f }
        val allScores = semantic + (0 until 521 - semantic.size).associate {
            "other-$it" to (0.2f + it / 1000f)
        } + ("Snoring" to 0.9f)
        val frames = strong().map { it.copy(scores = allScores) }
        val result = SoundClassifier(FakeModel(frames)).classify(candidate(), 0)
        assertEquals(NightEventType.SNORE, result.type)
        assertTrue(result.classScores.size <= 32)
        assertEquals(4, result.classScores.keys.count { it.startsWith("other-") })
        assertTrue(result.classScores.keys.containsAll(semantic.keys))
        val expectedOther = (492..495).map { "other-$it" }.toSet()
        assertEquals(expectedOther, result.classScores.keys.filter { it.startsWith("other-") }.toSet())
        assertEquals(521, frames[0].scores.size)
        assertEquals(allScores, frames[1].scores)
    }

    @Test fun coughAndSnoreConflictRemainsUnknown() {
        val frames = strong().map { it.copy(scores = it.scores + ("Cough" to 0.75f)) }
        val result = SoundClassifier(FakeModel(frames)).classify(candidate(), 0)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertTrue(result.suggestedTypes.containsAll(listOf("SNORE", "COUGH")))
    }

    @Test fun priorSnoreCannotInventCurrentEvidence() {
        val model = FakeModel(listOf(frame(0, 3200, "Snoring" to 0.9f)))
        val classifier = SoundClassifier(model)
        classifier.classify(candidate(3200), 0)
        model.frames = listOf(frame(0, 3200, "Silence" to 0.99f))
        val result = classifier.classify(candidate(3200), 1000)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertTrue(result.suggestedTypes.isEmpty())
        assertEquals(1, result.fusionCount)
    }

    @Test fun repeatedWeakEvidenceNeverBecomesStrong() {
        val classifier = SoundClassifier(FakeModel(listOf(frame(0, 3200, "Snoring" to 0.4f))))
        repeat(10) {
            assertEquals(NightEventType.UNKNOWN, classifier.classify(candidate(3200), it * 1000L).type)
        }
    }

    @Test fun averagedCompetitionCannotCreateIndependentStrongWindowSupport() {
        val model = FakeModel(listOf(
            frame(0, 15600, "Snoring" to 0.85f, "Cough" to 0.7f),
            frame(7680, 23280, "Snoring" to 0.85f, "Speech" to 0.7f)
        ))
        val classifier = SoundClassifier(model)
        repeat(3) {
            val result = classifier.classify(candidate(), it * 3000L)
            assertEquals(NightEventType.UNKNOWN, result.type)
            assertEquals(1, result.fusionCount)
        }
    }

    @Test fun preRollFramesCannotBeAssignedToCurrentCore() {
        val model = FakeModel(listOf(
            frame(0, 15600, "Speech" to 0.99f),
            frame(16000, 31600, "Snoring" to 0.85f),
            frame(23680, 39280, "Snoring" to 0.85f)
        ))
        model.bodyFrames = strong()
        val result = SoundClassifier(model).classify(candidate(24000, 16000), 0)
        assertEquals(NightEventType.SNORE, result.type)
        assertFalse("SPEECH" in result.suggestedTypes)
        assertEquals(2, model.calls)
        assertEquals(0.99f, result.contextSpeechScore!!, 0f)
    }

    @Test fun fullClipSpeechPeakIncludesEachSpeechLabelWithoutBodyFiltering() {
        for (label in listOf("Speech", "Conversation", "Whispering")) {
            val model = FakeModel(listOf(
                frame(0, 15600, label to 0.97f),
                frame(16000, 31600, "Snoring" to 0.85f),
                frame(23680, 39280, "Snoring" to 0.85f)
            ))
            model.bodyFrames = strong()
            val result = SoundClassifier(model).classify(candidate(24000, 16000), 0)
            assertEquals(NightEventType.SNORE, result.type)
            assertEquals(ClassificationStatus.SUGGESTED, result.status)
            assertFalse("SPEECH" in result.suggestedTypes)
            assertEquals(0.97f, result.contextSpeechScore!!, 0f)
        }
    }

    @Test fun temporalFusionPreservesFullClipSpeechPeak() {
        val model = FakeModel(listOf(
            frame(0, 15600, "Whispering" to 0.98f),
            frame(16000, 19200, "Snoring" to 0.85f)
        ))
        model.bodyFrames = listOf(frame(0, 3200, "Snoring" to 0.85f))
        val classifier = SoundClassifier(model)
        classifier.classify(candidate(3200, 16000), 0)
        val result = classifier.classify(candidate(3200, 16000), 1000)
        assertEquals(NightEventType.SNORE, result.type)
        assertEquals(2, result.fusionCount)
        assertEquals(0.98f, result.contextSpeechScore!!, 0f)
    }

    @Test fun availableNonSpeechFramesHaveZeroContextSpeechScore() {
        val result = SoundClassifier(FakeModel(strong())).classify(candidate(), 0)
        assertEquals(0f, result.contextSpeechScore!!, 0f)
    }

    @Test fun failedDecisionDefaultsToUnavailableContextSpeechScore() {
        val result = SoundDecision(
            NightEventType.UNKNOWN, 0f, ClassificationStatus.FAILED,
            emptyMap(), emptyList(), null, "model_failure"
        )
        assertNull(result.contextSpeechScore)
    }

    @Test fun shortOverlappingContextSnoreWithoutBodySnoreIsOnlyACandidate() {
        val model = FakeModel(listOf(frame(0, 15000, "Snoring" to 0.95f)))
        model.bodyFrames = listOf(frame(0, 3000, "Cough" to 0.9f))
        val result = SoundClassifier(model).classify(candidate(3000, 12000), 0)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertEquals(ClassificationStatus.UNCERTAIN, result.status)
        assertEquals(listOf("SNORE", "COUGH"), result.suggestedTypes)
        assertEquals(0.95f, result.classScores.getValue("Snoring"), 0.0001f)
        assertTrue(result.reason!!.contains("short_body_context_candidate"))
    }

    @Test fun shortBodySilenceCannotEraseOverlappingContextCandidateOrEnableFusion() {
        val model = FakeModel(listOf(frame(0, 15200, "Snoring" to 0.9f)))
        model.bodyFrames = listOf(frame(0, 3200, "Silence" to 0.95f))
        val classifier = SoundClassifier(model)
        repeat(3) {
            val result = classifier.classify(candidate(3200, 12000), it * 1000L)
            assertEquals(NightEventType.UNKNOWN, result.type)
            assertEquals(ClassificationStatus.UNCERTAIN, result.status)
            assertEquals(listOf("SNORE"), result.suggestedTypes)
            assertEquals(0.9f, result.classScores.getValue("Snoring"), 0.0001f)
            assertEquals(0f, result.confidence, 0f)
            assertEquals(1, result.fusionCount)
            assertTrue(result.reason!!.contains("short_body_context_candidate"))
        }
    }

    @Test fun shortBodyCannotBorrowStrongPriorEventToPromoteContextOnlyCandidate() {
        val model = FakeModel(listOf(frame(0, 15200, "Snoring" to 0.9f)))
        model.bodyFrames = listOf(frame(0, 3200, "Snoring" to 0.9f))
        val classifier = SoundClassifier(model)
        classifier.classify(candidate(3200, 12000), 0)
        model.bodyFrames = listOf(frame(0, 3200, "Silence" to 0.95f))
        val result = classifier.classify(candidate(3200, 12000), 1000)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertEquals(ClassificationStatus.UNCERTAIN, result.status)
        assertTrue("SNORE" in result.suggestedTypes)
        assertEquals(1, result.fusionCount)
    }

    @Test fun fullWindowBodyStillVetoesContextOnlyCandidatesAtExactBoundary() {
        for (size in listOf(15599, 15600, 24000)) {
            val model = FakeModel(listOf(frame(12000, 27600.coerceAtMost(12000 + size), "Snoring" to 0.9f)))
            model.bodyFrames = listOf(frame(0, size, "Silence" to 0.95f))
            val result = SoundClassifier(model).classify(candidate(size, 12000), 0)
            assertEquals(NightEventType.UNKNOWN, result.type)
            assertEquals(size < 15600, "SNORE" in result.suggestedTypes)
            assertEquals(if (size < 15600) 0.9f else 0f, result.classScores.getValue("Snoring"), 0.0001f)
        }
    }

    @Test fun shortBodyUnionExcludesCompletelyDisjointPreRollButKeepsPrivacyEvidence() {
        val model = FakeModel(listOf(
            frame(0, 15600, "Snoring" to 0.9f, "Speech" to 0.99f),
            frame(16000, 19200, "Silence" to 0.95f)
        ))
        model.bodyFrames = listOf(frame(0, 3200, "Silence" to 0.95f))
        val result = SoundClassifier(model).classify(candidate(3200, 16000), 0)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertTrue(result.suggestedTypes.isEmpty())
        assertEquals(0f, result.classScores["Snoring"] ?: 0f, 0f)
        assertEquals(0.99f, result.contextSpeechScore!!, 0f)
    }

    @Test fun shortBodyUnionKeepsMaximumDisplayScoreWithoutChangingConfidence() {
        val model = FakeModel(listOf(frame(0, 15200, "Snoring" to 0.4f)))
        model.bodyFrames = listOf(frame(0, 3200, "Snoring" to 0.6f))
        val result = SoundClassifier(model).classify(candidate(3200, 12000), 0)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertEquals(listOf("SNORE"), result.suggestedTypes)
        assertEquals(0.6f, result.classScores.getValue("Snoring"), 0.0001f)
        assertEquals(0.4f, result.confidence, 0.0001f)
    }

    @Test fun bodyConflictCannotDisappearThroughContextIntersection() {
        val model = FakeModel(listOf(frame(0, 15000, "Snoring" to 0.8f)))
        model.bodyFrames = listOf(frame(0, 3000, "Snoring" to 0.8f, "Cough" to 0.9f))
        val classifier = SoundClassifier(model)
        classifier.classify(candidate(3000, 12000), 0)
        assertEquals(NightEventType.UNKNOWN, classifier.classify(candidate(3000, 12000), 1000).type)
    }

    @Test fun duplicateWindowsAreNotMultipleSupport() {
        val single = frame(0, 15600, "Snoring" to 0.9f)
        val result = SoundClassifier(FakeModel(listOf(single, single))).classify(candidate(), 0)
        assertEquals(NightEventType.UNKNOWN, result.type)
    }

    @Test fun environmentIsOnlyASuggestionNeverFalseTrigger() {
        val result = SoundClassifier(FakeModel(strong("Music"))).classify(candidate(), 0)
        assertEquals(NightEventType.ENV_NOISE, result.type)
        assertEquals(ClassificationStatus.SUGGESTED, result.status)
    }

    @Test fun inferenceExceptionIsNotSilentlyConvertedToUnknown() {
        val failure = IllegalStateException("inference failed")
        val model = object : SoundModel {
            override val version = "broken"
            override fun infer(pcm: ShortArray, sampleRate: Int): ModelEvidence = throw failure
            override fun close() = Unit
        }
        try {
            SoundClassifier(model).classify(candidate(), 0)
            fail("Expected pipeline-visible Exception")
        } catch (actual: Exception) {
            assertSame(failure, actual)
        }
    }

    @Test fun noFramesMeansUnknown() {
        val result = SoundClassifier(FakeModel(emptyList())).classify(candidate(), 0)
        assertEquals(NightEventType.UNKNOWN, result.type)
        assertEquals(0f, result.confidence, 0f)
        assertNull(result.contextSpeechScore)
    }
}
