package com.watermonitor.app.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.watermonitor.app.R
import kotlin.math.sin

/**
 * Animates multiple sinusoidal wave layers scrolling horizontally,
 * matching the ocean-themed concept art background.
 */
class OceanWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePath1 = Path()
    private val wavePath2 = Path()
    private val wavePath3 = Path()

    private val paint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.wave_layer_1)
    }
    private val paint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.wave_layer_2)
    }
    private val paint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.wave_layer_3)
    }

    private var phase1 = 0f
    private var phase2 = 0f
    private var phase3 = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            val v = anim.animatedValue as Float
            phase1 = v
            phase2 = v * 1.3f
            phase3 = v * 0.7f
            invalidate()
        }
    }

    init {
        if (!isInEditMode) animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        drawWave(canvas, wavePath1, paint1, w, h, amplitude = h * 0.06f, yOffset = h * 0.65f, phase = phase1, frequency = 1.2f)
        drawWave(canvas, wavePath2, paint2, w, h, amplitude = h * 0.08f, yOffset = h * 0.72f, phase = phase2, frequency = 0.9f)
        drawWave(canvas, wavePath3, paint3, w, h, amplitude = h * 0.05f, yOffset = h * 0.80f, phase = phase3, frequency = 1.5f)
    }

    private fun drawWave(
        canvas: Canvas,
        path: Path,
        paint: Paint,
        w: Float,
        h: Float,
        amplitude: Float,
        yOffset: Float,
        phase: Float,
        frequency: Float
    ) {
        path.reset()
        path.moveTo(0f, yOffset)

        val stepSize = 8f
        var x = 0f
        while (x <= w) {
            val y = yOffset + amplitude * sin((x / w * 2 * Math.PI * frequency + phase).toDouble()).toFloat()
            path.lineTo(x, y)
            x += stepSize
        }

        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()

        canvas.drawPath(path, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isRunning) animator.start()
    }
}
