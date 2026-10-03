package com.i3u8.sleepdesk.audio

interface SoundModel : AutoCloseable {
    val version: String
    fun infer(pcm: ShortArray, sampleRate: Int): ModelEvidence
    override fun close()
}

/** Half-open sample offsets in the PCM passed to infer, never padded sample positions. */
data class ModelFrame(val startSample: Int, val endSample: Int, val scores: Map<String, Float>)
data class ModelEvidence(val version: String, val frames: List<ModelFrame>)

/** Uncalibrated, non-medical support. classScores contains at most 32 raw label summaries. */
data class SoundDecision(
    val type: NightEventType,
    val confidence: Float,
    val status: ClassificationStatus,
    val classScores: Map<String, Float>,
    val suggestedTypes: List<String>,
    val modelVersion: String?,
    val reason: String?,
    val fusionCount: Int = 1,
    /** Full-clip speech peak for privacy gating; null means unavailable, not silence. */
    val contextSpeechScore: Float? = null
)

internal class SoundClassifier(private val model: SoundModel) {
    private val fusion = TemporalSoundFusion()

    @Synchronized
    fun classify(candidate: CandidateAudio, eventStartMs: Long): SoundDecision {
        require(candidate.pcm.isNotEmpty() && candidate.clipPcm.isNotEmpty())
        val offset = candidate.startSample - candidate.clipStartSample
        require(offset in 0..candidate.clipPcm.size.toLong())
        val coreStart = offset.toInt()
        val coreEnd = minOf(
            candidate.clipPcm.size.toLong(), offset + candidate.pcm.size
        ).toInt()
        require(coreEnd > coreStart)
        val evidence = model.infer(candidate.clipPcm, SAMPLE_RATE)
        val local = summarize(evidence, coreStart, coreEnd, candidate.clipPcm.size)
        // A window overlapping a short core can still contain a loud, unrelated pre-roll.
        val body = if (coreStart != 0 || candidate.clipPcm.size != candidate.pcm.size) {
            val bodyEvidence = model.infer(candidate.pcm, SAMPLE_RATE)
            check(bodyEvidence.version == evidence.version) { "Model changed within candidate" }
            summarize(bodyEvidence, 0, candidate.pcm.size, candidate.pcm.size)
        } else local
        val shortBody = candidate.pcm.size < MODEL_WINDOW_SAMPLES
        // Padding can weaken a short isolated body. Union is for display only, never promotion.
        val displayScores = (local.scores.keys + body.scores.keys).associateWith {
            val localScore = local.scores[it] ?: 0f
            val bodyScore = body.scores[it] ?: 0f
            if (shortBody) maxOf(localScore, bodyScore) else minOf(localScore, bodyScore)
        }
        val types = groups.keys.associateWith { type ->
            minOf(local.types[type] ?: 0f, body.types[type] ?: 0f)
        }.entries.sortedByDescending { it.value }
        // Background labels and respiratory parents must not outrank a strong specific sound.
        val foreground = types.filter { it.key != NightEventType.ENV_NOISE && it.value >= CANDIDATE }
        val top = foreground.firstOrNull { it.key != NightEventType.BREATHING && it.value >= STRONG }
            ?: foreground.firstOrNull() ?: types.first()
        val competitor = maxOf(competition(top.key, local.scores), competition(top.key, body.scores))
        val strong = top.value >= STRONG && top.value - competitor >= MARGIN
        val support = minOf(local.support[top.key] ?: 0, body.support[top.key] ?: 0)
        val candidateTypes = if (shortBody) {
            local.types.entries.sortedByDescending { it.value } +
                body.types.entries.sortedByDescending { it.value }
        } else types
        val suggested = candidateTypes.filter { it.value >= CANDIDATE }.map { it.key.name }.distinct()
        val shortBodyContextCandidate = shortBody && groups.keys.any {
            val localScore = local.types.getValue(it)
            val bodyScore = body.types.getValue(it)
            maxOf(localScore, bodyScore) >= CANDIDATE && localScore != bodyScore
        }
        val decision = SoundDecision(
            if (strong && support >= 2) top.key else NightEventType.UNKNOWN,
            top.value,
            if (strong && support >= 2) ClassificationStatus.SUGGESTED
            else ClassificationStatus.UNCERTAIN,
            compactScores(displayScores), suggested, evidence.version,
            when {
                suggested.isEmpty() -> "no_local_model_evidence;non_medical"
                !strong -> "weak_or_competing_model_evidence;non_medical"
                support < 2 -> "single_window_requires_adjacent_support;non_medical"
                else -> "multiple_core_windows;uncalibrated;non_medical"
            }.let { if (shortBodyContextCandidate) "short_body_context_candidate;$it" else it },
            contextSpeechScore = evidence.frames.maxOfOrNull { frame ->
                groups.getValue(NightEventType.SPEECH).maxOf { frame.scores[it] ?: 0f }
            }
        )
        return fusion.fuse(
            decision, eventStartMs,
            eventStartMs + candidate.pcm.size * 1000L / SAMPLE_RATE,
            top.key.takeIf { strong && support >= 1 }
        )
    }

    private data class Summary(
        val scores: Map<String, Float>,
        val types: Map<NightEventType, Float>,
        val support: Map<NightEventType, Int>
    )

    private fun competition(type: NightEventType, scores: Map<String, Float>): Float =
        competitors.getValue(type).maxOfOrNull { scores[it] ?: 0f } ?: 0f

