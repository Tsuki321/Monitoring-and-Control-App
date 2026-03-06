package com.watermonitor.app.data.model

data class PumpState(
    val pumpA: Boolean = false,
    val pumpB: Boolean = false,
    val valveMain: Boolean = true,
    val valveBypass: Boolean = false
)
