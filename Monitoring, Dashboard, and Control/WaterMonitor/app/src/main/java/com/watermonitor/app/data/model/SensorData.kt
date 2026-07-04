package com.watermonitor.app.data.model

data class SensorData(
    val ph: Double = 7.0,
    val tds: Int = 150,
    val turbidity: Double = 1.5,
    val tankDistanceMm: Int? = null,
    val tankLevel: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)
