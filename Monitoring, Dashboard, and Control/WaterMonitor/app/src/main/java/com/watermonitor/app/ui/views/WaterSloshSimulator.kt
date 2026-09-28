package com.watermonitor.app.ui.views

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Physics for the "water sloshes when you move the phone" effect.
 *
 * The water surface wants to stay level with the ground. As the device rolls, the surface line
 * in *screen space* rotates by the device's roll angle so that it keeps pointing at true
 * horizontal. A sudden move overshoots and settles rather than snapping — the tilt is modelled as
 * a driven, under-damped harmonic oscillator (a spring pulling the surface toward the rest angle,
 * plus damping, plus a lateral-acceleration kick from shakes). That overshoot-then-settle is the
 * slosh.
 *
 * Inputs are the raw gravity vector (screen frame: +x right, +y up, +z out of the glass) plus the
 * lateral linear acceleration (gravity removed). The caller is responsible for splitting a raw
 * accelerometer reading into those two — see [WaterTankView].
 *
 * Pure Kotlin, no Android dependencies, so this is unit-testable in isolation. All inputs are
 * sanitised with [finiteOr] *before* any `coerceIn`, because `Double.NaN.coerceIn(a, b)` returns
 * NaN — a bad sample would otherwise ride straight through to the surface geometry.
 */
class WaterSloshSimulator(
    /** Slosh oscillation rate. ~1 Hz reads as water rather than jelly (fast) or oil (slow). */
    private val naturalFrequencyHz: Float = 0.9f,
    /** < 1 is under-damped, so the surface overshoots the rest angle and rocks back — the slosh. */
    private val dampingRatio: Float = 0.28f,
    /** Hard cap on how far the surface tilts, so extreme rolls never invert the water polygon. */
    private val maxTiltRadians: Float = DEFAULT_MAX_TILT,
    /** How hard a lateral shake kicks the surface (rad/s² per m/s²). */
    private val sloshGain: Float = 0.02f
) {
    /** Current surface tilt in radians (0 = level with the screen's horizontal). */
    var tiltRadians = 0f
        private set

    /** Angular rate of the surface, rad/s. Exposed for tests and for wave-amplitude coupling. */
    var angularVelocity = 0f
        private set

    /**
     * 0..1 measure of how agitated the water is right now, smoothed. The view multiplies the
     * decorative ripple amplitude by this so the surface gets choppy mid-slosh and calms when still.
     */
    var sloshIntensity = 0f
        private set

    /** Drop all motion back to a flat, still surface (call when the view detaches/reattaches). */
    fun reset() {
        tiltRadians = 0f
        angularVelocity = 0f
        sloshIntensity = 0f
    }

    /**
     * Advances the simulation by [dtSeconds].
     *
     * @param gravityX gravity along screen +x (right), m/s².
     * @param gravityY gravity along screen +y (up), m/s².
     * @param gravityZ gravity along screen +z (out of the glass), m/s². Used only to tell how
     *   upright the phone is held.
     * @param lateralAccel linear (gravity-removed) acceleration along screen x, m/s².
     * @param dtSeconds frame delta. Values <= 0 are treated as a no-op; large values are clamped so
     *   a dropped frame or a resume-from-background cannot blow the integrator up.
     * @return the new [tiltRadians].
     */
    fun update(
        gravityX: Float,
        gravityY: Float,
        gravityZ: Float,
        lateralAccel: Float,
        dtSeconds: Float
    ): Float {
        val dt = dtSeconds.finiteOr(0f)
        if (dt <= 0f) return tiltRadians
        val stepDt = dt.coerceAtMost(MAX_STEP)

        val gx = gravityX.finiteOr(0f)
        val gy = gravityY.finiteOr(0f)
        val gz = gravityZ.finiteOr(0f)
        val lat = lateralAccel.finiteOr(0f)

        // Weight the tilt by how upright the screen is. Held flat (face up/down) the in-plane
        // gravity is ~0 and atan2 is pure noise, so this fades the effect out and kills the jitter.
        // finiteOr guards the *derived* ratio too: a finite-but-enormous gravity component can
        // overflow both hypot and sqrt to +Inf, and Inf/Inf = NaN would ride through coerceIn.
        val inPlane = hypot(gx, gy)
        val total = sqrt(gx * gx + gy * gy + gz * gz)
        val uprightWeight = if (total > EPSILON) (inPlane / total).finiteOr(0f).coerceIn(0f, 1f) else 0f

        // Roll angle of the screen: direction of "down" projected onto the glass. 0 when the phone
        // is held upright in portrait; grows as you roll it left/right.
        val rollAngle = atan2(gx, gy)
        val restAngle = (uprightWeight * rollAngle).coerceIn(-maxTiltRadians, maxTiltRadians)

        // Driven, damped spring toward restAngle. Semi-implicit (symplectic) Euler: velocity first,
        // then position from the *new* velocity — noticeably more stable than explicit Euler for an
        // oscillator, which matters when a stall pushes stepDt to the clamp.
        val omega = TWO_PI * naturalFrequencyHz
        val stiffness = omega * omega
        val damping = 2f * dampingRatio * omega
        val drive = sloshGain * lat

        val angularAccel = -stiffness * (tiltRadians - restAngle) - damping * angularVelocity + drive
        angularVelocity += angularAccel * stepDt
        tiltRadians += angularVelocity * stepDt

        // Clamp at the wall and bleed off the velocity heading further into it, so energy does not
        // accumulate against the cap and stick the surface to one side.
        if (tiltRadians > maxTiltRadians) {
            tiltRadians = maxTiltRadians
            if (angularVelocity > 0f) angularVelocity = 0f
        } else if (tiltRadians < -maxTiltRadians) {
            tiltRadians = -maxTiltRadians
            if (angularVelocity < 0f) angularVelocity = 0f
        }

        // Agitation = current kinetic motion + shake input, normalised, then eased so it rises fast
        // and decays smoothly instead of strobing frame to frame.
        val energy = (abs(angularVelocity) / VELOCITY_REF + abs(lat) / ACCEL_REF).coerceIn(0f, 1f)
        val ease = (1f - exp(-stepDt / INTENSITY_TAU)).coerceIn(0f, 1f)
        sloshIntensity += (energy - sloshIntensity) * ease

        return tiltRadians
    }

    private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

    companion object {
        // Not `const`: `Math.PI` is a Java field and `.toFloat()` a call, so this is evaluated at
        // runtime, not compile time. Marking it `const val` fails to compile under Kotlin 2.1.0.
        private val TWO_PI = (2.0 * Math.PI).toFloat()

        /** ~28°. Enough to read as a real tilt without the water clipping out of a low tank. */
        val DEFAULT_MAX_TILT = Math.toRadians(28.0).toFloat()

        /** Never integrate more than one 30 fps frame per step, whatever the real delta was. */
        private const val MAX_STEP = 1f / 30f

        private const val EPSILON = 1e-4f

        // References that map raw motion onto the 0..1 agitation scale.
        private const val VELOCITY_REF = 3.0f  // rad/s of surface swing → "very choppy"
        private const val ACCEL_REF = 8.0f     // m/s² of shake → "very choppy"
        private const val INTENSITY_TAU = 0.25f
    }
}
