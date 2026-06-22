package com.watermonitor.app.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.watermonitor.app.R
import kotlin.math.sin

/**
 * Animated water tank custom view.
 * Displays a rounded-rect tank with an animated water fill level,
 * a ripple wave on the water surface, and a percentage + status label.
 */
class WaterTankView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Current displayed fill (0f–100f), animated toward targetFill
    private var displayFill = 65f
    private var targetFill = 65f

    private val tankRect = RectF()
    private val waterRect = RectF()
    private val wavePath = Path()

    private val tankBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ContextCompat.getColor(context, R.color.tank_border)
    }
    private val tankBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.tank_bg)
    }
    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Gradient will be set in onSizeChanged
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.tank_water_light)
    }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = ContextCompat.getColor(context, R.color.tank_water_shimmer)
        alpha = 180
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_blue)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
    }

    // Computed once in onSizeChanged; uses the same 20 dp value as card_corner_radius
    private var cornerRadius = 0f

    private var wavePhase = 0f
    private val waveAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 2500
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            wavePhase = anim.animatedValue as Float
            invalidate()
        }
    }

    private val fillAnimator = ValueAnimator().apply {
        duration = 1500
        interpolator = DecelerateInterpolator()
        addUpdateListener { anim ->
            displayFill = anim.animatedValue as Float
            invalidate()
        }
    }

    init {
        if (!isInEditMode) waveAnimator.start()
    }

    /** Call this to animate the tank fill to a new target percentage (0–100) */
    fun setFillPercent(percent: Float) {
        targetFill = percent.coerceIn(0f, 100f)
        if (fillAnimator.isRunning) fillAnimator.cancel()
        fillAnimator.setFloatValues(displayFill, targetFill)
        fillAnimator.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = 8f
        tankRect.set(pad, pad, w - pad, h - pad)
        textPaint.textSize = w * 0.20f
        labelPaint.textSize = w * 0.09f
        // Derive corner radius from the shared dimension (card_corner_radius = 28 dp)
        cornerRadius = resources.getDimension(R.dimen.card_corner_radius)

        // Create gradient for water - darker at bottom, lighter at top
        val waterTop = tankRect.top + cornerRadius
        val waterBottom = tankRect.bottom - cornerRadius
        waterPaint.shader = LinearGradient(
            0f, waterBottom, 0f, waterTop,
            intArrayOf(
                ContextCompat.getColor(context, R.color.tank_water_deep),
                ContextCompat.getColor(context, R.color.tank_water_mid),
                ContextCompat.getColor(context, R.color.tank_water_light)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Draw tank background
        canvas.drawRoundRect(tankRect, cornerRadius, cornerRadius, tankBgPaint)

        // Calculate water fill height inside tank
        val tankInnerTop = tankRect.top + cornerRadius
        val tankInnerBottom = tankRect.bottom - cornerRadius
        val tankInnerHeight = tankInnerBottom - tankInnerTop
        val waterTop = tankInnerBottom - (tankInnerHeight * displayFill / 100f)

        // Clip to tank shape — water, wave, AND overlay text all stay inside the rounded border
        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(tankRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        // Draw water body with gradient
        waterRect.set(tankRect.left, waterTop, tankRect.right, tankRect.bottom)
        canvas.drawRect(waterRect, waterPaint)

        // Draw wave on top of water
        drawSurfaceWave(canvas, waterTop, w)

        // Draw shimmer line at water surface for refined look
        if (displayFill > 5f) {
            canvas.drawLine(tankRect.left + 10f, waterTop, tankRect.right - 10f, waterTop, shimmerPaint)
        }

        // Percentage text centered in the view (inside clip so it respects rounded corners)
        val cx = w / 2f
        val textY = h * 0.45f
        canvas.drawText("${displayFill.toInt()}%", cx, textY, textPaint)

        // Label: FULL / LOW / level indicator (inside clip)
        val label = when {
            displayFill >= 90f -> "FULL"
            displayFill <= 15f -> "LOW"
            else -> "Level"
        }
        canvas.drawText(label, cx, textY + labelPaint.textSize + 4f, labelPaint)

        canvas.restore()

        // Draw tank border on top of everything
        canvas.drawRoundRect(tankRect, cornerRadius, cornerRadius, tankBorderPaint)
    }

    private fun drawSurfaceWave(canvas: Canvas, surfaceY: Float, w: Float) {
        wavePath.reset()
        wavePath.moveTo(0f, surfaceY)

        val amplitude = 6f
        val stepSize = 6f
        var x = 0f
        while (x <= w) {
            val y = surfaceY - amplitude * sin((x / w * 2 * Math.PI * 2 + wavePhase).toDouble()).toFloat()
            wavePath.lineTo(x, y)
            x += stepSize
        }

        wavePath.lineTo(w, tankRect.bottom)
        wavePath.lineTo(0f, tankRect.bottom)
        wavePath.close()

        canvas.drawPath(wavePath, wavePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        waveAnimator.cancel()
        fillAnimator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!waveAnimator.isRunning) waveAnimator.start()
    }
}
