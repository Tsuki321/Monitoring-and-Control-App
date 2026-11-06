package com.watermonitor.app.notifications

/** Tank telemetry is independent of the availability of the water-quality probes. */
internal data class TankReading(val level: Double?, val warning: Int?) {
    val isFull: Boolean?
        get() = when {
            warning == 3 || level == 100.0 -> true
            level != null || warning != null -> false
            else -> null
        }

    // Hysteresis prevents readings fluctuating around 100% from sounding repeatedly.
    val canRearm: Boolean
        get() = isFull == false && (level?.let { it <= 95.0 } ?: (warning != null))

    companion object {
        fun parse(level: Any?, warning: Any?): TankReading {
            fun number(value: Any?): Double? = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }?.takeIf { it.isFinite() }

            return TankReading(
                number(level)?.takeIf { it in 0.0..100.0 },
                number(warning)?.takeIf { it in 0.0..3.0 && it % 1.0 == 0.0 }?.toInt()
            )
        }
    }
}

/** One alert per fill cycle, including across a service/process restart. */
internal class TankFullAlertPolicy(var fullHandled: Boolean = false) {
    fun onReading(reading: TankReading, appVisible: Boolean, canNotify: Boolean): Boolean {
        if (reading.canRearm) fullHandled = false
        if (reading.isFull != true || fullHandled) return false
        if (appVisible) {
            // A full tank seen while using the app must not become a delayed alert on exit.
            fullHandled = true
            return false
        }
        if (!canNotify) return false
        fullHandled = true
        return true
    }
}
