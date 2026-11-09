package com.watermonitor.app.ui.history

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat
import com.watermonitor.app.R
import java.time.Instant
import java.time.format.DateTimeFormatter
import com.watermonitor.app.data.history.ArchiveCalendar
import java.util.Locale

class HistoryChart(context: Context) : View(context) {
    var points: List<Pair<Long, Double>> = emptyList()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val left = 65f * resources.displayMetrics.density
        val top = 22f * resources.displayMetrics.density
        val bottom = height - 30f * resources.displayMetrics.density
        val right = width - 12f * resources.displayMetrics.density
        val min = points.minOf { it.second }; val max = points.maxOf { it.second }
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val begin = points.first().first; val span = (points.last().first - begin).coerceAtLeast(1)
        fun x(t: Long) = left + (t - begin).toFloat() / span * (right - left)
        fun y(v: Double) = bottom - ((v - min) / range).toFloat() * (bottom - top)
        paint.textSize = 11f * resources.displayMetrics.scaledDensity
        paint.color = ContextCompat.getColor(context, R.color.text_secondary)
        paint.strokeWidth = resources.displayMetrics.density
        for (step in 0..2) {
            val value = min + range * step / 2
            canvas.drawText(String.format(Locale.getDefault(), "%.1f", value), 0f, y(value) + 4, paint)
            paint.alpha = 50; canvas.drawLine(left, y(value), right, y(value), paint); paint.alpha = 255
        }
        val format = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.getDefault()).withZone(ArchiveCalendar.zone)
        canvas.drawText(format.format(Instant.ofEpochMilli(begin)), left, height - 5f, paint)
        val endLabel = format.format(Instant.ofEpochMilli(points.last().first))
        canvas.drawText(endLabel, right - paint.measureText(endLabel), height - 5f, paint)
        paint.color = ContextCompat.getColor(context, R.color.teal_deep)
        paint.strokeWidth = 2f * resources.displayMetrics.density
        points.forEachIndexed { index, point ->
            if (index > 0 && point.first - points[index - 1].first <= 180000) {
                val previous = points[index - 1]
                canvas.drawLine(x(previous.first), y(previous.second), x(point.first), y(point.second), paint)
            } else canvas.drawCircle(x(point.first), y(point.second), paint.strokeWidth, paint)
        }
    }
}
