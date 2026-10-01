package com.i3u8.sleepdesk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.data.SessionStore
import com.i3u8.sleepdesk.data.SleepSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SessionDetailBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_session_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sessionId = requireArguments().getString(ARG_ID) ?: return
        val store = SessionStore(requireContext())
        val session = store.loadCurrent()?.takeIf { it.id == sessionId }
            ?: store.loadHistory().firstOrNull { it.id == sessionId }
            ?: return

        bindHeader(view, session)

        val rv = view.findViewById<RecyclerView>(R.id.rvSessionEvents)
        val empty = view.findViewById<TextView>(R.id.tvSessionEventsEmpty)
        val events = session.events.sortedByDescending { it.timeMs }
        if (events.isEmpty()) {
            empty.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            empty.visibility = View.GONE
            rv.visibility = View.VISIBLE
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.adapter = EventsAdapter(events) { e ->
                // Stop any prior detail sheet play by dismissing via new sheet; only one EventDetail at a time
                (parentFragmentManager.findFragmentByTag(EventDetailBottomSheet.TAG) as? EventDetailBottomSheet)
                    ?.dismissAllowingStateLoss()
                EventDetailBottomSheet.newInstance(e)
                    .show(parentFragmentManager, EventDetailBottomSheet.TAG)
            }
        }
    }

    private fun bindHeader(view: View, s: SleepSession) {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val end = s.endMs?.let { fmt.format(Date(it)) } ?: "…"
        val h = TimeUnit.MILLISECONDS.toHours(s.durationMs())
        val m = TimeUnit.MILLISECONDS.toMinutes(s.durationMs()) % 60
        view.findViewById<TextView>(R.id.tvSessionTitle).text =
            getString(R.string.history_item_title, fmt.format(Date(s.startMs)), end)
        view.findViewById<TextView>(R.id.tvSessionSubtitle).text = getString(
            R.string.session_detail_sub,
            h, m,
            s.audioEventCount(),
            s.clipCount()
        )
    }

    companion object {
        private const val ARG_ID = "session_id"
        const val TAG = "SessionDetailBottomSheet"

        fun newInstance(sessionId: String): SessionDetailBottomSheet {
            return SessionDetailBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_ID, sessionId) }
            }
        }
    }
}
