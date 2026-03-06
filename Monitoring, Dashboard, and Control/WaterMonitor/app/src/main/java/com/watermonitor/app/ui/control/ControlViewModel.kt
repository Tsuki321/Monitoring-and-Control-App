package com.watermonitor.app.ui.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermonitor.app.data.model.PumpState
import com.watermonitor.app.data.repository.MockSensorRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ControlViewModel : ViewModel() {

    val pumpState: StateFlow<PumpState> = MockSensorRepository.pumpState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PumpState()
    )

    fun togglePumpA() = MockSensorRepository.togglePumpA()
    fun togglePumpB() = MockSensorRepository.togglePumpB()
    fun toggleValveMain() = MockSensorRepository.toggleValveMain()
    fun toggleValveBypass() = MockSensorRepository.toggleValveBypass()
}