    private fun compactScores(scores: Map<String, Float>): Map<String, Float> {
        val ranked = scores.entries.sortedWith(
            compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key }
        )
        val relevant = ranked.filter { it.key in semanticLabels }.take(MAX_SCORES - MAX_OTHER_SCORES)
        val other = ranked.filter { it.key !in semanticLabels }.take(MAX_OTHER_SCORES)
        return (relevant + other).associate { it.key to it.value }
    }

    private fun summarize(evidence: ModelEvidence, start: Int, end: Int, size: Int): Summary {
        evidence.frames.forEach { frame ->
            require(frame.startSample >= 0 && frame.endSample > frame.startSample &&
                frame.endSample <= size) { "Invalid model frame offsets" }
            require(frame.scores.values.all { it.isFinite() && it in 0f..1f }) {
                "Invalid model scores"
            }
        }
        val frames = evidence.frames.distinctBy { it.startSample to it.endSample }
            .filter {
                val overlap = (minOf(end, it.endSample) - maxOf(start, it.startSample)).coerceAtLeast(0)
                overlap > 0 && overlap.toFloat() / minOf(end - start, it.endSample - it.startSample) >= 0.5f
            }.sortedBy { it.startSample }
        fun aggregate(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val weights = frames.map {
                (minOf(end, it.endSample) - maxOf(start, it.startSample)).toFloat()
            }
            val mean = values.indices.sumOf { (values[it] * weights[it]).toDouble() } / weights.sum()
            return (0.7 * mean + 0.3 * values.max()).toFloat()
        }
        val scores = frames.flatMap { it.scores.keys }.toSet().associateWith { label ->
            aggregate(frames.map { it.scores[label] ?: 0f })
        }
        val types = groups.mapValues { (_, labels) ->
            aggregate(frames.map { frame -> labels.maxOf { frame.scores[it] ?: 0f } })
        }
        val support = groups.mapValues { (type, labels) ->
            var lastStart = -HOP
            frames.count { frame ->
                val score = labels.maxOf { frame.scores[it] ?: 0f }
                val other = competition(type, frame.scores)
                val eligible = score >= STRONG && score - other >= MARGIN &&
                    frame.startSample - lastStart >= HOP
                if (eligible) lastStart = frame.startSample
                eligible
            }
        }
        return Summary(scores, types, support)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MODEL_WINDOW_SAMPLES = 15_600
        private const val HOP = 7_680
        private const val STRONG = 0.70f
        private const val MARGIN = 0.20f
        private const val CANDIDATE = 0.15f
        private const val MAX_SCORES = 32
        private const val MAX_OTHER_SCORES = 4

        // Display groupings, not a trained head. Raw infer frames retain all 521 labels.
        private val groups = linkedMapOf(
            NightEventType.SNORE to setOf("Snoring"),
            NightEventType.COUGH to setOf("Cough", "Throat clearing"),
            NightEventType.SPEECH to setOf("Speech", "Conversation", "Whispering"),
            NightEventType.BREATHING to setOf("Breathing", "Wheeze", "Sigh"),
            NightEventType.BED_MOVEMENT to setOf("Rustle", "Creak"),
            NightEventType.CONTACT_SOUND to setOf("Tap", "Thump, thud", "Knock", "Surface contact"),
            NightEventType.ENV_NOISE to setOf(
                "Noise", "White noise", "Pink noise", "Wind", "Rain", "Fan", "Air conditioning",
                "Vehicle", "Traffic noise, roadway noise", "Music"
            )
        )
        private val semanticLabels = groups.values.flatten().toSet()

        // Explicit single-event ambiguities, not all multilabel outputs. In particular,
        // breathing can accompany snoring/coughing; room/noise labels describe the scene.
        private val confusionTypes = mapOf(
            NightEventType.SNORE to setOf(
                NightEventType.COUGH, NightEventType.SPEECH,
                NightEventType.BED_MOVEMENT, NightEventType.CONTACT_SOUND
            ),
            NightEventType.COUGH to setOf(
                NightEventType.SNORE, NightEventType.SPEECH,
                NightEventType.BED_MOVEMENT, NightEventType.CONTACT_SOUND
            ),
            NightEventType.SPEECH to setOf(
                NightEventType.SNORE, NightEventType.COUGH, NightEventType.BREATHING,
                NightEventType.BED_MOVEMENT, NightEventType.CONTACT_SOUND
            ),
            NightEventType.BREATHING to setOf(
                NightEventType.SNORE, NightEventType.COUGH, NightEventType.SPEECH,
                NightEventType.BED_MOVEMENT, NightEventType.CONTACT_SOUND
            ),
            NightEventType.BED_MOVEMENT to setOf(
                NightEventType.SNORE, NightEventType.COUGH, NightEventType.SPEECH,
                NightEventType.BREATHING, NightEventType.CONTACT_SOUND
            ),
            NightEventType.CONTACT_SOUND to setOf(
                NightEventType.SNORE, NightEventType.COUGH, NightEventType.SPEECH,
                NightEventType.BREATHING, NightEventType.BED_MOVEMENT
            ),
            NightEventType.ENV_NOISE to (groups.keys - NightEventType.ENV_NOISE)
        )
        private val competitors = confusionTypes.mapValues { (type, others) ->
            others.flatMap { groups.getValue(it) }.toSet() + when (type) {
                NightEventType.SNORE -> setOf("Purr", "Growling", "Grunt")
                NightEventType.COUGH -> setOf("Sneeze")
                else -> emptySet()
            }
        }
    }
}
