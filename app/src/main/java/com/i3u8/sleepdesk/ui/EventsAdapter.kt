package com.i3u8.sleepdesk.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.data.SleepEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventsAdapter(
    private val items: List<SleepEvent>,
    private val onClick: (SleepEvent) -> Unit
) : RecyclerView.Adapter<EventsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val type: TextView = v.findViewById(R.id.tvEventType)
        val time: TextView = v.findViewById(R.id.tvEventTime)
        val meta: TextView = v.findViewById(R.id.tvEventMeta)
        val icon: ImageView = v.findViewById(R.id.ivEventIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val ctx = holder.itemView.context
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        holder.type.text = EventLabels.eventTitle(ctx, e)
        holder.time.text = timeFmt.format(Date(e.timeMs))
        val hasClip = !e.clipRelativePath.isNullOrEmpty()
        holder.meta.text = when {
            hasClip -> ctx.getString(R.string.event_has_clip)
            EventLabels.hasClipPotential(e.effectiveType) -> ctx.getString(R.string.event_no_clip)
            else -> ctx.getString(R.string.event_aux)
        }
        if (EventLabels.hasClipPotential(e.effectiveType) || e.userLabel != null) {
            holder.meta.text = listOf(
                ctx.getString(R.string.status_detected),
                EventLabels.classification(ctx, e.classificationStatus),
                EventLabels.clip(ctx, e.clipStatus),
                if (e.userLabel != null) ctx.getString(R.string.review_manual) else "",
                if (SleepEvent.REVIEW_IMPORTANT in e.reviewFlags) ctx.getString(R.string.review_important) else ""
            ).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        holder.icon.setImageResource(
            if (hasClip) R.drawable.ic_play_circle else R.drawable.ic_event_dot
        )
        holder.icon.alpha = if (hasClip) 1f else 0.45f
        holder.itemView.setOnClickListener { onClick(e) }
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
    }
}
