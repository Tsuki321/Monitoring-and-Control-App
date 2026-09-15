package com.watermonitor.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermonitor.app.data.model.PumpState
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.data.model.WaterQualityAssessment
import com.watermonitor.app.data.model.WaterQualityEvaluator
import com.watermonitor.app.data.repository.FirebaseRealtimeSensorRepository
import com.watermonitor.app.data.repository.MockSensorRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val pumpState: PumpState = PumpState(),
    val sensorData: SensorData = SensorData(),
    val waterQuality: WaterQualityAssessment = WaterQualityEvaluator.evaluate(sensorData)
) {
    val hasSensorReading: Boolean
        get() = sensorData.timestamp > 0L
}

class DashboardViewModel : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        MockSensorRepository.pumpState,
        FirebaseRealtimeSensorRepository.sensorDataFlow
    ) { pump, sensorData ->
        DashboardUiState(
            pumpState = pump,
            sensorData = sensorData,
            waterQuality = WaterQualityEvaluator.evaluate(sensorData)
        )
    }
        // Defense in depth, matching MonitoringViewModel: without this, a failure upstream
        // cancels the sharing coroutine inside viewModelScope and takes the screen — and
        // potentially the process — down with it.
        .catch { emit(DashboardUiState()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DashboardUiState()
        )

    init {
        // Sync actual pump states from RTDB into the dashboard's pump simulation.
        viewModelScope.launch {
            FirebaseRealtimeSensorRepository.pumpControlFlow.collect { state ->
                MockSensorRepository.setPumpA(state.actualPumpA)
                MockSensorRepository.setPumpB(state.actualPumpB)
            }
        }
    }
}
