package com.i3u8.sleepdesk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.audio.AudioClipStore
import com.i3u8.sleepdesk.audio.ClipPlayer
import com.i3u8.sleepdesk.data.NightSegment
import com.i3u8.sleepdesk.data.SegmentBuilder
import com.i3u8.sleepdesk.data.SessionStore
import com.i3u8.sleepdesk.data.SleepEvent
import com.i3u8.sleepdesk.data.SleepSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SegmentDetailBottomSheet : BottomSheetDialogFragment() {

    private var sessionId: String? = null
    private var segmentId: String? = null
    private var player: ClipPlayer? = null
    private var eventsExpanded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_segment_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sessionId = requireArguments().getString(ARG_SESSION) ?: return
        segmentId = requireArguments().getString(ARG_SEGMENT) ?: return
        val store = SessionStore(requireContext())
        val session = store.loadCurrent()?.takeIf { it.id == sessionId }
            ?: store.loadHistory().firstOrNull { it.id == sessionId }
            ?: return
        val segment = session.ensureSegments().firstOrNull { it.id == segmentId } ?: return

        bindHeader(view, segment)
        bindClips(view, session, segment)
        bindEventsExpand(view, session, segment)
    }

    private fun bindHeader(view: View, seg: NightSegment) {
        val ctx = requireContext()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val mins = TimeUnit.MILLISECONDS.toMinutes(seg.durationMs()).coerceAtLeast(1)
        view.findViewById<TextView>(R.id.tvSegDetailLabel).text =
            SegmentLabels.primaryLabel(ctx, seg.primaryLabel)
        view.findViewById<TextView>(R.id.tvSegDetailTime).text = getString(
            R.string.segment_detail_time,
            fmt.format(Date(seg.startMs)),
            fmt.format(Date(seg.endMs)),
            mins
        )
        view.findViewById<TextView>(R.id.tvSegDetailSummary).text =
            SegmentLabels.subtitle(ctx, seg)
    }

    private fun bindClips(view: View, session: SleepSession, seg: NightSegment) {
        val ctx = requireContext()
        val empty = view.findViewById<TextView>(R.id.tvSegClipsEmpty)
        val row = view.findViewById<LinearLayout>(R.id.rowClipButtons)
        row.removeAllViews()
        player?.release()
        player = ClipPlayer(ctx)

        val clipEvents = SegmentBuilder.eventsForClipPaths(session, seg.representativeClipPaths)
        if (clipEvents.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for ((index, e) in clipEvents.withIndex()) {
            val btn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = (8 * resources.displayMetrics.density).toInt() }
            val type = EventLabels.typeLabel(ctx, e.type)
            btn.text = getString(R.string.segment_play_clip, index + 1, type, timeFmt.format(Date(e.timeMs)))
            btn.isAllCaps = false
            btn.setOnClickListener { playClip(btn, e) }
            row.addView(btn)
        }
    }

    private fun playClip(btn: MaterialButton, e: SleepEvent) {
        val rel = e.clipRelativePath ?: return
        val file = AudioClipStore(requireContext()).fileForRelative(rel)
        val p = player ?: return
        if (!file.exists()) {
            btn.text = getString(R.string.clip_missing)
            return
        }
        p.setListener(object : ClipPlayer.Listener {
            override fun onPlayingChanged(path: String?, playing: Boolean) {
                if (!isAdded) return
                btn.setText(if (playing) R.string.btn_pause else R.string.btn_play)
            }
            override fun onError(message: String) {
                if (!isAdded) return
                btn.text = getString(R.string.clip_play_error)
            }
            override fun onCompleted(path: String) {
                if (!isAdded) return
                btn.setText(R.string.btn_play)
            }
        })
        p.toggle(file)
    }

    private fun bindEventsExpand(view: View, session: SleepSession, seg: NightSegment) {
        val btn = view.findViewById<MaterialButton>(R.id.btnExpandEvents)
        val rv = view.findViewById<RecyclerView>(R.id.rvSegEvents)
        val events = SegmentBuilder.eventsForSegment(session, seg)
        btn.text = getString(R.string.segment_expand_events, events.size)
        btn.setOnClickListener {
            eventsExpanded = !eventsExpanded
            if (eventsExpanded) {
                rv.visibility = View.VISIBLE
                rv.layoutManager = LinearLayoutManager(requireContext())
                rv.adapter = EventsAdapter(events) { e ->
                    EventDetailBottomSheet.newInstance(e)
                        .show(parentFragmentManager, EventDetailBottomSheet.TAG)
                }
                btn.text = getString(R.string.segment_collapse_events)
            } else {
                rv.visibility = View.GONE
                btn.text = getString(R.string.segment_expand_events, events.size)
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        player?.release()
        player = null
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        player?.release()
        player = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SESSION = "session_id"
        private const val ARG_SEGMENT = "segment_id"
        const val TAG = "SegmentDetailBottomSheet"

        fun newInstance(sessionId: String, segmentId: String): SegmentDetailBottomSheet {
            return SegmentDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION, sessionId)
                    putString(ARG_SEGMENT, segmentId)
                }
            }
        }
    }
}
