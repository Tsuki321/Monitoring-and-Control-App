package com.watermonitor.app.data.model

/** Converts cumulative hardware counters into unscaled, physically plausible pump-hours. */
object MeasuredPumpRuntime {
    fun deltaHours(currentSeconds: Long?, previousSeconds: Long?, wallDeltaHours: Double): Double {
        if (currentSeconds == null || previousSeconds == null) return 0.0
        if (currentSeconds < 0L || previousSeconds < 0L || currentSeconds < previousSeconds) return 0.0
        if (!wallDeltaHours.isFinite() || wallDeltaHours <= 0.0) return 0.0

        val hours = (currentSeconds - previousSeconds) / 3600.0
        // Allow counter quantization and small clock differences, never demo acceleration.
        val allowance = wallDeltaHours * 1.05 + 60.0 / 3600.0
        return if (hours <= allowance) hours else 0.0
    }
}
