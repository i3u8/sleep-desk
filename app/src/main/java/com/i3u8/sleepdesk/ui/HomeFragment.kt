package com.i3u8.sleepdesk.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.SleepTrackingService
import com.i3u8.sleepdesk.audio.NightEventType
import com.i3u8.sleepdesk.data.SessionStore
import com.i3u8.sleepdesk.data.SleepEvent
import com.i3u8.sleepdesk.data.SleepSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    private lateinit var store: SessionStore
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSummary: TextView
    private var tracking = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val micOk = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (micOk) startTracking() else tvSummary.text = getString(R.string.perm_mic_denied)
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = SessionStore(requireContext())
        btnToggle = view.findViewById(R.id.btnToggle)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvSummary = view.findViewById(R.id.tvSummary)
        btnToggle.setOnClickListener {
            if (tracking) stopTracking() else maybeStart()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(SleepTrackingService.ACTION_EVENT)
            addAction(SleepTrackingService.ACTION_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(refreshReceiver, filter)
        }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
    }

    private fun refreshUi() {
        if (!isAdded) return
        val current = store.loadCurrent()
        tracking = current != null
        if (tracking) {
            btnToggle.text = getString(R.string.btn_stop)
            btnToggle.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.stop_red)
            tvStatus.text = getString(R.string.status_tracking)
            tvSummary.text = formatRunning(current!!)
        } else {
            btnToggle.text = getString(R.string.btn_start)
            btnToggle.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.start_green)
            tvStatus.text = getString(R.string.status_idle)
            val last = store.loadLastFinished()
            tvSummary.text = if (last != null) formatFinished(last) else getString(R.string.hint_home)
        }
    }

    private fun maybeStart() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
        else startTracking()
    }

    private fun startTracking() {
        val intent = Intent(requireContext(), SleepTrackingService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
        tracking = true
        btnToggle.postDelayed({ refreshUi() }, 350)
    }

    private fun stopTracking() {
        val intent = Intent(requireContext(), SleepTrackingService::class.java)
            .setAction(SleepTrackingService.ACTION_STOP)
        requireContext().startService(intent)
        tracking = false
        btnToggle.postDelayed({ refreshUi() }, 300)
    }

    private fun formatRunning(s: SleepSession): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return getString(
            R.string.summary_running,
            fmt.format(Date(s.startMs)),
            formatDuration(s.durationMs()),
            s.countByType(NightEventType.SNORE),
            s.countByType(NightEventType.NIGHT_WAKE_SOUND) + s.countByType(NightEventType.COUGH),
            s.countByType(NightEventType.ENV_NOISE),
            s.clipCount()
        )
    }

    private fun formatFinished(s: SleepSession): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val end = s.endMs ?: s.startMs
        return getString(
            R.string.summary_last,
            fmt.format(Date(s.startMs)),
            fmt.format(Date(end)),
            formatDuration(s.durationMs()),
            s.audioEventCount(),
            s.clipCount(),
            s.countByType(SleepEvent.TYPE_SCREEN_ON)
        )
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return getString(R.string.duration_hm, h, m)
    }
}
