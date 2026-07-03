package com.watermonitor.app.data.repository

import com.watermonitor.app.data.model.PumpState
import com.watermonitor.app.data.model.SensorData
import com.watermonitor.app.data.model.SensorStatus
import com.watermonitor.app.data.model.TankStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

object MockSensorRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pumpState = MutableStateFlow(PumpState())
    val pumpState: StateFlow<PumpState> = _pumpState.asStateFlow()

    private val _tankStatus = MutableStateFlow(TankStatus(fillPercent = 65f))
    val tankStatus: StateFlow<TankStatus> = _tankStatus.asStateFlow()

    private val _sensorStatus = MutableStateFlow(SensorStatus())
    val sensorStatus: StateFlow<SensorStatus> = _sensorStatus.asStateFlow()

    init {
        // Tank fill simulation runs independently — always active regardless of which screen is shown
        scope.launch {
            while (true) {
                delay(3_000)
                _tankStatus.update { current ->
                    val delta = Random.nextFloat() * 0.6f - 0.3f
                    current.copy(fillPercent = (current.fillPercent + delta).coerceIn(10f, 100f))
                }
            }
        }

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

    /** Emits simulated sensor data every 3 seconds, oscillating naturally */
    val sensorDataFlow: Flow<SensorData> = flow {
        var tick = 0
        while (true) {
            val time = tick.toDouble()

            // pH oscillates 6.8 – 7.8 with gentle sine wave + small random jitter
            val ph = 7.3 + 0.5 * sin(time * 0.18) + Random.nextDouble(-0.05, 0.05)

            // TDS oscillates 120 – 200 ppm
            val tds = (160 + 40 * sin(time * 0.12) + Random.nextDouble(-5.0, 5.0)).toInt()
                .coerceIn(120, 200)

            // Turbidity 0.5 – 3.5 NTU
            val turbidity = 2.0 + 1.5 * sin(time * 0.22) + Random.nextDouble(-0.15, 0.15)

            emit(
                SensorData(
                    ph = ph.coerceIn(6.0, 9.0),
                    tds = tds,
                    turbidity = turbidity.coerceIn(0.2, 4.5),
                    timestamp = System.currentTimeMillis()
                )
            )

            tick++
            delay(3_000)
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
