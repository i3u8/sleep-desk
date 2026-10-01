package com.i3u8.sleepdesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.i3u8.sleepdesk.audio.AudioAlgoConfig
import com.i3u8.sleepdesk.audio.AudioClipStore
import com.i3u8.sleepdesk.audio.NightAudioEngine
import com.i3u8.sleepdesk.audio.NightAudioEngineImpl
import com.i3u8.sleepdesk.audio.NightAudioListener
import com.i3u8.sleepdesk.audio.NightEvent
import com.i3u8.sleepdesk.data.SessionStore
import com.i3u8.sleepdesk.data.SleepEvent

/**
 * FGS (microphone): owns [NightAudioEngine] lifecycle + secondary non-mic signals.
 * Accel is NOT primary in v0.2.
 */
class SleepTrackingService : Service(), NightAudioListener {

    private lateinit var store: SessionStore
    private lateinit var audioEngine: NightAudioEngine
    private var wakeLock: PowerManager.WakeLock? = null
    private var secondary: SecondarySignals? = null

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        audioEngine = NightAudioEngineImpl(this, AudioClipStore(this))
        audioEngine.setListener(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishAndStop()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification(getString(R.string.notif_tracking))
                if (Build.VERSION.SDK_INT >= 34) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                acquireWakeLock()
                val session = store.loadCurrent() ?: store.startNew()
                if (!audioEngine.isRunning()) {
                    audioEngine.start(session.id, AudioAlgoConfig())
                }
                startSecondary()
            }
        }
        return START_STICKY
    }

    override fun onEvent(event: NightEvent) {
        store.appendNightEvent(event)
        updateNotification()
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName))
    }

    override fun onEngineError(t: Throwable) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notif_mic_error))
        )
    }

    private fun startSecondary() {
        if (secondary != null) return
        secondary = SecondarySignals(this) { event ->
            if (event.type == SleepEvent.TYPE_SCREEN_ON) {
                audioEngine.onAuxScreenChanged(true)
            } else if (event.type == SleepEvent.TYPE_SCREEN_OFF) {
                audioEngine.onAuxScreenChanged(false)
            }
            store.appendEvent(event)
            updateNotification()
            sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName))
        }.also { it.start() }
    }

    private fun updateNotification() {
        val cur = store.loadCurrent()
        val audioN = cur?.audioEventCount() ?: 0
        val clips = cur?.clipCount() ?: 0
        val text = getString(R.string.notif_tracking_stats, audioN, clips)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun finishAndStop() {
        audioEngine.stop()
        secondary?.stop()
        secondary = null
        store.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepdesk:tracking").also {
            it.setReferenceCounted(false)
            if (!it.isHeld) it.acquire(14 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        audioEngine.stop()
        secondary?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
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
        const val ACTION_EVENT = "com.i3u8.sleepdesk.EVENT"
        const val ACTION_STOPPED = "com.i3u8.sleepdesk.STOPPED"
        const val CHANNEL_ID = "sleep_tracking"
        const val NOTIFICATION_ID = 1001
    }
}
