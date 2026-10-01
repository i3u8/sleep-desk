package com.i3u8.sleepdesk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.audio.NightEventType
import com.i3u8.sleepdesk.data.SessionStore
import com.i3u8.sleepdesk.data.SleepSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HistoryFragment : Fragment() {

    private lateinit var store: SessionStore
    private lateinit var chart: DurationBarChartView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var btnClearAll: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = SessionStore(requireContext())
        chart = view.findViewById(R.id.chartDuration)
        list = view.findViewById(R.id.rvHistory)
        empty = view.findViewById(R.id.tvEmpty)
        btnClearAll = view.findViewById(R.id.btnClearAll)
        list.layoutManager = LinearLayoutManager(requireContext())
        btnClearAll.setOnClickListener { confirmClearAll() }

        parentFragmentManager.setFragmentResultListener(
            SessionDetailBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(SessionDetailBottomSheet.RESULT_DELETED, false)) {
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val history = store.loadHistory()
        btnClearAll.isEnabled = history.isNotEmpty()
        btnClearAll.alpha = if (history.isEmpty()) 0.4f else 1f
        if (history.isEmpty()) {
            empty.visibility = View.VISIBLE
            list.visibility = View.GONE
            chart.setData(emptyList())
            return
        }
        empty.visibility = View.GONE
        list.visibility = View.VISIBLE

        val dayFmt = SimpleDateFormat("M/d", Locale.getDefault())
        val recent = history.take(14).asReversed()
        chart.setData(
            recent.map {
                DurationBarChartView.Bar(
                    label = dayFmt.format(Date(it.startMs)),
                    hours = it.durationMs() / 3_600_000f
                )
            }
        )
        list.adapter = HistoryAdapter(
            items = history,
            onClick = { session ->
                (parentFragmentManager.findFragmentByTag(SessionDetailBottomSheet.TAG) as? SessionDetailBottomSheet)
                    ?.dismissAllowingStateLoss()
                SessionDetailBottomSheet.newInstance(session.id)
                    .show(parentFragmentManager, SessionDetailBottomSheet.TAG)
            },
            onLongClick = { session ->
                confirmDeleteOne(session)
                true
            }
        )
    }

    private fun confirmDeleteOne(session: SleepSession) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val ok = store.deleteOne(session.id)
                if (ok) {
                    Toast.makeText(requireContext(), R.string.delete_done, Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun confirmClearAll() {
        if (store.loadHistory().isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_all_confirm_title)
            .setMessage(R.string.clear_all_confirm_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                // Second confirm
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.clear_all_confirm_again_title)
                    .setMessage(R.string.clear_all_confirm_again_msg)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_clear) { _, _ ->
                        val n = store.clearAll()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.clear_done, n),
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                    .show()
            }
            .show()
    }

    private class HistoryAdapter(
        private val items: List<SleepSession>,
        private val onClick: (SleepSession) -> Unit,
        private val onLongClick: (SleepSession) -> Boolean
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvItemTitle)
            val detail: TextView = v.findViewById(R.id.tvItemDetail)
            val badge: TextView = v.findViewById(R.id.tvItemBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val end = s.endMs?.let { fmt.format(Date(it)) } ?: "…"
            val h = TimeUnit.MILLISECONDS.toHours(s.durationMs())
            val m = TimeUnit.MILLISECONDS.toMinutes(s.durationMs()) % 60
            val ctx = holder.itemView.context
            holder.title.text = ctx.getString(
                R.string.history_item_title,
                fmt.format(Date(s.startMs)),
                end
            )
            holder.detail.text = ctx.getString(
                R.string.history_item_detail,
                h, m,
                s.audioEventCount(),
                s.clipCount(),
                s.countByType(NightEventType.SNORE),
                s.countByType(NightEventType.COUGH) + s.countByType(NightEventType.NIGHT_WAKE_SOUND)
            )
            holder.badge.text = ctx.getString(R.string.history_tap_events, s.events.size)
            holder.itemView.setOnClickListener { onClick(s) }
            holder.itemView.setOnLongClickListener { onLongClick(s) }
        }
    }
}
