package com.watermonitor.app.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.watermonitor.app.R
import kotlin.math.sin
import kotlin.math.tan

/**
 * Animated water tank custom view.
 * Displays a rounded-rect tank with an animated water fill level, a ripple wave on the water
 * surface, and a percentage + status label.
 *
 * The surface also reacts to the phone's motion: the water tries to stay level with the ground as
 * the device rolls, and a sudden move makes it slosh (overshoot then settle). The physics lives in
 * the framework-free [WaterSloshSimulator]; this view only reads the accelerometer, feeds it in,
 * and renders the tilted surface. Sensors are registered/unregistered with the window lifecycle so
 * the view never listens while off-screen.
 */
class WaterTankView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    // Current displayed fill (0f–100f), animated toward targetFill
    private var displayFill = 65f
    private var targetFill = 65f

    private val tankRect = RectF()
    private val bodyPath = Path()
    private val wavePath = Path()
    private val clipPath = Path()

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
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = ContextCompat.getColor(context, R.color.tank_border)
        alpha = 40
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.tank_water_shimmer)
        alpha = 90
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

    // Level markers drawn on the tank side (as fractions of fill height)
    private val levelMarkers = floatArrayOf(0.10f, 0.25f, 0.50f, 0.75f, 0.90f)

    // Ambient bubbles rising through the water — purely decorative
    private data class Bubble(var x: Float, var y: Float, var radius: Float, var speed: Float)
    private val bubbles = mutableListOf<Bubble>()
    private var bubblesSeeded = false

    // Computed once in onSizeChanged; uses the same 20 dp value as card_corner_radius
    private var cornerRadius = 0f

    // Surface ripple phase, advanced by real elapsed time (never a millisecond clock — see below).
    private var wavePhase = 0f

    // --- Motion / sloshing -------------------------------------------------------------------
    private val slosh = WaterSloshSimulator()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Latest raw accelerometer sample. Written on the sensor thread, read on the main (frame)
    // thread, so it is @Volatile; all physics runs single-threaded inside doFrame.
    @Volatile private var rawX = 0f
    @Volatile private var rawY = SensorManager.GRAVITY_EARTH   // sane default: upright, level
    @Volatile private var rawZ = 0f
    @Volatile private var hasSensorSample = false

    // Low-pass estimate of gravity (main thread only); the fast remainder is the "shake" that
    // drives the slosh.
    private var gravityX = 0f
    private var gravityY = SensorManager.GRAVITY_EARTH
    private var gravityZ = 0f
    private var gravityInitialised = false

    // --- Frame loop (delta-time based, per the project's animation rules) --------------------
    private var isAnimating = false
    private var lastFrameTimeNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAnimating) return

            if (lastFrameTimeNanos == 0L) lastFrameTimeNanos = frameTimeNanos
            val deltaSeconds = ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0)
                .toFloat()
                .coerceIn(0f, MAX_FRAME_DELTA)
            lastFrameTimeNanos = frameTimeNanos

            wavePhase = (wavePhase + deltaSeconds * WAVE_SPEED) % TWO_PI
            stepPhysics(deltaSeconds)
            advanceBubbles(deltaSeconds)

            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
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

    /** Call this to animate the tank fill to a new target percentage (0–100) */
    fun setFillPercent(percent: Float) {
        targetFill = percent.coerceIn(0f, 100f)
        if (fillAnimator.isRunning) fillAnimator.cancel()
        fillAnimator.setFloatValues(displayFill, targetFill)
        fillAnimator.start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        rawX = event.values[0]
        rawY = event.values[1]
        rawZ = event.values[2]
        hasSensorSample = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    /** Splits the latest accelerometer sample into gravity + shake and advances the slosh. */
    private fun stepPhysics(deltaSeconds: Float) {
        if (hasSensorSample) {
            val rx = rawX
            val ry = rawY
            val rz = rawZ
            if (!gravityInitialised) {
                // Seed the filter with the first real sample so gravity doesn't ramp from zero.
                gravityX = rx; gravityY = ry; gravityZ = rz
                gravityInitialised = true
            } else {
                // Time-aware low-pass: slow-moving component is gravity, remainder is motion.
                val alpha = (deltaSeconds / (GRAVITY_TAU + deltaSeconds)).coerceIn(0f, 1f)
                gravityX += (rx - gravityX) * alpha
                gravityY += (ry - gravityY) * alpha
                gravityZ += (rz - gravityZ) * alpha
            }
            val lateralAccel = rx - gravityX
            slosh.update(gravityX, gravityY, gravityZ, lateralAccel, deltaSeconds)
        } else {
            // No sensor / no sample yet: keep the surface level and still.
            slosh.update(0f, SensorManager.GRAVITY_EARTH, 0f, 0f, deltaSeconds)
        }
    }

    private fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
        // Registered here (not in the caller) so the listener's lifetime is tied 1:1 to isAnimating.
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopAnimation() {
        if (!isAnimating) return
        isAnimating = false
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        sensorManager?.unregisterListener(this)
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

        seedBubbles(w.toFloat(), h.toFloat())
    }

    /** Lays out a small set of bubbles at varied positions across the tank width. */
    private fun seedBubbles(w: Float, h: Float) {
        bubbles.clear()
        val count = 6
        for (i in 0 until count) {
            // Deterministic spread so bubbles don't clump; no RNG needed for a calm effect
            val fraction = (i + 0.5f) / count
            bubbles.add(
                Bubble(
                    x = tankRect.left + tankRect.width() * fraction,
                    y = h * (0.3f + 0.6f * fraction),
                    radius = 2.5f + (i % 3),
                    speed = 0.4f + 0.25f * (i % 3)
                )
            )
        }
        bubblesSeeded = true
    }

    /**
     * Advances bubbles upward; recycles them to the bottom once they reach the surface. Speeds are
     * scaled to a 60 fps baseline so the rise looks the same regardless of the real frame rate.
     */
    private fun advanceBubbles(deltaSeconds: Float) {
        if (!bubblesSeeded) return
        val frameScale = deltaSeconds * 60f
        val tankInnerTop = tankRect.top + cornerRadius
        val tankInnerBottom = tankRect.bottom - cornerRadius
        val tankInnerHeight = tankInnerBottom - tankInnerTop
        val waterTop = tankInnerBottom - (tankInnerHeight * displayFill / 100f)
        for (b in bubbles) {
            b.y -= b.speed * frameScale
            // Recycle bubble to the bottom once it rises past the water surface
            if (b.y < waterTop) {
                b.y = tankRect.bottom - cornerRadius
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return // pre-layout / zero-size: nothing to draw, and avoids x/w NaN below

        // Draw tank background
        canvas.drawRoundRect(tankRect, cornerRadius, cornerRadius, tankBgPaint)

        // Calculate water fill height inside tank
        val tankInnerTop = tankRect.top + cornerRadius
        val tankInnerBottom = tankRect.bottom - cornerRadius
        val tankInnerHeight = tankInnerBottom - tankInnerTop
        val waterTop = tankInnerBottom - (tankInnerHeight * displayFill / 100f)

        // Surface geometry: a line through the horizontal centre, rotated so the water stays level
        // with the ground. Pivoting at the centre conserves the water area while the whole surface
        // stays inside the tank; once an edge would drop past the floor it is clamped there (see
        // surfaceAt) and a little volume is visually lost — acceptable for a decorative gauge. tan()
        // is bounded because the simulator clamps the tilt angle.
        val cx = w / 2f
        val slope = tan(TILT_RENDER_SIGN * slosh.tiltRadians)
        val waveAmplitude = BASE_WAVE_AMPLITUDE * (1f + SLOSH_WAVE_GAIN * slosh.sloshIntensity)

        // Clip to tank shape — water, wave, AND overlay text all stay inside the rounded border
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(tankRect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Draw water body with gradient, under the tilted (un-rippled) surface
        buildSurfacePath(bodyPath, cx, waterTop, slope, amplitude = 0f, w = w)
        canvas.drawPath(bodyPath, waterPaint)

        // Draw rising bubbles within the submerged region for a subtle living effect
        for (b in bubbles) {
            val surface = waterTop + (b.x - cx) * slope
            if (b.y > surface) {
                canvas.drawCircle(b.x, b.y, b.radius, bubblePaint)
            }
        }

        // Draw the animated ripple wave on top, following the tilt
        buildSurfacePath(wavePath, cx, waterTop, slope, amplitude = waveAmplitude, w = w)
        canvas.drawPath(wavePath, wavePaint)

        // Draw shimmer line along the tilted water surface for a refined look
        if (displayFill > 5f) {
            val leftY = waterTop + (tankRect.left + 10f - cx) * slope
            val rightY = waterTop + (tankRect.right - 10f - cx) * slope
            canvas.drawLine(tankRect.left + 10f, leftY, tankRect.right - 10f, rightY, shimmerPaint)
        }

        // Subtle level markers on the right edge — a fixed scale, so they stay horizontal
        val tickRight = tankRect.right - 8f
        val tickLeft = tickRight - w * 0.08f
        for (marker in levelMarkers) {
            val markerY = tankInnerBottom - (tankInnerHeight * marker)
            canvas.drawLine(tickLeft, markerY, tickRight, markerY, markerPaint)
        }

        // Percentage + label stay upright — they are a readout, not part of the water
        val textY = h * 0.45f
        canvas.drawText("${displayFill.toInt()}%", cx, textY, textPaint)
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

    /**
     * Builds a closed polygon: the tilted, optionally-rippled water surface across the top down to
     * the tank floor. Every surface sample is clamped to the floor so a steep tilt can never push
     * one edge below the bottom and self-intersect the polygon.
     */
    private fun buildSurfacePath(
        path: Path,
        cx: Float,
        baseSurfaceY: Float,
        slope: Float,
        amplitude: Float,
        w: Float
    ) {
        path.reset()
        val bottom = tankRect.bottom
        path.moveTo(0f, surfaceAt(0f, cx, baseSurfaceY, slope, amplitude, w, bottom))
        var x = SURFACE_STEP
        while (x <= w) {
            path.lineTo(x, surfaceAt(x, cx, baseSurfaceY, slope, amplitude, w, bottom))
            x += SURFACE_STEP
        }
        // Pin the exact right edge in case the width isn't a whole number of steps.
        path.lineTo(w, surfaceAt(w, cx, baseSurfaceY, slope, amplitude, w, bottom))
        path.lineTo(w, bottom)
        path.lineTo(0f, bottom)
        path.close()
    }

    private fun surfaceAt(
        x: Float,
        cx: Float,
        baseSurfaceY: Float,
        slope: Float,
        amplitude: Float,
        w: Float,
        bottom: Float
    ): Float {
        val tilted = baseSurfaceY + (x - cx) * slope
        val ripple = if (amplitude != 0f) {
            amplitude * sin((x / w * 2 * Math.PI * 2 + wavePhase).toDouble()).toFloat()
        } else {
            0f
        }
        return (tilted - ripple).coerceAtMost(bottom)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
        fillAnimator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) resumeIfVisible()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isInEditMode) resumeIfVisible()
    }

    /**
     * Run the sensor + frame loop only while the view is attached AND its window is actually visible.
     * onDetachedFromWindow alone is not enough: pressing Home, locking the screen, or backgrounding
     * the app does not detach the view, so without the visibility gate the accelerometer keeps
     * sampling at ~50 Hz and the physics loop keeps spinning off-screen — a steady battery drain.
     * Starting re-seeds the gravity filter so the surface begins level rather than lurching on resume.
     */
    private fun resumeIfVisible() {
        if (isAttachedToWindow && windowVisibility == View.VISIBLE) {
            slosh.reset()
            gravityInitialised = false
            hasSensorSample = false
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    companion object {
        private val TWO_PI = (2.0 * Math.PI).toFloat()

        /** Ripple angular speed, preserving the original ~2.5 s surface-wave loop. */
        private val WAVE_SPEED = TWO_PI / 2.5f

        private const val BASE_WAVE_AMPLITUDE = 10f

        /** Ripple amplitude scales up to ~3× at peak agitation for a much choppier surface. */
        private const val SLOSH_WAVE_GAIN = 2.0f

        private const val SURFACE_STEP = 6f

        /** Clamp per-frame integration to one 30 fps step, so a stall can't jump the physics. */
        private const val MAX_FRAME_DELTA = 1f / 30f

        /** Gravity low-pass time constant (s); the fast remainder is the shake that drives slosh. */
        private const val GRAVITY_TAU = 0.18f

        /**
         * +1 renders a physically-correct tilt (derived: water pools on the low edge). If it ever
         * looks mirrored on a real device, flip this single constant.
         */
        private const val TILT_RENDER_SIGN = 1f
    }
}




