package com.watermonitor.app.ui.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unit tests for [WaterSloshSimulator]. Pure JVM tests — no Android, no device — so they run in CI
 * via `testDebugUnitTest`. They pin the physics contract the [WaterTankView] rendering relies on:
 * a level surface at rest, convergence to the roll angle, visible slosh (overshoot), hard tilt
 * clamping, and total robustness to junk sensor input.
 */
class WaterSloshSimulatorTest {

    private val dt = 1f / 60f
    private val maxTilt = WaterSloshSimulator.DEFAULT_MAX_TILT

    /** Drives the sim with a constant input for [steps] frames and returns it. */
    private fun WaterSloshSimulator.run(
        gx: Float, gy: Float, gz: Float, lat: Float, steps: Int
    ): WaterSloshSimulator {
        repeat(steps) { update(gx, gy, gz, lat, dt) }
        return this
    }

    /** Gravity vector (magnitude 1) that reads as a pure roll of [angle] radians, held upright. */
    private fun gravityForRoll(angle: Float) = Triple(sin(angle), cos(angle), 0f)

    @Test
    fun startsFlatAndStill() {
        val sim = WaterSloshSimulator()
        assertEquals(0.0, sim.tiltRadians.toDouble(), 0.0)
        assertEquals(0.0, sim.angularVelocity.toDouble(), 0.0)
        assertEquals(0.0, sim.sloshIntensity.toDouble(), 0.0)
    }

    @Test
    fun resetReturnsToRest() {
        val sim = WaterSloshSimulator().run(sin(0.4f), cos(0.4f), 0f, 6f, 120)
        sim.reset()
        assertEquals(0.0, sim.tiltRadians.toDouble(), 0.0)
        assertEquals(0.0, sim.angularVelocity.toDouble(), 0.0)
        assertEquals(0.0, sim.sloshIntensity.toDouble(), 0.0)
    }

    @Test
    fun uprightAndLevelStaysFlat() {
        // Phone held straight up in portrait: gravity points down the screen, no roll.
        val sim = WaterSloshSimulator().run(0f, 9.81f, 0f, 0f, 300)
        assertEquals(0.0, sim.tiltRadians.toDouble(), 1e-4)
        assertEquals(0.0, sim.angularVelocity.toDouble(), 1e-4)
    }

    @Test
    fun lyingFlatStaysFlatWithoutJitter() {
        // Face-up on a table: in-plane gravity is ~0, atan2 is pure noise — must be suppressed.
        val sim = WaterSloshSimulator().run(0f, 0f, 9.81f, 0f, 300)
        assertTrue(sim.tiltRadians.isFinite())
        assertEquals(0.0, sim.tiltRadians.toDouble(), 1e-4)
    }

    @Test
    fun convergesToRollAngle() {
        val angle = 0.30f
        val (gx, gy, gz) = gravityForRoll(angle)
        val sim = WaterSloshSimulator().run(gx, gy, gz, 0f, 900) // 15 s — fully settled
        assertEquals(angle.toDouble(), sim.tiltRadians.toDouble(), 5e-3)
        assertEquals(0.0, sim.angularVelocity.toDouble(), 1e-3)
    }

    @Test
    fun overshootsThenSettles() {
        val angle = 0.30f
        val (gx, gy, gz) = gravityForRoll(angle)
        val sim = WaterSloshSimulator()
        var peak = 0f
        repeat(240) {
            sim.update(gx, gy, gz, 0f, dt)
            if (sim.tiltRadians > peak) peak = sim.tiltRadians
        }
        // Under-damped: the surface swings past the rest angle before settling — that's the slosh.
        assertTrue("expected overshoot past $angle, peak=$peak", peak > angle + 0.02f)
        assertTrue("overshoot should stay bounded, peak=$peak", peak < maxTilt)
        sim.run(gx, gy, gz, 0f, 900)
        assertEquals(angle.toDouble(), sim.tiltRadians.toDouble(), 5e-3)
    }

    @Test
    fun clampsToMaxTilt() {
        val sim = WaterSloshSimulator()
        var maxObserved = 0f
        repeat(1200) {
            sim.update(100f, 0.001f, 0f, 0f, dt) // atan2 wants ~90°, far past the cap
            maxObserved = maxOf(maxObserved, kotlin.math.abs(sim.tiltRadians))
        }
        assertTrue("tilt must never exceed the cap, saw $maxObserved", maxObserved <= maxTilt + 1e-4f)
        assertEquals(maxTilt.toDouble(), sim.tiltRadians.toDouble(), 1e-3)
    }

