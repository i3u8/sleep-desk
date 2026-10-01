package com.i3u8.sleepdesk.ui

import android.content.Context
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.audio.NightEventType
import com.i3u8.sleepdesk.data.NightSegment

object SegmentLabels {

    fun primaryLabel(context: Context, label: String): String {
        val res = when (label) {
            NightEventType.SNORE.name -> R.string.segment_snore
            NightEventType.COUGH.name,
            NightEventType.NIGHT_WAKE_SOUND.name -> R.string.segment_wake_cough
            NightEventType.SPEECH.name -> R.string.segment_speech
            NightEventType.ABNORMAL.name -> R.string.segment_abnormal
            "MIXED" -> R.string.segment_mixed
            else -> null
        }
        return if (res != null) context.getString(res) else label
    }

    fun shortTypeName(context: Context, type: String): String {
        val res = when (type) {
            NightEventType.SNORE.name -> R.string.segment_short_snore
            NightEventType.COUGH.name -> R.string.segment_short_cough
            NightEventType.SPEECH.name -> R.string.segment_short_speech
            NightEventType.NIGHT_WAKE_SOUND.name -> R.string.segment_short_wake
            NightEventType.ABNORMAL.name -> R.string.segment_short_abnormal
            else -> null
        }
        return if (res != null) context.getString(res) else EventLabels.typeLabel(context, type)
    }

    fun subtitle(context: Context, segment: NightSegment): String {
        val parts = segment.labels
            .filter { it.value > 0 }
            .entries
            .sortedByDescending { it.value }
            .map { (type, count) ->
                context.getString(R.string.segment_count_part, count, shortTypeName(context, type))
            }
        val body = if (parts.isEmpty()) {
            context.getString(R.string.segment_no_events)
        } else {
            parts.joinToString(" · ")
        }
        val clips = segment.representativeClipPaths.size
        return context.getString(R.string.segment_subtitle, body, clips)
    }

    fun colorFor(label: String): Int = when (label) {
        NightEventType.SNORE.name -> 0xFF60A5FA.toInt()
        NightEventType.COUGH.name -> 0xFFF97316.toInt()
        NightEventType.SPEECH.name -> 0xFFA78BFA.toInt()
        NightEventType.NIGHT_WAKE_SOUND.name -> 0xFFEF4444.toInt()
        NightEventType.ABNORMAL.name -> 0xFFFBBF24.toInt()
        "MIXED" -> 0xFF94A3B8.toInt()
        else -> 0xFF7C9CFF.toInt()
    }
}
