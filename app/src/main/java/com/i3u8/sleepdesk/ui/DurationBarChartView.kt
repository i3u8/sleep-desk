package com.i3u8.sleepdesk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.i3u8.sleepdesk.R
import kotlin.math.max

/**
 * Lightweight custom bar chart — no chart library dependency.
 * Each entry: label + duration hours.
 */
class DurationBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Bar(val label: String, val hours: Float)

    private var bars: List<Bar> = emptyList()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.bar_fill)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_bg)
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        strokeWidth = 2f
        alpha = 80
    }
    private val rect = RectF()

    fun setData(data: List<Bar>) {
        bars = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                context.getString(R.string.history_empty_chart),
                width / 2f,
                height / 2f,
                labelPaint
            )
            return
        }
        val padL = 16f
        val padR = 16f
        val padT = 36f
        val padB = 48f
        val chartW = width - padL - padR
        val chartH = height - padT - padB
        val maxH = max(bars.maxOf { it.hours }, 1f)
        val gap = 10f
        val barW = ((chartW - gap * (bars.size - 1)) / bars.size).coerceAtLeast(8f)

        canvas.drawLine(padL, padT + chartH, padL + chartW, padT + chartH, axisPaint)

        bars.forEachIndexed { i, bar ->
            val left = padL + i * (barW + gap)
            val h = (bar.hours / maxH) * chartH
            rect.set(left, padT + chartH - h, left + barW, padT + chartH)
            canvas.drawRoundRect(rect, 12f, 12f, barPaint)
            val cx = left + barW / 2f
            canvas.drawText(bar.label, cx, height - 12f, labelPaint)
            val v = if (bar.hours >= 1f) "%.1fh".format(bar.hours) else "%dm".format((bar.hours * 60).toInt())
            canvas.drawText(v, cx, padT + chartH - h - 8f, valuePaint)
        }
    }
}