    @Test
    fun symmetricUnderMirroredRoll() {
        val angle = 0.25f
        val (gx, gy, gz) = gravityForRoll(angle)
        val a = WaterSloshSimulator().run(gx, gy, gz, 0f, 600)
        val b = WaterSloshSimulator().run(-gx, gy, gz, 0f, 600)
        assertEquals(a.tiltRadians.toDouble(), -b.tiltRadians.toDouble(), 1e-5)
    }

    @Test
    fun survivesNaNInput() {
        val sim = WaterSloshSimulator()
        repeat(300) {
            sim.update(Float.NaN, Float.NaN, Float.NaN, Float.NaN, dt)
            assertTrue("tilt went non-finite", sim.tiltRadians.isFinite())
            assertTrue(sim.angularVelocity.isFinite())
            assertTrue(sim.sloshIntensity.isFinite())
        }
        assertEquals(0.0, sim.tiltRadians.toDouble(), 1e-4) // NaN sanitised → flat
    }

    @Test
    fun survivesInfiniteInput() {
        val sim = WaterSloshSimulator()
        repeat(120) {
            sim.update(
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, dt
            )
        }
        assertTrue(sim.tiltRadians.isFinite())
        assertEquals(0.0, sim.tiltRadians.toDouble(), 1e-4)
    }

    @Test
    fun nonPositiveOrNaNDeltaIsNoOp() {
        val (gx, gy, gz) = gravityForRoll(0.3f)
        val sim = WaterSloshSimulator().run(gx, gy, gz, 0f, 60)
        val tilt = sim.tiltRadians
        val vel = sim.angularVelocity
        assertEquals(tilt.toDouble(), sim.update(gx, gy, gz, 5f, 0f).toDouble(), 0.0)
        sim.update(gx, gy, gz, 5f, -1f)
        sim.update(gx, gy, gz, 5f, Float.NaN)
        assertEquals(tilt.toDouble(), sim.tiltRadians.toDouble(), 0.0)
        assertEquals(vel.toDouble(), sim.angularVelocity.toDouble(), 0.0)
    }

    @Test
    fun hugeDeltaDoesNotExplode() {
        val (gx, gy, gz) = gravityForRoll(0.4f)
        val sim = WaterSloshSimulator()
        repeat(50) {
            sim.update(gx, gy, gz, 6f, 5f) // absurd 5-second frames, e.g. resume from background
            assertTrue("blew up: ${sim.tiltRadians}", sim.tiltRadians.isFinite())
            assertTrue(kotlin.math.abs(sim.tiltRadians) <= maxTilt + 1e-4f)
        }
    }

    @Test
    fun lateralShakeKicksThenSettles() {
        val sim = WaterSloshSimulator() // upright, rest angle 0
        var peak = 0f
        repeat(20) {
            sim.update(0f, 9.81f, 0f, 8f, dt) // strong lateral shake burst
            peak = maxOf(peak, kotlin.math.abs(sim.tiltRadians))
        }
        assertTrue("shake should disturb the surface, peak=$peak", peak > 1e-3f)
        sim.run(0f, 9.81f, 0f, 0f, 900)
        assertEquals(0.0, sim.tiltRadians.toDouble(), 1e-3) // returns to level
    }

    @Test
    fun sloshIntensityRisesWithMotionThenDecays() {
        val sim = WaterSloshSimulator()
        var peakIntensity = 0f
        repeat(30) {
            sim.update(0f, 9.81f, 0f, 8f, dt)
            peakIntensity = maxOf(peakIntensity, sim.sloshIntensity)
            assertTrue("intensity out of range: ${sim.sloshIntensity}", sim.sloshIntensity in 0f..1f)
        }
        assertTrue("intensity should rise under shaking, peak=$peakIntensity", peakIntensity > 0.1f)
        sim.run(0f, 9.81f, 0f, 0f, 600)
        assertTrue("intensity should decay when still, got ${sim.sloshIntensity}", sim.sloshIntensity < 0.05f)
    }

    @Test
    fun deterministicForSameInputs() {
        val a = WaterSloshSimulator()
        val b = WaterSloshSimulator()
        val (gx, gy, gz) = gravityForRoll(0.33f)
        repeat(500) {
            a.update(gx, gy, gz, 2f, dt)
            b.update(gx, gy, gz, 2f, dt)
        }
        assertEquals(a.tiltRadians.toDouble(), b.tiltRadians.toDouble(), 0.0)
        assertEquals(a.angularVelocity.toDouble(), b.angularVelocity.toDouble(), 0.0)
    }
}


