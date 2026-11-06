package com.watermonitor.app.ui.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermonitor.app.data.model.PumpControlState
import com.watermonitor.app.data.repository.FirebaseRealtimeSensorRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ControlViewModel : ViewModel() {

    /** Live pump states + mode from RTDB (/status written by ESP32, /control/auto by app). */
    val pumpControlState: StateFlow<PumpControlState> =
        FirebaseRealtimeSensorRepository.pumpControlFlow
            // Defense in depth, matching MonitoringViewModel: without this, a failure
            // upstream cancels the sharing coroutine inside viewModelScope.
            .catch { emit(PumpControlState()) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = PumpControlState()
            )

    /**
     * Leak sensor state from `/sensors/rainDetected` (firmware key).
     * true = moisture/leak detected → ESP32 force-stops both pumps.
     */
    val leakDetected: StateFlow<Boolean?> =
        FirebaseRealtimeSensorRepository.sensorDataFlow
            .map { it.leakDetected }
            .catch { emit(null) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    // ── RTDB-backed pump commands (app → ESP32 via /control) ──

    fun togglePumpA() {
        FirebaseRealtimeSensorRepository.setPumpA(!pumpControlState.value.commandedPumpA)
    }

    fun togglePumpB() {
        FirebaseRealtimeSensorRepository.setPumpB(!pumpControlState.value.commandedPumpB)
    }

    fun toggleAutoMode() {
        FirebaseRealtimeSensorRepository.setAutoMode(!pumpControlState.value.autoMode)
    }

}
