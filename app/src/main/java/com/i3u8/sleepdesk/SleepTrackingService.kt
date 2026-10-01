package com.i3u8.sleepdesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

/**
 * Foreground service: low-rate accelerometer sampling + 60s motion-energy buckets.
 */
class SleepTrackingService : Service(), SensorEventListener {

    private lateinit var store: SessionStore
    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var bucketStartMs = 0L
    private var sumDelta = 0.0
    private var sampleCount = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastZ = Float.NaN

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishAndStop()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_tracking)))
                acquireWakeLock()
                ensureSession()
                startSensors()
            }
        }
        return START_STICKY
    }

    private fun ensureSession() {
        if (store.loadCurrent() == null) {
            store.startNew()
        }
        bucketStartMs = System.currentTimeMillis()
        resetBucketAccum()
    }

    private fun startSensors() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        // ~5–10 Hz is enough for bed-side motion; SENSOR_DELAY_NORMAL ~5 Hz
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopSensors() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepdesk:tracking").also {
            it.setReferenceCounted(false)
            if (!it.isHeld) it.acquire(12 * 60 * 60 * 1000L) // up to 12h
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (!lastX.isNaN()) {
            val dx = x - lastX
            val dy = y - lastY
            val dz = z - lastZ
            sumDelta += sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            sampleCount++
        }
        lastX = x
        lastY = y
        lastZ = z

        val now = System.currentTimeMillis()
        if (now - bucketStartMs >= SessionStore.BUCKET_MS) {
            flushBucket(bucketStartMs)
            // Advance in whole buckets to avoid drift pile-up
            while (now - bucketStartMs >= SessionStore.BUCKET_MS) {
                bucketStartMs += SessionStore.BUCKET_MS
            }
            resetBucketAccum()
        }
    }

    private fun flushBucket(startMs: Long) {
        val energy = if (sampleCount > 0) sumDelta / sampleCount else 0.0
        store.appendBucket(startMs, energy)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(getString(R.string.notif_tracking)))
    }

    private fun resetBucketAccum() {
        sumDelta = 0.0
        sampleCount = 0
        lastX = Float.NaN
        lastY = Float.NaN
        lastZ = Float.NaN
    }

    private fun finishAndStop() {
        // Flush partial bucket
        if (sampleCount > 0) {
            flushBucket(bucketStartMs)
        }
        store.stop()
        stopSensors()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        stopSensors()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SleepTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, getString(R.string.btn_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.i3u8.sleepdesk.STOP"
        const val CHANNEL_ID = "sleep_tracking"
        const val NOTIFICATION_ID = 1001
    }
}
