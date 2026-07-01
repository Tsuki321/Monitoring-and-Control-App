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

/**
 * Live pump control state backed by Firebase RTDB.
 *
 * - [actualPumpA] / [actualPumpB] mirror `/status/pumpA` and `/status/pumpB` (0/1) —
 *   the ESP32's actual relay states.
 * - [autoMode] mirrors `/control/auto` (0/1) — 1 = ESP32 water-level automation,
 *   0 = the app manually commands each pump via `/control/pumpA` and `/control/pumpB`.
 */
data class PumpControlState(
    val actualPumpA: Boolean = false,
    val actualPumpB: Boolean = false,
    val autoMode: Boolean = true
)
