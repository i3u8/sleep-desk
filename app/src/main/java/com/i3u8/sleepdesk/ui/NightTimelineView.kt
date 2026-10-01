package com.i3u8.sleepdesk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.audio.NightEventType
import com.i3u8.sleepdesk.data.SleepEvent
import com.i3u8.sleepdesk.data.SleepSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-layer full-night timeline:
 * 1) experimental cycle band (optional)
 * 2) acoustic activity band
 * 3) event markers + interrupt ticks
 * 4) time axis
 *
 * Taps near an event marker invoke [onEventTap].
 */
class NightTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var session: SleepSession? = null
    private var showCycles: Boolean = true
    private var activity: List<NightTimelineHeuristics.ActivityBin> = emptyList()
    private var cycles: List<NightTimelineHeuristics.CycleBand> = emptyList()
    private var onEventTap: ((SleepEvent) -> Unit)? = null

    private val padL = 12f * resources.displayMetrics.density
    private val padR = 12f * resources.displayMetrics.density
    private val padT = 8f * resources.displayMetrics.density
    private val padB = 28f * resources.displayMetrics.density

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        strokeWidth = 2f
        alpha = 100
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        textSize = 11f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val interruptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.warning)
        strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_stroke)
        strokeWidth = 1f
    }
    private val rect = RectF()
    private val hitSlop = 18f * resources.displayMetrics.density
    private val markerHits = mutableListOf<Pair<Float, SleepEvent>>()

    private val colorSnore = 0xFF60A5FA.toInt()
    private val colorCough = 0xFFF97316.toInt()
    private val colorSpeech = 0xFFA78BFA.toInt()
    private val colorWake = 0xFFEF4444.toInt()
    private val colorEnv = 0xFF94A3B8.toInt()
    private val colorAbnormal = 0xFFFBBF24.toInt()
    private val colorDefault = 0xFF7C9CFF.toInt()
    private val colorActivity = 0xFF38BDF8.toInt()
    private val colorCycleWake = 0x66F87171.toInt()
    private val colorCycleQuiet = 0x6634D399.toInt()
    private val colorCycleRem = 0x66C084FC.toInt()

    fun setSession(
        session: SleepSession,
        showExperimentalCycles: Boolean = true,
        onEventTap: ((SleepEvent) -> Unit)? = null
    ) {
        this.session = session
        this.showCycles = showExperimentalCycles
        this.onEventTap = onEventTap
        this.activity = NightTimelineHeuristics.activityBins(session)
        this.cycles = if (showExperimentalCycles) {
            NightTimelineHeuristics.experimentalCycles(session)
        } else emptyList()
        invalidate()
    }

    fun setShowCycles(show: Boolean) {
        showCycles = show
        val s = session ?: return
        cycles = if (show) NightTimelineHeuristics.experimentalCycles(s) else emptyList()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val desired = (if (showCycles) 168f else 128f) * density
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val h = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> min(desired.toInt(), hSize)
            else -> desired.toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        markerHits.clear()
        val s = session ?: return
        val start = s.startMs
        val end = (s.endMs ?: System.currentTimeMillis()).coerceAtLeast(start + 60_000L)
        val span = (end - start).toFloat()
        val chartL = padL
        val chartR = width - padR
        val chartW = chartR - chartL
        if (chartW <= 0f) return

        var y = padT
        val cycleH = if (showCycles && cycles.isNotEmpty()) height * 0.22f else 0f
        val activityH = height * 0.28f
        val eventsH = height * (if (showCycles) 0.28f else 0.42f)
        val axisY = height - padB + 4f

        // --- Experimental cycle band ---
        if (cycleH > 0f) {
            for (band in cycles) {
                val x0 = chartL + ((band.startMs - start) / span) * chartW
                val x1 = chartL + ((band.endMs - start) / span) * chartW
                bandPaint.color = when (band.kind) {
                    NightTimelineHeuristics.CycleKind.WAKE_ISH -> colorCycleWake
                    NightTimelineHeuristics.CycleKind.QUIETER_NREM_ISH -> colorCycleQuiet
                    NightTimelineHeuristics.CycleKind.REM_ISH -> colorCycleRem
                }
                rect.set(x0, y, x1, y + cycleH)
                canvas.drawRoundRect(rect, 4f, 4f, bandPaint)
            }
            y += cycleH + 6f * resources.displayMetrics.density
        }

        // --- Activity band ---
        val actTop = y
        val actBottom = y + activityH
        // baseline track
        bandPaint.color = 0x221E293B
        rect.set(chartL, actTop, chartR, actBottom)
        canvas.drawRoundRect(rect, 6f, 6f, bandPaint)
        for (bin in activity) {
            val x0 = chartL + ((bin.startMs - start) / span) * chartW
            val x1 = chartL + ((bin.endMs - start) / span) * chartW
            val alpha = (40 + (bin.level * 180).toInt()).coerceIn(40, 220)
            bandPaint.color = (alpha shl 24) or (colorActivity and 0x00FFFFFF)
            val h = max(2f, activityH * (0.15f + bin.level * 0.85f))
            rect.set(x0, actBottom - h, x1, actBottom)
            canvas.drawRect(rect, bandPaint)
        }
        y = actBottom + 8f * resources.displayMetrics.density

        // --- Event / interrupt layer ---
        val evTop = y
        val evBottom = y + eventsH
        canvas.drawLine(chartL, evBottom, chartR, evBottom, guidePaint)

        val events = s.events.sortedBy { it.timeMs }
        val density = resources.displayMetrics.density
        for (e in events) {
            if (e.timeMs < start || e.timeMs > end) continue
            val x = chartL + ((e.timeMs - start) / span) * chartW
            if (NightTimelineHeuristics.isInterrupt(e.type)) {
                canvas.drawLine(x, evTop, x, evBottom, interruptPaint)
                // small triangle tip
                markerPaint.color = ContextCompat.getColor(context, R.color.warning)
                canvas.drawCircle(x, evTop + 3f * density, 3.5f * density, markerPaint)
                markerHits.add(x to e)
            } else if (NightTimelineHeuristics.isAudioEvent(e.type)) {
                markerPaint.color = colorForType(e.type)
                val dur = (e.endMs - e.timeMs).coerceAtLeast(0L)
                val w = max(3f * density, min(14f * density, (dur / span) * chartW))
                val blockH = when (e.type) {
                    NightEventType.SNORE.name -> eventsH * 0.55f
                    NightEventType.NIGHT_WAKE_SOUND.name,
                    NightEventType.COUGH.name -> eventsH * 0.85f
                    else -> eventsH * 0.65f
                }
                rect.set(x - w / 2f, evBottom - blockH, x + w / 2f, evBottom)
                canvas.drawRoundRect(rect, 3f, 3f, markerPaint)
                markerHits.add(x to e)
            }
        }

        // --- Time axis labels ---
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val ticks = 5
        for (i in 0 until ticks) {
            val frac = i / (ticks - 1f)
            val t = start + (span * frac).toLong()
            val x = chartL + frac * chartW
            canvas.drawLine(x, axisY - 10f, x, axisY - 2f, axisPaint)
            labelPaint.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                ticks - 1 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(fmt.format(Date(t)), x, height - 6f, labelPaint)
        }
    }

    private fun colorForType(type: String): Int = when (type) {
        NightEventType.SNORE.name -> colorSnore
        NightEventType.COUGH.name -> colorCough
        NightEventType.SPEECH.name -> colorSpeech
        NightEventType.NIGHT_WAKE_SOUND.name -> colorWake
        NightEventType.ENV_NOISE.name -> colorEnv
        NightEventType.ABNORMAL.name -> colorAbnormal
        else -> colorDefault
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) {
            return event.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
        }
        val x = event.x
        var best: SleepEvent? = null
        var bestDist = hitSlop
        for ((mx, ev) in markerHits) {
            val d = abs(mx - x)
            if (d <= bestDist) {
                bestDist = d
                best = ev
            }
        }
        if (best != null) {
            onEventTap?.invoke(best)
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
