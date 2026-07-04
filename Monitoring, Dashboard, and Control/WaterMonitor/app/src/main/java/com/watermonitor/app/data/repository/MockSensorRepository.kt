package com.watermonitor.app.data.repository

import com.watermonitor.app.data.model.PumpState
import com.watermonitor.app.data.model.SensorStatus
import com.watermonitor.app.data.model.TankStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

object MockSensorRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pumpState = MutableStateFlow(PumpState())
    val pumpState: StateFlow<PumpState> = _pumpState.asStateFlow()

    private val _tankStatus = MutableStateFlow(TankStatus(fillPercent = 0f))
    val tankStatus: StateFlow<TankStatus> = _tankStatus.asStateFlow()

    private val _sensorStatus = MutableStateFlow(SensorStatus())
    val sensorStatus: StateFlow<SensorStatus> = _sensorStatus.asStateFlow()

    init {
        // Pump monitoring simulation — updates speed and voltage every 500ms when pumps are on
        scope.launch {
            while (true) {
                delay(500)
                _pumpState.update { currentState ->
                    // Pump A monitoring
                    val pumpASpeed = if (currentState.pumpA) {
                        // Oscillate around 2400 RPM ± 150 RPM when on
                        (2400 + Random.nextInt(-150, 150)).coerceIn(1500, 3000)
                    } else 0

                    val pumpAVoltage = if (currentState.pumpA) {
                        // Oscillate around 230V ± 5V when on
                        230f + Random.nextFloat() * 10f - 5f
                    } else 0f

                    // Pump B monitoring
                    val pumpBSpeed = if (currentState.pumpB) {
                        (2400 + Random.nextInt(-150, 150)).coerceIn(1500, 3000)
                    } else 0

                    val pumpBVoltage = if (currentState.pumpB) {
                        230f + Random.nextFloat() * 10f - 5f
                    } else 0f

                    currentState.copy(
                        pumpASpeed = pumpASpeed,
                        pumpAVoltage = pumpAVoltage,
                        pumpBSpeed = pumpBSpeed,
                        pumpBVoltage = pumpBVoltage
                    )
                }
            }
        }
    }

    fun togglePumpA() {
        _pumpState.update { it.copy(pumpA = !it.pumpA) }
    }

    /**
     * Sets Pump A's on/off state without toggling. Used to sync the mock monitoring
     * simulation (speed/voltage) to the real pump state coming from RTDB.
     */
    fun setPumpA(value: Boolean) {
        _pumpState.update { if (it.pumpA != value) it.copy(pumpA = value) else it }
    }

    /**
     * Sets the tank fill level from the ToF sensor reading (via RTDB /sensors).
     * The Dashboard relies solely on this real data.
     */
    fun setTankLevel(fillPercent: Float, warning: Int = 0) {
        _tankStatus.update {
            it.copy(
                fillPercent = fillPercent.coerceIn(0f, 100f),
                tankWarning = warning.coerceIn(0, 3)
            )
        }
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
