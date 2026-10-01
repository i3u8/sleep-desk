package com.i3u8.sleepdesk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = SessionStore(requireContext())
        chart = view.findViewById(R.id.chartDuration)
        list = view.findViewById(R.id.rvHistory)
        empty = view.findViewById(R.id.tvEmpty)
        list.layoutManager = LinearLayoutManager(requireContext())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val history = store.loadHistory()
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
        list.adapter = HistoryAdapter(history)
    }

    private class HistoryAdapter(private val items: List<SleepSession>) :
        RecyclerView.Adapter<HistoryAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvItemTitle)
            val detail: TextView = v.findViewById(R.id.tvItemDetail)
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
        }
    }
}
