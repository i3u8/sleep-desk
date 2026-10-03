import com.i3u8.sleepdesk.audio.FeatureExtractor
import com.i3u8.sleepdesk.audio.RuleClassifier
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replay the local manifest produced by analyze_export.py --pcm-dir.
 * Uses production Kotlin DSP/rules; neither old labels nor estimated offsets are truth.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: ReplayAudioKt <manifest.tsv> <output.tsv>" }
    val rows = File(args[0]).readLines()
    val header = rows.first().split('\t')
    val counts = sortedMapOf<String, Int>()
    File(args[1]).bufferedWriter().use { output ->
        output.appendLine(
            "pcm\told_type\tview\tnew_type\tscore\tduration_ms\tperiodicity\t" +
                "period_sec\tflatness\tflux\tenvelope_cv\tband_mid\tband_high"
        )
        for (line in rows.drop(1).filter { it.isNotBlank() }) {
            val fields = line.split('\t')
            require(fields.size == header.size) { "Invalid TSV record" }
            val row = header.zip(fields).toMap()
            val source = File(row.getValue("path"))
            val bytes = source.readBytes()
            require(bytes.size % 2 == 0) { "PCM must be signed 16-bit little-endian" }
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val pcm = ShortArray(data.remaining())
            data.get(pcm)
            val sampleRate = row.getValue("sample_rate").toInt()
            val views = linkedMapOf(
                "full_clip_context" to 0,
                "estimated_candidate_tail" to row.getValue("candidate_tail_start_sample").toInt(),
                "estimated_candidate_pre2s" to row.getValue("candidate_pre2s_start_sample").toInt()
            )
            for ((view, offset) in views) {
                require(offset in 0..pcm.size) { "Invalid estimated offset" }
                val f = FeatureExtractor.extract(pcm.copyOfRange(offset, pcm.size), sampleRate, 0f)
                // The old export has no contemporaneous noise floor. Do not fabricate it.
                val (type, score) = RuleClassifier.classify(
                    f, row.getValue("screen_context") == "true"
                )
                val key = "$view:${type.name}"
                counts[key] = (counts[key] ?: 0) + 1
                output.appendLine(listOf(
                    source.name, row.getValue("event_type"), view, type.name, score,
                    f.durationMs, f.periodicity, f.periodSec, f.spectralFlatness,
                    f.spectralFlux, f.envelopeVariation, f.bandMid, f.bandHigh
                ).joinToString("\t"))
            }
        }
    }
    println("Algorithm: ${RuleClassifier.VERSION}; exploratory replay, no accuracy estimate.")
    for ((key, count) in counts) println("$key=$count")
}
