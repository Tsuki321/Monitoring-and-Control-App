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
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan

/**
 * Animated water tank custom view.
 * Displays a rounded-rect tank with an animated water fill level, a wavy water surface, and a
 * percentage + status label.
 *
 * The surface reacts to the phone's motion two ways: the whole body rolls to stay level with the
 * ground and sloshes (overshoot-then-settle) via the framework-free [WaterSloshSimulator], and a
 * 1-D shallow-water field ([WaterSurfaceWaves]) carries travelling ripples that pile toward the
 * low edge, reflect off the walls, and interfere — genuinely wavy water rather than a flat tilted
 * line. This view only reads the accelerometer, feeds both models, and renders the result. Sensors
 * are registered/unregistered with the window lifecycle so the view never listens while off-screen.
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
    private val edgePath = Path()
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
        // Translucent so the gradient body shows through as depth; this layer is a surface sheen
        // riding the waves, not a flat flood-fill over the whole tank.
        alpha = 105
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

    // Ambient ripple phases, advanced by real elapsed time (never a millisecond clock — see below).
    // These drive a few small procedural sine waves so the surface is always gently wavy, even when
    // the phone is held still; the physical slosh waves below ride on top of them.
    private var ambPhase1 = 0f
    private var ambPhase2 = 0f

    // --- Motion / sloshing -------------------------------------------------------------------
    // Calmer than the defaults on purpose: a soft roll deadzone means incidental handling leaves the
    // water flat, and the surface only tilts/sloshes once the phone is *intentionally* rotated past
    // it. sloshGain is also lowered so small translational jiggles barely register.
    private val slosh = WaterSloshSimulator(
        sloshGain = SLOSH_SHAKE_GAIN,
        restAngleGain = TILT_FOLLOW_GAIN,
        rollDeadzoneRadians = TILT_DEADZONE_RAD
    )

    // Travelling/reflecting surface waves — a simplified shallow-water field driven by phone motion.
    // A touch more damping than the default so a shake settles quickly instead of wobbling on.
    private val waves = WaterSurfaceWaves(damping = 0.02f)

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

            ambPhase1 = (ambPhase1 + deltaSeconds * AMBIENT_SPEED_1) % TWO_PI
            ambPhase2 = (ambPhase2 + deltaSeconds * AMBIENT_SPEED_2) % TWO_PI
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

    /** Soft deadzone: values within ±[dz] read as zero, and past it the response ramps in from zero
     *  (no snap). Lets small, incidental motion leave the water undisturbed. */
    private fun deadzone(v: Float, dz: Float): Float = when {
        v > dz -> v - dz
        v < -dz -> v + dz
        else -> 0f
    }

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
            val lateralAccel = deadzone(rx - gravityX, LATERAL_DEADZONE)
            slosh.update(gravityX, gravityY, gravityZ, lateralAccel, deltaSeconds)
            // Feed the same lateral motion into the wave field, and let a fast bulk rock stir the
            // surface near the edge the water is piling toward.
            waves.driveLateral(lateralAccel, deltaSeconds)
            if (abs(slosh.angularVelocity) > ROCK_STIR_THRESHOLD) {
                val edge = if (slosh.tiltRadians >= 0f) 0.9f else 0.1f
                waves.disturb(edge, slosh.angularVelocity * ROCK_STIR_GAIN)
            }
            waves.update(deltaSeconds)
        } else {
            // No sensor / no sample yet: keep the bulk surface level; ambient ripples still animate.
            slosh.update(0f, SensorManager.GRAVITY_EARTH, 0f, 0f, deltaSeconds)
            waves.update(deltaSeconds)
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
        // Ambient ripple grows choppier mid-slosh; a floor keeps the surface always faintly wavy.
        val ambientAmp = BASE_AMBIENT_AMP * (1f + AMBIENT_SLOSH_GAIN * slosh.sloshIntensity)

        // Clip to tank shape — water, wave, AND overlay text all stay inside the rounded border
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(tankRect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Draw the water body up to the full wavy surface (tilt + travelling waves + ambient ripple)
        buildSurfacePath(bodyPath, cx, waterTop, slope, w, ambientAmp, highlight = false)
        canvas.drawPath(bodyPath, waterPaint)

        // Draw rising bubbles within the submerged region for a subtle living effect
        for (b in bubbles) {
            val surface =
                surfaceAt(b.x, cx, waterTop, slope, w, ambientAmp, highlight = false, bottom = tankRect.bottom)
            if (b.y > surface) {
                canvas.drawCircle(b.x, b.y, b.radius, bubblePaint)
            }
        }

        // Lighter translucent layer hugging just under the surface for a sense of depth
        buildSurfacePath(wavePath, cx, waterTop, slope, w, ambientAmp, highlight = true)
        canvas.drawPath(wavePath, wavePaint)

        // Stroke a shimmer along the very top edge so the highlight follows every wave crest
        if (displayFill > 3f) {
            buildTopEdge(edgePath, cx, waterTop, slope, w, ambientAmp)
            canvas.drawPath(edgePath, shimmerPaint)
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
     * Builds a closed polygon: the wavy water surface across the top down to the tank floor. Every
     * surface sample is clamped to the floor so a steep tilt or deep trough can never push a point
     * below the bottom and self-intersect the polygon. [highlight] shifts the surface down a touch
     * and adds a fine ripple, for the lighter sheen layer drawn over the body.
     */
    private fun buildSurfacePath(
        path: Path,
        cx: Float,
        waterTop: Float,
        slope: Float,
        w: Float,
        ambientAmp: Float,
        highlight: Boolean
    ) {
        path.reset()
        val bottom = tankRect.bottom
        path.moveTo(0f, surfaceAt(0f, cx, waterTop, slope, w, ambientAmp, highlight, bottom))
        var x = SURFACE_STEP
        while (x <= w) {
            path.lineTo(x, surfaceAt(x, cx, waterTop, slope, w, ambientAmp, highlight, bottom))
            x += SURFACE_STEP
        }
        // Pin the exact right edge in case the width isn't a whole number of steps.
        path.lineTo(w, surfaceAt(w, cx, waterTop, slope, w, ambientAmp, highlight, bottom))
        path.lineTo(w, bottom)
        path.lineTo(0f, bottom)
        path.close()
    }

    /** Open polyline tracing just the top (wavy) edge of the water, for the shimmer stroke. */
    private fun buildTopEdge(
        path: Path,
        cx: Float,
        waterTop: Float,
        slope: Float,
        w: Float,
        ambientAmp: Float
    ) {
        path.reset()
        val bottom = tankRect.bottom
        path.moveTo(0f, surfaceAt(0f, cx, waterTop, slope, w, ambientAmp, false, bottom))
        var x = SURFACE_STEP
        while (x <= w) {
            path.lineTo(x, surfaceAt(x, cx, waterTop, slope, w, ambientAmp, false, bottom))
            x += SURFACE_STEP
        }
        path.lineTo(w, surfaceAt(w, cx, waterTop, slope, w, ambientAmp, false, bottom))
    }

    /**
     * Y of the water surface at pixel [x]: the tilted rest line, minus the physical wave height from
     * [waves], minus a few small procedural sines ([ambientAt]) so it is always gently wavy. Screen
     * y grows downward, so a positive height sits higher up (smaller y).
     */
    private fun surfaceAt(
        x: Float,
        cx: Float,
        waterTop: Float,
        slope: Float,
        w: Float,
        ambientAmp: Float,
        highlight: Boolean,
        bottom: Float
    ): Float {
        val tilted = waterTop + (x - cx) * slope
        val frac = if (w > 0f) (x / w).coerceIn(0f, 1f) else 0.5f
        val physical = waves.heightAt(frac) * WAVE_RENDER_SCALE
        var y = tilted - physical - ambientAt(frac, ambientAmp)
        if (highlight) {
            y += SURFACE_LAYER_PX
            y -= HIGHLIGHT_RIPPLE * sin((frac * TWO_PI * 6f + ambPhase2).toDouble()).toFloat()
        }
        return y.coerceAtMost(bottom)
    }

    /** A few small sines of differing wavelength/speed summed into an organic, always-moving ripple. */
    private fun ambientAt(frac: Float, ambientAmp: Float): Float {
        return ambientAmp * (
            0.6f * sin((frac * TWO_PI * 2.5f + ambPhase1).toDouble()).toFloat() +
                0.3f * sin((frac * TWO_PI * 4.3f - ambPhase2).toDouble()).toFloat() +
                0.2f * sin((frac * TWO_PI * 7.1f + ambPhase1 * 1.7f).toDouble()).toFloat()
            )
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
            waves.reset()
            gravityInitialised = false
            hasSensorSample = false
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    companion object {
        private val TWO_PI = (2.0 * Math.PI).toFloat()

        /** Angular speeds of the two ambient ripple layers (slow swell + faster chop). */
        private val AMBIENT_SPEED_1 = TWO_PI / 4.5f
        private val AMBIENT_SPEED_2 = TWO_PI / 3.0f

        /** Always-on ambient ripple height (px); the surface is never a dead-flat line, but kept
         *  gentle so a still phone reads as calm water rather than a constant wobble. */
        private const val BASE_AMBIENT_AMP = 3.5f

        /** Ambient ripple grows up to ~2.6× taller at peak agitation for a choppier surface. */
        private const val AMBIENT_SLOSH_GAIN = 1.6f

        /** Pixels per unit of [WaterSurfaceWaves] height — how tall the physical slosh waves render. */
        private const val WAVE_RENDER_SCALE = 8f

        /** Offset + fine ripple for the lighter surface sheen layer. */
        private const val SURFACE_LAYER_PX = 7f
        private const val HIGHLIGHT_RIPPLE = 3f

        /** Bulk rock (rad/s) above which the rocking stirs extra surface chop, and how hard. */
        private const val ROCK_STIR_THRESHOLD = 0.15f
        private const val ROCK_STIR_GAIN = 0.20f

        // --- Motion sensitivity ------------------------------------------------------------------
        /** Soft roll deadzone (rad, ~9°): the water stays flat until the phone is rotated past this,
         *  so only an intentional tilt sloshes it — incidental handling does nothing. */
        private const val TILT_DEADZONE_RAD = 0.16f
        /** Follows a real roll at less than 1:1, so even a big rotation moves the surface calmly. */
        private const val TILT_FOLLOW_GAIN = 0.9f
        /** Lateral-shake → slosh gain (rad/s² per m/s²); below the simulator default so flicks are soft. */
        private const val SLOSH_SHAKE_GAIN = 0.05f
        /** Lateral acceleration (m/s²) below which shake is ignored, so small jiggles don't ripple. */
        private const val LATERAL_DEADZONE = 1.2f

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




