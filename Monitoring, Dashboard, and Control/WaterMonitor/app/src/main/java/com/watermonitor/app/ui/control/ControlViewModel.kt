package com.watermonitor.app.ui.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermonitor.app.data.model.PumpControlState
import com.watermonitor.app.data.model.PumpState
import com.watermonitor.app.data.repository.FirebaseRealtimeSensorRepository
import com.watermonitor.app.data.repository.MockSensorRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ControlViewModel : ViewModel() {

    /** Live pump states + mode from RTDB (/status written by ESP32, /control/auto by app). */
    val pumpControlState: StateFlow<PumpControlState> =
        FirebaseRealtimeSensorRepository.pumpControlFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = PumpControlState()
            )

    /** Mock-derived pump monitoring data (speed, voltage) and valve toggles. */
    val pumpState: StateFlow<PumpState> = MockSensorRepository.pumpState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PumpState()
    )

    init {
        // Sync actual pump states from RTDB → mock so the speed/voltage
        // simulation reflects what the hardware is really doing.
        viewModelScope.launch {
            pumpControlState.collect { state ->
                MockSensorRepository.setPumpA(state.actualPumpA)
                MockSensorRepository.setPumpB(state.actualPumpB)
            }
        }
    }

    // ── RTDB-backed pump commands (app → ESP32 via /control) ──

    fun togglePumpA() {
        FirebaseRealtimeSensorRepository.setPumpA(!pumpControlState.value.actualPumpA)
    }

    fun togglePumpB() {
        FirebaseRealtimeSensorRepository.setPumpB(!pumpControlState.value.actualPumpB)
    }

    fun toggleAutoMode() {
        FirebaseRealtimeSensorRepository.setAutoMode(!pumpControlState.value.autoMode)
    }

    // ── Mock-backed valve toggles (no hardware yet) ──

    fun toggleValveMain() = MockSensorRepository.toggleValveMain()
    fun toggleValveBypass() = MockSensorRepository.toggleValveBypass()
}
