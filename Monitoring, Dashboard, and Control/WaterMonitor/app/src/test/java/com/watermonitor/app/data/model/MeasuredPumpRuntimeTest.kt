package com.watermonitor.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasuredPumpRuntimeTest {
    @Test
    fun missingCountersNeverInventRuntime() {
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(null, null, 24.0), 0.0)
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(null, 100L, 24.0), 0.0)
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(86400L, null, 24.0), 0.0)
    }

    @Test
    fun counterMeasuresPumpBWithoutDemoScaling() {
        assertEquals(1.0, MeasuredPumpRuntime.deltaHours(4600L, 1000L, 2.0), 1e-9)
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(1000L, 1000L, 2.0), 0.0)
    }

    @Test
    fun appClosedGapUsesFullMeasuredDelta() {
        assertEquals(36.0, MeasuredPumpRuntime.deltaHours(130600L, 1000L, 48.0), 1e-9)
    }

    @Test
    fun resetRollbackAndImpossibleJumpsDoNotConsumeLife() {
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(50L, 1000L, 1.0), 0.0)
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(-1L, 0L, 1.0), 0.0)
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(2000L, -1L, 1.0), 0.0)
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(2000L, 1000L, 0.0), 0.0)
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(2000L, 1000L, Double.NaN), 0.0)
        assertEquals(0.0, MeasuredPumpRuntime.deltaHours(500000L, 1000L, 1.0), 0.0)
    }
}
