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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    private lateinit var btnToggle: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var cardStats: MaterialCardView
    private lateinit var tvStatDuration: TextView
    private lateinit var tvStatSnore: TextView
    private lateinit var tvStatWake: TextView
    private lateinit var tvStatClips: TextView
    private lateinit var tvEventsTitle: TextView
    private lateinit var rvEvents: RecyclerView
    private lateinit var tvEventsEmpty: TextView
    private var tracking = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val micOk = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (micOk) startTracking() else {
            tvHint.visibility = View.VISIBLE
            tvHint.text = getString(R.string.perm_mic_denied)
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SleepTrackingService.ACTION_EVENT) {
                val type = intent.getStringExtra(SleepTrackingService.EXTRA_EVENT_TYPE)
                if (!type.isNullOrEmpty()) {
                    showLiveEventFeedback(type)
                }
            }
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
        tvHint = view.findViewById(R.id.tvHint)
        cardStats = view.findViewById(R.id.cardStats)
        tvStatDuration = view.findViewById(R.id.tvStatDuration)
        tvStatSnore = view.findViewById(R.id.tvStatSnore)
        tvStatWake = view.findViewById(R.id.tvStatWake)
        tvStatClips = view.findViewById(R.id.tvStatClips)
        tvEventsTitle = view.findViewById(R.id.tvEventsTitle)
        rvEvents = view.findViewById(R.id.rvTonightEvents)
        tvEventsEmpty = view.findViewById(R.id.tvEventsEmpty)
        rvEvents.layoutManager = LinearLayoutManager(requireContext())
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
            btnToggle.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.stop_red)
            tvStatus.text = getString(R.string.status_tracking)
            tvHint.visibility = View.GONE
            bindStats(current!!)
            bindEvents(current.events, getString(R.string.events_tonight))
        } else {
            btnToggle.text = getString(R.string.btn_start)
            btnToggle.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.start_green)
            tvStatus.text = getString(R.string.status_idle)
            val last = store.loadLastFinished()
            if (last != null) {
                tvHint.visibility = View.GONE
                bindStats(last)
                bindEvents(last.events, getString(R.string.events_last))
            } else {
                cardStats.visibility = View.GONE
                tvEventsTitle.visibility = View.GONE
                rvEvents.visibility = View.GONE
                tvEventsEmpty.visibility = View.GONE
                tvHint.visibility = View.VISIBLE
                tvHint.text = getString(R.string.hint_home)
            }
        }
    }

    private fun bindStats(s: SleepSession) {
        cardStats.visibility = View.VISIBLE
        tvStatDuration.text = formatDuration(s.durationMs())
        tvStatSnore.text = s.countByType(NightEventType.SNORE).toString()
        tvStatWake.text = (
            s.countByType(NightEventType.NIGHT_WAKE_SOUND) +
                s.countByType(NightEventType.COUGH)
            ).toString()
        tvStatClips.text = s.clipCount().toString()
    }

    private fun bindEvents(events: List<SleepEvent>, title: String) {
        tvEventsTitle.visibility = View.VISIBLE
        tvEventsTitle.text = title
        val sorted = events.sortedByDescending { it.timeMs }
        if (sorted.isEmpty()) {
            rvEvents.visibility = View.GONE
            tvEventsEmpty.visibility = View.VISIBLE
            tvEventsEmpty.text = getString(R.string.events_empty)
        } else {
            tvEventsEmpty.visibility = View.GONE
            rvEvents.visibility = View.VISIBLE
            rvEvents.adapter = EventsAdapter(sorted) { e ->
                (parentFragmentManager.findFragmentByTag(EventDetailBottomSheet.TAG) as? EventDetailBottomSheet)
                    ?.dismissAllowingStateLoss()
                EventDetailBottomSheet.newInstance(e)
                    .show(parentFragmentManager, EventDetailBottomSheet.TAG)
            }
        }
    }


    private fun showLiveEventFeedback(type: String) {
        if (!isAdded || view == null) return
        val label = EventLabels.typeLabel(requireContext(), type)
        val msg = getString(R.string.event_live_toast, label)
        val anchor = view ?: return
        try {
            Snackbar.make(anchor, msg, Snackbar.LENGTH_SHORT)
                .setAnchorView(btnToggle)
                .show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
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

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return getString(R.string.duration_hm, h, m)
    }
}
