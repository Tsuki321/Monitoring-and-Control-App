package com.watermonitor.app.data.model

data class TankStatus(
    val fillPercent: Float = 0f,
    val tankWarning: Int = 0,
    val isOnline: Boolean = true
)
