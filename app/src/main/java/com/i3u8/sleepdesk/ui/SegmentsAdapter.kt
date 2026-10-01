package com.i3u8.sleepdesk.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.data.NightSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SegmentsAdapter(
    private val items: List<NightSegment>,
    private val onClick: (NightSegment) -> Unit
) : RecyclerView.Adapter<SegmentsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val color: View = v.findViewById(R.id.vSegColor)
        val label: TextView = v.findViewById(R.id.tvSegLabel)
        val time: TextView = v.findViewById(R.id.tvSegTime)
        val meta: TextView = v.findViewById(R.id.tvSegMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_segment, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val seg = items[position]
        val ctx = holder.itemView.context
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        holder.label.text = SegmentLabels.primaryLabel(ctx, seg.primaryLabel)
        val mins = TimeUnit.MILLISECONDS.toMinutes(seg.durationMs()).coerceAtLeast(1)
        holder.time.text = ctx.getString(
            R.string.segment_item_time,
            fmt.format(Date(seg.startMs)),
            fmt.format(Date(seg.endMs)),
            mins
        )
        holder.meta.text = SegmentLabels.subtitle(ctx, seg)
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * ctx.resources.displayMetrics.density
            setColor(SegmentLabels.colorFor(seg.primaryLabel))
        }
        holder.color.background = gd
        holder.itemView.setOnClickListener { onClick(seg) }
    }
}
