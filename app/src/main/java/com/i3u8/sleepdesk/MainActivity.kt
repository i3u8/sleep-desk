package com.i3u8.sleepdesk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSummary: TextView

    private var tracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SessionStore(this)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvSummary = findViewById(R.id.tvSummary)

        btnToggle.setOnClickListener {
            if (tracking) stopTracking() else maybeStart()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val current = store.loadCurrent()
        tracking = current != null
        if (tracking) {
            btnToggle.text = getString(R.string.btn_stop)
            btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.stop_red))
            tvStatus.text = getString(R.string.status_tracking)
            tvSummary.text = formatRunning(current!!)
        } else {
            btnToggle.text = getString(R.string.btn_start)
            btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.start_green))
            tvStatus.text = getString(R.string.status_idle)
            val last = store.loadLast()
            tvSummary.text = if (last != null) formatFinished(last) else getString(R.string.hint_place_phone)
        }
    }

    private fun maybeStart() {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!ok) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
                return
            }
        }
        startTracking()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Start even if denied: FGS still works; notification may be hidden on some OEMs
        if (requestCode == REQ_NOTIF) startTracking()
    }

    private fun startTracking() {
        val intent = Intent(this, SleepTrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        tracking = true
        // Small delay so service can create the session file
        btnToggle.postDelayed({ refreshUi() }, 300)
    }

    private fun stopTracking() {
        val intent = Intent(this, SleepTrackingService::class.java).setAction(SleepTrackingService.ACTION_STOP)
        startService(intent)
        // Also stop via store if service already dead
        store.stop()
        tracking = false
        btnToggle.postDelayed({ refreshUi() }, 200)
    }

    private fun formatRunning(s: SessionStore.Session): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return getString(
            R.string.summary_running,
            fmt.format(Date(s.startMs)),
            formatDuration(s.durationMs()),
            s.buckets.size,
            s.highMotionCount()
        )
    }

    private fun formatFinished(s: SessionStore.Session): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val end = s.endMs ?: s.startMs
        return getString(
            R.string.summary_last,
            fmt.format(Date(s.startMs)),
            fmt.format(Date(end)),
            formatDuration(s.durationMs()),
            s.highMotionCount(),
            s.buckets.size
        )
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return getString(R.string.duration_hm, h, m)
    }

    companion object {
        private const val REQ_NOTIF = 42
    }
}
