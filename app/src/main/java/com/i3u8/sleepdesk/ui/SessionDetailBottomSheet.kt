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
import com.google.android.material.materialswitch.MaterialSwitch
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
        val switchCycles = view.findViewById<MaterialSwitch>(R.id.switchCycles)
        val disclaimer = view.findViewById<TextView>(R.id.tvCycleDisclaimer)
        val isRunning = session.isRunning

        timeline.setSession(session, showExperimentalCycles = switchCycles.isChecked) { e ->
            (parentFragmentManager.findFragmentByTag(EventDetailBottomSheet.TAG) as? EventDetailBottomSheet)
                ?.dismissAllowingStateLoss()
            EventDetailBottomSheet.newInstance(e)
                .show(parentFragmentManager, EventDetailBottomSheet.TAG)
        }
        switchCycles.setOnCheckedChangeListener { _, checked ->
            disclaimer.visibility = if (checked) View.VISIBLE else View.GONE
            timeline.setShowCycles(checked)
            timeline.requestLayout()
        }
        disclaimer.visibility = if (switchCycles.isChecked) View.VISIBLE else View.GONE

        val btnDelete = view.findViewById<MaterialButton>(R.id.btnDeleteSession)
        if (isRunning) {
            btnDelete.visibility = View.GONE
        } else {
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener { confirmDelete(store, session) }
        }

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
                (parentFragmentManager.findFragmentByTag(EventDetailBottomSheet.TAG) as? EventDetailBottomSheet)
                    ?.dismissAllowingStateLoss()
                EventDetailBottomSheet.newInstance(e)
                    .show(parentFragmentManager, EventDetailBottomSheet.TAG)
            }
        }
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
