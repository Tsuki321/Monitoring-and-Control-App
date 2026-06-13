package com.watermonitor.app.data.model

data class PumpState(
    val pumpA: Boolean = false,
    val pumpB: Boolean = false,
    val valveMain: Boolean = true,
    val valveBypass: Boolean = false,
    val pumpASpeed: Int = 0,        // RPM (0 when off, 1500-3000 when on)
    val pumpAVoltage: Float = 0f,   // Volts (0 when off, ~220-240V when on)
    val pumpBSpeed: Int = 0,        // RPM
    val pumpBVoltage: Float = 0f    // Volts
)
