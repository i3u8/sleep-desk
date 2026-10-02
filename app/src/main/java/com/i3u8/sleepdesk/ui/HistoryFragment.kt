package com.i3u8.sleepdesk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.data.SessionExporter
import com.i3u8.sleepdesk.data.SessionStore
import com.i3u8.sleepdesk.data.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var btnExportAll: MaterialButton
    private var summaries: List<SessionSummary> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = SessionStore(requireContext())
        chart = view.findViewById(R.id.chartDuration)
        list = view.findViewById(R.id.rvHistory)
        empty = view.findViewById(R.id.tvEmpty)
        btnClearAll = view.findViewById(R.id.btnClearAll)
        btnExportAll = view.findViewById(R.id.btnExportAll)
        list.layoutManager = LinearLayoutManager(requireContext())
        btnClearAll.setOnClickListener { confirmClearAll() }
        btnExportAll.setOnClickListener { exportAll() }

        parentFragmentManager.setFragmentResultListener(
            SessionDetailBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(SessionDetailBottomSheet.RESULT_DELETED, false) ||
                bundle.getBoolean(SessionDetailBottomSheet.RESULT_EXPORTED, false)
            ) {
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                store.loadHistorySummaries()
            }
            if (!isAdded) return@launch
            summaries = loaded
            btnClearAll.isEnabled = loaded.isNotEmpty()
            btnClearAll.alpha = if (loaded.isEmpty()) 0.4f else 1f
            btnExportAll.isEnabled = loaded.isNotEmpty()
            btnExportAll.alpha = if (loaded.isEmpty()) 0.4f else 1f
            if (loaded.isEmpty()) {
                empty.visibility = View.VISIBLE
                list.visibility = View.GONE
                chart.setData(emptyList())
                return@launch
            }
            empty.visibility = View.GONE
            list.visibility = View.VISIBLE

            val dayFmt = SimpleDateFormat("M/d", Locale.getDefault())
            val recent = loaded.take(14).asReversed()
            chart.setData(
                recent.map {
                    DurationBarChartView.Bar(
                        label = dayFmt.format(Date(it.startMs)),
                        hours = it.durationMs / 3_600_000f
                    )
                }
            )
            list.adapter = HistoryAdapter(
                items = loaded,
                onClick = { summary ->
                    (parentFragmentManager.findFragmentByTag(SessionDetailBottomSheet.TAG) as? SessionDetailBottomSheet)
                        ?.dismissAllowingStateLoss()
                    SessionDetailBottomSheet.newInstance(summary.id)
                        .show(parentFragmentManager, SessionDetailBottomSheet.TAG)
                },
                onLongClick = { summary ->
                    showItemMenu(summary)
                    true
                }
            )
        }
    }

    private fun showItemMenu(summary: SessionSummary) {
        val items = arrayOf(
            getString(R.string.export_one_night),
            getString(R.string.action_delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_item_menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exportOne(summary.id)
                    1 -> confirmDeleteOne(summary)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun exportOne(sessionId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SessionExporter(requireContext()).exportOne(sessionId)
                }
                if (!isAdded) return@launch
                val intent = SessionExporter.shareZip(
                    requireContext(),
                    result.zipFile,
                    getString(R.string.export_share_title)
                )
                startActivity(intent)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_done, result.sessionCount, result.clipCount),
                    Toast.LENGTH_SHORT
                ).show()
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

    private fun exportAll() {
        if (summaries.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Toast.makeText(requireContext(), R.string.export_preparing, Toast.LENGTH_SHORT).show()
                val result = withContext(Dispatchers.IO) {
                    SessionExporter(requireContext()).exportAll()
                }
                if (!isAdded) return@launch
                val intent = SessionExporter.shareZip(
                    requireContext(),
                    result.zipFile,
                    getString(R.string.export_share_title)
                )
                startActivity(intent)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_done, result.sessionCount, result.clipCount),
                    Toast.LENGTH_SHORT
                ).show()
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

    private fun confirmDeleteOne(summary: SessionSummary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { store.deleteOne(summary.id) }
                    if (!isAdded) return@launch
                    if (ok) {
                        Toast.makeText(requireContext(), R.string.delete_done, Toast.LENGTH_SHORT).show()
                        refresh()
                    } else {
                        Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun confirmClearAll() {
        if (summaries.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_all_confirm_title)
            .setMessage(R.string.clear_all_confirm_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.clear_all_confirm_again_title)
                    .setMessage(R.string.clear_all_confirm_again_msg)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_clear) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            val n = withContext(Dispatchers.IO) { store.clearAll() }
                            if (!isAdded) return@launch
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.clear_done, n),
                                Toast.LENGTH_SHORT
                            ).show()
                            refresh()
                        }
                    }
                    .show()
            }
            .show()
    }

    private class HistoryAdapter(
        private val items: List<SessionSummary>,
        private val onClick: (SessionSummary) -> Unit,
        private val onLongClick: (SessionSummary) -> Boolean
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
            val h = TimeUnit.MILLISECONDS.toHours(s.durationMs)
            val m = TimeUnit.MILLISECONDS.toMinutes(s.durationMs) % 60
            val ctx = holder.itemView.context
            holder.title.text = ctx.getString(
                R.string.history_item_title,
                fmt.format(Date(s.startMs)),
                end
            )
            // Bind summary fields only — no event arrays, no SegmentBuilder on bind
            holder.detail.text = ctx.getString(
                R.string.history_item_detail_v03,
                h, m,
                s.segmentCount,
                s.audioEventCount,
                s.clipCount,
                s.snoreEventCount
            )
            holder.badge.text = ctx.getString(R.string.history_tap_segments, s.segmentCount)
            holder.itemView.setOnClickListener { onClick(s) }
            holder.itemView.setOnLongClickListener { onLongClick(s) }
        }
    }
}
