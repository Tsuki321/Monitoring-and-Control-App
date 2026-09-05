package com.watermonitor.app.data.model

data class SensorData(
    val ph: Double = 7.0,
    val tds: Int = 150,
    val turbidity: Double = 1.5,
    val tankDistanceMm: Int? = null,
    val tankLevel: Float? = null,
    val tankWarning: Int? = null,
    /**
     * Leak detected by the floor/tray moisture sensor on the ESP32.
     * RTDB key is still `rainDetected` (firmware naming); app domain is leak.
     * null = not yet received from RTDB.
     */
    val leakDetected: Boolean? = null,
    // Zero identifies placeholder/no-data states; RTDB snapshots receive the current time.
    val timestamp: Long = 0L,
    /**
     * Cumulative seconds each pump has been energized, counted by the ESP32 and
     * persisted in NVS so it survives reboots (firmware V17+).
     *
     * Absolute counters, not deltas: a gap while the app is closed costs no runtime.
     * null = firmware predates V17, so the filter model falls back to simulation.
     */
    val runtimeASeconds: Long? = null,
    val runtimeBSeconds: Long? = null,
    /**
     * Feed-line pressure (PSI) and pump motor speed (RPM). No firmware publishes these
     * yet — RTDB leaves them null and the filter repository simulates them at nominal
     * until a future build writes `psi`/`rpm` keys under /sensors.
     */
    val pressurePsi: Double? = null,
    val pumpRpm: Double? = null
)
