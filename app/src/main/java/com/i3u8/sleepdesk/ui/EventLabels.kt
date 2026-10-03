package com.i3u8.sleepdesk.ui

import android.content.Context
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.audio.NightEventType
import com.i3u8.sleepdesk.audio.ClassificationStatus
import com.i3u8.sleepdesk.audio.ClipStatus
import com.i3u8.sleepdesk.data.SleepEvent

object EventLabels {
    fun eventTitle(context: Context, event: SleepEvent): String {
        val label = typeLabel(context, event.effectiveType)
        return if (event.userLabel == null) label else context.getString(R.string.event_manual_title, label)
    }

    fun candidates(context: Context, event: SleepEvent): String =
        event.suggestedTypes.asSequence()
            .filter { type -> NightEventType.entries.any { it.name == type } }
            .distinct().take(3).map { typeLabel(context, it) }
            .joinToString("、").ifEmpty { context.getString(R.string.event_no_candidates) }

    fun typeLabel(context: Context, type: String): String {
        val res = when (type) {
            NightEventType.SNORE.name -> R.string.event_snore
            NightEventType.COUGH.name -> R.string.event_cough
            NightEventType.SPEECH.name -> R.string.event_speech
            NightEventType.NIGHT_WAKE_SOUND.name -> R.string.event_wake
            NightEventType.ENV_NOISE.name -> R.string.event_env
            NightEventType.ABNORMAL.name -> R.string.event_abnormal
            NightEventType.FALSE_TRIGGER.name -> R.string.event_false
            NightEventType.UNKNOWN.name -> R.string.event_unknown
            NightEventType.BREATHING.name -> R.string.event_breathing
            NightEventType.BED_MOVEMENT.name -> R.string.event_bed_movement
            NightEventType.CONTACT_SOUND.name -> R.string.event_contact
            SleepEvent.TYPE_SCREEN_ON -> R.string.event_screen_on
            SleepEvent.TYPE_SCREEN_OFF -> R.string.event_screen_off
            SleepEvent.TYPE_CHARGING_ON -> R.string.event_charge_on
            SleepEvent.TYPE_CHARGING_OFF -> R.string.event_charge_off
            SleepEvent.TYPE_LIGHT_SPIKE -> R.string.event_light
            else -> null
        }
        return if (res != null) context.getString(res) else type
    }

    fun hasClipPotential(type: String): Boolean {
        return NightEventType.entries.any { it.name == type && it != NightEventType.FALSE_TRIGGER }
    }

    fun classification(context: Context, status: ClassificationStatus): String =
        context.getString(when (status) {
            ClassificationStatus.LEGACY -> R.string.status_legacy
            ClassificationStatus.PENDING -> R.string.status_pending
            ClassificationStatus.SUGGESTED -> R.string.status_suggested
            ClassificationStatus.UNCERTAIN -> R.string.status_uncertain
            ClassificationStatus.FAILED -> R.string.status_failed
        })

    fun clip(context: Context, status: ClipStatus): String = context.getString(when (status) {
        ClipStatus.LEGACY -> R.string.clip_status_legacy
        ClipStatus.PENDING -> R.string.clip_status_pending
        ClipStatus.SAVED -> R.string.clip_status_saved
        ClipStatus.QUOTA_REACHED -> R.string.clip_status_quota
        ClipStatus.FAILED -> R.string.clip_status_failed
        ClipStatus.DISABLED -> R.string.clip_status_disabled
    })
}
