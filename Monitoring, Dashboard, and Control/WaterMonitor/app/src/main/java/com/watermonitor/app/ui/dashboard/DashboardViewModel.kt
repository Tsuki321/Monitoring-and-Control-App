package com.watermonitor.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermonitor.app.data.model.PumpState
import com.watermonitor.app.data.model.SensorStatus
import com.watermonitor.app.data.model.TankStatus
import com.watermonitor.app.data.repository.MockSensorRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val tankStatus: TankStatus = TankStatus(),
    val pumpState: PumpState = PumpState(),
    val sensorStatus: SensorStatus = SensorStatus()
)

class DashboardViewModel : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        MockSensorRepository.tankStatus,
        MockSensorRepository.pumpState,
        MockSensorRepository.sensorStatus
    ) { tank, pump, sensors ->
        DashboardUiState(tankStatus = tank, pumpState = pump, sensorStatus = sensors)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )
}
