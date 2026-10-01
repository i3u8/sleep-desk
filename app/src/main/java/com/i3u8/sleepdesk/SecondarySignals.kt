package com.i3u8.sleepdesk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.i3u8.sleepdesk.data.SleepEvent
import java.util.UUID

/**
 * Secondary signals that do NOT require bedside placement.
 * UsageStats skipped for v0.2 (heavy permission UX).
 */
class SecondarySignals(
    private val context: Context,
    private val onEvent: (SleepEvent) -> Unit
) : SensorEventListener {

    private var receiver: BroadcastReceiver? = null
    private var sensorManager: SensorManager? = null
    private var lastLight = Float.NaN
    private var lastLightEventMs = 0L

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val type = when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> SleepEvent.TYPE_SCREEN_ON
                    Intent.ACTION_SCREEN_OFF -> SleepEvent.TYPE_SCREEN_OFF
                    Intent.ACTION_POWER_CONNECTED -> SleepEvent.TYPE_CHARGING_ON
                    Intent.ACTION_POWER_DISCONNECTED -> SleepEvent.TYPE_CHARGING_OFF
                    else -> return
                }
                emit(type, 0.0)
            }
        }
        context.registerReceiver(receiver, filter)

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val lux = event.values[0]
        if (!lastLight.isNaN()) {
            val delta = kotlin.math.abs(lux - lastLight)
            val now = System.currentTimeMillis()
            if (delta >= 30f && lux >= 20f && now - lastLightEventMs > 60_000L) {
                lastLightEventMs = now
                emit(SleepEvent.TYPE_LIGHT_SPIKE, lux.toDouble(), "lux=$lux")
            }
        }
        lastLight = lux
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun emit(type: String, peak: Double, note: String? = null) {
        onEvent(
            SleepEvent(
                id = UUID.randomUUID().toString().take(8),
                timeMs = System.currentTimeMillis(),
                type = type,
                peakLevel = peak,
                clipRelativePath = null,
                note = note
            )
        )
    }
}
