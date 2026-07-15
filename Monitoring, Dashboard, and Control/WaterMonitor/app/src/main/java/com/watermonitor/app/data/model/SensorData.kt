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
    val timestamp: Long = 0L
)
