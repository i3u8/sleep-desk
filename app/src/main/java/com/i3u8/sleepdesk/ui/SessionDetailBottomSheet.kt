package com.i3u8.sleepdesk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.data.SessionExporter
import com.i3u8.sleepdesk.data.SessionStore
import com.i3u8.sleepdesk.data.SleepSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SessionDetailBottomSheet : BottomSheetDialogFragment() {

    private var sessionId: String? = null
    private var deleted = false
    private var eventsExpanded = false
    private var loadedSession: SleepSession? = null
    private var eventCountHint: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_session_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sessionId = requireArguments().getString(ARG_ID) ?: return
        val id = sessionId!!
        val btnDelete = view.findViewById<MaterialButton>(R.id.btnDeleteSession)
        val btnExport = view.findViewById<MaterialButton>(R.id.btnExportSession)
        val btnExpand = view.findViewById<MaterialButton>(R.id.btnExpandAllEvents)
        val rvEvents = view.findViewById<RecyclerView>(R.id.rvSessionEvents)
        val emptyEvents = view.findViewById<TextView>(R.id.tvSessionEventsEmpty)
        emptyEvents.visibility = View.GONE
        rvEvents.visibility = View.GONE
        btnExpand.isEnabled = false
        btnExport.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                val store = SessionStore(requireContext())
                store.ensureSegmentsPersisted(id)
                    ?: store.loadSession(id)
            }
            if (!isAdded || session == null) return@launch
            loadedSession = session
            eventCountHint = session.events.size
            bindHeader(view, session)

            val timeline = view.findViewById<NightTimelineView>(R.id.nightTimeline)
            val segments = session.segments // already ensured on IO
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

            if (session.isRunning) {
                btnDelete.visibility = View.GONE
                btnExport.visibility = View.GONE
            } else {
                btnDelete.visibility = View.VISIBLE
                btnExport.visibility = View.VISIBLE
                btnExport.isEnabled = true
                btnDelete.setOnClickListener {
                    confirmDelete(SessionStore(requireContext()), session)
                }
                btnExport.setOnClickListener { exportNight(session.id) }
            }

            // Lazy: do NOT sort/bind full event list until expand
            btnExpand.isEnabled = true
            btnExpand.text = getString(R.string.session_expand_events, eventCountHint)
            btnExpand.setOnClickListener {
                eventsExpanded = !eventsExpanded
                if (eventsExpanded) {
                    val allEvents = loadedSession?.events?.sortedByDescending { it.timeMs }.orEmpty()
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
                    rvEvents.adapter = null
                    btnExpand.text = getString(R.string.session_expand_events, eventCountHint)
                }
            }
        }
    }

    private fun exportNight(sessionId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Toast.makeText(requireContext(), R.string.export_preparing, Toast.LENGTH_SHORT).show()
                val result = withContext(Dispatchers.IO) {
                    SessionExporter(requireContext()).exportOne(sessionId)
                }
                if (!isAdded) return@launch
                startActivity(
                    SessionExporter.shareZip(
                        requireContext(),
                        result.zipFile,
                        getString(R.string.export_share_title)
                    )
                )
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_done, result.sessionCount, result.clipCount),
                    Toast.LENGTH_SHORT
                ).show()
                parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
                    putBoolean(RESULT_EXPORTED, true)
                    putString(RESULT_ID, sessionId)
                })
            } catch (e: Exception) {
                if (!isAdded) return@launch
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
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
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { store.deleteOne(session.id) }
                    if (!isAdded) return@launch
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
            }
            .show()
    }

    private fun bindHeader(view: View, s: SleepSession) {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val end = s.endMs?.let { fmt.format(Date(it)) } ?: "…"
        val h = TimeUnit.MILLISECONDS.toHours(s.durationMs())
        val m = TimeUnit.MILLISECONDS.toMinutes(s.durationMs()) % 60
        val segs = s.segments
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
        const val RESULT_EXPORTED = "exported"
        const val RESULT_ID = "id"

        fun newInstance(sessionId: String): SessionDetailBottomSheet {
            return SessionDetailBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_ID, sessionId) }
            }
        }
    }
}
