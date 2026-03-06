package com.watermonitor.app.data.model

data class SensorStatus(
    val phOnline: Boolean = true,
    val tdsOnline: Boolean = true,
    val turbidityOnline: Boolean = true
)
