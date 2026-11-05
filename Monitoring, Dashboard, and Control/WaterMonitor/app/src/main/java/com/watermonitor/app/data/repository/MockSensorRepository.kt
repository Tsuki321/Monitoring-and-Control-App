package com.watermonitor.app.data.repository

import com.watermonitor.app.data.model.PumpState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object MockSensorRepository {

    private val _pumpState = MutableStateFlow(PumpState())
    val pumpState: StateFlow<PumpState> = _pumpState.asStateFlow()

    fun togglePumpA() {
        _pumpState.update { it.copy(pumpA = !it.pumpA) }
    }

    /**
     * Mirrors the actual relay state received from RTDB for Dashboard consumers.
     */
    fun setPumpA(value: Boolean) {
        _pumpState.update { if (it.pumpA != value) it.copy(pumpA = value) else it }
    }

    fun togglePumpB() {
        _pumpState.update { it.copy(pumpB = !it.pumpB) }
    }

    fun setPumpB(value: Boolean) {
        _pumpState.update { if (it.pumpB != value) it.copy(pumpB = value) else it }
    }

    fun toggleValveMain() {
        _pumpState.update { it.copy(valveMain = !it.valveMain) }
    }

    fun toggleValveBypass() {
        _pumpState.update { it.copy(valveBypass = !it.valveBypass) }
    }
}
