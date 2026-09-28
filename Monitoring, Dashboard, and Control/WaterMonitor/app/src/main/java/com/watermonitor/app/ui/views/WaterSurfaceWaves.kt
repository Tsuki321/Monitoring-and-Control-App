package com.watermonitor.app.ui.views

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A tiny 1-D "shallow water" surface for the tank: a row of height columns coupled like a chain of
 * springs, so a disturbance travels sideways, reflects off the tank walls, and interferes with
 * itself — the way real water in a container ripples and sloshes. Framework-free and unit-testable;
 * [WaterTankView] owns the accelerometer and rendering and only feeds motion in.
 *
 * Model: the damped 1-D wave equation on a height field. Each column is nudged by its neighbours
 * (that coupling is what makes waves travel), pulled weakly back toward the flat rest line
 * (tension), and bled of energy each step (damping). Advanced at a fixed internal tick so the look
 * is identical at any real frame rate, and hard-clamped so no drive or dropped frame can blow it up.
 * All inputs are sanitised with [finiteOr] before use, matching the NaN-safety rule the rest of the
 * animation code follows.
 */
class WaterSurfaceWaves(
    val columns: Int = 48,
    /** Weak pull back to flat, so the surface eventually stills. */
    private val tension: Float = 0.010f,
    /** Energy bled per tick; higher settles faster. */
    private val damping: Float = 0.012f,
    /** Neighbour coupling (wave speed). Must stay < ~0.5 for stability. */
    private val spread: Float = 0.18f,
    /** How hard a sideways phone acceleration piles water up on the trailing edge. */
    private val lateralDrive: Float = 0.9f
) {
    private val pos = FloatArray(columns)
    private val vel = FloatArray(columns)
    private var tickRemainder = 0f

    /** Drop all motion back to a flat, still surface (call when the view detaches/reattaches). */
    fun reset() {
        pos.fill(0f)
        vel.fill(0f)
        tickRemainder = 0f
    }

    /**
     * Push the whole surface as if the tank accelerated sideways by [lateralAccel] m/s²: water piles
     * up on the trailing side and drops on the leading side (velocity proportional to signed
     * distance from centre). Neighbour coupling then breaks that tilt into travelling waves.
     */
    fun driveLateral(lateralAccel: Float, dtSeconds: Float) {
        if (columns < 2) return
        val a = lateralAccel.finiteOr(0f)
        val dt = dtSeconds.finiteOr(0f)
        if (dt <= 0f) return
        val half = (columns - 1) / 2f
        val k = lateralDrive * a * dt.coerceAtMost(MAX_ACCUMULATED)
        for (i in 0 until columns) {
            vel[i] += k * ((i - half) / half)
        }
    }

    /** A localized splash centred at [fraction] (0..1 across the width), signed [amount] (up +). */
    fun disturb(fraction: Float, amount: Float) {
        if (columns < 1) return
        val f = fraction.finiteOr(0.5f).coerceIn(0f, 1f)
        val amt = amount.finiteOr(0f)
        val i = (f * (columns - 1)).roundToInt().coerceIn(0, columns - 1)
        vel[i] += amt
        if (i > 0) vel[i - 1] += amt * 0.5f
        if (i < columns - 1) vel[i + 1] += amt * 0.5f
    }

    /** Advance by [dtSeconds] of real time using fixed internal ticks (frame-rate independent). */
    fun update(dtSeconds: Float) {
        val dt = dtSeconds.finiteOr(0f)
        if (dt <= 0f) return
        tickRemainder += dt.coerceAtMost(MAX_ACCUMULATED)
        var ticks = 0
        while (tickRemainder >= FIXED_TICK && ticks < MAX_TICKS_PER_FRAME) {
            step()
            tickRemainder -= FIXED_TICK
            ticks++
        }
        if (ticks >= MAX_TICKS_PER_FRAME) tickRemainder = 0f
    }

    private fun step() {
        val n = columns
        // Neighbour coupling + tension + damping updates velocity; reflecting walls (each end mirrors
        // itself, so the edge term vanishes and waves bounce back instead of leaking away).
        for (i in 0 until n) {
            val left = if (i > 0) pos[i - 1] else pos[i]
            val right = if (i < n - 1) pos[i + 1] else pos[i]
            val laplacian = (left - pos[i]) + (right - pos[i])
            var v = (vel[i] + spread * laplacian - tension * pos[i]) * (1f - damping)
            if (!v.isFinite()) v = 0f
            vel[i] = v
        }
        for (i in 0 until n) {
            var p = pos[i] + vel[i]
            if (!p.isFinite()) p = 0f
            pos[i] = p.coerceIn(-CLAMP_AMPLITUDE, CLAMP_AMPLITUDE)
        }
    }

    /** Interpolated surface height at [fraction] (0..1 across the width). */
    fun heightAt(fraction: Float): Float {
        if (columns == 1) return pos[0]
        val f = fraction.finiteOr(0.5f).coerceIn(0f, 1f)
        val x = f * (columns - 1)
        val i = x.toInt().coerceIn(0, columns - 1)
        val a = pos[i]
        val b = if (i < columns - 1) pos[i + 1] else pos[i]
        return a + (b - a) * (x - i)
    }

    /** Largest |height| across the field — for coupling effects and tests. */
    fun maxAbsHeight(): Float {
        var m = 0f
        for (p in pos) {
            val a = abs(p)
            if (a > m) m = a
        }
        return m
    }

    private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

    companion object {
        private const val TICKS_PER_SECOND = 120f
        private const val FIXED_TICK = 1f / TICKS_PER_SECOND
        private const val MAX_TICKS_PER_FRAME = 6
        /** Cap on dt fed to drive/update, so a long stall can't inject a huge impulse or backlog. */
        private const val MAX_ACCUMULATED = 0.05f
        /** Hard bound on displacement (column units); render scales this to px. */
        private const val CLAMP_AMPLITUDE = 4f
    }
}
