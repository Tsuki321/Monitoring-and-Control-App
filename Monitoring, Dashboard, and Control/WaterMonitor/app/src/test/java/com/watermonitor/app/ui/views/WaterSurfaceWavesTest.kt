package com.watermonitor.app.ui.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [WaterSurfaceWaves] — the 1-D height-field water surface. Pure JVM, no Android, so
 * they run in CI via testDebugUnitTest. They pin the contract [WaterTankView] relies on: starts
 * flat, a disturbance propagates and reflects off the walls, everything decays to flat when
 * undriven, lateral drive tilts the two sides opposite ways, and the field stays finite and bounded
 * under any input (including NaN/Inf).
 */
class WaterSurfaceWavesTest {

    private val dt = 1f / 60f

    private fun WaterSurfaceWaves.run(frames: Int): WaterSurfaceWaves {
        repeat(frames) { update(dt) }
        return this
    }

    @Test
    fun startsFlat() {
        val w = WaterSurfaceWaves()
        assertEquals(0.0, w.maxAbsHeight().toDouble(), 0.0)
        assertEquals(0.0, w.heightAt(0.5f).toDouble(), 0.0)
    }

    @Test
    fun disturbancePropagates() {
        val w = WaterSurfaceWaves(columns = 41)
        w.disturb(0.5f, 1.5f)
        w.run(3)
        assertTrue("struck point should rise", w.heightAt(0.5f) > 0.1f)
        // The disturbance should travel out to off-centre columns that started still.
        var offMax = 0f
        repeat(120) {
            w.update(dt)
            offMax = maxOf(offMax, abs(w.heightAt(0.2f)))
        }
        assertTrue("wave should reach off-centre columns, saw $offMax", offMax > 1e-3f)
    }

    @Test
    fun reflectsToFarWall() {
        val w = WaterSurfaceWaves(columns = 41)
        w.disturb(0.05f, 2.0f) // splash near the left wall
        var farMax = 0f
        repeat(200) {
            w.update(dt)
            farMax = maxOf(farMax, abs(w.heightAt(0.95f)))
        }
        assertTrue("far wall should be reached, saw $farMax", farMax > 1e-3f)
    }

    @Test
    fun decaysToFlatWhenUndriven() {
        val w = WaterSurfaceWaves()
        w.disturb(0.5f, 2.0f)
        w.run(4000) // damping should win
        assertTrue("should settle near flat, was ${w.maxAbsHeight()}", w.maxAbsHeight() < 0.02f)
    }

    @Test
    fun lateralDriveTiltsSidesOppositeWays() {
        val w = WaterSurfaceWaves(columns = 41)
        repeat(10) { w.driveLateral(9f, dt) } // steady sideways push
        w.run(2)
        val left = w.heightAt(0.1f)
        val right = w.heightAt(0.9f)
        assertTrue("sides should tilt opposite ways: L=$left R=$right", left * right < 0f)
    }

    @Test
    fun boundedUnderHardContinuousDriving() {
        val w = WaterSurfaceWaves()
        repeat(2000) {
            w.driveLateral(50f, dt) // absurd sustained shaking
            w.update(dt)
            assertTrue("blew up: ${w.maxAbsHeight()}", w.maxAbsHeight().isFinite())
        }
        assertTrue("must stay bounded, saw ${w.maxAbsHeight()}", w.maxAbsHeight() <= 4f + 1e-3f)
    }

    @Test
    fun survivesJunkInput() {
        val w = WaterSurfaceWaves()
        repeat(200) {
            w.driveLateral(Float.NaN, dt)
            w.disturb(Float.NaN, Float.POSITIVE_INFINITY)
            w.update(Float.NaN)
            w.update(dt)
        }
        assertTrue("field went non-finite", w.maxAbsHeight().isFinite())
    }

    @Test
    fun heightAtClampsFractionRange() {
        val w = WaterSurfaceWaves(columns = 10)
        w.disturb(0.5f, 1f)
        w.run(5)
        // Out-of-range fractions must not crash or read out of bounds; they clamp to the ends.
        assertEquals(w.heightAt(0f).toDouble(), w.heightAt(-5f).toDouble(), 0.0)
        assertEquals(w.heightAt(1f).toDouble(), w.heightAt(5f).toDouble(), 0.0)
    }

    @Test
    fun nonPositiveDtIsNoOp() {
        val w = WaterSurfaceWaves()
        w.disturb(0.5f, 1f)
        w.run(2)
        val before = w.heightAt(0.5f)
        w.update(0f)
        w.update(-1f)
        assertEquals(before.toDouble(), w.heightAt(0.5f).toDouble(), 0.0)
    }

    @Test
    fun resetReturnsToFlat() {
        val w = WaterSurfaceWaves()
        w.disturb(0.4f, 2f)
        w.run(30)
        w.reset()
        assertEquals(0.0, w.maxAbsHeight().toDouble(), 0.0)
    }

    @Test
    fun deterministicForSameInputs() {
        val a = WaterSurfaceWaves()
        val b = WaterSurfaceWaves()
        repeat(300) {
            a.driveLateral(3f, dt); a.update(dt)
            b.driveLateral(3f, dt); b.update(dt)
        }
        assertEquals(a.maxAbsHeight().toDouble(), b.maxAbsHeight().toDouble(), 0.0)
        assertEquals(a.heightAt(0.3f).toDouble(), b.heightAt(0.3f).toDouble(), 0.0)
    }
}
