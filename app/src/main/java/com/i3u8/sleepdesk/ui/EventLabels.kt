package com.i3u8.sleepdesk.ui

import android.content.Context
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.audio.NightEventType
import com.i3u8.sleepdesk.data.SleepEvent

object EventLabels {

    fun typeLabel(context: Context, type: String): String {
        val res = when (type) {
            NightEventType.SNORE.name -> R.string.event_snore
            NightEventType.COUGH.name -> R.string.event_cough
            NightEventType.SPEECH.name -> R.string.event_speech
            NightEventType.NIGHT_WAKE_SOUND.name -> R.string.event_wake
            NightEventType.ENV_NOISE.name -> R.string.event_env
            NightEventType.ABNORMAL.name -> R.string.event_abnormal
            NightEventType.FALSE_TRIGGER.name -> R.string.event_false
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
}
