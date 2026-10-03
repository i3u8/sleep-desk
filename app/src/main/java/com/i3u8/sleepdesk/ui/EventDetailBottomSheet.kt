package com.i3u8.sleepdesk.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Spinner
import android.widget.CheckBox
import android.widget.ArrayAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.audio.AudioClipStore
import com.i3u8.sleepdesk.audio.ClipPlayer
import com.i3u8.sleepdesk.data.SleepEvent
import com.i3u8.sleepdesk.data.SessionStore
import com.i3u8.sleepdesk.audio.ClassificationStatus
import com.i3u8.sleepdesk.audio.ClipStatus
import com.i3u8.sleepdesk.audio.NightEventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventDetailBottomSheet : BottomSheetDialogFragment() {

    private var event: SleepEvent? = null
    private var player: ClipPlayer? = null
    private lateinit var btnPlay: MaterialButton
    private lateinit var tvMessage: TextView
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        val store = SessionStore(requireContext())
        sessionId = args.getString(ARG_SESSION) ?: store.findSessionForEvent(args.getString(ARG_ID, ""))?.id
        val stored = sessionId?.let { store.loadSession(it) }?.events?.firstOrNull { it.id == args.getString(ARG_ID) }
        if (stored != null) {
            event = stored
            return
        }
        event = SleepEvent(
            id = args.getString(ARG_ID, ""),
            timeMs = args.getLong(ARG_TIME),
            endMs = args.getLong(ARG_END),
            type = args.getString(ARG_TYPE, ""),
            peakLevel = args.getDouble(ARG_PEAK),
            confidence = args.getFloat(ARG_CONF),
            clipRelativePath = args.getString(ARG_CLIP),
            note = args.getString(ARG_NOTE),
            algoVersion = args.getString(ARG_ALGO),
            features = args.getBundle("features")?.let { features ->
                features.keySet().associateWith { features.getFloat(it) }
            }.orEmpty(),
            detectionConfidence = args.getFloat("detectionConfidence"),
            classificationStatus = ClassificationStatus.entries.firstOrNull {
                it.name == args.getString("classificationStatus")
            } ?: ClassificationStatus.LEGACY,
            clipStatus = ClipStatus.entries.firstOrNull {
                it.name == args.getString("clipStatus")
            } ?: ClipStatus.LEGACY,
            suggestedTypes = args.getStringArrayList("suggestedTypes").orEmpty(),
            classScores = args.getBundle("classScores")?.let { scores ->
                scores.keySet().associateWith { scores.getFloat(it) }
            }.orEmpty(),
            modelVersion = args.getString("modelVersion"),
            classificationReason = args.getString("classificationReason"),
            userLabel = args.getString("userLabel"),
            reviewFlags = args.getStringArrayList("reviewFlags").orEmpty().toSet(),
            revision = args.getLong("revision", 0L)
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme)
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_event_detail, container, false)
    }

    private fun bindMetadata(view: View, e: SleepEvent) {
        val ctx = requireContext()
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        view.findViewById<TextView>(R.id.tvDetailType).text = EventLabels.eventTitle(ctx, e)
        view.findViewById<TextView>(R.id.tvDetailTime).text = timeFmt.format(Date(e.timeMs))
        val confPct = (e.confidence * 100).toInt().coerceIn(0, 100)
        val classificationSupport = if (e.classificationStatus == ClassificationStatus.PENDING ||
            e.classificationStatus == ClassificationStatus.FAILED) getString(R.string.event_no_candidates) else "$confPct%"
        view.findViewById<TextView>(R.id.tvDetailMeta).text = getString(
            R.string.event_supports,
            (e.detectionConfidence * 100).toInt().coerceIn(0, 100),
            classificationSupport,
            e.peakLevel
        )
        val contextWarnings = listOf(
            "context.PRE_ROLL_SHORT" to R.string.clip_context_pre_short,
            "context.POST_ROLL_SHORT" to R.string.clip_context_post_short,
            "context.CLIP_LIMIT" to R.string.clip_context_limit
        ).filter { (key, _) -> e.features[key]?.let { it.isFinite() && it > 0f } == true }
            .map { (_, label) -> getString(label) }
        val status = listOf(
            getString(R.string.status_detected),
            EventLabels.classification(ctx, e.classificationStatus),
            EventLabels.clip(ctx, e.clipStatus)
        ).joinToString(" · ")
        view.findViewById<TextView>(R.id.tvDetailStatus).text =
            if (contextWarnings.isEmpty()) status else status + "\n" + contextWarnings.joinToString(" · ")
        view.findViewById<TextView>(R.id.tvDetailCandidates).text =
            getString(R.string.event_candidates, EventLabels.candidates(ctx, e))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val e = event ?: return
        val ctx = requireContext()
        bindMetadata(view, e)
        tvMessage = view.findViewById(R.id.tvDetailMessage)
        btnPlay = view.findViewById(R.id.btnPlayPause)
        val labels = listOf<String?>(null) + NightEventType.entries.map { it.name }
        val spinner = view.findViewById<Spinner>(R.id.spinnerReviewLabel)
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            labels.map { it?.let { type -> EventLabels.typeLabel(ctx, type) } ?: getString(R.string.review_unset) })
        spinner.setSelection(labels.indexOf(e.userLabel).coerceAtLeast(0))
        val important = view.findViewById<CheckBox>(R.id.checkReviewImportant)
        important.isChecked = SleepEvent.REVIEW_IMPORTANT in e.reviewFlags
        view.findViewById<MaterialButton>(R.id.btnSaveReview).setOnClickListener {
            val saved = sessionId?.let {
                SessionStore(ctx).updateEventReview(it, e.id, labels[spinner.selectedItemPosition], important.isChecked)
            } == true
            tvMessage.visibility = View.VISIBLE
            tvMessage.setText(if (saved) R.string.review_saved else R.string.review_save_failed)
            if (saved) {
                val latest = SessionStore(ctx).loadSession(sessionId!!)?.events?.firstOrNull { it.id == e.id }
                if (latest != null) {
                    event = latest
                    bindMetadata(view, latest)
                }
                parentFragmentManager.setFragmentResult(RESULT_REVIEW, Bundle())
                parentFragmentManager.setFragmentResult(RESULT_REVIEW_SESSION, Bundle())
                parentFragmentManager.setFragmentResult(RESULT_REVIEW_SEGMENT, Bundle().apply {
                    putString(ARG_ID, e.id)
                })
            }
        }

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

        bindPlayback(e)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val store = SessionStore(ctx)
                while (true) {
                    delay(500)
                    val latest = sessionId?.let { store.loadSession(it) }?.events?.firstOrNull { it.id == e.id }
                    if (latest != null && latest != event) {
                        val clipChanged = latest.clipRelativePath != event?.clipRelativePath
                        event = latest
                        bindMetadata(view, latest)
                        if (clipChanged) bindPlayback(latest)
                    }
                }
            }
        }
    }

    private fun bindPlayback(e: SleepEvent) {
        val ctx = requireContext()
        val rel = e.clipRelativePath
        btnPlay.isEnabled = !rel.isNullOrEmpty()
        btnPlay.alpha = if (btnPlay.isEnabled) 1f else 0.5f
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
        private const val ARG_SESSION = "session_id"
        private const val ARG_TIME = "time"
        private const val ARG_END = "end"
        private const val ARG_TYPE = "type"
        private const val ARG_PEAK = "peak"
        private const val ARG_CONF = "conf"
        private const val ARG_CLIP = "clip"
        private const val ARG_NOTE = "note"
        private const val ARG_ALGO = "algo"
        const val TAG = "EventDetailBottomSheet"
        const val RESULT_REVIEW = "event_review_saved"
        const val RESULT_REVIEW_SESSION = "event_review_saved_session"
        const val RESULT_REVIEW_SEGMENT = "event_review_saved_segment"

        fun newInstance(e: SleepEvent, sessionId: String? = null): EventDetailBottomSheet {
            return EventDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, e.id)
                    putString(ARG_SESSION, sessionId)
                    putLong(ARG_TIME, e.timeMs)
                    putLong(ARG_END, e.endMs)
                    putString(ARG_TYPE, e.type)
                    putDouble(ARG_PEAK, e.peakLevel)
                    putFloat(ARG_CONF, e.confidence)
                    putString(ARG_CLIP, e.clipRelativePath)
                    putString(ARG_NOTE, e.note)
                    putString(ARG_ALGO, e.algoVersion)
                    putBundle("features", Bundle().apply {
                        e.features.filterValues { it.isFinite() }.forEach { (key, value) -> putFloat(key, value) }
                    })
                    putFloat("detectionConfidence", e.detectionConfidence)
                    putString("classificationStatus", e.classificationStatus.name)
                    putString("clipStatus", e.clipStatus.name)
                    putStringArrayList("suggestedTypes", ArrayList(e.suggestedTypes))
                    putBundle("classScores", Bundle().apply {
                        e.classScores.filterValues { it.isFinite() }.forEach { (type, score) -> putFloat(type, score) }
                    })
                    putString("modelVersion", e.modelVersion)
                    putString("classificationReason", e.classificationReason)
                    putString("userLabel", e.userLabel)
                    putStringArrayList("reviewFlags", ArrayList(e.reviewFlags))
                    putLong("revision", e.revision)
                }
            }
        }
    }
}
