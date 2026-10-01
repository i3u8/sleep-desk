package com.i3u8.sleepdesk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.data.SessionStore
import com.i3u8.sleepdesk.data.SleepSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SessionDetailBottomSheet : BottomSheetDialogFragment() {

    private var sessionId: String? = null
    private var deleted = false
    private var eventsExpanded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_session_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sessionId = requireArguments().getString(ARG_ID) ?: return
        val store = SessionStore(requireContext())
        val session = store.loadCurrent()?.takeIf { it.id == sessionId }
            ?: store.loadHistory().firstOrNull { it.id == sessionId }
            ?: return

        bindHeader(view, session)

        val timeline = view.findViewById<NightTimelineView>(R.id.nightTimeline)
        val segments = session.ensureSegments()

        timeline.setSession(session, showActivityRibbon = true) { seg ->
            openSegment(session.id, seg.id)
        }

        val rvSeg = view.findViewById<RecyclerView>(R.id.rvSessionSegments)
        val emptySeg = view.findViewById<TextView>(R.id.tvSessionSegmentsEmpty)
        if (segments.isEmpty()) {
            emptySeg.visibility = View.VISIBLE
            rvSeg.visibility = View.GONE
        } else {
            emptySeg.visibility = View.GONE
            rvSeg.visibility = View.VISIBLE
            rvSeg.layoutManager = LinearLayoutManager(requireContext())
            rvSeg.adapter = SegmentsAdapter(segments) { seg ->
                openSegment(session.id, seg.id)
            }
        }

        val btnDelete = view.findViewById<MaterialButton>(R.id.btnDeleteSession)
        if (session.isRunning) {
            btnDelete.visibility = View.GONE
        } else {
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener { confirmDelete(store, session) }
        }

        // Collapsed raw events
        val btnExpand = view.findViewById<MaterialButton>(R.id.btnExpandAllEvents)
        val rvEvents = view.findViewById<RecyclerView>(R.id.rvSessionEvents)
        val emptyEvents = view.findViewById<TextView>(R.id.tvSessionEventsEmpty)
        emptyEvents.visibility = View.GONE
        rvEvents.visibility = View.GONE
        val allEvents = session.events.sortedByDescending { it.timeMs }
        btnExpand.text = getString(R.string.session_expand_events, allEvents.size)
        btnExpand.setOnClickListener {
            eventsExpanded = !eventsExpanded
            if (eventsExpanded) {
                if (allEvents.isEmpty()) {
                    emptyEvents.visibility = View.VISIBLE
                    rvEvents.visibility = View.GONE
                } else {
                    emptyEvents.visibility = View.GONE
                    rvEvents.visibility = View.VISIBLE
                    rvEvents.layoutManager = LinearLayoutManager(requireContext())
                    rvEvents.adapter = EventsAdapter(allEvents) { e ->
                        EventDetailBottomSheet.newInstance(e)
                            .show(parentFragmentManager, EventDetailBottomSheet.TAG)
                    }
                }
                btnExpand.text = getString(R.string.session_collapse_events)
            } else {
                emptyEvents.visibility = View.GONE
                rvEvents.visibility = View.GONE
                btnExpand.text = getString(R.string.session_expand_events, allEvents.size)
            }
        }
    }

    private fun openSegment(sessionId: String, segmentId: String) {
        (parentFragmentManager.findFragmentByTag(SegmentDetailBottomSheet.TAG) as? SegmentDetailBottomSheet)
            ?.dismissAllowingStateLoss()
        SegmentDetailBottomSheet.newInstance(sessionId, segmentId)
            .show(parentFragmentManager, SegmentDetailBottomSheet.TAG)
    }

    private fun confirmDelete(store: SessionStore, session: SleepSession) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val ok = store.deleteOne(session.id)
                if (ok) {
                    deleted = true
                    Toast.makeText(requireContext(), R.string.delete_done, Toast.LENGTH_SHORT).show()
                    parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
                        putBoolean(RESULT_DELETED, true)
                        putString(RESULT_ID, session.id)
                    })
                    dismissAllowingStateLoss()
                } else {
                    Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun bindHeader(view: View, s: SleepSession) {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val end = s.endMs?.let { fmt.format(Date(it)) } ?: "…"
        val h = TimeUnit.MILLISECONDS.toHours(s.durationMs())
        val m = TimeUnit.MILLISECONDS.toMinutes(s.durationMs()) % 60
        val segs = s.ensureSegments()
        view.findViewById<TextView>(R.id.tvSessionTitle).text =
            getString(R.string.history_item_title, fmt.format(Date(s.startMs)), end)
        view.findViewById<TextView>(R.id.tvSessionSubtitle).text = getString(
            R.string.session_detail_sub_v03,
            h, m,
            segs.size,
            s.audioEventCount(),
            s.clipCount()
        )
    }

    companion object {
        private const val ARG_ID = "session_id"
        const val TAG = "SessionDetailBottomSheet"
        const val RESULT_KEY = "session_detail_result"
        const val RESULT_DELETED = "deleted"
        const val RESULT_ID = "id"

        fun newInstance(sessionId: String): SessionDetailBottomSheet {
            return SessionDetailBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_ID, sessionId) }
            }
        }
    }
}
