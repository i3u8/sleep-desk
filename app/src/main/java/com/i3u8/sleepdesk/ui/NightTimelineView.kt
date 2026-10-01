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
import com.i3u8.sleepdesk.data.NightSegment
import com.i3u8.sleepdesk.data.SleepEvent
import com.i3u8.sleepdesk.data.SleepSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Segment-first full-night timeline (docs/segments.md):
 * colored segment bands + thin activity ribbon + interrupt / clip ticks.
 * Tap a segment → [onSegmentTap]. Full event flood omitted.
 */
class NightTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var session: SleepSession? = null
    private var segments: List<NightSegment> = emptyList()
    private var activity: List<NightTimelineHeuristics.ActivityBin> = emptyList()
    private var onSegmentTap: ((NightSegment) -> Unit)? = null
    private var showActivityRibbon: Boolean = true

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
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val interruptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.warning)
        strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_stroke)
        strokeWidth = 1f
    }
    private val strokeBand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0x33FFFFFF
    }
    private val rect = RectF()
    private val hitSlop = 12f * resources.displayMetrics.density
    private val segmentHits = mutableListOf<Pair<RectF, NightSegment>>()

    private val colorSnore = 0xAA60A5FA.toInt()
    private val colorCough = 0xAAF97316.toInt()
    private val colorSpeech = 0xAAA78BFA.toInt()
    private val colorWake = 0xAAEF4444.toInt()
    private val colorAbnormal = 0xAAFBBF24.toInt()
    private val colorMixed = 0xAA94A3B8.toInt()
    private val colorDefault = 0xAA7C9CFF.toInt()
    private val colorActivity = 0xFF38BDF8.toInt()
    private val colorClipTick = 0xFFE2E8F0.toInt()

    fun setSession(
        session: SleepSession,
        showActivityRibbon: Boolean = true,
        onSegmentTap: ((NightSegment) -> Unit)? = null
    ) {
        this.session = session
        this.showActivityRibbon = showActivityRibbon
        this.onSegmentTap = onSegmentTap
        this.segments = session.ensureSegments()
        this.activity = if (showActivityRibbon) {
            NightTimelineHeuristics.activityBins(session, binMs = 5 * 60_000L)
        } else emptyList()
        invalidate()
        requestLayout()
    }

    fun getSegments(): List<NightSegment> = segments

    @Deprecated("Cycles replaced by segments in v0.3")
    fun setShowCycles(show: Boolean) { /* no-op */ }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val desired = (if (showActivityRibbon) 148f else 112f) * density
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
        segmentHits.clear()
        val s = session ?: return
        val start = s.startMs
        val end = (s.endMs ?: System.currentTimeMillis()).coerceAtLeast(start + 60_000L)
        val span = (end - start).toFloat()
        val chartL = padL
        val chartR = width - padR
        val chartW = chartR - chartL
        if (chartW <= 0f) return

        var y = padT
        val segH = height * (if (showActivityRibbon) 0.42f else 0.58f)
        val activityH = if (showActivityRibbon) height * 0.22f else 0f
        val ticksH = height * 0.12f
        val axisY = height - padB + 4f
        val density = resources.displayMetrics.density

        val segTop = y
        val segBottom = y + segH
        bandPaint.color = 0x221E293B
        rect.set(chartL, segTop, chartR, segBottom)
        canvas.drawRoundRect(rect, 8f, 8f, bandPaint)

        for (seg in segments) {
            val x0 = chartL + ((seg.startMs - start) / span) * chartW
            val x1 = chartL + ((seg.endMs - start) / span) * chartW
            val xRight = max(x0 + 3f * density, x1)
            bandPaint.color = colorForLabel(seg.primaryLabel)
            rect.set(x0 + 1f, segTop + 2f, xRight - 1f, segBottom - 2f)
            canvas.drawRoundRect(rect, 5f, 5f, bandPaint)
            canvas.drawRoundRect(rect, 5f, 5f, strokeBand)
            segmentHits.add(RectF(rect) to seg)

            val clipN = seg.representativeClipPaths.size
            if (clipN > 0) {
                markerPaint.color = colorClipTick
                val cx = (x0 + xRight) / 2f
                for (i in 0 until clipN) {
                    val dx = (i - (clipN - 1) / 2f) * 6f * density
                    canvas.drawCircle(cx + dx, segTop + 8f * density, 2.2f * density, markerPaint)
                }
            }
        }
        y = segBottom + 6f * density

        if (activityH > 0f && activity.isNotEmpty()) {
            val actTop = y
            val actBottom = y + activityH
            bandPaint.color = 0x221E293B
            rect.set(chartL, actTop, chartR, actBottom)
            canvas.drawRoundRect(rect, 4f, 4f, bandPaint)
            for (bin in activity) {
                val x0 = chartL + ((bin.startMs - start) / span) * chartW
                val x1 = chartL + ((bin.endMs - start) / span) * chartW
                val alpha = (35 + (bin.level * 170).toInt()).coerceIn(35, 205)
                bandPaint.color = (alpha shl 24) or (colorActivity and 0x00FFFFFF)
                val h = max(2f, activityH * (0.12f + bin.level * 0.88f))
                rect.set(x0, actBottom - h, x1, actBottom)
                canvas.drawRect(rect, bandPaint)
            }
            y = actBottom + 6f * density
        }

        val tickTop = y
        val tickBottom = y + ticksH
        canvas.drawLine(chartL, tickBottom, chartR, tickBottom, guidePaint)
        for (e in s.events) {
            if (e.timeMs < start || e.timeMs > end) continue
            val x = chartL + ((e.timeMs - start) / span) * chartW
            if (NightTimelineHeuristics.isInterrupt(e.type)) {
                canvas.drawLine(x, tickTop, x, tickBottom, interruptPaint)
            }
        }
        markerPaint.color = colorClipTick
        for (seg in segments) {
            for (path in seg.representativeClipPaths) {
                val ev = s.events.firstOrNull { it.clipRelativePath == path } ?: continue
                val x = chartL + ((ev.timeMs - start) / span) * chartW
                canvas.drawCircle(x, (tickTop + tickBottom) / 2f, 3.2f * density, markerPaint)
            }
        }

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

    private fun colorForLabel(label: String): Int = when (label) {
        NightEventType.SNORE.name -> colorSnore
        NightEventType.COUGH.name -> colorCough
        NightEventType.SPEECH.name -> colorSpeech
        NightEventType.NIGHT_WAKE_SOUND.name -> colorWake
        NightEventType.ABNORMAL.name -> colorAbnormal
        "MIXED" -> colorMixed
        else -> colorDefault
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) {
            return event.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
        }
        val x = event.x
        val y = event.y
        for ((r, seg) in segmentHits) {
            if (r.contains(x, y) || (abs(y - (r.top + r.bottom) / 2f) < hitSlop * 3 &&
                    x >= r.left - hitSlop && x <= r.right + hitSlop)
            ) {
                onSegmentTap?.invoke(seg)
                performClick()
                return true
            }
        }
        var best: NightSegment? = null
        var bestDist = Float.MAX_VALUE
        for ((r, seg) in segmentHits) {
            val cx = (r.left + r.right) / 2f
            val d = abs(cx - x)
            if (d < bestDist) {
                bestDist = d
                best = seg
            }
        }
        if (best != null && bestDist < width * 0.25f) {
            onSegmentTap?.invoke(best)
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
