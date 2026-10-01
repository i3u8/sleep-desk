package com.i3u8.sleepdesk.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.audio.AudioClipStore
import com.i3u8.sleepdesk.audio.ClipPlayer
import com.i3u8.sleepdesk.data.SleepEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventDetailBottomSheet : BottomSheetDialogFragment() {

    private var event: SleepEvent? = null
    private var player: ClipPlayer? = null
    private lateinit var btnPlay: MaterialButton
    private lateinit var tvMessage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        event = SleepEvent(
            id = args.getString(ARG_ID, ""),
            timeMs = args.getLong(ARG_TIME),
            endMs = args.getLong(ARG_END),
            type = args.getString(ARG_TYPE, ""),
            peakLevel = args.getDouble(ARG_PEAK),
            confidence = args.getFloat(ARG_CONF),
            clipRelativePath = args.getString(ARG_CLIP),
            note = args.getString(ARG_NOTE),
            algoVersion = args.getString(ARG_ALGO)
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_event_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val e = event ?: return
        val ctx = requireContext()
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        view.findViewById<TextView>(R.id.tvDetailType).text = EventLabels.typeLabel(ctx, e.type)
        view.findViewById<TextView>(R.id.tvDetailTime).text = timeFmt.format(Date(e.timeMs))
        val confPct = (e.confidence * 100).toInt().coerceIn(0, 100)
        view.findViewById<TextView>(R.id.tvDetailMeta).text = getString(
            R.string.event_detail_meta,
            confPct,
            e.peakLevel
        )
        tvMessage = view.findViewById(R.id.tvDetailMessage)
        btnPlay = view.findViewById(R.id.btnPlayPause)

        player = ClipPlayer(ctx).also { p ->
            p.setListener(object : ClipPlayer.Listener {
                override fun onPlayingChanged(path: String?, playing: Boolean) {
                    if (!isAdded) return
                    btnPlay.setText(if (playing) R.string.btn_pause else R.string.btn_play)
                }

                override fun onError(message: String) {
                    if (!isAdded) return
                    tvMessage.visibility = View.VISIBLE
                    tvMessage.text = when (message) {
                        "missing" -> getString(R.string.clip_missing)
                        else -> getString(R.string.clip_play_error)
                    }
                    btnPlay.setText(R.string.btn_play)
                }

                override fun onCompleted(path: String) {
                    if (!isAdded) return
                    btnPlay.setText(R.string.btn_play)
                }
            })
        }

        val rel = e.clipRelativePath
        if (rel.isNullOrEmpty()) {
            btnPlay.isEnabled = false
            btnPlay.alpha = 0.5f
            tvMessage.visibility = View.VISIBLE
            tvMessage.text = getString(R.string.clip_none)
        } else {
            val file = AudioClipStore(ctx).fileForRelative(rel)
            if (!file.exists()) {
                btnPlay.isEnabled = true
                tvMessage.visibility = View.VISIBLE
                tvMessage.text = getString(R.string.clip_missing)
            } else {
                tvMessage.visibility = View.GONE
            }
            btnPlay.setOnClickListener {
                val f = AudioClipStore(ctx).fileForRelative(rel)
                if (!f.exists()) {
                    tvMessage.visibility = View.VISIBLE
                    tvMessage.text = getString(R.string.clip_missing)
                    player?.stop()
                    btnPlay.setText(R.string.btn_play)
                } else {
                    tvMessage.visibility = View.GONE
                    player?.toggle(f)
                }
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
        private const val ARG_ID = "id"
        private const val ARG_TIME = "time"
        private const val ARG_END = "end"
        private const val ARG_TYPE = "type"
        private const val ARG_PEAK = "peak"
        private const val ARG_CONF = "conf"
        private const val ARG_CLIP = "clip"
        private const val ARG_NOTE = "note"
        private const val ARG_ALGO = "algo"
        const val TAG = "EventDetailBottomSheet"

        fun newInstance(e: SleepEvent): EventDetailBottomSheet {
            return EventDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, e.id)
                    putLong(ARG_TIME, e.timeMs)
                    putLong(ARG_END, e.endMs)
                    putString(ARG_TYPE, e.type)
                    putDouble(ARG_PEAK, e.peakLevel)
                    putFloat(ARG_CONF, e.confidence)
                    putString(ARG_CLIP, e.clipRelativePath)
                    putString(ARG_NOTE, e.note)
                    putString(ARG_ALGO, e.algoVersion)
                }
            }
        }
    }
}
